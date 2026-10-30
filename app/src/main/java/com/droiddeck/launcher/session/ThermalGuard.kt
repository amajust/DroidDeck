package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Holds a session below the panel's rate while the device is running out of thermal headroom,
 * and lets it go again once the device has cooled.
 *
 * The cap goes through the compositor's existing frame limit, so it needs no privileges and the
 * game still decides how it draws; it only gets its buffers back more slowly. The cap divides the
 * panel's refresh rate evenly (60 on 120 Hz, 48 on 144 Hz), which keeps frame pacing intact.
 *
 * Headroom looks ahead of the drop, but some ROMs (HyperOS on the Redmi K50 Gaming, for one) do not
 * initialise the thermal HAL: headroom is NaN and the status stays at 0 even at 94 °C. The CPU and
 * GPU zones are read directly as well, so the guard still works there.
 */
object ThermalGuard {
    private const val TAG = "ThermalGuard"
    internal const val ENTER_HEADROOM = 0.90f
    internal const val LEAVE_HEADROOM = 0.80f
    internal const val LEAVE_HOLD_MS = 60_000L
    internal const val POLL_MS = 5_000L
    internal const val MIN_CAP = 30
    internal const val MAX_CAP = 60
    internal const val ENTER_CPU_C = 80f
    internal const val LEAVE_CPU_C = 65f
    internal const val ENTER_GPU_C = 72f
    internal const val LEAVE_GPU_C = 60f

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var listener: (() -> Unit)? = null

    /** The limit the session is held to, or 0 when the panel's own pace is left alone. */
    @Volatile
    var capFps: Int = 0
        private set

    @Synchronized
    fun start(context: Context, onChanged: () -> Unit): String {
        if (!SessionPrefs.thermalGuard(context)) return "thermal guard: off"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "thermal guard: needs Android 10"
        val pm = context.getSystemService(PowerManager::class.java)
            ?: return "thermal guard: no PowerManager"
        stop()
        listener = onChanged
        worker = Thread({ run(pm) }, "thermal-guard").apply { isDaemon = true; start() }
        return "thermal guard: watching"
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        listener = null
        capFps = 0
    }

    private fun run(pm: PowerManager) {
        var cooledSince = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val headroom = headroom(pm)
                val status = pm.currentThermalStatus
                val cpu = cpuTempC()
                val gpu = gpuTempC()
                val hot = tooHot(headroom, status, cpu, gpu)
                val cool = coolEnough(headroom, status, cpu, gpu)
                val now = SystemClock.elapsedRealtime()
                cooledSince = when {
                    hot -> 0L
                    cool -> cooledSince.takeIf { it != 0L } ?: now
                    else -> 0L
                }
                val next = decide(
                    cap = capFps,
                    hot = hot,
                    coolMs = if (cooledSince == 0L) 0 else now - cooledSince,
                    powerSave = pm.isPowerSaveMode,
                    refreshHz = SessionState.refreshHz,
                )
                if (next != capFps) {
                    capFps = next
                    Log.i(TAG, (if (next > 0) "hot: cap at $next fps" else "cooled: cap lifted") +
                        " (headroom ${headroom?.let { String.format(Locale.US, "%.2f", it) } ?: "not reported"}, " +
                        "status $status, cpu ${cpu?.toInt() ?: "?"} °C, gpu ${gpu?.toInt() ?: "?"} °C, " +
                        "panel ${SessionState.refreshHz.toInt()} Hz)")
                    listener?.invoke()
                }
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "stopped", t)
        }
    }

    private fun headroom(pm: PowerManager): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            pm.getThermalHeadroom(10).takeIf { !it.isNaN() && it > 0f }
        } catch (_: Throwable) {
            null
        }
    }

    /** The hottest readable CPU zone, in °C; null when the app may not read one. */
    private fun cpuTempC(): Float? =
        LinuxRuntime.cpuTempZones().mapNotNull { readCelsius(File(it, "temp")) }.maxOrNull()

    private fun gpuTempC(): Float? = LinuxRuntime.gpuTempSource()?.let { readCelsius(File(it)) }

    private fun readCelsius(file: File): Float? = try {
        val milli = file.readText().trim().toFloatOrNull() ?: return null
        (milli / 1000f).takeIf { it in 1f..150f }
    } catch (_: Throwable) {
        null
    }

    internal fun tooHot(headroom: Float?, status: Int, cpuC: Float?, gpuC: Float?): Boolean =
        (headroom != null && headroom >= ENTER_HEADROOM) ||
            status >= PowerManager.THERMAL_STATUS_SEVERE ||
            (cpuC != null && cpuC >= ENTER_CPU_C) ||
            (gpuC != null && gpuC >= ENTER_GPU_C)

    internal fun coolEnough(headroom: Float?, status: Int, cpuC: Float?, gpuC: Float?): Boolean =
        (headroom == null || headroom <= LEAVE_HEADROOM) &&
            status <= PowerManager.THERMAL_STATUS_LIGHT &&
            (cpuC == null || cpuC <= LEAVE_CPU_C) &&
            (gpuC == null || gpuC <= LEAVE_GPU_C)

    /**
     * The cap for this poll: 0 to leave the panel alone, otherwise a frame rate that divides
     * [refreshHz] evenly. A cap already in place stays until the device has been cool for
     * [LEAVE_HOLD_MS], so a session does not oscillate around the threshold.
     */
    internal fun decide(cap: Int, hot: Boolean, coolMs: Long, powerSave: Boolean, refreshHz: Float): Int {
        if (powerSave) return 0
        if (cap > 0) return if (coolMs >= LEAVE_HOLD_MS) 0 else cap
        if (refreshHz < 1f) return 0
        return if (hot) capFor(refreshHz) else 0
    }

    /** The highest rate at or below [MAX_CAP] that divides [refreshHz]; [MIN_CAP] when none does. */
    internal fun capFor(refreshHz: Float): Int {
        if (refreshHz < 1f) return MIN_CAP
        for (divisor in 1..8) {
            val cap = (refreshHz / divisor).roundToInt()
            if (cap <= MAX_CAP && cap >= MIN_CAP && kotlin.math.abs(cap * divisor - refreshHz) < 0.5f) return cap
        }
        return MIN_CAP
    }
}
