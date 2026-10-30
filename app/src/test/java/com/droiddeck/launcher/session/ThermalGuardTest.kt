package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ThermalGuardTest {
    @Test fun theCapDividesThePanelEvenly() {
        assertEquals(40, ThermalGuard.capFor(120f))
        assertEquals(48, ThermalGuard.capFor(144f))
        assertEquals(55, ThermalGuard.capFor(165f))
        assertEquals(45, ThermalGuard.capFor(90f))
        assertEquals(30, ThermalGuard.capFor(60f))
        for (hz in listOf(60f, 90f, 120f, 144f, 165f)) {
            val cap = ThermalGuard.capFor(hz)
            assertTrue("$hz Hz / $cap", (hz.toInt() % cap) == 0)
        }
    }

    @Test fun aCapNeedsAWorkingPanelRate() {
        assertEquals(0, ThermalGuard.decide(cap = 0, hot = true, coolMs = 0, powerSave = false, refreshHz = 0f))
        assertEquals(40, ThermalGuard.decide(cap = 0, hot = true, coolMs = 0, powerSave = false, refreshHz = 120f))
        assertEquals(0, ThermalGuard.decide(cap = 0, hot = false, coolMs = 0, powerSave = false, refreshHz = 120f))
    }

    @Test fun aCapStaysUntilTheDeviceHasCooledForAMinute() {
        assertEquals(40, ThermalGuard.decide(cap = 40, hot = true, coolMs = 0, powerSave = false, refreshHz = 120f))
        assertEquals(40, ThermalGuard.decide(cap = 40, hot = false, coolMs = ThermalGuard.LEAVE_HOLD_MS - 1, powerSave = false, refreshHz = 120f))
        assertEquals(0, ThermalGuard.decide(cap = 40, hot = false, coolMs = ThermalGuard.LEAVE_HOLD_MS, powerSave = false, refreshHz = 120f))
    }

    @Test fun powerSaveLiftsTheCap() {
        assertEquals(0, ThermalGuard.decide(cap = 40, hot = true, coolMs = 0, powerSave = true, refreshHz = 120f))
    }

    @Test fun theGuardIsOnByDefaultAndCanBeTurnedOff() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(true, SessionPrefs.thermalGuard(context))
        SessionPrefs.setThermalGuard(context, false)
        assertEquals(false, SessionPrefs.thermalGuard(context))
    }
}
