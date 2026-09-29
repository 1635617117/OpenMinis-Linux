package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.DiscussionGraph
import com.openminis.app.tools.GroupChat
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal suspend fun ChatViewModel.runGroupChat(provider: LLMProvider, closing: Boolean) {
    if (closing) groupChatCloseRequested = true
    val analyzing = context.getString(com.openminis.app.R.string.group_chat_analyzing)
    val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
    val closingNow = closing || GroupChat.isCloseRequest(userText)
    val prior = if (closingNow) closeRecord() else groupTranscript()
    if (closingNow && prior.isEmpty()) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_nothing_to_close))
        groupChatCloseRequested = false
        return
    }
    if (!closing && userText.isBlank()) return

    val config = providerRepository.config.value
    val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
    val hostName = (mainEntry?.model?.displayName ?: currentModel?.displayName ?: "Host") +
        " · " + context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    val hostSnapshot = snapshotFor(mainEntry)
    val hostVendor = GroupChat.vendorKey(
        mainEntry?.model?.id ?: currentModel?.id,
        mainEntry?.model?.displayName ?: currentModel?.displayName,
        hostSnapshot?.providerTypeRaw,
    )
    val slotMembers = groupMembers(config.modelEntries, mainEntry?.id)
    val hostPlain = mainEntry?.model?.displayName ?: currentModel?.displayName ?: "Host"
    val hostMember = GroupMember(
        name = hostPlain,
        vendor = hostVendor,
        provider = provider,
        snapshot = hostSnapshot,
        maxTokens = (mainEntry?.model?.maxOutputTokens ?: 1024).coerceIn(256, 2048),
        temperature = mainEntry?.overrides?.temperature,
        thinkingLevel = mainEntry?.effectiveMaxThinkingLevel
            ?: com.openminis.app.data.model.ThinkingLevel.OFF,
    )
    val members = listOf(hostMember) + slotMembers.filter { it.name != hostPlain }
    val wantsClose = closingNow
    if (!wantsClose && members.size < 2) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_need_models))
        return
    }
    val spoken = prior.toMutableList()
    val contextText = recentContext()
    val addressed = if (!wantsClose) {
        GroupChat.addressedName(userText, members.map { it.name } + hostPlain)
    } else {
        null
    }
    if (!wantsClose && addressed != null && !groupChatCloseRequested) {
        val member = members.find { it.name == addressed }
        if (member != null) {
            spoken += speakMembers(listOf(member), analyzing) {
                GroupChat.directPrompt(it.name, userText, GroupChat.transcript(prior), contextText)
            }
        } else {
            val answer = speakVisible(
                speaker = hostName,
                vendor = hostVendor,
                snapshot = hostSnapshot,
                provider = provider,
                system = GroupChat.memberSystem(hostPlain),
                user = GroupChat.directPrompt(hostPlain, userText, GroupChat.transcript(prior), contextText),
                tools = groupTools(provider),
                placeholder = analyzing,
                thinkingLevel = mainEntry?.effectiveMaxThinkingLevel
                    ?: com.openminis.app.data.model.ThinkingLevel.OFF,
            )
            if (answer.isNotBlank()) spoken += GroupChat.Line(hostName, answer)
        }
    } else if (!wantsClose && !groupChatCloseRequested && members.isNotEmpty()) {
        val firstRound = speakMembers(members, analyzing) { member ->
            val index = members.indexOf(member)
            GroupChat.opinionPrompt(
                member.name,
                GroupChat.stance(index),
                userText,
                GroupChat.transcript(spoken),
                contextText,
            )
        }
        spoken += firstRound
        if (!groupChatCloseRequested && firstRound.size >= 2) {
            spoken += speakMembers(members, analyzing, deferBubble = true) { member ->
                GroupChat.replyPrompt(member.name, userText, GroupChat.transcript(spoken))
            }
        }
    }

    if (wantsClose || groupChatCloseRequested) {
        val summary = speakVisible(
            speaker = hostName,
            vendor = hostVendor,
            snapshot = hostSnapshot,
            provider = provider,
            system = GroupChat.HOST_SYSTEM,
            user = GroupChat.summaryPrompt(userText, GroupChat.transcript(spoken)),
            tools = emptyList(),
            placeholder = context.getString(com.openminis.app.R.string.group_chat_summarizing),
        )
        if (summary.isNotBlank()) {
            groupChatClosedAfterId = _messages.value.lastOrNull { it.speakerName == hostName }?.id
            groupChatPrefs().edit().putString(closedKey(), groupChatClosedAfterId).apply()
            withContext(Dispatchers.Main) {
                _promptQueue.value = emptyList()
                _messages.value = _messages.value.filterNot { it.isQueued }
            }
            setGroupChatEnabled(false)
        } else {
            publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_summary_failed))
        }
    }
    groupChatCloseRequested = false
}

