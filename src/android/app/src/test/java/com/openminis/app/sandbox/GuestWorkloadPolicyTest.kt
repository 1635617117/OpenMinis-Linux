package com.openminis.app.sandbox

import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript
import com.openminis.app.sandbox.kernel.WorkClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestWorkloadPolicyTest {

    private val incident =
        "python3 audit_openminis_comprehensive.py 2>&1 | tee /tmp/audit_report.txt | head -150"

    @Test
    fun wrapRunsThePipelineOnceInTheSameShell() {
        val budget = BudgetClassifier.classify(incident)
        val wrapped = GuestLimits.wrap(incident)
        val groupKill = "kill -TERM -" + "$" + "$"
        assertTrue(wrapped.startsWith("ulimit -H -v ${budget.addressKiB()}"))
        assertTrue(wrapped.contains("ulimit -H -t ${budget.cpuSeconds}"))
        assertTrue(wrapped.contains("ulimit -S -t ${budget.cpuSeconds}"))
        assertTrue(wrapped.contains("ulimit -H -u ${budget.nproc}"))
        assertTrue(wrapped.contains("ulimit -H -f ${budget.fileBlocks()}"))
        assertTrue(wrapped.contains("trap 'kill -KILL"))
        assertTrue(wrapped.contains(groupKill))
        assertFalse(wrapped.contains("kill -0"))
        assertFalse(wrapped.contains("|| nice"))
        assertFalse(wrapped.contains("|| ionice"))
        assertEquals(1, wrapped.split(incident).size - 1)
    }

    @Test
    fun persistentCommandIsNotASubshellAndHasNoTrap() {
        val script = GuardianScript.persistentCommand("echo hi | head", 60)
        val groupKill = "kill -TERM -" + "$" + "$"
        assertTrue(script.contains(groupKill))
        assertFalse(script.contains("trap"))
        assertFalse(script.contains("ulimit"))
        assertFalse(script.contains("kill -0"))
        assertFalse(script.contains("( echo hi"))
        assertEquals(1, script.split("echo hi | head").size - 1)
    }

    @Test
    fun pythonRequestCannotRaiseTheCeiling() {
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs(incident, 600_000L))
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs(incident, 10_000_000L))
        assertEquals(600_000L, BudgetClassifier.classify(incident).wallMs)
        assertEquals(WorkClass.NORMAL, BudgetClassifier.classify(incident).workClass)
    }

    @Test
    fun setupFloorSurvivesAShortRequest() {
        val cmd = "minis-dev-setup-full"
        assertEquals(
            ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS,
            ShellTimeoutPolicy.effectiveMs(cmd, 60_000L),
        )
    }

    @Test
    fun findIsNormalAndACallerTimeoutDoesNotChangeIt() {
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs("find /var/minis/workspace -name '*.kt'", 60_000L))
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs("find /data -name '*.db'", 30_000L))
        assertTrue(GuestWorkloadPolicy.hostRefusal("find /data -name '*.db'")!!.contains("/data"))
    }

    @Test
    fun diskPressureRefusesTheIncidentAndStillAllowsLs() {
        val total = 213L * 1024 * 1024 * 1024
        val free = 35L * 1024 * 1024 * 1024
        assertTrue(GuestWorkloadPolicy.diskRefusal(free, total, incident)!!.contains("exit 126"))
        assertNull(GuestWorkloadPolicy.diskRefusal(free, total, "ls /tmp"))
        val healthy = 100L * 1024 * 1024 * 1024
        assertNull(GuestWorkloadPolicy.diskRefusal(healthy, total, incident))
        assertTrue(GuestWorkloadPolicy.diskRefusal(free, total, "./not-a-known-tool --bomb")!!.contains("exit 126"))
        assertTrue(GuestWorkloadPolicy.diskRefusal(free, total, "echo hi > /tmp/x")!!.contains("exit 126"))
        assertNull(GuestWorkloadPolicy.diskRefusal(free, total, "echo hi"))
        assertNull(GuestWorkloadPolicy.diskRefusal(free, total, "git status"))
        assertEquals(300, GuestWorkloadPolicy.cpuSeconds("python3 -m http.server"))
    }

    @Test
    fun escapedChildStaysOwnedUntilItExits() {
        val tree = StickyTree(7254)
        val seen = tree.observe(setOf(7254, 8988, 8989), setOf(7254, 8988, 8989))
        assertEquals(setOf(7254, 8988, 8989), seen)
        val escaped = tree.observe(setOf(7254), setOf(7254, 8988))
        assertTrue(escaped.contains(8988))
        val dead = tree.observe(setOf(7254), setOf(7254))
        assertFalse(dead.contains(8988))
    }

    @Test
    fun extremeStallKillsOnTheFirstHangAndAShortStutterDoesNot() {
        assertFalse(GuestWorkloadPolicy.shouldKillLiveWork(1, 3_000L))
        assertTrue(GuestWorkloadPolicy.shouldKillLiveWork(1, 8_000L))
        assertTrue(GuestWorkloadPolicy.shouldKillLiveWork(2, 3_000L))
        assertTrue(GuestWorkloadPolicy.exceedsProcessCap(GuestWorkloadPolicy.PROCESS_LIMIT + 1))
        assertFalse(GuestWorkloadPolicy.exceedsProcessCap(GuestWorkloadPolicy.PROCESS_LIMIT))
    }

    @Test
    fun longGapStillCountsAndOnlySamplingIsReduced() {
        assertTrue(GuestWorkloadPolicy.countsHang(48_000L, 30_000L, workloadLive = false))
        assertTrue(GuestWorkloadPolicy.countsHang(48_000L, 30_000L, workloadLive = true))
        assertFalse(GuestWorkloadPolicy.shouldSampleHang(48_000L, 30_000L, workloadLive = false))
        assertTrue(GuestWorkloadPolicy.shouldSampleHang(48_000L, 30_000L, workloadLive = true))
        assertTrue(GuestWorkloadPolicy.shouldSampleHang(4_000L, 30_000L, workloadLive = false))
    }

    @Test
    fun reparentedChildrenStayInTheTree() {
        val links = listOf(
            ProcLink(pid = 7254, ppid = 6530, tracerPid = 0),
            ProcLink(pid = 8988, ppid = 6530, tracerPid = 7254),
            ProcLink(pid = 8989, ppid = 6530, tracerPid = 7254),
            ProcLink(pid = 1, ppid = 0, tracerPid = 0),
        )
        assertEquals(setOf(7254, 8988, 8989), ProcessTree.collect(7254, links))
        assertEquals(setOf(1), ProcessTree.collect(1, links))
    }

    @Test
    fun statPgrpIgnoresSpacesInsideComm() {
        assertEquals(7254, ProcessTree.parseStatPgrp("7254 (proot) S 6530 7254 7254"))
        assertEquals(42, ProcessTree.parseStatPgrp("9 (python 3) R 1 42 42"))
    }

    @Test
    fun hostSuTimeoutIsCappedAndBroadFindIsRefused() {
        assertEquals(120_000L, GuestWorkloadPolicy.clampHostTimeout(999_000L))
        assertEquals(15_000L, GuestWorkloadPolicy.clampHostTimeout(15_000L))
        assertTrue(GuestWorkloadPolicy.requiresFreshConfirm("su -c id"))
        assertTrue(GuestWorkloadPolicy.hostRefusal("find /var/minis /tmp /root /home /data -maxdepth 3")!!.contains("/data"))
        assertNull(GuestWorkloadPolicy.hostRefusal("find /var/minis/workspace -maxdepth 4"))
    }

    @Test
    fun boundedBufferSetsTruncatedOnlyAfterACharIsDropped() {
        val buf = BoundedOutputBuffer(headChars = 4, tailChars = 4)
        buf.append("abcdefghij")
        assertTrue(buf.truncated)
        assertEquals(2, buf.dropped)
        assertTrue(buf.toString().contains("abcd"))
        assertTrue(buf.toString().endsWith("ghij"))
        val exact = BoundedOutputBuffer(headChars = 4, tailChars = 4)
        exact.append("abcdef")
        assertFalse(exact.truncated)
        assertEquals("abcdef", exact.toString())
    }
}
