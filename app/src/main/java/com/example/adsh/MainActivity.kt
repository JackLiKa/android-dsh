package com.example.adsh

import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val engineBinder = mutableStateOf<DshEngineService.LocalBinder?>(null)
    private val fileSelection = FileSelectionController<Array<Uri>>()
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        fileSelection.complete(selected)
    }
    private var bound = false
    private val engineConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engineBinder.value = service as? DshEngineService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val database = Room.databaseBuilder(applicationContext, SessionDatabase::class.java, "sessions.db").build()
        val sessions = LocalSessions(filesDir.resolve("dsh-home/sessions"), database.events())
        val settings = SecureSettings(applicationContext)
        val task = intent.getStringExtra("task").orEmpty()
        val requestedProfile = intent.getStringExtra("profile")
        val showConfig = intent.getBooleanExtra("dump-config", false)
        setContent {
            MaterialTheme {
                val llm = remember { LlmRegistry() }
                App(sessions, settings, llm, task, requestedProfile, showConfig,
                    engineBinder.value,
                    { startForegroundService(Intent(this, DshEngineService::class.java)
                        .setAction(DshEngineService.ACTION_START)) },
                    ::launchFileChooser,
                    { fileSelection.cancel() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, DshEngineService::class.java),
            engineConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            unbindService(engineConnection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        fileSelection.cancel()
        super.onDestroy()
    }

    private fun launchFileChooser(callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams): Boolean {
        fileSelection.offer(callback::onReceiveValue)
        return try {
            val request = params.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileChooser.launch(request)
            true
        } catch (_error: Exception) {
            fileSelection.cancel()
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
            true
        }
    }
}

@Composable
private fun App(
    sessions: LocalSessions,
    settings: SecureSettings,
    llm: LlmRegistry,
    initialTask: String,
    requestedProfile: String?,
    showConfig: Boolean,
    binder: DshEngineService.LocalBinder?,
    startEngine: () -> Unit,
    chooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    cancelFileChooser: () -> Unit,
) {
    var page by remember { mutableStateOf(if (showConfig) "Plugins" else "Chat") }
    var profile by remember { mutableStateOf("web") }
    var sessionId by remember { mutableStateOf(sessions.newId()) }
    var rows by remember { mutableStateOf<List<SessionEventRow>>(emptyList()) }
    var priorSessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<WorkspaceRef>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf(WorkspaceStore.DEFAULT_ID) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var workspaceReady by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(initialTask) }
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var selectedRoute by remember {
        mutableStateOf(ModelRoute(BuiltInModels.DEEPSEEK_PROVIDER, BuiltInModels.DEEPSEEK_FLASH))
    }
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.getString(R.string.ready)) }
    var sending by remember { mutableStateOf(false) }
    var llmReady by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<ComposedProfile?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var rawConfig by remember { mutableStateOf(showConfig) }
    val catalog = remember(context) { ProfileCatalog(context) }
    val workspaceStore = remember(context) { WorkspaceStore(context.filesDir.resolve("dsh-home/workspaces")) }
    val scope = rememberCoroutineScope()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val engineStatus = binder?.status?.collectAsState()?.value
        ?: EngineStatus(EngineStatus.Phase.IDLE, "引擎尚未连接")
    BackHandler(enabled = page == "Native") { page = "Plugins" }
    val profileBundles = mapOf(
        "web" to "dsh-base, dsh-web-app",
        "headless" to "dsh-base, dsh-headless",
        "sdk" to "dsh-base, dsh-sdk-app",
        "acp" to "dsh-base, dsh-acp-app",
        "sdk-minimal" to "dsh-sdk-minimal",
    )
    val availableModels = if (llmReady) {
        llm.providers().filter { it == BuiltInModels.DEEPSEEK_PROVIDER }
            .flatMap { provider -> llm.adapterFor(provider).models.map { ModelRoute(provider, it) } }
    } else emptyList()
    val chooseModel: (ModelRoute) -> Unit = { route ->
        if (!sending && route in availableModels) scope.launch {
            try {
                settings.setSelectedModel(route)
                selectedRoute = route
                status = context.getString(R.string.model_selected, route.model)
            } catch (error: Exception) {
                status = context.getString(R.string.model_provider_failed, error.message.orEmpty())
            }
        }
    }
    LaunchedEffect(llm) {
        val runtime = PluginRuntime(mapOf(ModelServices.Llm to llm))
        val fiber = try {
            runtime.mount(DeepSeekProviderPlugin())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status = context.getString(R.string.model_provider_failed, error.message.orEmpty())
            return@LaunchedEffect
        }
        llmReady = true
        try {
            awaitCancellation()
        } finally {
            llmReady = false
            fiber.dispose()
        }
    }

    LaunchedEffect(requestedProfile) {
        val selected = requestedProfile ?: settings.profile()
        profile = if (selected in profileBundles) selected else "web"
        settings.setProfile(profile)
        hasKey = try { settings.apiKey() != null } catch (error: Exception) {
            status = context.getString(R.string.key_unavailable, error.message.orEmpty())
            false
        }
        try {
            val defaultRoute = settings.selectedModel()
            workspaces = withContext(Dispatchers.IO) { workspaceStore.list() }
            val storedWorkspace = settings.workspaceId()
            selectedWorkspaceId = withContext(Dispatchers.IO) { workspaceStore.get(storedWorkspace).id }
            val savedSession = if (initialTask.isEmpty()) settings.sessionId() else null
            if (savedSession != null) {
                rows = sessions.events(savedSession)
                sessionId = savedSession
                selectedRoute = selectedModel(rows, defaultRoute)
                selectedWorkspaceId = withContext(Dispatchers.IO) {
                    workspaceStore.get(sessions.workspaceIdOf(savedSession)).id
                }
                settings.setWorkspaceId(selectedWorkspaceId)
            } else {
                selectedRoute = defaultRoute
                settings.setSessionId(sessionId)
            }
            priorSessions = sessions.list()
            workspaceReady = true
        } catch (error: Exception) {
            workspaceError = context.getString(R.string.workspace_failed, error.message.orEmpty())
            status = workspaceError.orEmpty()
        }
    }
    LaunchedEffect(profile) {
        try {
            config = catalog.compose(profile)
            configError = null
        } catch (error: Exception) {
            config = null
            configError = error.message ?: error.javaClass.simpleName
        }
    }

    val openSession: (String) -> Unit = { id ->
        scope.launch {
            try {
                val history = sessions.events(id)
                val originalWorkspace = sessions.workspaceIdOf(id)
                withContext(Dispatchers.IO) { workspaceStore.get(originalWorkspace) }
                settings.setWorkspaceId(originalWorkspace)
                settings.setSessionId(id)
                sessionId = id
                rows = history
                selectedRoute = selectedModel(history, settings.selectedModel())
                selectedWorkspaceId = originalWorkspace
                status = context.getString(R.string.restored)
                workspaceError = null
                page = "Chat"
            } catch (error: Exception) {
                workspaceError = context.getString(R.string.restore_failed, error.message.orEmpty())
                status = workspaceError.orEmpty()
            }
        }
    }
    val selectWorkspace: (WorkspaceRef) -> Unit = { workspace ->
        if (workspace.id != selectedWorkspaceId) scope.launch {
            try {
                withContext(Dispatchers.IO) { workspaceStore.get(workspace.id) }
                val nextSession = sessions.newId()
                settings.setWorkspaceId(workspace.id)
                settings.setSessionId(nextSession)
                selectedWorkspaceId = workspace.id
                sessionId = nextSession
                rows = emptyList()
                status = context.getString(R.string.workspace_selected, workspace.name)
                workspaceError = null
            } catch (error: Exception) {
                workspaceError = context.getString(R.string.workspace_failed, error.message.orEmpty())
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(Modifier.statusBarsPadding(), tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    if (page == "Native") {
                        TextButton(onClick = { page = "Plugins" }) {
                            Text(stringResource(R.string.back_to_plugins))
                        }
                    } else {
                        Text("深度求索助手", style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.current_profile, profile),
                            style = MaterialTheme.typography.labelMedium)
                        val workspaceName = workspaces.firstOrNull { it.id == selectedWorkspaceId }?.name
                            ?: selectedWorkspaceId
                        Text(stringResource(R.string.current_workspace, workspaceName),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        bottomBar = {
            if (!keyboardVisible && page != "Native") NavigationBar(windowInsets = WindowInsets.navigationBars) {
                listOf(
                    "Chat" to R.string.chat,
                    "History" to R.string.history,
                    "Workspaces" to R.string.workspaces,
                    "Plugins" to R.string.plugins,
                    "Settings" to R.string.settings,
                ).forEach { (tab, label) ->
                    NavigationBarItem(
                        selected = page == tab,
                        onClick = { page = tab },
                        icon = {
                            Icon(
                                when (tab) {
                                    "Chat" -> Icons.Default.Home
                                    "History" -> Icons.AutoMirrored.Filled.List
                                    "Workspaces" -> Icons.Default.Build
                                    "Plugins" -> Icons.Default.Info
                                    else -> Icons.Default.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
        when (page) {
            "Chat" -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.session_label, sessionId.take(8)), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            scope.launch {
                                val next = sessions.newId()
                                try {
                                    settings.setSessionId(next)
                                    sessionId = next
                                    rows = emptyList()
                                    status = context.getString(R.string.new_session_status)
                                } catch (error: Exception) {
                                    status = context.getString(R.string.restore_failed, error.message.orEmpty())
                                }
                            }
                        }, enabled = !sending && workspaceReady) { Text(stringResource(R.string.new_session)) }
                    }
                    if (rows.isEmpty()) Text(stringResource(R.string.no_messages))
                    Text(stringResource(R.string.local_chat_notice),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    rows.forEach { row ->
                        SessionEventCard(row)
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (priorSessions.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.saved_sessions), style = MaterialTheme.typography.titleSmall)
                        priorSessions.take(3).forEach { summary ->
                            TextButton(onClick = {
                                openSession(summary.id)
                            }, enabled = !sending && workspaceReady) {
                                Text("${summary.preview.ifEmpty { summary.id.take(8) }} · ${summary.eventCount} 条")
                            }
                        }
                    }
                }
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        ModelRoutePicker(selectedRoute, availableModels, !sending, chooseModel)
                        TextButton(onClick = {
                            if (engineStatus.phase != EngineStatus.Phase.READY &&
                                engineStatus.phase != EngineStatus.Phase.DEGRADED &&
                                engineStatus.phase != EngineStatus.Phase.STARTING) startEngine()
                            page = "Native"
                        }, enabled = binder != null && !sending) {
                            Text(stringResource(R.string.open_full_conversation))
                        }
                        if (hasKey && llmReady && selectedRoute !in availableModels) {
                            Text(stringResource(R.string.model_unavailable),
                                color = MaterialTheme.colorScheme.error)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                label = { Text(stringResource(R.string.task_hint)) },
                                modifier = Modifier.weight(1f),
                                maxLines = 4,
                            )
                            Button(onClick = {
                        val input = prompt.trim()
                        if (input.isEmpty()) return@Button
                        sending = true
                        val targetSession = sessionId
                        val targetWorkspace = selectedWorkspaceId
                        val targetRoute = selectedRoute
                        scope.launch {
                            try {
                                sessions.associate(targetSession, targetWorkspace)
                                val previousEvents = sessions.events(targetSession)
                                sessions.append(targetSession, "user/message", input)
                                prompt = ""
                                rows = sessions.events(targetSession)
                                priorSessions = sessions.list()
                                val key = settings.apiKey()
                                if (key == null) {
                                    status = context.getString(R.string.offline_saved)
                                } else {
                                    val adapter = llm.adapterFor(targetRoute.provider)
                                    check(targetRoute.model in adapter.models) { "所选模型尚未就绪" }
                                    val previousRoute = previousEvents.lastOrNull {
                                        it.type == "model/selection"
                                    }?.let { ModelRoute.decode(it.data) }
                                    modelChangeNotice(previousRoute, targetRoute)?.let {
                                        sessions.append(targetSession, "user/message", it)
                                    }
                                    if (shouldRecordModelSelection(previousEvents, targetRoute)) {
                                        sessions.append(targetSession, "model/selection", targetRoute.encode())
                                    }
                                    rows = sessions.events(targetSession)
                                    status = context.getString(R.string.streaming)
                                    val text = StringBuilder()
                                    adapter.stream(targetRoute.model, key, rows).collect { chunk ->
                                        text.append(chunk)
                                        status = text.toString()
                                    }
                                    if (text.isNotEmpty()) {
                                        sessions.append(targetSession, "assistant/message", text.toString())
                                        rows = sessions.events(targetSession)
                                        priorSessions = sessions.list()
                                        status = context.getString(R.string.ready)
                                    } else {
                                        sessions.append(targetSession, "assistant/attempt",
                                            "模型结束请求但未返回文本")
                                        rows = sessions.events(targetSession)
                                        priorSessions = sessions.list()
                                        status = context.getString(R.string.model_no_text)
                                    }
                                }
                            } catch (error: Exception) {
                                status = context.getString(R.string.request_failed, error.message ?: error.javaClass.simpleName)
                            } finally {
                                sending = false
                            }
                        }
                            }, enabled = !sending && workspaceReady &&
                                (!hasKey || (llmReady && selectedRoute in availableModels)),
                                modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.send))
                            }
                        }
                    }
                }
            }
            "History" -> HistoryScreen(
                summaries = priorSessions,
                workspaces = workspaces,
                selectedWorkspaceId = selectedWorkspaceId,
                enabled = !sending && workspaceReady,
                onOpen = openSession,
            )
            "Workspaces" -> WorkspaceScreen(
                workspaces = workspaces,
                summaries = priorSessions,
                selectedWorkspaceId = selectedWorkspaceId,
                error = workspaceError,
                enabled = !sending && workspaceReady,
                onCreate = { name ->
                    scope.launch {
                        try {
                            val created = withContext(Dispatchers.IO) { workspaceStore.create(name) }
                            workspaces = withContext(Dispatchers.IO) { workspaceStore.list() }
                            selectWorkspace(created)
                        } catch (error: Exception) {
                            workspaceError = context.getString(R.string.workspace_failed, error.message.orEmpty())
                        }
                    }
                },
                onSelect = selectWorkspace,
            )
            "Plugins" -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(stringResource(R.string.profile_bundles), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.native_runtime_notice),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.engine_status, engineStatus.message))
                    if ((engineStatus.phase == EngineStatus.Phase.READY ||
                            engineStatus.phase == EngineStatus.Phase.DEGRADED) &&
                        engineStatus.url != null) {
                        Button(onClick = { page = "Native" }) {
                            Text(stringResource(R.string.open_original_dsh))
                        }
                    } else {
                        Button(onClick = startEngine,
                            enabled = binder != null && engineStatus.phase != EngineStatus.Phase.STARTING) {
                            Text(stringResource(R.string.start_original_dsh))
                        }
                    }
                    Text(stringResource(R.string.plugin_pending))
                    Text(stringResource(R.string.config_preview), style = MaterialTheme.typography.bodySmall)
                    configError?.let { Text(stringResource(R.string.profile_error, it), color = MaterialTheme.colorScheme.error) }
                    config?.let { composed ->
                        Text(stringResource(R.string.profile_rows, composed.rows.size))
                        composed.warnings.forEach {
                            Text(stringResource(R.string.config_warning, it), color = MaterialTheme.colorScheme.error)
                        }
                        composed.rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row["id"]?.toString().orEmpty(), modifier = Modifier.weight(1f))
                                Text(row["module"]?.toString() ?: row["name"]?.toString().orEmpty())
                                if (row["disabled"] == true) Text(stringResource(R.string.disabled))
                            }
                        }
                        TextButton(onClick = { rawConfig = !rawConfig }) {
                            Text(stringResource(if (rawConfig) R.string.hide_config else R.string.dump_config))
                        }
                        if (rawConfig) Text(ProfileComposer().dump(composed.rows), style = MaterialTheme.typography.bodySmall)
                    }
                    profileBundles.forEach { (name, bundles) ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(bundles)
                            }
                        }
                    }
                }
            }
            "Native" -> {
                val url = engineStatus.url
                if (url != null && (engineStatus.phase == EngineStatus.Phase.READY ||
                        engineStatus.phase == EngineStatus.Phase.DEGRADED)) {
                    Column(Modifier.fillMaxSize()) {
                        if (engineStatus.phase == EngineStatus.Phase.DEGRADED) {
                            Text(engineStatus.message, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(12.dp))
                        }
                        NativeDshWebView(url, chooseFiles, cancelFileChooser,
                            Modifier.weight(1f).navigationBarsPadding())
                    }
                } else {
                    Box(Modifier.fillMaxSize().navigationBarsPadding(),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (engineStatus.phase == EngineStatus.Phase.STARTING) {
                                CircularProgressIndicator()
                            }
                            Text(stringResource(R.string.engine_status, engineStatus.message),
                                modifier = Modifier.padding(16.dp))
                            if (engineStatus.phase == EngineStatus.Phase.FAILED ||
                                engineStatus.phase == EngineStatus.Phase.STOPPED) {
                                Button(onClick = startEngine, enabled = binder != null) {
                                    Text(stringResource(R.string.start_original_dsh))
                                }
                            }
                        }
                    }
                }
            }
            "Settings" -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.model_provider_label, selectedRoute.provider))
                Text(stringResource(R.string.legacy_model_scope),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_stats_note),
                    style = MaterialTheme.typography.bodySmall)
                ModelRoutePicker(selectedRoute, availableModels, !sending, chooseModel)
                Text(stringResource(R.string.key_status,
                    stringResource(if (hasKey) R.string.saved_on_device else R.string.not_set)))
                TextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    val value = keyInput.trim()
                    if (value.isNotEmpty()) scope.launch {
                        try {
                            settings.saveKey(value)
                            keyInput = ""
                            hasKey = true
                            status = context.getString(R.string.key_saved)
                        } catch (error: Exception) {
                            status = context.getString(R.string.key_save_failed, error.message.orEmpty())
                        }
                    }
                }) { Text(stringResource(R.string.save_key)) }
                Text(stringResource(R.string.local_storage))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.profile_pending), style = MaterialTheme.typography.bodySmall)
                profileBundles.keys.forEach { name ->
                    TextButton(onClick = {
                        scope.launch {
                            profile = name
                            settings.setProfile(name)
                        }
                    }) { Text(if (name == profile) "$name (${context.getString(R.string.selected)})" else name) }
                }
                }
            }
        }
        }
    }
}

@Composable
private fun SessionEventCard(row: SessionEventRow) {
    var expanded by remember(row.sessionId, row.seq) { mutableStateOf(false) }
    val rendered = presentEventContent(row.type, row.data)
    val content = if (expanded || rendered.length <= 320) rendered else rendered.take(320) + "…"
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(eventLabel(row.type), style = MaterialTheme.typography.titleSmall)
                Text("#${row.seq + 1} · ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(row.time))}",
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(content)
            if (rendered.length > 320) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起内容" else "展开全文")
            }
        }
    }
}
