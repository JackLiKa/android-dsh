package com.example.adsh

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.adsh.engine.SessionMeter
import com.example.adsh.engine.TraceSnapshot
import com.example.adsh.engine.TraceStep
import com.example.adsh.engine.TraceTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** dsh-style trajectory: every turn, model step, token count and tool call. */
@Composable
fun TraceScreen(
    trace: TraceSnapshot,
    sessionTitle: String?,
    sessionId: String?,
    running: Boolean,
    logPath: String,
    onBack: () -> Unit,
    onReload: suspend () -> List<String>,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf(0) }
    LaunchedEffect(sessionId, trace.turns.size, running) {
        archived = withContext(Dispatchers.IO) { onReload().size }
    }
    val report = remember(trace, sessionTitle, sessionId) {
        buildTraceText(trace, sessionTitle, sessionId)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("轨迹", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (running) "本轮生成中 · 实时更新" else "已归档 $archived 轮 · $logPath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, "轨迹已复制", Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(Icons.Default.Share, contentDescription = "复制轨迹")
            }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "分享轨迹"))
            }) { Text("分享") }
        }

        SummaryCard(trace)

        if (trace.turns.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有轨迹", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "完成一轮对话后，这里会按轮次记录模型、Token、首字延迟、生成速度与工具耗时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trace.turns.asReversed(), key = { it.turn }) { turn ->
                    TurnCard(turn)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(trace: TraceSnapshot) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                trace.model ?: "尚无模型数据",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("轮次", trace.turns.size.toString(), Modifier.weight(1f))
                Metric("步骤", trace.stepCount.toString(), Modifier.weight(1f))
                Metric("工具", trace.turns.sumOf { it.toolCount }.toString(), Modifier.weight(1f))
                Metric("输出速度", trace.outputTps?.let { "${oneDecimal(it)} tok/s" } ?: "—", Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("输入", formatTokens(trace.inputTokens), Modifier.weight(1f))
                Metric("输出", formatTokens(trace.outputTokens), Modifier.weight(1f))
                Metric("缓存读取", formatTokens(trace.cacheReadTokens), Modifier.weight(1f))
                Metric("总计", formatTokens(trace.totalTokens), Modifier.weight(1.2f))
            }
            trace.meter?.let { meter ->
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Metric(
                        "缓存命中",
                        meter.cacheHitPercent?.let { "$it%" } ?: "—",
                        Modifier.weight(1f),
                    )
                    Metric("上下文", contextLabel(meter), Modifier.weight(2.2f))
                    Metric(
                        "会话 Token",
                        formatTokens(meter.sessionTokens),
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TurnCard(turn: TraceTurn) {
    var expanded by rememberSaveable(turn.turn, turn.status) { mutableStateOf(turn.status == "进行中") }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxDot(if (turn.status == "完成") MaterialTheme.colorScheme.primary
                else if (turn.status == "失败") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("第 ${turn.turn} 轮", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    turn.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        turn.durationMs?.let { formatDuration(it) },
                        "${formatTokens(turn.tokens)} tokens",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            turn.userText?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                turn.steps.forEach { step ->
                    Spacer(Modifier.height(4.dp))
                    StepRow(step)
                }
                turn.assistantText?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    listOfNotNull(
                        "${turn.steps.size} 步",
                        turn.steps.sumOf { it.tools.size }.takeIf { it > 0 }?.let { "$it 个工具" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: TraceStep) {
    var detail by rememberSaveable(step.turn, step.step) { mutableStateOf(false) }
    Surface(
        onClick = { detail = !detail },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "步骤 ${step.step}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    step.model ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatTokens(step.totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    step.durationMs?.let { "耗时 ${formatDuration(it)}" },
                    step.ttftMs?.let { "首字 ${formatDuration(it)}" },
                    step.outputTps?.let { "${oneDecimal(it)} tok/s" },
                    step.outputTokens.takeIf { it > 0 }?.let { "输出 $it" },
                    step.inputTokens.takeIf { it > 0 }?.let { "输入 $it" },
                    step.cacheReadTokens.takeIf { it > 0 }?.let { "缓存 $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step.tools.forEach { tool ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoxDot(if (tool.failed == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary, 6)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tool.durationMs?.let { formatDuration(it) } ?: "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tool.failed == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail) {
                step.reasoningText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("思考", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
                step.contentText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("输出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BoxDot(color: androidx.compose.ui.graphics.Color, size: Int = 8) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(size.dp).clip(CircleShape).background(color),
    )
}

private fun buildTraceText(trace: TraceSnapshot, title: String?, sessionId: String?): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return buildString {
        appendLine("# dsh 轨迹")
        appendLine("会话：${title ?: sessionId ?: "未命名"}")
        appendLine("模型：${trace.model ?: "—"}")
        appendLine(
            "汇总：${trace.turns.size} 轮 · ${trace.stepCount} 步 · " +
                "输入 ${trace.inputTokens} · 输出 ${trace.outputTokens} · " +
                "缓存 ${trace.cacheReadTokens} · 总计 ${trace.totalTokens}",
        )
        trace.meter?.let { meter ->
            appendLine(
                "缓存命中 ${meter.cacheHitPercent?.let { "$it%" } ?: "—"} · " +
                    "上下文 ${contextLabel(meter)} · 会话 Token ${meter.sessionTokens}",
            )
        }
        trace.turns.forEach { turn ->
            appendLine()
            appendLine("## 第 ${turn.turn} 轮 · ${turn.status} · ${turn.durationMs?.let(::formatDuration) ?: "—"}")
            turn.startTime?.let { appendLine("开始：${format.format(Date(it))}") }
            turn.userText?.let { appendLine("用户：${it.replace('\n', ' ')}") }
            turn.steps.forEach { step ->
                appendLine(
                    "步骤 ${step.step}：${step.model ?: "—"} · ${step.totalTokens} tokens · " +
                        "耗时 ${step.durationMs?.let(::formatDuration) ?: "—"} · " +
                        "首字 ${step.ttftMs?.let(::formatDuration) ?: "—"} · " +
                        "速度 ${step.outputTps?.let { "${oneDecimal(it)} tok/s" } ?: "—"}",
                )
                step.tools.forEach { tool ->
                    appendLine(
                        "  · ${tool.name} ${tool.durationMs?.let(::formatDuration) ?: "进行中"}" +
                            if (tool.failed == true) " 失败" else "",
                    )
                }
            }
            turn.assistantText?.let { appendLine("回复：${it.replace('\n', ' ')}") }
        }
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1_000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun contextLabel(meter: SessionMeter): String {
    val used = meter.usedContextTokens ?: return "—"
    val percent = meter.contextPercent
    val window = meter.contextWindow
    return if (percent != null && window != null) {
        "$percent% · ${formatTokens(used)} / ${formatTokens(window)}"
    } else {
        formatTokens(used)
    }
}

private fun formatTokens(count: Int): String = when {
    count >= 1_000_000 -> compact(count / 1_000_000.0, "M")
    count >= 1_000 -> compact(count / 1_000.0, "k")
    else -> count.toString()
}

private fun compact(value: Double, unit: String): String {
    val text = String.format(Locale.US, "%.1f", value)
    return (if (text.endsWith(".0")) text.dropLast(2) else text) + unit
}
