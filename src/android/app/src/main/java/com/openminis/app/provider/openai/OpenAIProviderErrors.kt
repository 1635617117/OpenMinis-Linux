package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.safeOptString
import org.json.JSONObject

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
    if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
    if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

    val message = try {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        val errorMessage = error?.safeOptString("message", "") ?: body
        "[$statusCode] $errorMessage"
    } catch (_: Exception) {
        "HTTP $statusCode: ${body.take(500)}"
    }

    // [T-llm-error-classification] 细分 OpenAI 400 类错误，不再笼统地归 ProviderError。
    val lower = message.lowercase()
    when {
        // "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
        // 不是上下文超长，保持 ProviderError。
        !lower.contains("max_tokens too large") && (
            lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            (statusCode == 400 && (
                lower.contains("too many tokens") ||
                lower.contains("reduce the length") ||
                (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed")))
            ))
        ) -> return LLMError.ContextLengthExceeded(message)
        lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || statusCode == 422 && lower.contains("content") ->
            return LLMError.ContentFiltered(message)
    }

    val transientCodes = setOf(500, 502, 503, 504, 529)
    if (statusCode in transientCodes) {
        // 503 with permanent failure indicators → ProviderError (trigger group fallback)
        if (statusCode == 503 && HttpRetryAfter.isPermanentCapacityBody(body)) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}

internal fun OpenAIProvider.mapError(error: Throwable): LLMError {
    if (error is LLMError) return error
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}
