package com.example.adsh.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DshRemoteException(
    val code: String,
    override val message: String,
    val details: Any? = null,
) : Exception(message)

class DshTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DshProtocolException(message: String) : Exception(message)

/** One dsh Host (`dsh web`) reached through its launch URL. */
class DshWebClient private constructor(
    private val base: HttpUrl,
    private val token: String,
    private val http: OkHttpClient,
) {
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val EVENTS_STREAM = "\$events"

        /** Parses `http://127.0.0.1:3080/?token=…` printed by `dsh web`. */
        fun fromTokenUrl(tokenUrl: String): DshWebClient? {
            val url = tokenUrl.trim().toHttpUrlOrNull() ?: return null
            val token = url.queryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
            val base = url.newBuilder().query(null).fragment(null).build()
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            return DshWebClient(base, token, http)
        }
    }

    @Volatile
    private var cookie: String? = null

    private val mux = DshMux(http, base, ::cookie, ::relogin)
    val hostEvents = mux.hostEvents
    val connection: StateFlow<DshMux.State> = mux.state

    suspend fun connect() {
        if (cookie == null) login()
        mux.start()
        mux.awaitReady()
    }

    private suspend fun relogin() {
        cookie = null
        login()
    }

    fun close() = mux.close()

    /** Exchanges the launch token for the `dsh-auth-…` cookie. */
    suspend fun login() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(base.newBuilder().addQueryParameter("token", token).build())
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val setCookie = response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("dsh-auth-") }
            if (setCookie == null) {
                throw DshRemoteException(
                    "gateway/unauthorized",
                    "登录 dsh 失败（HTTP ${response.code}）：请重启引擎后再试",
                )
            }
            cookie = setCookie.substringBefore(';').trim()
        }
    }

    /** Unary `POST /api/<endpoint>` with the client-request envelope. */
    suspend fun call(endpoint: String, args: Map<String, Any?> = emptyMap()): Any? {
        if (cookie == null) login()
        return try {
            post(endpoint, args)
        } catch (error: Unauthorized) {
            login()
            post(endpoint, args)
        }
    }

    private class Unauthorized : Exception()

    private suspend fun post(endpoint: String, args: Map<String, Any?>): Any? =
        withContext(Dispatchers.IO) {
            val rpcId = UUID.randomUUID().toString()
            val payload = DshJson.stringify(
                mapOf(
                    "type" to "client-request",
                    "rpcId" to rpcId,
                    "method" to endpoint,
                    "payload" to mapOf("args" to args),
                ),
            )
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/$endpoint").build())
                .header("Cookie", cookie.orEmpty())
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (error: Exception) {
                throw DshTransportException("无法连接 dsh 引擎：${error.message}", error)
            }
            response.use {
                val text = it.body?.string().orEmpty()
                when {
                    it.code == 401 -> throw Unauthorized()
                    it.code == 403 -> throw DshRemoteException("gateway/forbidden", "引擎拒绝了请求（Host 校验未通过）")
                    it.code == 404 -> throw DshRemoteException("gateway/unknown-endpoint", "引擎没有该接口：$endpoint")
                    it.code == 415 -> throw DshRemoteException("gateway/media-type", "引擎不接受该请求格式")
                    !it.isSuccessful -> throw DshRemoteException("gateway/http-${it.code}", "引擎返回 HTTP ${it.code}")
                }
                val decoded = DshJson.parse(text).asDshMap()
                    ?: throw DshProtocolException("$endpoint：引擎响应不是 JSON 对象")
                if (decoded["type"] != "server-response") {
                    throw DshProtocolException("$endpoint：响应缺少 server-response 信封")
                }
                val echoed = decoded["rpcId"].dshString()
                val result = decoded["result"].asDshMap()
                    ?: throw DshProtocolException("$endpoint：响应缺少 result")
                if (result["ok"] == true) {
                    if (echoed != rpcId) {
                        throw DshProtocolException("$endpoint：rpcId 不一致")
                    }
                    result["value"]
                } else {
                    val error = result["error"].asDshMapOrEmpty()
                    throw DshRemoteException(
                        code = error["code"].dshString().orEmpty(),
                        message = error["message"].dshString().orEmpty().ifBlank { "引擎请求失败" },
                        details = error["details"],
                    )
                }
            }
        }

    suspend fun listSessions(): List<DshSessionSummary> {
        val value = call("session/list", mapOf("_request" to emptyMap<String, Any?>()))
        return value.asDshMapOrEmpty()["items"].asDshList().orEmpty()
            .mapNotNull { DshSessionSummary.from(it) }
            .filter { it.origin != "subagent" }
    }

    suspend fun createSession(cwd: String?, resumeSessionId: String? = null): String {
        val request = LinkedHashMap<String, Any?>()
        if (resumeSessionId != null) request["sessionId"] = resumeSessionId
        else if (!cwd.isNullOrBlank()) request["cwd"] = cwd
        val value = call("session/create", mapOf("request" to request))
        return value.asDshMapOrEmpty()["sessionId"].dshString()
            ?.takeIf { it.isNotBlank() }
            ?: throw DshProtocolException("session/create 未返回会话 ID")
    }

    suspend fun prompt(sessionId: String, text: String, requestId: String = UUID.randomUUID().toString()) {
        call(
            "session/prompt",
            mapOf(
                "request" to mapOf(
                    "requestId" to requestId,
                    "sessionId" to sessionId,
                    "mode" to "queue",
                    "content" to listOf(mapOf("type" to "text", "text" to text)),
                ),
            ),
        )
    }

    suspend fun cancel(sessionId: String) {
        call("session/cancel", mapOf("request" to mapOf("sessionId" to sessionId)))
    }

    /** Executes one slash-command line; the lifecycle renders as flow nodes. */
    suspend fun executeCommand(sessionId: String, line: String) {
        call(
            "commands/execute",
            mapOf(
                "agentId" to sessionId,
                "line" to line,
                "submittedAttachments" to emptyList<Any?>(),
            ),
        )
    }

    suspend fun modelCatalog(): DshModelCatalog = DshModelCatalog.from(call("session/modelCatalog"))

    suspend fun selectModel(sessionId: String, provider: String, model: String, effort: String?) {
        val request = LinkedHashMap<String, Any?>()
        request["sessionId"] = sessionId
        request["provider"] = provider
        request["model"] = model
        if (effort != null) request["reasoningEffort"] = effort
        call("session/selectModel", mapOf("request" to request))
    }

    suspend fun setApiKey(value: String, ref: String = "DEEPSEEK_API_KEY") {
        call("credentials/set", mapOf("ref" to ref, "value" to value))
    }

    suspend fun decideApproval(eventId: String, decision: String) {
        val outcome = if (decision == "next") {
            mapOf("kind" to "next")
        } else {
            mapOf("kind" to "result", "value" to decision)
        }
        respond(eventId, outcome)
    }

    suspend fun answerQuestions(eventId: String, answers: List<Map<String, Any?>>) {
        respond(
            eventId,
            mapOf("kind" to "result", "value" to mapOf("answers" to answers)),
        )
    }

    suspend fun rejectQuestions(eventId: String, message: String) {
        respond(
            eventId,
            mapOf(
                "kind" to "rejected",
                "error" to mapOf("name" to "UserQuestionError", "message" to message),
            ),
        )
    }

    private suspend fun respond(eventId: String, outcome: Map<String, Any?>) {
        val ready = mux.currentReady() ?: mux.awaitReady()
        try {
            call(
                "\$events/result",
                mapOf("clientId" to ready.clientId, "eventId" to eventId, "outcome" to outcome),
            )
        } catch (error: DshRemoteException) {
            if (!error.message.contains("active event stream")) throw error
            val current = mux.currentReady() ?: mux.awaitReady()
            call(
                "\$events/result",
                mapOf("clientId" to current.clientId, "eventId" to eventId, "outcome" to outcome),
            )
        }
    }

    /** Follows one session, reopening on disconnect until the collector cancels. */
    fun followSession(sessionId: String): Flow<Map<String, Any?>> = flow {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            mux.awaitReady()
            var opened = false
            try {
                mux.openStream(
                    "session/follow",
                    mapOf(
                        "request" to mapOf(
                            "address" to mapOf("kind" to "session", "sessionId" to sessionId),
                            "assistantStream" to true,
                            "maxMessages" to 500,
                            "turnWindow" to mapOf("minMessages" to 50, "minTurns" to 2),
                        ),
                    ),
                ).collect { item ->
                    if (!opened) {
                        if (item["type"].dshString() != "snapshot") {
                            throw DshProtocolException("session/follow 未以 snapshot 开始")
                        }
                        opened = true
                    }
                    emit(item)
                }
            } catch (error: DshTransportException) {
                opened = false
            } catch (error: DshProtocolException) {
                opened = false
            }
            attempt = if (opened) 0 else (attempt + 1).coerceAtMost(5)
            delay((RETRY_CAPS_MS[attempt] * (0.5 + Random.nextDouble() * 0.5)).toLong())
        }
    }

    class DshMux(
        private val http: OkHttpClient,
        private val base: HttpUrl,
        private val cookie: () -> String?,
        private val reauth: suspend () -> Unit = {},
    ) {
        sealed class State {
            data object Connecting : State()
            data class Ready(val generation: Int, val clientId: String, val home: String) : State()
            data class Stopped(val message: String) : State()
        }

        private sealed class Signal {
            data object Opened : Signal()
            data class Text(val text: String) : Signal()
            data class Lost(val message: String, val cause: Throwable?) : Signal()
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _state = MutableStateFlow<State>(State.Connecting)
        val state: StateFlow<State> = _state.asStateFlow()
        private val _hostEvents = MutableSharedFlow<DshHostEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val hostEvents = _hostEvents.asSharedFlow()

        private val streams = ConcurrentHashMap<String, Channel<Map<String, Any?>>>()
        private val pending = LinkedHashMap<String, String>()
        private var job: Job? = null

        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        private var generation = 0

        @Volatile
        private var ready: State.Ready? = null

        @Volatile
        private var eventsStreamId: String? = null

        @Volatile
        private var needsReauth = false

        fun currentReady(): State.Ready? = ready

        fun start() {
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }

        fun close() {
            job?.cancel()
            scope.cancel()
            socket?.cancel()
            _state.value = State.Stopped("引擎连接已关闭")
        }

        suspend fun awaitReady(timeoutMs: Long = 25_000): State.Ready =
            withTimeout(timeoutMs) { state.filterIsInstance<State.Ready>().first() }

        fun openStream(endpoint: String, args: Map<String, Any?>): Flow<Map<String, Any?>> = flow {
            val streamId = UUID.randomUUID().toString()
            val channel = Channel<Map<String, Any?>>(
                Channel.UNLIMITED,
            )
            streams[streamId] = channel
            val frame = DshJson.stringify(
                mapOf(
                    "type" to "open",
                    "streamId" to streamId,
                    "endpoint" to endpoint,
                    "payload" to mapOf("args" to args),
                ),
            )
            synchronized(pending) {
                pending[streamId] = frame
                socket?.let { ws -> if (ready != null) ws.send(frame) }
            }
            try {
                for (item in channel) emit(item)
            } finally {
                streams.remove(streamId)
                synchronized(pending) { pending.remove(streamId) }
                socket?.send(DshJson.stringify(mapOf("type" to "cancel", "streamId" to streamId)))
            }
        }

        private suspend fun runLoop() {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (needsReauth) {
                    needsReauth = false
                    try {
                        reauth()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        needsReauth = true
                    }
                }
                val established = connectOnce()
                attempt = if (established) 0 else (attempt + 1).coerceAtMost(RETRY_CAPS_MS.lastIndex)
                delay((RETRY_CAPS_MS[attempt] * (0.5 + Random.nextDouble() * 0.5)).toLong())
            }
        }

        private suspend fun connectOnce(): Boolean {
            _state.value = State.Connecting
            ready = null
            val signals = Channel<Signal>(
                Channel.UNLIMITED,
            )
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/remote.mux").build())
                .header("Cookie", cookie().orEmpty())
                .build()
            val ws = http.newWebSocket(request, Listener(signals))
            val streamId = UUID.randomUUID().toString()
            eventsStreamId = streamId
            socket = ws
            var established = false
            try {
                for (signal in signals) {
                    when (signal) {
                        Signal.Opened -> ws.send(
                            DshJson.stringify(
                                mapOf(
                                    "type" to "open",
                                    "streamId" to streamId,
                                    "endpoint" to EVENTS_STREAM,
                                    "payload" to mapOf("args" to emptyMap<String, Any?>()),
                                ),
                            ),
                        )
                        is Signal.Text -> if (handleFrame(ws, signal.text)) established = true
                        is Signal.Lost -> break
                    }
                }
            } finally {
                socket = null
                ready = null
                signals.close()
                ws.cancel()
                failStreams()
            }
            return established
        }

        private fun handleFrame(ws: WebSocket, text: String): Boolean {
            val frame = try {
                DshJson.parse(text).asDshMap()
            } catch (error: Exception) {
                null
            } ?: return false
            val streamId = frame["streamId"].dshString().orEmpty()
            if (streamId == eventsStreamId) {
                when (frame["type"].dshString()) {
                    "item" -> {
                        val value = frame["value"].asDshMap() ?: return false
                        val current = ready
                        if (current == null) {
                            if (value["type"].dshString() != "ready") return false
                            val readyState = State.Ready(
                                generation = ++generation,
                                clientId = value["clientId"].dshString().orEmpty(),
                                home = value["host"].asDshMapOrEmpty()["home"].dshString().orEmpty(),
                            )
                            ready = readyState
                            _state.value = readyState
                            flushPending(ws)
                            _hostEvents.tryEmit(DshHostEvent.Ready(readyState.clientId, readyState.home))
                            return true
                        }
                        DshHostEvent.from(value)?.let { _hostEvents.tryEmit(it) }
                    }
                    "end", "error" -> {
                        ready = null
                        ws.close(4003, "events closed")
                    }
                }
                return false
            }
            val sink = streams[streamId] ?: return false
            when (frame["type"].dshString()) {
                "item" -> sink.trySend(frame["value"].asDshMapOrEmpty())
                "end" -> {
                    streams.remove(streamId)
                    sink.close()
                }
                "error" -> {
                    streams.remove(streamId)
                    val error = frame["error"].asDshMapOrEmpty()
                    sink.close(
                        DshRemoteException(
                            error["code"].dshString().orEmpty(),
                            error["message"].dshString().orEmpty().ifBlank { "引擎流失败" },
                            error["details"],
                        ),
                    )
                }
            }
            return false
        }

        private fun flushPending(ws: WebSocket) {
            synchronized(pending) {
                pending.values.forEach { ws.send(it) }
                pending.clear()
            }
        }

        private fun failStreams() {
            val lost = DshTransportException("与 dsh 引擎的连接已断开")
            streams.values.forEach { it.close(lost) }
            streams.clear()
            synchronized(pending) { pending.clear() }
        }

        private inner class Listener(private val signals: Channel<Signal>) :
            WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                signals.trySend(Signal.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                signals.trySend(Signal.Text(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                signals.trySend(Signal.Lost("连接已关闭（$code）", null))
                signals.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) needsReauth = true
                signals.trySend(Signal.Lost(t.message ?: "WebSocket 连接失败", t))
                signals.close()
            }
        }
    }

}

private val RETRY_CAPS_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000, 10_000)