internal fun ChatViewModel.endGroupChat() {
    if (_isStreaming.value) {
        groupChatCloseRequested = true
        return
    }
    val provider = currentProvider ?: idleGroupProvider()
    if (provider == null) {
        appendSystemInfo(context.getString(com.openminis.app.R.string.group_chat_no_provider), "info")
        return
    }
    currentProvider = provider
    groupChatCloseRequested = true
    _isStreaming.value = true
    streamJob = viewModelScope.launchActiveRun(
        activeSessionId,
        Dispatchers.IO,
        ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId),
        beforeStart = { streamJob = it },
    ) {
        try {
            com.openminis.app.service.SessionConcurrencyManager.acquireSlot(activeSessionId)
            runGroupChat(provider, closing = true)
        } catch (_: CancellationException) {
        } finally {
            com.openminis.app.service.SessionConcurrencyManager.releaseSlot(activeSessionId)
            if (streamJob === coroutineContext[kotlinx.coroutines.Job]) {
                _isStreaming.value = false
            }
        }
    }
}

private data class GroupMember(
    val name: String,
    val vendor: String,
    val provider: LLMProvider,
    val snapshot: ModelAttributionSnapshot?,
    val maxTokens: Int,
    val temperature: Double?,
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
)

private fun ChatViewModel.idleGroupProvider(): LLMProvider? {
    val config = providerRepository.config.value
    val entry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        ?: config.modelEntries.firstOrNull()
    return entry?.let { providerForModelEntry(it) }
}

private fun ChatViewModel.groupMembers(
    entries: List<ModelEntry>,
    hostEntryId: String?,
): List<GroupMember> {
    val ids = MultiAgentSettings.retainLive(
        multiAgentSettings.selectedModelEntryIds.value,
        entries.map { it.id }.toSet(),
        multiAgentSettings.maxConcurrent.value,
    )
    return ids.mapNotNull { id ->
        if (id.isBlank() || id == hostEntryId) return@mapNotNull null
        val entry = entries.find { it.id == id } ?: return@mapNotNull null
        val provider = providerForModelEntry(entry) ?: return@mapNotNull null
        GroupMember(
            name = entry.model.displayName,
            vendor = GroupChat.vendorKey(
                entry.model.id,
                entry.model.displayName,
                snapshotFor(entry)?.providerTypeRaw,
            ),
            provider = provider,
            snapshot = snapshotFor(entry),
            maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 2048),
            temperature = entry.overrides.temperature,
            thinkingLevel = entry.effectiveMaxThinkingLevel,
        )
    }
}

private fun ChatViewModel.groupTools(provider: LLMProvider) = AgentTools.makeAgentTools(
    supportsImageInput = provider.model.hasImageInput,
    visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
        providerRepository, context,
    ),
    memoryEnabled = false,
    subAgentEnabled = false,
).filter { !SubAgentKind.blocks(SubAgentKind.PLAN, it.name) }

private suspend fun ChatViewModel.speakMembers(
    members: List<GroupMember>,
    analyzing: String,
    deferBubble: Boolean = false,
    promptFor: (GroupMember) -> String,
): List<GroupChat.Line> = supervisorScope {
    val gate = Mutex()
    members.map { member ->
        async {
            if (groupChatCloseRequested) return@async null
            val text = try {
                speakVisible(
                speaker = member.name,
                snapshot = member.snapshot,
                provider = member.provider,
                system = GroupChat.memberSystem(member.name),
                user = promptFor(member),
                tools = groupTools(member.provider),
                placeholder = analyzing,
                toolGate = gate,
                allowPass = true,
                thinkingLevel = member.thinkingLevel,
                vendor = member.vendor,
                deferBubble = deferBubble,
            )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                upsertGroupBubble(
                    UUID.randomUUID().toString(),
                    member.name,
                    context.getString(
                        com.openminis.app.R.string.group_chat_member_failed,
                        member.name,
                        e.message ?: e.javaClass.simpleName,
                    ),
                    analyzing = false,
                    vendor = member.vendor,
                )
                return@async null
            }
            text.takeIf { it.isNotBlank() }?.let { GroupChat.Line(member.name, it) }
        }
    }.awaitAll().filterNotNull()
}

