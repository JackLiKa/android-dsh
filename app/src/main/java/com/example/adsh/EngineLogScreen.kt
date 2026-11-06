package com.example.adsh

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun EngineLogScreen(
    binder: DshEngineService.LocalBinder?,
    status: EngineStatus,
    onStart: () -> Unit,
    onSafeStart: () -> Unit,
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshCount by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    suspend fun reload() {
        lines = withContext(Dispatchers.IO) { binder?.logs().orEmpty() }
    }

    LaunchedEffect(binder, refreshCount) { reload() }
    LaunchedEffect(binder) {
        while (true) {
            delay(1_500)
            reload()
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val running = status.phase == EngineStatus.Phase.READY ||
        status.phase == EngineStatus.Phase.DEGRADED
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (binder == null) {
                    Text(
                        stringResource(R.string.logs_disconnected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStart,
                        enabled = binder != null &&
                            status.phase != EngineStatus.Phase.STARTING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (running) R.string.restart_engine else R.string.engine_start
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = onSafeStart,
                        enabled = binder != null &&
                            status.phase != EngineStatus.Phase.STARTING,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.safe_mode_restart)) }
                }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { refreshCount++ }) {
                        Text(stringResource(R.string.refresh_logs))
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                            Toast.makeText(
                                context,
                                context.getString(R.string.logs_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = lines.isNotEmpty(),
                    ) { Text(stringResource(R.string.copy_logs)) }
                    TextButton(
                        onClick = {
                            binder?.clearLogs()
                            lines = emptyList()
                        },
                        enabled = binder != null && lines.isNotEmpty(),
                    ) { Text(stringResource(R.string.clear_logs)) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.log_line_count, lines.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.engine_logs_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        if (lines.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.logs_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = logColor(line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun logColor(line: String): Color {
    val lower = line.lowercase()
    return when {
        lower.contains("错误") || lower.contains("失败") || lower.contains("异常") ||
            lower.contains("error") || lower.contains("fatal") ->
            MaterialTheme.colorScheme.error
        lower.contains("警告") || lower.contains("warn") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
}
