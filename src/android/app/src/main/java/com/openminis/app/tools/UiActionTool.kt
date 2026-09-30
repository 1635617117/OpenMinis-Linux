package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-ui-action] GUI-agent hands: a single typed entry point for the system
 * UI actions the accessibility CLI exposes, so the loop
 * `ui_read → decide → ui_action → ui_read (verify)` stays schema-validated
 * instead of the model hand-rolling `android-a11y-cli` flags in shell.
 *
 * Requires the AccessibilityService permission (same as [UiReadTool]).
 */
object UiActionTool {
    const val NAME = "ui_action"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Perform one UI action on the current foreground screen via the " +
            "accessibility service: tap coordinates, type text, swipe, scroll, press back, " +
            "open an app by package, or launch an intent URL. " +
            "Use with ui_read in a loop: read the screen to find a target, act, read again " +
            "to verify. Requires Accessibility permission.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, e.g. 'Tap the send button'."),
            "action" to AgentToolParam("string", "One of: tap, type, swipe, scroll, back, open_app, open_url, long_press."),
            "x" to AgentToolParam("integer", "X coordinate in screen pixels (required for tap, long_press, swipe start)."),
            "y" to AgentToolParam("integer", "Y coordinate in screen pixels (required for tap, long_press, swipe start)."),
            "x2" to AgentToolParam("integer", "Swipe end X (required for swipe)."),
            "y2" to AgentToolParam("integer", "Swipe end Y (required for swipe)."),
            "text" to AgentToolParam("string", "Text to type (required for type)."),
            "direction" to AgentToolParam("string", "up or down (for scroll)."),
            "package_name" to AgentToolParam("string", "Android package to launch (for open_app)."),
            "url" to AgentToolParam("string", "Intent URL (for open_url, e.g. https://…, tel:, geo:, market:)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "x", "y", "x2", "y2", "text",
            "direction", "package_name", "url",
        ),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false)
        }
        val action = obj.optString("action").lowercase()
        val toolTitle = obj.optString("tool_title", "ui_action")

        fun num(key: String): String = obj.opt(key).let { v ->
            if (v == JSONObject.NULL || v == null) "" else v.toString()
        }

        val cmd: String = when (action) {
            "tap" -> "android-a11y-cli tap ${num("x")} ${num("y")}"
            "long_press" -> "android-a11y-cli long-press ${num("x")} ${num("y")}"
            "type" -> "android-a11y-cli type " + "'" + obj.optString("text").replace("'", "'\\''") + "'"
            "swipe" -> "android-a11y-cli swipe ${num("x")} ${num("y")} ${num("x2")} ${num("y2")}"
            "scroll" -> "android-a11y-cli scroll ${obj.optString("direction", "down")}"
            "back" -> "android-a11y-cli back"
            "open_app" -> "android-shizuku-cli launch " + "'" + obj.optString("package_name").replace("'", "'\\''") + "'"
            "open_url" -> "android-open " + "'" + obj.optString("url").replace("'", "'\\''") + "'"
            else -> return ToolExecutionResult(
                "Error: unknown action '$action' (支持: tap/type/swipe/scroll/back/open_app/open_url/long_press)",
                false,
                toolTitle = toolTitle,
            )
        }

        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              $cmd 2>&1 || true
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()

        return runCatching {
            val process = ProcessBuilder("/bin/bash", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            ToolExecutionResult(out.trim().ifBlank { "(无输出)" }, process.exitValue() == 0, toolTitle = toolTitle)
        }.getOrElse {
            ToolExecutionResult("Error: ${it.message}", false, toolTitle = toolTitle)
        }
    }
}
