package com.example.adsh.engine

import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Provider-reported usage plus context occupancy for one session. */
data class SessionMeter(
    val cacheReadTokens: Int = 0,
    val uncachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val outputTokens: Int = 0,
    val pressureTokens: Int? = null,
    val projectedTokens: Int? = null,
    val contextWindow: Int? = null,
) {
    val promptTokens: Int
        get() = (uncachedInputTokens + cacheReadTokens + cacheWriteTokens).coerceAtLeast(0)

    val sessionTokens: Int
        get() = promptTokens + outputTokens.coerceAtLeast(0)

    /** Metered cache hit, null when the provider reported no billed input. */
    val cacheHitPercent: String?
        get() {
            val prompt = promptTokens
            if (prompt <= 0) return null
            val cached = cacheReadTokens.coerceAtLeast(0)
            if (cached >= prompt) return "100"
            val tenths = (cached * 1000.0 / prompt).roundToInt().coerceAtMost(999)
            return if (tenths % 10 == 0) {
                (tenths / 10).toString()
            } else {
                String.format(java.util.Locale.US, "%.1f", tenths / 10.0)
            }
        }

    val usedContextTokens: Int?
        get() = projectedTokens ?: pressureTokens

    val contextPercent: Int?
        get() {
            val window = contextWindow?.takeIf { it > 0 } ?: return null
            val used = usedContextTokens ?: return null
            return min(100, (used * 100.0 / window).roundToInt())
        }
}

/**
 * Reads the Host's session projection cache. The Cache is the same source the
 * Web trajectory reads, so both surfaces show identical numbers.
 */
class SessionMeterStore(home: File) {
    private val dir = File(home, "storages/session_projcache/sessions")

    fun read(sessionId: String?): SessionMeter? {
        if (sessionId.isNullOrBlank()) return null
        val name = if (sessionId.startsWith("session-")) "$sessionId.json" else "session-$sessionId.json"
        val file = File(dir, name)
        if (!file.isFile) return null
        val rows = runCatching {
            DshJson.parse(file.readText())
                .asDshMapOrEmpty()["record"]
                .asDshMapOrEmpty()["rows"]
                .asDshMapOrEmpty()
        }.getOrNull() ?: return null
        val totals = rows["tokenUsage"]
            .asDshMapOrEmpty()["val"]
            .asDshMapOrEmpty()["totals"]
            .asDshMapOrEmpty()
        val pressure = rows["contextPressure"].asDshMapOrEmpty()["val"].asDshMapOrEmpty()
        if (totals.isEmpty() && pressure.isEmpty()) return null
        return SessionMeter(
            cacheReadTokens = totals["cacheReadTokens"].dshInt() ?: 0,
            uncachedInputTokens = totals["uncachedInputTokens"].dshInt() ?: 0,
            cacheWriteTokens = totals["cacheWriteTokens"].dshInt() ?: 0,
            outputTokens = totals["outputTokens"].dshInt() ?: 0,
            pressureTokens = pressure["pressureTokens"].dshInt(),
            projectedTokens = pressure["projectedTokens"].dshInt(),
            contextWindow = pressure["contextWindow"].dshInt()?.takeIf { it > 0 },
        )
    }
}
