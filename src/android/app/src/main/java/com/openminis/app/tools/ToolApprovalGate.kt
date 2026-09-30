package com.openminis.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-tool-approval] Human-in-the-loop gate for tool calls (Kelivo
 * ToolApprovalService, scoped down). When [enabled], a matching tool call
 * suspends until the user approves or denies through [pending] — surfaced
 * by the UI as an approval card. While disabled (default) every call
 * passes through, preserving current behaviour.
 */
object ToolApprovalGate {
    data class Pending(
        val id: String,
        val toolName: String,
        val argsPreview: String,
    ) {
        val deferred = CompletableDeferred<Boolean>()
    }

    @Volatile
    var enabled: Boolean = false

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /**
     * Suspends until approval when [enabled] and [toolName] matches
     * [gatedTools] (case-insensitive). Returns true = execute, false = deny.
     */
    suspend fun awaitApproval(toolName: String, argsPreview: String): Boolean {
        if (!enabled) return true
        if (!isGated(toolName)) return true
        val item = Pending(
            id = java.util.UUID.randomUUID().toString(),
            toolName = toolName,
            argsPreview = argsPreview.take(400),
        )
        _pending.value = _pending.value + item
        return try {
            item.deferred.await()
        } finally {
            _pending.value = _pending.value.filterNot { it.id == item.id }
        }
    }

    fun approve(id: String) {
        _pending.value.firstOrNull { it.id == id }?.deferred?.complete(true)
    }

    fun deny(id: String) {
        _pending.value.firstOrNull { it.id == id }?.deferred?.complete(false)
    }

    private fun isGated(toolName: String): Boolean =
        toolName.equals("su_exec", ignoreCase = true) ||
            toolName.equals("shell_execute", ignoreCase = true) ||
            toolName.equals("shell_exec", ignoreCase = true) ||
            toolName.equals("env_exec", ignoreCase = true)
}
