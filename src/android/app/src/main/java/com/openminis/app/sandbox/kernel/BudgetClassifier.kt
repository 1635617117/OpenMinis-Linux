package com.openminis.app.sandbox.kernel

import com.openminis.app.sandbox.GuestWorkloadPolicy
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.ShellTimeoutPolicy

/**
 * The live classifier. [ShellTimeoutPolicy] owns the numbers; this object is
 * the only thing [com.openminis.app.sandbox.ExecutionCoordinator] and
 * [com.openminis.app.sandbox.ShellExecutor] arm. A requested timeout is not an
 * input — the old `maxOf(requested, minimumMs)` path is how a 10-minute default
 * outlived the table.
 *
 * SETUP is only the named setup scripts. A background `&` is SERVICE, not
 * SETUP, so a long install window cannot be claimed by an unrelated task.
 * `find` is NORMAL: a short special case does not stop a tree walk, the
 * output rate and the host deadline do.
 */
object BudgetClassifier {

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    fun classify(
        command: String,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
    ): ProcessBudget {
        val text = command.trim()
        if (text.isEmpty()) return interactive()
        // Split consumes `&`, so a background job must be seen on the raw
        // command. Otherwise `sleep 100 &` becomes NORMAL and inherits a
        // CPU limit a daemon must not have.
        val background = isBackground(text)
        val segments = text.split(Regex("""&&|\|\||[;&|]|\$\(|`"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val detected = if (segments.isEmpty()) interactive()
            else segments.map { classifySegment(it) }.maxBy { rank(it.workClass) }
        val raised = if (background && rank(detected.workClass) < rank(WorkClass.SERVICE)) service() else detected
        if (resourceClass == SandboxResourceGate.ResourceClass.HEAVY &&
            rank(raised.workClass) < rank(WorkClass.BATCH)
        ) {
            return batch()
        }
        return raised
    }

    private fun isBackground(command: String): Boolean {
        val stripped = command
            .replace(Regex(""""[^"]*"|'[^']*'"""), " ")
            // `2>&1` and `&>file` are redirections, not background jobs.
            .replace(Regex("""\d*>&\d*|\d*<&\d*|&>>|&>"""), " ")
        if (Regex("""(?:^|[\s;&|`(\n])(?:nohup|setsid)(?:\s|$)""").containsMatchIn(stripped)) return true
        return Regex("""(?<![&])&(?![&])""").containsMatchIn(stripped)
    }

    /** Armed wall clock. [requestedMs] is ignored on purpose. */
    fun armMs(command: String, requestedMs: Long = 0L): Long {
        if (requestedMs < 0L) return classify(command).wallMs
        return classify(command).wallMs
    }

    private fun classifySegment(segment: String): ProcessBudget {
        if (GuestWorkloadPolicy.isSetup(segment)) return setup()
        if (isService(segment)) return service()
        if (GuestWorkloadPolicy.isBuild(segment) || GuestWorkloadPolicy.isInstall(segment)) return batch()
        if (isInteractive(segment)) return interactive()
        return normal()
    }

    private fun isService(segment: String): Boolean {
        val c = segment.trim()
        if (c.endsWith("&")) return true
        val token = c.substringBefore(' ').substringAfterLast('/')
        return token == "nohup" || token == "setsid"
    }

    private fun isInteractive(segment: String): Boolean {
        val token = segment.trim().substringBefore(' ').substringAfterLast('/')
        return token in INTERACTIVE_TOKENS
    }

    private fun rank(cls: WorkClass): Int = when (cls) {
        WorkClass.INTERACTIVE -> 0
        WorkClass.NORMAL -> 1
        WorkClass.BATCH -> 2
        WorkClass.SERVICE -> 3
        WorkClass.SETUP -> 4
    }

    fun interactive(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.INTERACTIVE,
        wallMs = ShellTimeoutPolicy.INTERACTIVE_WALL_MS,
        cpuSeconds = 30,
        fileSizeBytes = 256L * MIB,
        nproc = 64,
        outputCapBytes = 8L * MIB,
        outputRateBytesPerSec = 256L * 1024L,
    )

    fun normal(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.NORMAL,
        wallMs = ShellTimeoutPolicy.NORMAL_WALL_MS,
        cpuSeconds = 300,
        fileSizeBytes = GIB,
        nproc = 128,
        outputCapBytes = 32L * MIB,
        outputRateBytesPerSec = 2L * MIB,
    )

    fun batch(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.BATCH,
        wallMs = ShellTimeoutPolicy.BATCH_WALL_MS,
        cpuSeconds = 600,
        fileSizeBytes = 4L * GIB,
        nproc = 4096,
        outputCapBytes = 64L * MIB,
        outputRateBytesPerSec = 8L * MIB,
    )

    /**
     * Ordinary long-running services. Output rate is 64 KiB/s, not the 1 MiB/s
     * in the design draft: a service that can write a megabyte a second is the
     * incident's resource type.
     */
    fun service(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.SERVICE,
        wallMs = ShellTimeoutPolicy.SERVICE_WALL_MS,
        cpuSeconds = 0,
        fileSizeBytes = 4L * GIB,
        nproc = 4096,
        outputCapBytes = 16L * MIB,
        outputRateBytesPerSec = ShellTimeoutPolicy.SERVICE_RATE_BPS,
    )

    /**
     * Named setup scripts only. Same wall as a service, but a separate class
     * so nothing else inherits the window. FSIZE is 8 GiB because an SDK zip
     * is one file; the output rate stays 64 KiB/s so the install cannot flood
     * the UI pipe.
     */
    fun setup(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.SETUP,
        wallMs = ShellTimeoutPolicy.SETUP_WALL_MS,
        cpuSeconds = 0,
        fileSizeBytes = ShellTimeoutPolicy.SETUP_FSIZE_BYTES,
        nproc = 4096,
        outputCapBytes = 16L * MIB,
        outputRateBytesPerSec = ShellTimeoutPolicy.SETUP_RATE_BPS,
    )

    private val INTERACTIVE_TOKENS = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "rg", "egrep", "fgrep",
        "echo", "printf", "pwd", "which", "whoami", "id", "true", "false",
        "basename", "dirname", "stat", "df",
    )
}