private suspend fun ChatViewModel.speakVisible(
    speaker: String,
    snapshot: ModelAttributionSnapshot?,
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    placeholder: String,
    toolGate: Mutex? = null,
    allowPass: Boolean = false,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel =
        com.openminis.app.data.model.ThinkingLevel.OFF,
    vendor: String = GroupChat.VENDOR_UNKNOWN,
    deferBubble: Boolean = false,
): String {
    if (groupChatCloseRequested && allowPass) return ""
    val id = UUID.randomUUID().toString()
    if (!deferBubble) upsertGroupBubble(id, speaker, placeholder, analyzing = true, vendor = vendor)
    val text = try {
        speakModel(provider, system, user, tools, toolGate, thinkingLevel, stopWhenClosing = allowPass) { block ->
            showGroupStatus(id, speaker, vendor, block)
        }
    } catch (e: CancellationException) {
        removeGroupBubble(id)
        throw e
    }
    if (text.isBlank() || (allowPass && GroupChat.isPass(text))) {
        if (allowPass) {
            upsertGroupBubble(
                id,
                speaker,
                context.getString(com.openminis.app.R.string.group_chat_passed),
                analyzing = false,
                vendor = vendor,
            )
        } else if (!deferBubble) {
            removeGroupBubble(id)
        }
        return ""
    }
    if (deferBubble) upsertGroupBubble(id, speaker, text, analyzing = false, vendor = vendor)
    val dbId = persistGroupUtterance(speaker, text, snapshot, vendor)
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map { message ->
            if (message.id == id) message.copy(
                content = text,
                toolBlocks = emptyList(),
                speakerName = speaker,
                speakerVendor = vendor,
                isStreaming = false,
                isAwaitingModelResponse = false,
                sourceDbIds = listOfNotNull(dbId),
            ) else message
        }
    }
    return text
}

private suspend fun ChatViewModel.speakModel(
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    toolGate: Mutex?,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
    stopWhenClosing: Boolean,
    onStatus: suspend (AssistantBlock) -> Unit,
): String {
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = user))
    val report = StringBuilder()
    repeat(3) {
        if (stopWhenClosing && groupChatCloseRequested) return report.toString().trim()
        val textSb = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<Triple<String, String, JSONObject>>()
        var lastStatusAt = 0L
        provider.streamMessage(
            messages = history,
            systemPrompt = system,
            maxTokens = 1024,
            temperature = null,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Started -> onStatus(
                    AssistantBlock(
                        id = "group-status",
                        kind = "info",
                        content = context.getString(com.openminis.app.R.string.group_chat_thinking),
                    ),
                )
                is LLMStreamChunk.ThinkingDelta -> {
                    thinking.append(chunk.text)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastStatusAt >= 120L) {
                        lastStatusAt = now
                        onStatus(thinkingStatus(thinking))
                    }
                }
                is LLMStreamChunk.Text -> textSb.append(chunk.text)
                is LLMStreamChunk.ToolUseStart -> onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, ""))
                is LLMStreamChunk.ToolCallComplete -> {
                    calls += Triple(chunk.id, chunk.name, chunk.args)
                    onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, chunk.args.toString()))
                }
                else -> Unit
            }
        }
        if (thinking.isNotEmpty()) onStatus(thinkingStatus(thinking))
        val text = textSb.toString().trim()
        if (calls.isEmpty() || tools.isEmpty()) {
            if (text.isNotEmpty()) {
                if (report.isNotEmpty()) report.append("\n\n")
                report.append(text)
            }
            return report.toString().trim()
        }
        val assistantParts = mutableListOf<AgentContentPart>()
        if (text.isNotEmpty()) assistantParts += AgentContentPart.Text(text)
        calls.forEach { (callId, name, args) ->
            assistantParts += AgentContentPart.ToolUse(callId, name, input = args)
        }
        history += LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = assistantParts)
        val results = mutableListOf<AgentContentPart>()
        for ((callId, name, args) in calls) {
            onStatus(toolStatus(callId, name, ToolBlockStatus.RUNNING, args.toString()))
            val denied = DiscussionGraph.denyExecution(name, args.toString())
            val result = if (denied != null) {
                ToolExecutionResult(denied, false)
            } else if (toolGate != null) {
                toolGate.withLock { executeTool(name, args.toString(), "", mutableListOf(), "", "") }
            } else {
                executeTool(name, args.toString(), "", mutableListOf(), "", "")
            }
            results += AgentContentPart.ToolResult(callId, name, result.output, isError = !result.success)
        }
        history += LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = results)
    }
    return report.toString().trim()
}

private fun thinkingStatus(thinking: StringBuilder) = AssistantBlock(
    id = "group-thinking",
    kind = "thinking",
    content = thinking.toString().takeLast(600),
    toolTitle = "Thinking",
)

private fun toolStatus(
    id: String,
    name: String,
    status: ToolBlockStatus,
    args: String,
) = AssistantBlock(
    id = id.ifBlank { "group-tool" },
    kind = "tool_use",
    toolName = name,
    toolTitle = name,
    toolArgs = args.take(240),
    toolStatus = status,
    content = name,
)

