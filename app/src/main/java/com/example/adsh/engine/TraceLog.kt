package com.example.adsh.engine

import java.io.File
import kotlin.math.roundToInt

data class TraceTool(
    val callId: String,
    val name: String,
    val callTime: Long,
    val resultTime: Long? = null,
    val failed: Boolean? = null,
    val resultChars: Int = 0,
) {
    val durationMs: Long?
        get() = resultTime?.let { (it - callTime).coerceAtLeast(0) }
}

data class TraceStep(
    val turn: Int,
    val step: Int,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val provider: String? = null,
    val model: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val totalTokens: Int = 0,
    val firstChunkTime: Long? = null,
    val lastChunkTime: Long? = null,
    val reasoningChars: Int = 0,
    val contentChars: Int = 0,
    val reasoningText: String? = null,
    val contentText: String? = null,
    val tools: List<TraceTool> = emptyList(),
    val raw: String? = null,
) {
    val ttftMs: Long?
        get() = if (startTime != null && firstChunkTime != null) {
            (firstChunkTime - startTime).coerceAtLeast(0)
        } else {
            null
        }

    val durationMs: Long?
        get() = if (startTime != null && endTime != null) {
            (endTime - startTime).coerceAtLeast(0)
        } else {
            null
        }

    val generationMs: Long?
        get() = if (firstChunkTime != null && lastChunkTime != null) {
            (lastChunkTime - firstChunkTime).coerceAtLeast(0)
        } else {
            null
        }

    val outputTps: Double?
        get() {
            val ms = generationMs?.takeIf { it > 0 } ?: return null
            if (outputTokens <= 0) return null
            return outputTokens * 1000.0 / ms
        }
}

data class TraceTurn(
    val turn: Int,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val status: String = "进行中",
    val userText: String? = null,
    val assistantText: String? = null,
    val steps: List<TraceStep> = emptyList(),
) {
    val tokens: Int get() = steps.sumOf { it.totalTokens }
    val inputTokens: Int get() = steps.sumOf { it.inputTokens }
    val outputTokens: Int get() = steps.sumOf { it.outputTokens }
    val cacheReadTokens: Int get() = steps.sumOf { it.cacheReadTokens }
    val toolCount: Int get() = steps.sumOf { it.tools.size }

    val durationMs: Long?
        get() = if (startTime != null && endTime != null) {
            (endTime - startTime).coerceAtLeast(0)
        } else {
            null
        }
}

data class TraceSnapshot(
    val turns: List<TraceTurn> = emptyList(),
    val provider: String? = null,
    val model: String? = null,
) {
    val stepCount: Int get() = turns.sumOf { it.steps.size }
    val totalTokens: Int get() = turns.sumOf { it.tokens }
    val inputTokens: Int get() = turns.sumOf { it.inputTokens }
    val outputTokens: Int get() = turns.sumOf { it.outputTokens }
    val cacheReadTokens: Int get() = turns.sumOf { it.cacheReadTokens }

    /** Weighted by generation time, so long quiet steps do not skew the rate. */
    val outputTps: Double?
        get() {
            val generated = turns.flatMap { it.steps }.sumOf { it.generationMs ?: 0L }
            if (generated <= 0 || outputTokens <= 0) return null
            return outputTokens * 1000.0 / generated
        }
}

/** Builds per-turn trace metrics from the session event log the Host already streams. */
object TraceLog {
    private const val PREVIEW_CHARS = 800

