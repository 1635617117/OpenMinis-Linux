package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityGateImplTest {
    private lateinit var gate: SecurityGateImpl

    @Before
    fun setUp() {
        gate = SecurityGateImpl()
        gate.setPermissionMode(PermissionMode.ASK)
    }

    @Test
    fun denyAllBlocksRead() {
        gate.setPermissionMode(PermissionMode.DENY_ALL)
        val d = gate.decide(gate.classify("file_read", """{"path":"/tmp/a"}"""), PermissionMode.DENY_ALL)
        assertTrue(d is Decision.Denied)
    }

    @Test
    fun askAllowsReadOnly() {
        val d = gate.decide(gate.classify("file_read", """{"path":"/tmp/a"}"""), PermissionMode.ASK)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun askConfirmsFileWrite() {
        val d = gate.decide(gate.classify("file_write", """{"path":"/tmp/a","content":"x"}"""), PermissionMode.ASK)
        assertTrue(d is Decision.NeedConfirm)
    }

    @Test
    fun readOnlyBlocksShell() {
        gate.setPermissionMode(PermissionMode.READ_ONLY)
        val d = gate.decide(
            gate.classify("shell_execute", """{"command":"rm -rf /tmp/foo"}"""),
            PermissionMode.READ_ONLY,
        )
        assertTrue(d is Decision.Denied)
    }

    @Test
    fun fatalBansRmRfRoot() {
        assertEquals(RiskLevel.FATAL_BANNED, gate.classifyRisk("rm -rf /"))
        assertEquals(RiskLevel.FATAL_BANNED, gate.classifyRisk("rm -rf /*"))
        val d = gate.decide(
            gate.classify("shell_execute", """{"command":"rm -rf /"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue(d is Decision.NeedConfirm)
        assertTrue((d as Decision.NeedConfirm).mustPrompt)
        assertFalse(sessionAllowAllSkipsPrompt(sessionAllowAll = true, mustPrompt = true))
        assertTrue(sessionAllowAllSkipsPrompt(sessionAllowAll = true, mustPrompt = false))
        val denied = gate.decide(
            gate.classify("shell_execute", """{"command":"rm -rf /"}"""),
            PermissionMode.ASK,
        )
        assertTrue(denied is Decision.Denied)
    }

    /**
     * YOYO means "run it". 2.0.10 asked on every host `su` even under
     * ALLOW_ALL, which contradicted the mode the user picked.
     */
    @Test
    fun yoyoLetsHostSuThrough() {
        val su = gate.decide(
            gate.classify("shell_execute", """{"command":"su -c id"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue("su must follow the mode under YOLO: $su", su is Decision.Allow)
    }

    @Test
    fun askStillConfirmsHostSuEveryTime() {
        val su = gate.decide(
            gate.classify("shell_execute", """{"command":"su -c id"}"""),
            PermissionMode.ASK,
        )
        assertTrue(su is Decision.NeedConfirm)
        assertTrue((su as Decision.NeedConfirm).mustPrompt)
    }

    /**
     * An unscoped walk is slow, not irreversible, so it is not refused
     * outright any more — the wall clock, output rate and resident window
     * are the brakes for it. 2.0.10 refused it in every mode, which made a
     * read-only command unavailable.
     *
     * `/var`, not `/data`: a guest `/data` is the sandbox's own unmounted
     * tree and is refused by SuPathPolicy for being unusable, which is a
     * separate and correct rule.
     */
    @Test
    fun yoyoLetsABroadFindThroughToTheResourceBrakes() {
        val find = gate.decide(
            gate.classify("shell_execute", """{"command":"find /var -name '*.db'"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue("a broad find must not be refused outright: $find", find is Decision.Allow)
    }

    /**
     * A read-only command is auto-run in every mode by design, so a broad
     * walk is available under ASK too. What changed in 2.0.10 was that the
     * DESTRUCTIVE branch reached in front of this and refused it outright.
     */
    @Test
    fun aBroadFindIsAvailableUnderAskBecauseItIsReadOnly() {
        val find = gate.decide(
            gate.classify("shell_execute", """{"command":"find /var -name '*.db'"}"""),
            PermissionMode.ASK,
        )
        assertTrue("a read-only walk must stay available: $find", find is Decision.Allow)
    }

    @Test
    fun yoyoStillConfirmsHostBlockDeviceWrites() {
        // A block-device write is FATAL_BANNED, so it is a confirm-with-prompt
        // even under YOLO — that branch runs before the mode check and YOLO's
        // contract is "auto-run except fatal-confirm". It is never an Allow.
        for (command in listOf("dd if=/dev/zero of=/dev/block/sda", "mkfs.ext4 /dev/block/sda")) {
            val d = gate.decide(
                gate.classify("shell_execute", """{"command":"$command"}"""),
                PermissionMode.ALLOW_ALL,
            )
            assertTrue("$command must not auto-run: $d", d is Decision.NeedConfirm)
            assertTrue((d as Decision.NeedConfirm).mustPrompt)
        }
    }

    @Test
    fun denyRuleBeatsAllow() {
        gate.setPermissionRules(
            listOf(
                PermissionRule(action = "allow", toolFilter = "shell_execute", pattern = "*"),
                PermissionRule(action = "deny", toolFilter = "shell_execute", pattern = "rm *"),
            ),
        )
        val d = gate.decide(
            gate.classify("shell_execute", """{"command":"rm /tmp/x"}"""),
            PermissionMode.ASK,
        )
        assertTrue(d is Decision.Denied)
    }

    @Test
    fun allowRuleBypassesAsk() {
        gate.setPermissionRules(
            listOf(PermissionRule(action = "allow", toolFilter = "file_write", pattern = "/tmp/*")),
        )
        val d = gate.decide(
            gate.classify("file_write", """{"path":"/tmp/a","content":"x"}"""),
            PermissionMode.ASK,
        )
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun auditHashChainDetectsTamper() {
        val cmd = gate.classify("file_read", """{"path":"/tmp/a"}""")
        gate.audit(cmd, Decision.Allow("ok"), "ok")
        gate.audit(cmd, Decision.Allow("ok2"), "ok2")
        assertTrue(gate.verifyAuditChain().ok)
        gate.tamperDecision(0, "TAMPERED")
        val v = gate.verifyAuditChain()
        assertFalse(v.ok)
        assertEquals(0, v.brokenAt)
    }

    @Test
    fun authorityFenceDeniesOutsideWorkspace() {
        gate.setAuthorityProfile(PermissionProfile.workspace("/var/minis/workspace"))
        val asked = gate.decide(
            gate.classify("file_write", """{"path":"/etc/passwd","content":"x"}"""),
            PermissionMode.ASK,
        )
        assertTrue(asked is Decision.Denied)
        val allowed = gate.decide(
            gate.classify("file_write", """{"path":"/etc/passwd","content":"x"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue(allowed is Decision.Allow)
    }

    @Test
    fun denyRuleStillBeatsAllowAll() {
        gate.setPermissionRules(
            listOf(PermissionRule(action = "deny", toolFilter = "shell_execute", pattern = "rm *")),
        )
        val d = gate.decide(
            gate.classify("shell_execute", """{"command":"rm /tmp/x"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue(d is Decision.Denied)
        assertTrue((d as Decision.Denied).reason.startsWith("规则拒绝"))
    }

    @Test
    fun sessionAllowAllUsesSameModeAsGlobal() {
        assertEquals(
            PermissionMode.ALLOW_ALL,
            effectivePermissionMode(PermissionMode.ASK, sessionAllowAll = true),
        )
        assertEquals(
            PermissionMode.DENY_ALL,
            effectivePermissionMode(PermissionMode.DENY_ALL, sessionAllowAll = false),
        )
    }

    @Test
    fun aliasesCanonicalize() {
        assertEquals("shell_execute", ToolAliases.canonical("shell_exec"))
        assertEquals("web_fetch", ToolAliases.canonical("fetch_url"))
        assertEquals("memory_write", ToolAliases.canonical("save_memory"))
        assertEquals("execute_code", ToolAliases.canonical("code_exec"))
    }

    @Test
    fun tokenizeRespectsQuotes() {
        assertEquals(listOf("echo", "a b"), tokenizeCommand("echo 'a b'"))
        assertEquals(listOf("rm", "-rf", "/"), tokenizeCommand("rm -rf /"))
    }
}
