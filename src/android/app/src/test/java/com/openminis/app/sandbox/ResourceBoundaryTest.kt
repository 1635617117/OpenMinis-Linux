package com.openminis.app.sandbox

import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.network.NetworkFlapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResourceBoundaryTest {
    @Test
    fun guest_limits_are_absolute_not_a_device_fraction() {
        assertEquals("--max-old-space-size=192", GuestLimits.nodeOptions(null))
        assertEquals(
            "--max-old-space-size=192",
            GuestLimits.nodeOptions("--max-old-space-size=4096"),
        )
        assertFalse(GuestLimits.nodeOptions().contains("%"))
        assertEquals(192, ResourceLimits.NODE_OLD_SPACE_MB)
    }

    /**
     * The brick. HyperOS and the memory-pressure policies clamp the hard
     * RLIMIT_AS on a new process and that clamp survives an app restart, so
     * an app-side `ulimit -H -v` could only raise a limit it is not allowed
     * to raise: EPERM, and with `exit 1` a dead shell. The app must not set
     * the address space at all, and no rlimit may be fatal.
     */
    @Test
    fun the_app_never_sets_the_address_space_and_never_exits_on_a_failed_rlimit() {
        for (command in listOf("true", "ls -la", "apt install -y curl", "sleep 100 &")) {
            val wrapped = GuestLimits.wrap(command)
            assertFalse("address space set for: $command", wrapped.contains("ulimit -H -v"))
            assertFalse("address space set for: $command", wrapped.contains("ulimit -S -v"))
            assertFalse("fatal rlimit for: $command", wrapped.contains("|| exit 1"))
        }
    }

    /** The two assets bootstrap scripts had the same fatal ulimit. */
    @Test
    fun the_bootstrap_scripts_do_not_set_a_fatal_address_space() {
        val profile = File("src/main/assets/default_mount/etc/profile.d")
        for (name in listOf("minis.sh", "minis-limits.sh")) {
            val text = File(profile, name).readText()
            assertFalse("$name sets an address-space cap", Regex("""ulimit\s+(-[A-Za-z]+\s+)*-v""").containsMatchIn(text))
            assertFalse("$name can kill the shell", text.contains("|| exit 1"))
        }
    }

    @Test
    fun a_network_flap_does_not_evict_the_pool() {
        assertFalse(NetworkFlapPolicy.shouldEvictPool())
        assertTrue(NetworkFlapPolicy.DEBOUNCE_MS >= 1_000L)
    }

    @Test
    fun staging_replaces_the_installed_tree_only_after_it_exists() {
        val root = File.createTempFile("rootfs", "").apply {
            delete()
            mkdirs()
        }
        val installed = File(root, "rootfs").apply { mkdirs() }
        File(installed, "old").writeText("keep-until-promote")
        val staging = File(root, "rootfs.staging").apply { mkdirs() }
        File(staging, "usr").apply { mkdirs() }
        File(File(staging, "usr"), "bin").apply { mkdirs() }
        RootfsStaging.promote(staging, installed)
        assertTrue(File(installed, "usr/bin").isDirectory)
        assertFalse(File(installed, "old").exists())
        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun hot_path_queries_do_not_select_star_or_raw_parts_json() {
        val dao = File("src/main/java/com/openminis/app/data/db/ChatDao.kt").readText()
        assertFalse(dao.contains("SELECT * FROM messages"))
        assertFalse(dao.contains("SELECT parts_json FROM messages WHERE session_id"))
        assertFalse(dao.contains("SELECT parts_json FROM messages ORDER"))
        // List loads stay projected. One-row display hydration is the only raw cell read.
        assertTrue(dao.contains("SELECT parts_json FROM messages WHERE id = :id"))
        val screen = File("src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt").readText()
        val event = screen.substringAfter("fun onAccessibilityEvent")
            .substringBefore("fun onInterrupt")
        assertFalse(event.contains("event.source"))
        assertTrue(event.contains("AccessibilityQueryGuard.submit"))
        val body = File("src/main/java/com/openminis/app/data/body").walk()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(body.contains(".readBytes()"))
        assertFalse(body.contains(".readText()"))
        assertFalse(body.contains("JSONArray("))
        assertFalse(File("src/main/java/com/openminis/app/diagnostics/LaunchCycleBeacon.kt").readText()
            .contains("RESTART_COUNT_FORCE_HOME_THRESHOLD"))
    }
}