    fun parse(events: List<Map<String, Any?>>): TraceSnapshot {
        val turnStarts = mutableMapOf<Int, Long>()
        val turnEnds = mutableMapOf<Int, Pair<Long, String>>()
        val steps = mutableMapOf<Pair<Int, Int>, StepAcc>()
        val tools = mutableMapOf<String, Pair<TraceStepKey, MutableTool>>()
        val userTexts = mutableMapOf<Int, String>()
        val assistantTexts = mutableMapOf<Int, String>()
        var provider: String? = null
        var model: String? = null

        for (event in events) {
            val type = event["type"].dshString() ?: continue
            val time = event["time"].dshLong()
            val data = event["data"].asDshMapOrEmpty()
            when (type) {
                "turn/start" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    turnStarts[turn] = time ?: System.currentTimeMillis()
                }
                "turn/end" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    val kind = data["reason"].asDshMapOrEmpty()["kind"].dshString().orEmpty()
                    turnEnds[turn] = (time ?: System.currentTimeMillis()) to statusOf(kind)
                }
                "step/start" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    val step = data["step"].dshInt() ?: continue
                    acc(steps, turn, step).let { if (it.startTime == null) it.startTime = time }
                }
                "step/end" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    val step = data["step"].dshInt() ?: continue
                    acc(steps, turn, step).endTime = time
                }
                "user/message" -> {
                    if (data["source"].asDshMapOrEmpty()["kind"].dshString() != "user") continue
                    val text = data["content"].asDshList().orEmpty()
                        .mapNotNull { part -> part.asDshMapOrEmpty()["text"].dshString() }
                        .joinToString("\n")
                        .trim()
                    if (text.isBlank()) continue
                    val turn = turnStarts.keys.maxOrNull() ?: continue
                    userTexts[turn] = text.take(PREVIEW_CHARS)
                }
                "assistant/message" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    val step = data["step"].dshInt() ?: continue
                    val target = acc(steps, turn, step)
                    val message = data["message"].asDshMapOrEmpty()
                    val source = message["source"].asDshMapOrEmpty()
                    if (source["kind"].dshString() == "model") {
                        source["provider"].dshString()?.let { provider = it }
                        source["model"].dshString()?.let { model = it }
                    }
                    target.provider = source["provider"].dshString() ?: target.provider
                    target.model = source["model"].dshString() ?: target.model

                    val usage = data["usage"].asDshMapOrEmpty()
                    target.inputTokens = usage["inputTokens"].dshInt() ?: 0
                    target.outputTokens = usage["outputTokens"].dshInt() ?: 0
                    target.cacheReadTokens = usage["cacheReadTokens"].dshInt() ?: 0
                    target.cacheWriteTokens = usage["cacheWriteTokens"].dshInt() ?: 0
                    target.totalTokens = usage["totalTokens"].dshInt()
                        ?: (target.inputTokens + target.outputTokens + target.cacheReadTokens)

                    val blocks = message["content"].asDshList().orEmpty()
                    target.reasoningChars = blocks.sumOf { block ->
                        val map = block.asDshMapOrEmpty()
                        if (map["type"].dshString() == "reasoning") {
                            map["text"].dshString().orEmpty().length
                        } else {
                            0
                        }
                    }
                    target.contentChars = blocks.sumOf { block ->
                        val map = block.asDshMapOrEmpty()
                        if (map["type"].dshString() == "text") {
                            map["text"].dshString().orEmpty().length
                        } else {
                            0
                        }
                    }
                    target.reasoningText = blocks
                        .firstOrNull { it.asDshMapOrEmpty()["type"].dshString() == "reasoning" }
                        ?.asDshMapOrEmpty()?.get("text")?.dshString()?.take(PREVIEW_CHARS)
                    val answer = blocks
                        .filter { it.asDshMapOrEmpty()["type"].dshString() == "text" }
                        .mapNotNull { it.asDshMapOrEmpty()["text"].dshString() }
                        .joinToString("\n")
                    if (answer.isNotBlank()) {
                        target.contentText = answer.take(PREVIEW_CHARS)
                        assistantTexts[turn] = answer.take(PREVIEW_CHARS)
                    }
                    val stream = data["stream"].asDshList().orEmpty()
                    streamTimes(stream).forEach { chunkTime ->
                        if (target.firstChunkTime == null || chunkTime < target.firstChunkTime!!) {
                            target.firstChunkTime = chunkTime
                        }
                        if (target.lastChunkTime == null || chunkTime > target.lastChunkTime!!) {
                            target.lastChunkTime = chunkTime
                        }
                    }
                    target.raw = DshJson.stringify(event).take(8_000)
                }
                "tool/call" -> {
                    val turn = data["turn"].dshInt() ?: continue
                    val step = data["step"].dshInt() ?: continue
                    val callId = data["callId"].dshString() ?: continue
                    val tool = MutableTool(
                        callId = callId,
                        name = data["name"].dshString() ?: "tool",
                        callTime = time ?: System.currentTimeMillis(),
                    )
                    val key = TraceStepKey(turn, step)
                    tools[callId] = key to tool
                    acc(steps, turn, step).tools += tool
                }
                "tool/result" -> {
                    val message = data["message"].asDshMapOrEmpty()
                    val callId = message["toolCallId"].dshString()
                        ?: message["source"].asDshMapOrEmpty()["callId"].dshString()
                        ?: continue
                    val tool = tools[callId]?.second ?: continue
                    tool.resultTime = time
                    tool.failed = message["isError"].dshBool()
                    tool.resultChars = message["content"].asDshList().orEmpty().sumOf { part ->
                        part.asDshMapOrEmpty()["text"].dshString().orEmpty().length
                    }
                }
            }
        }

        val turns = (turnStarts.keys + turnEnds.keys + steps.keys.map { it.first })
            .toSortedSet()
            .map { turn ->
                val turnSteps = steps.entries
                    .filter { it.key.first == turn }
                    .sortedBy { it.key.second }
                    .map { it.value.freeze() }
                val end = turnEnds[turn]
                val start = turnStarts[turn]
                    ?: turnSteps.mapNotNull { it.startTime }.minOrNull()
                val endTime = end?.first ?: turnSteps.mapNotNull { it.endTime }.maxOrNull()
                TraceTurn(
                    turn = turn,
                    startTime = start,
                    endTime = endTime,
                    status = end?.second ?: "进行中",
                    userText = userTexts[turn],
                    assistantText = assistantTexts[turn],
                    steps = turnSteps,
                )
            }
        return TraceSnapshot(turns = turns, provider = provider, model = model)
    }

    fun turnRecord(sessionId: String?, turn: TraceTurn): String = DshJson.stringify(
        linkedMapOf(
            "time" to (turn.endTime ?: turn.startTime ?: System.currentTimeMillis()),
            "sessionId" to sessionId,
            "turn" to turn.turn,
            "status" to turn.status,
            "startedAt" to turn.startTime,
            "endedAt" to turn.endTime,
            "durationMs" to turn.durationMs,
            "user" to turn.userText,
            "assistant" to turn.assistantText,
            "tokens" to linkedMapOf(
                "input" to turn.inputTokens,
                "output" to turn.outputTokens,
                "cacheRead" to turn.cacheReadTokens,
                "total" to turn.tokens,
            ),
            "steps" to turn.steps.map { step ->
                linkedMapOf(
                    "step" to step.step,
                    "provider" to step.provider,
                    "model" to step.model,
                    "startedAt" to step.startTime,
                    "durationMs" to step.durationMs,
                    "ttftMs" to step.ttftMs,
                    "generationMs" to step.generationMs,
                    "outputTps" to step.outputTps?.let { (it * 10).roundToInt() / 10.0 },
                    "tokens" to linkedMapOf(
                        "input" to step.inputTokens,
                        "output" to step.outputTokens,
                        "cacheRead" to step.cacheReadTokens,
                        "cacheWrite" to step.cacheWriteTokens,
                        "total" to step.totalTokens,
                    ),
                    "reasoningChars" to step.reasoningChars,
                    "contentChars" to step.contentChars,
                    "reasoning" to step.reasoningText,
                    "content" to step.contentText,
                    "tools" to step.tools.map { tool ->
                        linkedMapOf(
                            "name" to tool.name,
                            "durationMs" to tool.durationMs,
                            "failed" to tool.failed,
                            "resultChars" to tool.resultChars,
                        )
                    },
                )
            },
        ),
    )

    private fun acc(steps: MutableMap<Pair<Int, Int>, StepAcc>, turn: Int, step: Int): StepAcc =
        steps.getOrPut(turn to step) { StepAcc(turn, step) }

    private fun statusOf(kind: String): String = when (kind) {
        "completed" -> "完成"
        "canceled", "cancelled", "aborted", "interrupted" -> "已取消"
        "failed", "error" -> "失败"
        "" -> "完成"
        else -> kind
    }

    private fun streamTimes(stream: List<Any?>): List<Long> {
        val times = mutableListOf<Long>()
        for (item in stream) {
            val map = item.asDshMap() ?: continue
            map["time"].dshLong()?.let { times += it }
            val start = map["time0"].dshLong() ?: continue
            times += start
            val delta = map["dt"].asDshList().orEmpty().sumOf { it.dshLong() ?: 0L }
            if (delta > 0) times += start + delta
        }
        return times
    }

    private data class TraceStepKey(val turn: Int, val step: Int)

    private class MutableTool(
        val callId: String,
        val name: String,
        val callTime: Long,
    ) {
        var resultTime: Long? = null
        var failed: Boolean? = null
        var resultChars: Int = 0

        fun freeze() = TraceTool(
            callId = callId,
            name = name,
            callTime = callTime,
            resultTime = resultTime,
            failed = failed,
            resultChars = resultChars,
        )
    }

    private class StepAcc(val turn: Int, val step: Int) {
        var startTime: Long? = null
        var endTime: Long? = null
        var provider: String? = null
        var model: String? = null
        var inputTokens: Int = 0
        var outputTokens: Int = 0
        var cacheReadTokens: Int = 0
        var cacheWriteTokens: Int = 0
        var totalTokens: Int = 0
        var firstChunkTime: Long? = null
        var lastChunkTime: Long? = null
        var reasoningChars: Int = 0
        var contentChars: Int = 0
        var reasoningText: String? = null
        var contentText: String? = null
        var raw: String? = null
        val tools = mutableListOf<MutableTool>()

        fun freeze() = TraceStep(
            turn = turn,
            step = step,
            startTime = startTime,
            endTime = endTime,
            provider = provider,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            totalTokens = totalTokens,
            firstChunkTime = firstChunkTime,
            lastChunkTime = lastChunkTime,
            reasoningChars = reasoningChars,
            contentChars = contentChars,
            reasoningText = reasoningText,
            contentText = contentText,
            tools = tools.map { it.freeze() },
            raw = raw,
        )
    }
}

/** Durable, bounded JSONL trace so metrics survive restarts like the engine log. */
class TraceLogStore(
    private val file: File,
    private val maxBytes: Long = 2_000_000L,
    private val maxLines: Int = 1_500,
) {
    fun append(record: String) {
        synchronized(this) {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) rotate()
            file.appendText(record.take(200_000) + "\n")
        }
    }

    fun read(): List<String> = synchronized(this) {
        if (!file.isFile) emptyList() else file.readLines().takeLast(200)
    }

    /** session#turn keys already on disk, so a restart can backfill without duplicates. */
    fun keys(): Set<String> = synchronized(this) {
        if (!file.isFile) return emptySet()
        val sessionPattern = Regex("\"sessionId\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null)")
        val turnPattern = Regex("\"turn\"\\s*:\\s*(\\d+)")
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val session = sessionPattern.find(line)?.groupValues?.get(1)
                    ?.trim('"')
                    .orEmpty()
                val turn = turnPattern.find(line)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                "$session#$turn"
            }.toSet()
        }
    }

    private fun rotate() {
        if (!file.isFile) return
        val keep = file.readLines().takeLast(maxLines)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
    }
}