private suspend fun ChatViewModel.showGroupStatus(
    id: String,
    speaker: String,
    vendor: String,
    block: AssistantBlock,
) {
    withContext(Dispatchers.Main) {
        val current = _messages.value
        val status = ChatMessage(
            id = id,
            role = "assistant",
            content = "",
            speakerName = speaker,
            speakerVendor = vendor,
            isStreaming = true,
            isAwaitingModelResponse = false,
            toolBlocks = listOf(block),
        )
        val next = if (current.any { it.id == id }) {
            current.map { if (it.id == id) status else it }
        } else {
            current + status
        }
        _messages.value = trimLoadedWindow(next)
    }
}

private fun ChatViewModel.closeRecord(): List<GroupChat.Line> {
    val windowed = groupTranscript()
    if (windowed.isNotEmpty()) return windowed
    val lastTopic = _messages.value.indexOfLast { message ->
        message.role == "user" && !message.isQueued && !GroupChat.isCloseRequest(message.content)
    }
    val slice = if (lastTopic >= 0) _messages.value.drop(lastTopic + 1) else _messages.value
    return slice.mapNotNull { message ->
        if (message.role != "assistant") return@mapNotNull null
        val speaker = message.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val text = message.content.trim().ifBlank {
            message.toolBlocks
                .filter { it.kind == "text" || it.kind == "thinking" }
                .joinToString("\n") { it.content }
                .trim()
        }
        val placeholder = context.getString(com.openminis.app.R.string.group_chat_analyzing)
        if (text.isBlank() || text == placeholder || message.isAwaitingModelResponse) null
        else GroupChat.Line(speaker, text)
    }
}

private suspend fun ChatViewModel.upsertGroupBubble(
    id: String,
    speaker: String,
    text: String,
    analyzing: Boolean,
    vendor: String = GroupChat.VENDOR_UNKNOWN,
) {
    withContext(Dispatchers.Main) {
        val current = _messages.value
        val next = if (current.any { it.id == id }) {
            current.map {
                if (it.id == id) it.copy(
                    content = text,
                    toolBlocks = if (analyzing) it.toolBlocks else emptyList(),
                    speakerName = speaker,
                    speakerVendor = vendor,
                    isStreaming = analyzing,
                    isAwaitingModelResponse = analyzing,
                ) else it
            }
        } else {
            current + ChatMessage(
                id = id,
                role = "assistant",
                content = text,
                speakerName = speaker,
                speakerVendor = vendor,
                isStreaming = analyzing,
                isAwaitingModelResponse = analyzing,
            )
        }
        _messages.value = trimLoadedWindow(next)
    }
}

private suspend fun ChatViewModel.removeGroupBubble(id: String) {
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }
}

private suspend fun ChatViewModel.publishGroupNotice(text: String) {
    val id = UUID.randomUUID().toString()
    val speaker = context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    upsertGroupBubble(id, speaker, text, analyzing = false)
    val dbId = persistGroupUtterance(speaker, text, snapshotFor(null))
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(isStreaming = false, sourceDbIds = listOfNotNull(dbId)) else it
        }
    }
}

private suspend fun ChatViewModel.persistGroupUtterance(
    speaker: String,
    text: String,
    snapshot: ModelAttributionSnapshot?,
    vendor: String = GroupChat.vendorKey(snapshot?.modelId, snapshot?.displayName, snapshot?.providerTypeRaw),
): String? = withContext(Dispatchers.IO) {
    val parts = JSONArray()
        .put(
            JSONObject()
                .put("type", GroupChat.SPEAKER_PART)
                .put("value", speaker)
                .put("vendor", vendor),
        )
        .put(JSONObject().put("type", "text").put("value", text))
        .toString()
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId },
        "assistant",
        parts,
        modelSnapshot = snapshot,
    ).id
}

private fun ChatViewModel.snapshotFor(entry: ModelEntry?): ModelAttributionSnapshot? {
    val model = entry?.model ?: currentModel ?: return null
    val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        ?: _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }?.let { providerRepository.instance(it.providerInstanceId) }
    return ModelAttributionSnapshot(
        modelId = model.id,
        displayName = model.displayName,
        providerTypeRaw = instance?.providerType?.name ?: "",
        providerInstanceId = entry?.providerInstanceId,
    )
}

private fun ChatViewModel.groupTranscript(): List<GroupChat.Line> {
    val messages = _messages.value
    val start = groupChatClosedAfterId?.let { marker ->
        val index = messages.indexOfLast { it.id == marker }
        if (index >= 0) index + 1 else 0
    } ?: 0
    return messages.drop(start).mapNotNull { message ->
        val speaker = message.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val text = message.content.trim()
        if (text.isBlank() || message.isAwaitingModelResponse) null
        else GroupChat.Line(speaker, text)
    }
}

private fun ChatViewModel.recentContext(): String =
    _messages.value.takeLast(8).joinToString("\n") { message ->
        val who = message.speakerName ?: message.role
        "$who: ${message.content.take(400)}"
    }
