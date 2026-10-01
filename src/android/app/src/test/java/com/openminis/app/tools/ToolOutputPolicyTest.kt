package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOutputPolicyTest {

    private val sensitive = listOf("sk-Ab12Cd34Ef56Gh78")

    @Test
    fun plainTextReplace() {
        val out = ToolOutputPolicy.redactWith("token=sk-Ab12Cd34Ef56Gh78 done", sensitive)
        assertEquals("token=[REDACTED] done", out)
    }

    @Test
    fun jsonObjectValueRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"apiKey":"sk-Ab12Cd34Ef56Gh78","ok":true}""",
            sensitive,
        )
        assertEquals("""{"apiKey":"[REDACTED]","ok":true}""", out)
    }

    @Test
    fun jsonEscapedValueRedacted() {
        // 值经 JSON 转义（引号逃逸）后纯文本 replace 匹配不到原文，
        // 结构化递归仍能命中。
        val out = ToolOutputPolicy.redactWith(
            """{"cmd":"echo \"sk-Ab12Cd34Ef56Gh78\" > /tmp/x"}""",
            sensitive,
        )
        assertEquals("""{"cmd":"echo \"[REDACTED]\" > /tmp/x"}""", out)
    }

    @Test
    fun jsonNestedArrayRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"env":[{"k":"A","v":"sk-Ab12Cd34Ef56Gh78"},{"k":"B","v":"x"}]}""",
            sensitive,
        )
        val parsed = org.json.JSONObject(out)
        val arr = parsed.getJSONArray("env")
        assertEquals("[REDACTED]", arr.getJSONObject(0).getString("v"))
        assertEquals("x", arr.getJSONObject(1).getString("v"))
    }

    @Test
    fun nonJsonFallsBackToTextReplace() {
        val out = ToolOutputPolicy.redactWith("line1\nsk-Ab12Cd34Ef56Gh78\nline3", sensitive)
        assertEquals("line1\n[REDACTED]\nline3", out)
    }

    @Test
    fun emptySensitiveListIsNoop() {
        val text = """{"apiKey":"sk-Ab12Cd34Ef56Gh78"}"""
        assertEquals(text, ToolOutputPolicy.redactWith(text, emptyList()))
    }
}
