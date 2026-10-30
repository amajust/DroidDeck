package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ThermalGuardTest {
    @Test fun theCapDividesThePanelAndLeavesAtLeastHalfTheRate() {
        assertEquals(60, ThermalGuard.capFor(120f))
        assertEquals(48, ThermalGuard.capFor(144f))
        assertEquals(55, ThermalGuard.capFor(165f))
        assertEquals(45, ThermalGuard.capFor(90f))
        assertEquals(60, ThermalGuard.capFor(60f))
        for (hz in listOf(60f, 90f, 120f, 144f, 165f)) {
            val cap = ThermalGuard.capFor(hz)
            assertEquals("$hz Hz / $cap", 0, (hz.toInt() % cap))
        }
    }

    @Test fun aCapNeedsAWorkingPanelRate() {
        assertEquals(0, ThermalGuard.decide(cap = 0, hot = true, coolMs = 0, powerSave = false, refreshHz = 0f))
        assertEquals(60, ThermalGuard.decide(cap = 0, hot = true, coolMs = 0, powerSave = false, refreshHz = 120f))
        assertEquals(0, ThermalGuard.decide(cap = 0, hot = false, coolMs = 0, powerSave = false, refreshHz = 120f))
    }

    @Test fun aCapStaysUntilTheDeviceHasCooledForAMinute() {
        assertEquals(60, ThermalGuard.decide(cap = 60, hot = true, coolMs = 0, powerSave = false, refreshHz = 120f))
        assertEquals(60, ThermalGuard.decide(cap = 60, hot = false, coolMs = ThermalGuard.LEAVE_HOLD_MS - 1, powerSave = false, refreshHz = 120f))
        assertEquals(0, ThermalGuard.decide(cap = 60, hot = false, coolMs = ThermalGuard.LEAVE_HOLD_MS, powerSave = false, refreshHz = 120f))
    }

    @Test fun powerSaveLiftsTheCap() {
        assertEquals(0, ThermalGuard.decide(cap = 60, hot = true, coolMs = 0, powerSave = true, refreshHz = 120f))
    }

    @Test fun anyHotSignalCapsEvenWithoutThermalHeadroom() {
        assertTrue(ThermalGuard.tooHot(headroom = null, status = 0, cpuC = 82f, gpuC = 60f))
        assertTrue(ThermalGuard.tooHot(headroom = null, status = 0, cpuC = 60f, gpuC = 74f))
        assertTrue(ThermalGuard.tooHot(headroom = 0.91f, status = 0, cpuC = 50f, gpuC = 50f))
        assertTrue(ThermalGuard.tooHot(headroom = null, status = 3, cpuC = 50f, gpuC = 50f))
        assertFalse(ThermalGuard.tooHot(headroom = null, status = 0, cpuC = 70f, gpuC = 65f))
    }

    @Test fun everyReadingMustBeCoolBeforeTheCapCanLift() {
        assertTrue(ThermalGuard.coolEnough(headroom = null, status = 0, cpuC = 60f, gpuC = 55f))
        assertTrue(ThermalGuard.coolEnough(headroom = 0.70f, status = 1, cpuC = 60f, gpuC = 55f))
        assertFalse(ThermalGuard.coolEnough(headroom = null, status = 0, cpuC = 70f, gpuC = 55f))
        assertFalse(ThermalGuard.coolEnough(headroom = null, status = 2, cpuC = 60f, gpuC = 55f))
    }

    @Test fun theGuardIsOnByDefaultAndCanBeTurnedOff() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(true, SessionPrefs.thermalGuard(context))
        SessionPrefs.setThermalGuard(context, false)
        assertEquals(false, SessionPrefs.thermalGuard(context))
    }
}
