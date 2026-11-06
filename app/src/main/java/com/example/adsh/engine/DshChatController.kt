package com.example.adsh.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DshChatState(
    val connection: String = "引擎未启动",
    val engineReady: Boolean = false,
    val connecting: Boolean = false,
    val sessionId: String? = null,
    val title: String? = null,
    val messages: List<DshChatMessage> = emptyList(),
    val streaming: DshStreaming? = null,
    val optimisticUser: String? = null,
    val running: Boolean = false,
    val loading: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val error: String? = null,
    val approvals: List<DshApproval> = emptyList(),
    val questions: List<DshQuestionRequest> = emptyList(),
) {
    val canSend: Boolean get() = engineReady && !loading

    fun selectedLabel(catalog: DshModelCatalog?): String {
        val match = catalog?.options?.firstOrNull {
            it.provider == provider && it.model == model
        }
        return match?.label ?: model ?: provider ?: "选择模型"
    }
}

/**
 * Owns the chat session on the dsh Host: one followed session at a time,
 * plus the global approval/question waterfalls.
 */
class DshChatController(
    private val cwdProvider: () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val persistSession: suspend (String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DshChatState())
    val state: StateFlow<DshChatState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<DshSessionSummary>>(emptyList())
    val sessions: StateFlow<List<DshSessionSummary>> = _sessions.asStateFlow()

    private val _catalog = MutableStateFlow<DshModelCatalog?>(null)
    val catalog: StateFlow<DshModelCatalog?> = _catalog.asStateFlow()

    private var client: DshWebClient? = null
    private var clientUrl: String? = null
    private var remoteMode = false
    private var seedSession: String? = null
    private var connectJob: Job? = null
    private var followJob: Job? = null
    private var hostJob: Job? = null
    private var pendingOpen: String? = null

    private fun sessionCwd(): String? = if (remoteMode) null else cwdProvider()

    private val events = ArrayList<Map<String, Any?>>()
    private var streaming: DshStreaming? = null
    private var optimistic: String? = null
    private var running = false
    private var currentSession: String? = null
    private var pendingModel: DshModelOption? = null
    private var pendingEffort: String? = null
    private var streamClear: Job? = null

    private val approvals = LinkedHashMap<String, DshApproval>()
    private val questions = LinkedHashMap<String, DshQuestionRequest>()

    /** Points the controller at the Host launch URL printed by `dsh web`. */
    fun attach(url: String?, seedSessionId: String?, remote: Boolean = false) {
        if (url == null) {
            detachClient("引擎未启动")
            return
        }
        if (url == clientUrl && client != null && remote == remoteMode) return
        detachClient("正在连接 dsh 引擎…")
        clientUrl = url
        remoteMode = remote
        seedSession = seedSessionId
        val created = DshWebClient.fromTokenUrl(url)
        if (created == null) {
            _state.update { it.copy(connection = "引擎地址无效", connecting = false, error = "无法解析引擎地址") }
            return
        }
        client = created
        _state.update { it.copy(connecting = true, engineReady = false, connection = "正在连接 dsh 引擎…", error = null) }
        hostJob = scope.launch {
            created.hostEvents.collect { event -> onHostEvent(event) }
        }
        connectJob = scope.launch {
            while (isActive && client === created) {
                try {
                    created.connect()
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _state.update {
                        it.copy(
                            connecting = true,
                            engineReady = false,
                            connection = "正在连接 dsh 引擎…",
                        )
                    }
                    delay(1_500)
                }
            }
            if (client !== created) return@launch
            bootstrap(created)
            created.connection.collect { state ->
                when (state) {
                    is DshWebClient.DshMux.State.Ready -> _state.update {
                        it.copy(engineReady = true, connecting = false, connection = "已连接 dsh 引擎")
                    }
                    is DshWebClient.DshMux.State.Connecting -> _state.update {
                        it.copy(engineReady = false, connecting = true, connection = "正在重新连接 dsh 引擎…")
                    }
                    is DshWebClient.DshMux.State.Stopped -> _state.update {
                        it.copy(engineReady = false, connecting = false, connection = state.message)
                    }
                }
            }
        }
    }

    fun retry() {
        val url = clientUrl ?: return
        clientUrl = null
        attach(url, seedSession)
    }

    fun close() {
        detachClient("引擎未启动")
        scope.cancel()
    }

    private fun detachClient(message: String) {
        followJob?.cancel()
        hostJob?.cancel()
        connectJob?.cancel()
        streamClear?.cancel()
        followJob = null
        hostJob = null
        connectJob = null
        streamClear = null
        client?.close()
        client = null
        clientUrl = null
        events.clear()
        approvals.clear()
        questions.clear()
        streaming = null
        optimistic = null
        running = false
        currentSession = null
        _state.update {
            it.copy(
                connection = message,
                engineReady = false,
                connecting = false,
                sessionId = null,
                messages = emptyList(),
                streaming = null,
                optimisticUser = null,
                running = false,
                loading = false,
                error = null,
            )
        }
    }

    private suspend fun bootstrap(created: DshWebClient) {
        _state.update { it.copy(engineReady = true, connecting = false, connection = "已连接 dsh 引擎") }
        runCatching { apiKeyProvider()?.takeIf { key -> key.isNotBlank() }?.let { created.setApiKey(it) } }
        refreshCatalog(created)
        refreshSessions(created)
        val target = pendingOpen ?: seedSession
        pendingOpen = null
        seedSession = null
        if (target != null) openSession(target) else publishAll()
    }

    fun openSession(sessionId: String) {
        val c = client
        if (c == null) {
            pendingOpen = sessionId
            return
        }
        followJob?.cancel()
        streamClear?.cancel()
        events.clear()
        streaming = null
        optimistic = null
        running = false
        currentSession = sessionId
        seedSession = null
        _state.update {
            it.copy(
                sessionId = sessionId,
                title = null,
                messages = emptyList(),
                streaming = null,
                optimisticUser = null,
                running = false,
                loading = true,
                error = null,
            )
        }
        persist(sessionId)
        followJob = scope.launch {
            try {
                c.followSession(sessionId).collect { item -> handleFollow(item) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DshRemoteException) {
                clearMissingSession(error)
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, error = "会话订阅失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    fun newSession() {
        followJob?.cancel()
        followJob = null
        streamClear?.cancel()
        events.clear()
        streaming = null
        optimistic = null
        running = false
        currentSession = null
        pendingModel = null
        pendingEffort = null
        seedSession = null
        _state.update {
            it.copy(
                sessionId = null,
                title = null,
                messages = emptyList(),
                streaming = null,
                optimisticUser = null,
                running = false,
                loading = false,
                provider = null,
                model = null,
                effort = null,
                error = null,
            )
        }
        persist(null)
        val c = client ?: return
        if (!_state.value.engineReady) return
        scope.launch {
            try {
                val id = c.createSession(sessionCwd())
                currentSession = id
                seedSession = null
                _state.update { it.copy(sessionId = id) }
                persist(id)
                startFollow(c, id)
                refreshSessions(c)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "新建会话失败：${error.message}") }
            }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val c = client
        if (c == null || !_state.value.engineReady) {
            _state.update { it.copy(error = "引擎尚未就绪，请稍候") }
            return
        }
        scope.launch {
            try {
                var id = currentSession
                if (id == null) {
                    id = c.createSession(sessionCwd())
                    currentSession = id
                    _state.update { it.copy(sessionId = id) }
                    persist(id)
                    applyPendingModel(c, id)
                    startFollow(c, id)
                } else if (pendingModel != null) {
                    applyPendingModel(c, id)
                }
                if (input.startsWith("/")) {
                    c.executeCommand(id, input)
                    refreshSessions(c)
                    return@launch
                }
                optimistic = input
                running = true
                publishStream()
                c.prompt(id, input)
                refreshSessions(c)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                running = false
                optimistic = null
                publishStream()
                _state.update { it.copy(error = "发送失败：${error.message}") }
            }
        }
    }

    fun cancel() {
        val id = currentSession ?: return
        val c = client ?: return
        scope.launch {
            try {
                c.cancel(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "停止失败：${error.message}") }
            }
        }
    }

    fun decideApproval(approval: DshApproval, decision: String) {
        approvals.remove(approval.eventId)
        publishAll()
        val c = client ?: return
        scope.launch {
            try {
                c.decideApproval(approval.eventId, decision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "审批提交失败：${error.message}") }
            }
        }
    }

    fun answerQuestions(request: DshQuestionRequest, answers: List<Map<String, Any?>>) {
        questions.remove(request.eventId)
        publishAll()
        val c = client ?: return
        scope.launch {
            try {
                c.answerQuestions(request.eventId, answers)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "回答提交失败：${error.message}") }
            }
        }
    }

    fun rejectQuestions(request: DshQuestionRequest) {
        questions.remove(request.eventId)
        publishAll()
        val c = client ?: return
        scope.launch {
            try {
                c.rejectQuestions(request.eventId, "用户跳过了问题")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "提交失败：${error.message}") }
            }
        }
    }

    fun selectModel(option: DshModelOption, effort: String?) {
        _state.update { it.copy(provider = option.provider, model = option.model, effort = effort) }
        val id = currentSession
        val c = client
        if (id == null || c == null || !_state.value.engineReady) {
            pendingModel = option
            pendingEffort = effort
            return
        }
        scope.launch {
            try {
                c.selectModel(id, option.provider, option.model, effort)
                pendingModel = null
                pendingEffort = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "模型切换失败：${error.message}") }
            }
        }
    }

    fun setApiKey(value: String) {
        val c = client ?: return
        if (!_state.value.engineReady) return
        scope.launch {
            try {
                c.setApiKey(value)
                refreshCatalog(c)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "密钥写入引擎失败：${error.message}") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun refreshCatalog() {
        val c = client ?: return
        scope.launch { refreshCatalog(c) }
    }

    private suspend fun refreshCatalog(c: DshWebClient) {
        try {
            val catalog = c.modelCatalog()
            _catalog.value = catalog
            _state.update {
                if (it.provider == null && it.model == null) {
                    val choice = pendingModel ?: catalog.defaultOption()
                    it.copy(
                        provider = choice?.provider,
                        model = choice?.model,
                        effort = pendingEffort ?: choice?.defaultEffort,
                    )
                } else it
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(error = "模型目录读取失败：${error.message}") }
        }
    }

    private fun refreshSessions(c: DshWebClient) {
        scope.launch {
            runCatching { c.listSessions() }.onSuccess { _sessions.value = it }
        }
    }

    private fun applyPendingModel(c: DshWebClient, sessionId: String) {
        val option = pendingModel ?: return
        scope.launch {
            try {
                c.selectModel(sessionId, option.provider, option.model, pendingEffort)
                pendingModel = null
                pendingEffort = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = "模型切换失败：${error.message}") }
            }
        }
    }

    /** Re-subscribes so a foreground return rebuilds the transcript from a fresh snapshot. */
    fun resumeSession() {
        val c = client ?: return
        val id = currentSession ?: return
        startFollow(c, id)
    }

    private fun startFollow(c: DshWebClient, sessionId: String) {
        followJob?.cancel()
        followJob = scope.launch {
            try {
                c.followSession(sessionId).collect { item -> handleFollow(item) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DshRemoteException) {
                clearMissingSession(error)
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "会话订阅失败：${error.message}") }
            }
        }
    }

    private fun clearMissingSession(error: DshRemoteException) {
        if (currentSession == null) return
        currentSession = null
        followJob = null
        events.clear()
        streaming = null
        optimistic = null
        running = false
        _state.update {
            it.copy(
                sessionId = null,
                title = null,
                messages = emptyList(),
                streaming = null,
                optimisticUser = null,
                running = false,
                loading = false,
                error = "无法打开会话：${error.message}",
            )
        }
        persist(null)
    }

    private fun onHostEvent(event: DshHostEvent) {
        when (event) {
            is DshHostEvent.ApprovalRequested -> {
                approvals[event.approval.eventId] = event.approval
                publishAll()
            }
            is DshHostEvent.QuestionRequested -> {
                questions[event.request.eventId] = event.request
                publishAll()
            }
            is DshHostEvent.RequestCancelled -> {
                approvals.remove(event.eventId)
                questions.remove(event.eventId)
                publishAll()
            }
            is DshHostEvent.SessionStatus -> {
                if (event.sessionId == currentSession) {
                    running = event.running
                    publishStream()
                }
            }
            is DshHostEvent.SessionError -> {
                if (event.sessionId == currentSession) {
                    _state.update { it.copy(error = event.message.ifBlank { "会话出错" }) }
                }
            }
            is DshHostEvent.CredentialUpdated -> {
                if (event.ref == "DEEPSEEK_API_KEY") refreshCatalog()
            }
            is DshHostEvent.Ready -> Unit
        }
    }

    private fun handleFollow(item: Map<String, Any?>) {
        when (item["type"].dshString()) {
            "snapshot" -> {
                val snapshot = DshFollowFrame.snapshot(item) ?: return
                events.clear()
                events.addAll(snapshot.events)
                streamClear?.cancel()
                streaming = DshFollowFrame.activeAttempt(item)
                optimistic = null
                running = inferRunning(events)
                _state.update {
                    it.copy(
                        loading = false,
                        title = snapshot.title,
                        provider = snapshot.provider ?: it.provider,
                        model = snapshot.model ?: it.model,
                        error = null,
                    )
                }
                publishAll()
            }
            "event" -> {
                val event = item["event"].asDshMap() ?: return
                events += event
                when (event["type"].dshString()) {
                    "turn/start" -> running = true
                    "turn/end" -> {
                        running = false
                        clearStreaming()
                        client?.let { refreshSessions(it) }
                    }
                    "user/message" -> {
                        val message = event["data"].asDshMapOrEmpty()["message"].asDshMap()
                            ?: event["data"].asDshMapOrEmpty()
                        if (message["source"].asDshMapOrEmpty()["kind"].dshString() == "user") {
                            optimistic = null
                        }
                    }
                    "assistant/message", "assistant/attempt" -> {
                        val seq = event["seq"].dshLong() ?: -1
                        val active = streaming
                        if (active != null && (active.startedAfterSeq < 0 || seq >= active.startedAfterSeq)) {
                            clearStreaming()
                        }
                    }
                }
                publishAll()
            }
            "assistant-stream" -> {
                val frame = item["frame"].asDshMap() ?: return
                val next = DshFollowFrame.applyStream(streaming, frame)
                if (next == null) {
                    val attemptId = frame["attemptId"].dshString()
                    streamClear?.cancel()
                    streamClear = scope.launch {
                        delay(3_000)
                        if (streaming != null && (attemptId == null || streaming?.attemptId == attemptId)) {
                            streaming = null
                            publishStream()
                        }
                    }
                } else {
                    streamClear?.cancel()
                    streaming = next
                    running = true
                    publishStream()
                }
            }
        }
    }

    private fun inferRunning(events: List<Map<String, Any?>>): Boolean {
        for (event in events.asReversed()) {
            when (event["type"].dshString()) {
                "turn/end" -> return false
                "turn/start" -> return true
            }
        }
        return false
    }

    private fun clearStreaming() {
        streamClear?.cancel()
        streamClear = null
        streaming = null
        publishStream()
    }

    private fun publishAll() {
        _state.update {
            it.copy(
                messages = DshTranscript.fold(events),
                streaming = streaming,
                optimisticUser = optimistic,
                running = running,
                loading = false,
                approvals = approvals.values.toList(),
                questions = questions.values.toList(),
            )
        }
    }

    private fun publishStream() {
        _state.update {
            it.copy(
                streaming = streaming,
                optimisticUser = optimistic,
                running = running,
                approvals = approvals.values.toList(),
                questions = questions.values.toList(),
            )
        }
    }

    private fun persist(sessionId: String?) {
        if (remoteMode) return
        scope.launch {
            try {
                persistSession(sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (_error: Exception) {
            }
        }
    }
}
