package com.openminis.app.sandbox.kernel

/**
 * Two guardians. They are not interchangeable.
 *
 * [oneshot] is for `bash -c` and `su -c`. The wrapper is the process-group
 * leader, so a watchdog may use `kill -TERM -$$` and an EXIT trap may reap
 * that watchdog. `kill -0` is not a group kill: in the shell it is an
 * existence test.
 *
 * [supervisor] is for a long-lived shell. It starts one background watchdog
 * and nothing else: no `trap`, no `ulimit`, no subshell around later commands.
 * A subshell would drop cwd and exported variables, which is the whole point
 * of the persistent shell. A trap on that shell kills the pipe reader and the
 * next write gets EPIPE.
 *
 * [commandWatchdog] re-arms the same supervisor for one command. It is not a
 * wrap. The command stays in the persistent shell. On success only the
 * watchdog pid is killed; on expiry `kill -TERM -$$` kills the group,
 * including grandchildren that called setsid inside the group.
 */
object GuardianScript {

    fun oneshot(command: String, budget: ProcessBudget): String = buildString {
        append(hardLimits(budget))
        append(watchdog(budget.wallSeconds))
        append("__minis_wd=\$!; ")
        append("trap 'kill -KILL \$__minis_wd 2>/dev/null' EXIT; ")
        append(command)
    }

    /** Boot-time rlimits for a persistent shell. No watchdog, no trap. */
    fun bootLimits(budget: ProcessBudget): String = hardLimits(budget)

    /**
     * Injected once into the persistent shell's start command.
     * `kill -TERM -$$` — the leading dash is the process group, not a signal
     * number and not pid 0.
     */
    fun supervisor(wallSeconds: Int): String = buildString {
        val wall = wallSeconds.coerceAtLeast(1)
        append("( sleep ").append(wall)
        append("; kill -TERM -\$\$ 2>/dev/null; sleep 3; kill -KILL -\$\$ 2>/dev/null ) & ")
        append("echo \"[guardian] watchdog armed, wall=").append(wall).append("s pgid=\$\$\"")
    }

    /**
     * Lines written before one persistent-shell command. Not a subshell.
     * The caller must capture `$?` and then `kill -KILL $__minis_cmd_wd`
     * so a finished command does not leave a timer that kills the session.
     */
    fun commandWatchdog(wallSeconds: Int): String = buildString {
        val wall = wallSeconds.coerceAtLeast(1)
        append("kill -KILL \${__minis_cmd_wd:-} 2>/dev/null\n")
        append("( sleep ").append(wall)
        append("; kill -TERM -\$\$ 2>/dev/null; sleep 3; kill -KILL -\$\$ 2>/dev/null ) &\n")
        append("__minis_cmd_wd=\$!\n")
    }

    /**
     * What the persistent shell actually writes. The command is not inside
     * parentheses. Ulimits are not repeated here; they were set at boot.
     */
    fun persistentCommand(command: String, wallSeconds: Int): String = buildString {
        // Re-arm. Kill the boot supervisor and the previous command watchdog
        // so a 30-minute session backstop cannot outlive the command that
        // replaced it. The command itself is not inside parentheses.
        append("kill -KILL \${__minis_wd:-} 2>/dev/null\n")
        append(commandWatchdog(wallSeconds))
        append(command)
        if (!command.endsWith("\n")) append('\n')
    }

    fun persistentBoot(limits: ProcessBudget, sessionWallSeconds: Int): String = buildString {
        append(bootLimits(limits))
        append(supervisor(sessionWallSeconds))
        append("; exec /bin/bash --noprofile --norc")
    }

    private fun watchdog(wallSeconds: Int): String {
        val wall = wallSeconds.coerceAtLeast(1)
        return "( sleep $wall; kill -TERM -\$\$ 2>/dev/null; sleep 3; kill -KILL -\$\$ 2>/dev/null ) & "
    }

    /**
     * CPU, process count, file size, core dump. NOT the address space.
     *
     * RLIMIT_AS is the host's decision. HyperOS and the memory-pressure
     * policies clamp the hard limit on the new process, and that clamp
     * survives an app restart, so an app-side `ulimit -H -v` is either
     * redundant or a brick: raising a hard limit needs privilege, the shell
     * gets EPERM, and the previous `|| exit 1` turned that into a dead
     * shell. Nothing here may kill the shell.
     */
    private fun hardLimits(budget: ProcessBudget): String = buildString {
        if (budget.cpuSeconds > 0) {
            append("ulimit -H -t ").append(budget.cpuSeconds).append(" 2>/dev/null || true; ")
            append("ulimit -S -t ").append(budget.cpuSeconds).append(" 2>/dev/null || true; ")
        }
        append("ulimit -H -u ").append(budget.nproc).append(" 2>/dev/null || true; ")
        append("ulimit -S -u ").append(budget.nproc).append(" 2>/dev/null || true; ")
        append("ulimit -H -f ").append(budget.fileBlocks()).append(" 2>/dev/null || true; ")
        append("ulimit -S -f ").append(budget.fileBlocks()).append(" 2>/dev/null || true; ")
        append("ulimit -H -c 0 2>/dev/null || true; ")
        // [T-as-soft-probe-removed] A previous cut probed/raised the address
        // space here: `( ulimit -S -v unlimited && ulimit -H -v unlimited ) || true`.
        // rlimit is a per-process attribute — the subshell's setrlimit died with
        // it, the parent shell and every exec'd process were unaffected, and
        // nothing consumed the result. A pure no-op that contradicted the
        // "the app never sets RLIMIT_AS" contract (887d48c); deleted.
    }
}
