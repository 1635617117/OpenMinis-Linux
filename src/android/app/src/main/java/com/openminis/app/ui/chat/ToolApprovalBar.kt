package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.tools.ToolApprovalGate

/**
 * [T-tool-approval] Human-in-the-loop approval bar. When the gate is enabled
 * and a shell/su tool call suspends, this bottom bar shows the pending call
 * with an approve/deny pair; answering resumes the tool coroutine.
 */
@Composable
fun ToolApprovalBar() {
    val pending by ToolApprovalGate.pending.collectAsState()
    if (pending.isEmpty()) return
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            pending.forEach { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "工具待批准：${item.toolName}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = item.argsPreview.ifBlank { "(空参数)" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            maxLines = 3,
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { ToolApprovalGate.deny(item.id) },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text("拒绝")
                            }
                            TextButton(onClick = { ToolApprovalGate.approve(item.id) }) {
                                Text("允许执行")
                            }
                        }
                    }
                }
            }
        }
    }
}
