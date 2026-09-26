package com.openminis.app.data.body

/**
 * Byte budget for a session preview. Row count is not a limit.
 */
object PreviewBudget {
    fun canTake(used: Long, rowBytes: Long, budget: Long): Boolean {
        if (rowBytes < 0 || budget < 0) return false
        if (rowBytes > budget) return false
        return used + rowBytes <= budget
    }

    fun fittingCount(rowBytes: Long, rows: Long, budget: Long): Long {
        if (rowBytes <= 0 || rows < 0 || budget < 0) return 0
        return minOf(rows, budget / rowBytes)
    }
}
