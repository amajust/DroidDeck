package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

/**
 * Holds a session below the panel's rate while Android reports that the device is running out of
 * thermal headroom, and lets it go again once the device has cooled.
 *
 * The cap goes through the compositor's existing frame limit, so it needs no privileges and the
 * game still decides how it draws; it only gets its buffers back more slowly. The cap divides the
 * panel's refresh rate evenly (a third of 120 Hz, half of 90 Hz), which keeps frame pacing intact.
 *
 * Headroom is the only signal that looks ahead of the drop; devices that do not report it fall back
 * to the thermal status, which reacts later.
 */
object ThermalGuard {
    private const val TAG = "ThermalGuard"
    internal const val ENTER_HEADROOM = 0.90f
    internal const val LEAVE_HEADROOM = 0.80f
    internal const val LEAVE_HOLD_MS = 60_000L
    internal const val POLL_MS = 5_000L
    internal const val MIN_CAP = 30

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
                val hot = if (headroom != null) headroom >= ENTER_HEADROOM else status >= PowerManager.THERMAL_STATUS_SEVERE
                val cool = if (headroom != null) headroom <= LEAVE_HEADROOM else status <= PowerManager.THERMAL_STATUS_LIGHT
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
                        " (headroom ${headroom?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "not reported"}, " +
                        "status $status, panel ${SessionState.refreshHz.toInt()} Hz)")
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

    /** A third of a fast panel and half of a slow one, at least [MIN_CAP]; both divide it evenly. */
    internal fun capFor(refreshHz: Float): Int {
        if (refreshHz < 1f) return MIN_CAP
        val divisor = if (refreshHz >= 100f) 3 else 2
        return (refreshHz / divisor).roundToInt().coerceAtLeast(MIN_CAP)
    }
}
