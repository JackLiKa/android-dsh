package com.example.adsh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

fun textDelta(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

class DeepSeekClient : ModelAdapter {
    override val models = listOf(BuiltInModels.DEEPSEEK_FLASH, BuiltInModels.DEEPSEEK_PRO)

    override fun stream(model: String, apiKey: String, messages: List<SessionEventRow>): Flow<String> = flow {
        require(model in models) { "未配置的 DeepSeek 模型：$model" }
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            val history = JSONArray()
            deriveMessages(messages).forEach {
                history.put(JSONObject().put("role", it.role).put("content", it.content))
            }
            val body = JSONObject().put("model", model).put("stream", true).put("messages", history)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(512) }
                error("DeepSeek HTTP ${connection.responseCode}: $detail")
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    if (!line.startsWith("data: ")) continue
                    val payload = line.removePrefix("data: ")
                    if (payload == "[DONE]") break
                    val frame = JSONObject(payload)
                    if (frame.has("error")) error("DeepSeek 流式响应报告失败")
                    val choices = frame.optJSONArray("choices")
                        ?: error("DeepSeek 流式响应缺少 choices")
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue
                    if ((delta.optJSONArray("tool_calls")?.length() ?: 0) > 0 ||
                        choice.optString("finish_reason") == "tool_calls") {
                        error("基础对话未接入工具调用；请改用完整 dsh 对话")
                    }
                    textDelta(delta.opt("content"))?.let { emit(it) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
