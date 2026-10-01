package com.openminis.app.sandbox

import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript
import com.openminis.app.sandbox.kernel.WorkClass
import com.openminis.app.service.SessionConcurrencyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelContractTest {
    private val groupKill = "kill -TERM -" + "$" + "$"
    private val groupKillHard = "kill -KILL -" + "$" + "$"

    @Test
    fun oneshotKillsTheProcessGroupAndDoesNotUseKillZero() {
        val script = GuardianScript.oneshot("echo hi", BudgetClassifier.normal())
        assertTrue(script.contains("trap 'kill -KILL"))
        assertTrue(script.contains(groupKill))
        assertTrue(script.contains(groupKillHard))
        assertFalse(script.contains("kill -0"))
        assertFalse(script.contains("kill -TERM 0"))
        assertEquals(1, script.split("echo hi").size - 1)
    }

    @Test
    fun supervisorHasNoTrapAndNoUlimit() {
        val script = GuardianScript.supervisor(600)
        assertTrue(script.contains(groupKill))
        assertTrue(script.contains(groupKillHard))
        assertFalse(script.contains("trap"))
        assertFalse(script.contains("ulimit"))
        assertFalse(script.contains("kill -0"))
    }

    @Test
    fun setupIsNotABackgroundAmpersand() {
        val setup = BudgetClassifier.classify("sh /usr/local/bin/minis-dev-setup-full")
        assertEquals(WorkClass.SETUP, setup.workClass)
        assertEquals(ShellTimeoutPolicy.SETUP_WALL_MS, setup.wallMs)
        assertEquals(ShellTimeoutPolicy.SETUP_RATE_BPS, setup.outputRateBytesPerSec)
        assertEquals(ShellTimeoutPolicy.SETUP_FSIZE_BYTES, setup.fileSizeBytes)
        val service = BudgetClassifier.classify("sleep 100 &")
        assertEquals(WorkClass.SERVICE, service.workClass)
        assertEquals(ShellTimeoutPolicy.SERVICE_RATE_BPS, service.outputRateBytesPerSec)
        assertEquals(512L * 1024L, service.outputRateBytesPerSec)
    }

    @Test
    fun callerTimeoutIsNotAnInput() {
        assertEquals(60_000L, BudgetClassifier.armMs("ls /tmp", 10_000_000L))
        assertEquals(1_200_000L, BudgetClassifier.armMs("apt-get install curl", 1_000L))
        assertEquals(WorkClass.BATCH, BudgetClassifier.classify("apt-get install curl").workClass)
    }

    @Test
    fun unsetQueueWaitIsTwoMinutes() {
        assertEquals(120_000L, SessionConcurrencyManager.queueWaitMs(0))
        assertEquals(15_000L, SessionConcurrencyManager.queueWaitMs(15))
    }
}
