package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-scoped Goal API used by the slash UI, tool dispatcher and agent loop. */
class GoalManager(private val repository: ChatRepository) {
    private companion object {
        val locks = Array(64) { Mutex() }
    }

    private suspend fun <T> locked(sessionId: String, block: suspend () -> T): T =
        locks[(sessionId.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block() }

    suspend fun get(sessionId: String): SessionGoalEntity? = repository.goal(sessionId)

    suspend fun createByUser(sessionId: String, objective: String, tokenBudget: Long? = null): SessionGoalEntity =
        locked(sessionId) {
            check(repository.goal(sessionId) == null) { "A Goal already exists for this session" }
            GoalStateMachine.create(sessionId, objective, tokenBudget, System.currentTimeMillis()).also {
                repository.createGoal(it)
            }
        }

    suspend fun setPausedByUser(sessionId: String, paused: Boolean): SessionGoalEntity? = locked(sessionId) {
        val current = repository.goal(sessionId) ?: return@locked null
        val updated = if (paused) GoalStateMachine.pause(current, System.currentTimeMillis())
        else GoalStateMachine.resume(current, System.currentTimeMillis())
        repository.saveGoal(updated)
        updated
    }

    suspend fun updateBudgetByUser(sessionId: String, tokenBudget: Long?): SessionGoalEntity = locked(sessionId) {
        val current = checkNotNull(repository.goal(sessionId)) { "No Goal exists for this session" }
        GoalStateMachine.setBudget(current, tokenBudget, System.currentTimeMillis()).also { repository.saveGoal(it) }
    }

    suspend fun updateObjectiveByUser(sessionId: String, objective: String): SessionGoalEntity = locked(sessionId) {
        val normalized = objective.trim()
        require(normalized.isNotEmpty() && normalized.length <= GoalStateMachine.MAX_OBJECTIVE_LENGTH)
        val current = checkNotNull(repository.goal(sessionId)) { "No Goal exists for this session" }
        current.copy(objective = normalized, updatedAt = System.currentTimeMillis()).also { repository.saveGoal(it) }
    }

    /** Model tools cannot create goals or pause/resume them. */
    suspend fun updateByModel(sessionId: String, status: String, condition: String?): SessionGoalEntity = locked(sessionId) {
        val current = checkNotNull(repository.goal(sessionId)) { "No Goal exists for this session" }
        GoalStateMachine.modelUpdate(current, status, condition, System.currentTimeMillis()).also { repository.saveGoal(it) }
    }

    suspend fun recordUsage(sessionId: String, tokens: Long, elapsedMs: Long): SessionGoalEntity? = locked(sessionId) {
        val current = repository.goal(sessionId) ?: return@locked null
        GoalStateMachine.recordProgress(current, tokens.coerceAtLeast(0), elapsedMs.coerceAtLeast(0), System.currentTimeMillis())
            .also { repository.saveGoal(it) }
    }

    suspend fun mayContinue(sessionId: String, usefulActivity: Boolean): Boolean =
        repository.goal(sessionId)?.let { GoalStateMachine.canContinue(it, usefulActivity) } ?: false

    fun continuationPrompt(goal: SessionGoalEntity): String = goalContextPrompt(goal)

    fun render(goal: SessionGoalEntity?): String {
        if (goal == null) return "(no goal)"
        val spent = goal.tokensUsed
        val budget = goal.tokenBudget?.toString() ?: "unlimited"
        return buildString {
            appendLine("Goal [${goal.status}] — ${goal.objective}")
            append("tokens=$spent/$budget elapsed=${goal.elapsedMs}ms")
            goal.blockedCondition?.let { append(" blocked=$it (${goal.blockedCount}/${GoalStateMachine.BLOCKED_CONFIRMATIONS})") }
        }
    }
}
