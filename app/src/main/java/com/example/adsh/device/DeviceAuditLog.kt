package com.example.adsh.device

import android.content.Context
import com.example.adsh.engine.DshJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceAuditEntry(
    val time: Long,
    val decision: DeviceDecision,
    val action: DeviceAction,
    val packageName: String?,
    val category: DeviceCategory?,
    val label: String?,
    val detail: String,
    val reason: String,
)

/**
 * Append-only JSONL history. There is no in-memory cap: the file is the
 * record, and only sensitive payloads (message bodies, typed text) are
 * excluded at the call site.
 */
class DeviceAuditLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE)
    private val lock = Any()

    private fun append(entry: DeviceAuditEntry) {
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    val backup = File(file.parentFile, "$FILE.1")
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendText(DshJson.stringify(entry.toMap()) + "\n")
            }
        }
    }

    fun recent(limit: Int): List<String> {
        synchronized(lock) {
            if (!file.isFile) return emptyList()
            return runCatching {
                file.readLines().takeLast(limit).mapNotNull(::format)
            }.getOrDefault(emptyList())
        }
    }

    fun clear() {
        synchronized(lock) {
            file.delete()
            File(file.parentFile, "$FILE.1").delete()
        }
    }

    private fun format(line: String): String? {
        val map = runCatching { DshJson.parse(line) as? Map<*, *> }.getOrNull() ?: return null
        val time = (map["time"] as? Number)?.toLong() ?: return null
        val decision = runCatching {
            DeviceDecision.valueOf(map["decision"]?.toString().orEmpty())
        }.getOrNull() ?: return null
        val action = runCatching {
            DeviceAction.valueOf(map["action"]?.toString().orEmpty())
        }.getOrNull() ?: return null
        val category = runCatching {
            DeviceCategory.valueOf(map["category"]?.toString().orEmpty())
        }.getOrNull()
        val packageName = map["package"]?.toString()?.takeIf { it.isNotBlank() }
        val detail = map["detail"]?.toString().orEmpty()
        val reason = map["reason"]?.toString().orEmpty()
        val decisionLabel = when (decision) {
            DeviceDecision.ALLOW -> "允许"
            DeviceDecision.ASK -> "询问"
            DeviceDecision.DENY -> "拒绝"
        }
        val target = when {
            category != null -> category.label
            packageName != null -> packageName
            else -> "系统"
        }
        val suffix = if (decision == DeviceDecision.DENY) reason else detail.ifBlank { reason }
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
        return "$clock $decisionLabel ${action.label} · $target${if (suffix.isBlank()) "" else " · $suffix"}"
    }

    private fun DeviceAuditEntry.toMap(): Map<String, Any?> = linkedMapOf(
        "time" to time,
        "decision" to decision.name,
        "action" to action.name,
        "package" to packageName,
        "category" to category?.name,
        "label" to label,
        "detail" to detail,
        "reason" to reason,
    )

    private fun String?.clean(max: Int): String? = this
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.take(max)
        ?.takeIf { it.isNotEmpty() }

    private fun normalize(entry: DeviceAuditEntry): DeviceAuditEntry = entry.copy(
        label = entry.label.clean(60),
        detail = entry.detail.clean(160).orEmpty(),
        reason = entry.reason.clean(160).orEmpty(),
    )

    fun record(entry: DeviceAuditEntry) {
        append(normalize(entry))
    }

    private companion object {
        const val FILE = "device-audit.jsonl"
        const val MAX_BYTES = 512 * 1024L
    }
}
