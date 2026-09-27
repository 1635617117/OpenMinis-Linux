package com.openminis.app.sandbox

import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript

/**
 * Guest-side ceilings handed to the shell.
 *
 * No address-space limit: RLIMIT_AS is the host's decision. The prefix runs
 * in the same shell as the command, so it must not re-exec the command on
 * fallback: `nice cmd || nice cmd` runs a pipeline twice and treats `head`'s
 * exit as success. Nothing in the prefix may kill the shell — a failed
 * rlimit is logged and ignored, because the host's clamp is authoritative
 * and an `exit 1` here is a brick.
 */
object GuestLimits {
    fun nodeOptions(existing: String? = null): String {
        val flag = "--max-old-space-size=${ResourceLimits.NODE_OLD_SPACE_MB}"
        val stripped = existing.orEmpty()
            .replace(Regex("""--max-old-space-size(?:=|\s+)\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (stripped.isEmpty()) flag else "$stripped $flag"
    }

    /**
     * One-shot `bash -c` / `su -c` guardian. Hard rlimits, a process-group
     * watchdog, and an EXIT trap that reaps only that watchdog.
     */
    fun wrap(shellCommand: String): String =
        GuardianScript.oneshot(shellCommand, BudgetClassifier.classify(shellCommand))

    /**
     * Do not call this from [PersistentShell]. A subshell drops cwd and
     * exports, and an EXIT trap on that shell kills the pipe reader.
     */
    @Deprecated(
        "PersistentShell must use GuardianScript.persistentCommand. A subshell wrap is the failure.",
        level = DeprecationLevel.ERROR,
    )
    fun wrapChild(shellCommand: String): String =
        throw IllegalStateException("wrapChild($shellCommand) is forbidden on the persistent shell")

    /** Interactive terminal: rlimits only. No watchdog, so a hang cannot kill the user's shell. */
    fun interactivePrelude(): String = GuardianScript.bootLimits(BudgetClassifier.service())
}
