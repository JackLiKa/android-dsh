package com.example.adsh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.adsh.device.AdshAccessibilityService
import com.example.adsh.device.AdshNotificationListenerService
import com.example.adsh.device.DeviceCapabilities
import com.example.adsh.engine.DshChatController
import com.example.adsh.ui.AccentColor
import com.example.adsh.ui.AdshTheme
import com.example.adsh.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VOICE_START = "voice-start"
    }

    private val engineBinder = mutableStateOf<DshEngineService.LocalBinder?>(null)
    private val voiceRequest = mutableStateOf(0)
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
        val settings = SecureSettings(applicationContext)
        val workspaceStore = WorkspaceStore(filesDir.resolve("dsh-home/workspaces"))
        val task = if (savedInstanceState == null) intent.getStringExtra("task").orEmpty() else ""
        val requestedProfile = intent.getStringExtra("profile")
        val showConfig = intent.getBooleanExtra("dump-config", false)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_VOICE_START, false)) {
            voiceRequest.value = 1
        }
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            var accentColor by remember { mutableStateOf(AccentColor.BLUE) }
            val themeScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                themeMode = ThemeMode.fromId(settings.themeMode())
                accentColor = AccentColor.fromId(settings.accentColor())
            }
            AdshTheme(themeMode, accentColor) {
                App(
                    settings,
                    workspaceStore,
                    task,
                    requestedProfile,
                    showConfig,
                    voiceRequest.value,
                    engineBinder.value,
                    {
                        startForegroundService(
                            Intent(this, DshEngineService::class.java)
                                .setAction(DshEngineService.ACTION_START)
                        )
                    },
                    {
                        startForegroundService(
                            Intent(this, DshEngineService::class.java)
                                .setAction(DshEngineService.ACTION_SAFE_START)
                        )
                    },
                    ::launchFileChooser,
                    { fileSelection.cancel() },
                    themeMode,
                    { mode ->
                        themeMode = mode
                        themeScope.launch { settings.setThemeMode(mode.id) }
                    },
                    accentColor,
                    { accent ->
                        accentColor = accent
                        themeScope.launch { settings.setAccentColor(accent.id) }
                    },
                    { voiceRequest.value = 0 },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, DshEngineService::class.java),
            engineConnection,
            Context.BIND_AUTO_CREATE,
        )
        engineBinder.value?.setForeground(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_VOICE_START, false)) {
            voiceRequest.value += 1
        }
    }

    override fun onStop() {
        engineBinder.value?.setForeground(false)
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

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
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
    settings: SecureSettings,
    workspaceStore: WorkspaceStore,
    initialTask: String,
    requestedProfile: String?,
    showConfig: Boolean,
    voiceRequest: Int,
    binder: DshEngineService.LocalBinder?,
    startEngine: () -> Unit,
    startSafeMode: () -> Unit,
    chooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    cancelFileChooser: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    accentColor: AccentColor,
    onAccentColor: (AccentColor) -> Unit,
    onVoiceRequestHandled: () -> Unit,
) {
    var page by remember { mutableStateOf(if (showConfig) "Plugins" else "Chat") }
    var profile by remember { mutableStateOf("web") }
    var workspaces by remember { mutableStateOf<List<WorkspaceRef>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf(WorkspaceStore.DEFAULT_ID) }
    var workspacePath by remember { mutableStateOf<String?>(null) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var workspaceReady by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var config by remember { mutableStateOf<ComposedProfile?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var rawConfig by remember { mutableStateOf(showConfig) }
    var savedSession by remember { mutableStateOf<String?>(null) }
    var settingsLoaded by remember { mutableStateOf(false) }
    var bridgeEnabled by remember { mutableStateOf(false) }
    var bridgePort by remember { mutableStateOf<Int?>(null) }
    var accessibilityOn by remember { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(false) }
    var smsPermissionOn by remember { mutableStateOf(false) }
    var bridgeRefresh by remember { mutableStateOf(0) }
    var bubbleOn by remember { mutableStateOf(false) }
    var routines by remember { mutableStateOf<List<AutomationRoutine>>(emptyList()) }
    var remoteUrl by remember { mutableStateOf("") }
    var remoteInput by remember { mutableStateOf("") }
    var remoteError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        smsPermissionOn = granted
        bridgeRefresh++
        status = context.getString(
            if (granted) R.string.sms_permission_granted else R.string.sms_permission_denied,
        )
    }
    val catalog = remember(context) { ProfileCatalog(context) }
    val automationStore = remember(context) { AutomationStore(context) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val engineStatus = binder?.status?.collectAsState()?.value
        ?: EngineStatus(EngineStatus.Phase.IDLE, "引擎尚未连接")

    val controller = remember {
        DshChatController(
            cwdProvider = { workspacePath },
            apiKeyProvider = { settings.apiKey() },
            persistSession = { sessionId ->
                if (sessionId == null) settings.clearSessionId()
                else settings.setSessionId(sessionId)
            },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    val chatState by controller.state.collectAsState()
    val modelCatalog by controller.catalog.collectAsState()
    val engineSessions by controller.sessions.collectAsState()
    LaunchedEffect(chatState.sessionId, remoteUrl) {
        if (remoteUrl.isBlank()) chatState.sessionId?.let { savedSession = it }
    }
    LaunchedEffect(binder, chatState.running) {
        binder?.setBusy(chatState.running)
    }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(binder, lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    binder?.setForeground(true)
                    bridgeRefresh++
                    controller.resumeSession()
                }
                Lifecycle.Event.ON_STOP -> binder?.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        if (lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            binder?.setForeground(true)
        }
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val profileBundles = mapOf(
        "web" to "dsh-base, dsh-web-app",
        "headless" to "dsh-base, dsh-headless",
        "sdk" to "dsh-base, dsh-sdk-app",
        "acp" to "dsh-base, dsh-acp-app",
        "sdk-minimal" to "dsh-sdk-minimal",
    )

    BackHandler(enabled = page == "Native") { page = "Plugins" }
    BackHandler(enabled = page == "Logs") { page = "Settings" }

    LaunchedEffect(voiceRequest) {
        if (voiceRequest > 0) page = "Chat"
    }

    LaunchedEffect(Unit) {
        val selected = requestedProfile ?: settings.profile()
        profile = if (selected in profileBundles) selected else "web"
        settings.setProfile(profile)
        hasKey = try { settings.apiKey() != null } catch (error: Exception) {
            status = context.getString(R.string.key_unavailable, error.message.orEmpty())
            false
        }
        try {
            workspaces = withContext(Dispatchers.IO) { workspaceStore.list() }
            val stored = settings.workspaceId()
            selectedWorkspaceId = withContext(Dispatchers.IO) { workspaceStore.get(stored).id }
            workspacePath = withContext(Dispatchers.IO) {
                workspaceStore.get(selectedWorkspaceId).directory.absolutePath
            }
            savedSession = if (initialTask.isEmpty()) settings.sessionId() else null
            remoteUrl = settings.remoteEngineUrl().orEmpty()
            remoteInput = remoteUrl
            settingsLoaded = true
            workspaceReady = true
        } catch (error: Exception) {
            workspaceError = context.getString(R.string.workspace_failed, error.message.orEmpty())
        }
    }

    LaunchedEffect(automationStore) {
        routines = automationStore.list()
    }

    LaunchedEffect(engineStatus.url, savedSession, settingsLoaded, remoteUrl) {
        if (!settingsLoaded) return@LaunchedEffect
        val remote = remoteUrl.isNotBlank()
        controller.attach(
            remoteUrl.ifBlank { engineStatus.url },
            if (remote) null else savedSession,
            remote,
        )
    }

    LaunchedEffect(page, engineStatus.phase, bridgeRefresh) {
        if (page != "Settings") return@LaunchedEffect
        bridgeEnabled = settings.deviceBridgeEnabled()
        accessibilityOn = AdshAccessibilityService.isEnabled(context)
        notificationsOn = AdshNotificationListenerService.isEnabled(context)
        smsPermissionOn = context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        bubbleOn = AdshAccessibilityService.isBubbleEnabled(context)
        bridgePort = binder?.bridgePort()
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
        controller.openSession(id)
        page = "Chat"
    }
    val newSession: () -> Unit = {
        controller.newSession()
        page = "Chat"
    }
    val selectWorkspace: (WorkspaceRef) -> Unit = { workspace ->
        if (workspace.id != selectedWorkspaceId) {
            selectedWorkspaceId = workspace.id
            workspacePath = workspace.directory.absolutePath
            scope.launch {
                try {
                    settings.setWorkspaceId(workspace.id)
                    workspaceError = null
                    status = context.getString(R.string.workspace_selected, workspace.name)
                    controller.newSession()
                } catch (error: Exception) {
                    workspaceError = context.getString(R.string.workspace_failed, error.message.orEmpty())
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(Modifier.statusBarsPadding(), tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (page == "Native") {
                        TextButton(onClick = { page = "Plugins" }) {
                            Text(stringResource(R.string.back_to_plugins))
                        }
                    } else if (page == "Logs") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page = "Settings" }) {
                                Text(stringResource(R.string.back))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.open_engine_logs),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Text(
                            engineStatus.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("深度求索助手", style = MaterialTheme.typography.titleLarge)
                        if (page != "Chat") {
                            Text(
                                stringResource(R.string.current_profile, profile),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            val workspaceName = workspaces.firstOrNull { it.id == selectedWorkspaceId }?.name
                                ?: selectedWorkspaceId
                            Text(
                                stringResource(R.string.current_workspace, workspaceName),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!keyboardVisible && page != "Native") {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    listOf(
                        "Chat" to R.string.chat,
                        "History" to R.string.history,
                        "Workspaces" to R.string.workspaces,
                        "Automation" to R.string.automation,
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
                                        "Automation" -> Icons.Default.Refresh
                                        "Plugins" -> Icons.Default.Info
                                        else -> Icons.Default.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
            when (page) {
                "Chat" -> ChatScreen(
                    state = chatState,
                    catalog = modelCatalog,
                    engineStatus = engineStatus,
                    initialPrompt = initialTask,
                    voiceRequest = voiceRequest,
                    onVoiceRequestHandled = onVoiceRequestHandled,
                    onStartEngine = startEngine,
                    onRetryEngine = startEngine,
                    onSend = {
                        binder?.touch()
                        controller.send(it)
                    },
                    onCancel = controller::cancel,
                    onNewSession = { controller.newSession() },
                    onSelectModel = controller::selectModel,
                    onApproval = controller::decideApproval,
                    onAnswer = controller::answerQuestions,
                    onRejectQuestion = controller::rejectQuestions,
                    onDismissError = controller::clearError,
                    onOpenLogs = { page = "Logs" },
                )

                "History" -> EngineHistoryScreen(
                    sessions = engineSessions,
                    currentSessionId = chatState.sessionId,
                    enabled = chatState.engineReady,
                    engineReady = chatState.engineReady,
                    onOpen = openSession,
                    onNew = newSession,
                    onStartEngine = startEngine,
                )

                "Logs" -> EngineLogScreen(
                    binder = binder,
                    status = engineStatus,
                    onStart = startEngine,
                    onSafeStart = startSafeMode,
                )

                "Workspaces" -> {
                    val counts = engineSessions.groupingBy { it.cwd.orEmpty() }.eachCount()
                    WorkspaceScreen(
                        workspaces = workspaces,
                        sessionCounts = workspaces.associate { workspace ->
                            workspace.id to (counts[workspace.directory.absolutePath] ?: 0)
                        },
                        selectedWorkspaceId = selectedWorkspaceId,
                        error = workspaceError,
                        enabled = workspaceReady,
                        onCreate = { name ->
                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) { workspaceStore.create(name) }
                                    workspaces = withContext(Dispatchers.IO) { workspaceStore.list() }
                                    selectWorkspace(created)
                                } catch (error: Exception) {
                                    workspaceError = context.getString(
                                        R.string.workspace_failed,
                                        error.message.orEmpty(),
                                    )
                                }
                            }
                        },
                        onSelect = selectWorkspace,
                    )
                }

                "Automation" -> AutomationScreen(
                    routines = routines,
                    engineReady = chatState.engineReady,
                    onRun = { routine ->
                        binder?.touch()
                        controller.send(routine.prompt)
                        page = "Chat"
                    },
                    onAdd = { title, prompt ->
                        routines = automationStore.add(title, prompt)
                    },
                    onDelete = { routine ->
                        routines = automationStore.remove(routine.id)
                    },
                )

                "Plugins" -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        Text(stringResource(R.string.profile_bundles), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.native_runtime_notice),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(R.string.engine_status, engineStatus.message))
                        if (
                            (engineStatus.phase == EngineStatus.Phase.READY ||
                                engineStatus.phase == EngineStatus.Phase.DEGRADED) &&
                            engineStatus.url != null
                        ) {
                            Button(onClick = { page = "Native" }) {
                                Text(stringResource(R.string.open_original_dsh))
                            }
                        } else {
                            Button(
                                onClick = startEngine,
                                enabled = binder != null &&
                                    engineStatus.phase != EngineStatus.Phase.STARTING,
                            ) {
                                Text(stringResource(R.string.start_original_dsh))
                            }
                        }
                        Text(stringResource(R.string.plugin_pending))
                        Text(
                            stringResource(R.string.config_preview),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        configError?.let {
                            Text(
                                stringResource(R.string.profile_error, it),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        config?.let { composed ->
                            Text(stringResource(R.string.profile_rows, composed.rows.size))
                            composed.warnings.forEach {
                                Text(
                                    stringResource(R.string.config_warning, it),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            composed.rows.forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(row["id"]?.toString().orEmpty(), modifier = Modifier.weight(1f))
                                    Text(row["module"]?.toString() ?: row["name"]?.toString().orEmpty())
                                    if (row["disabled"] == true) Text(stringResource(R.string.disabled))
                                }
                            }
                            TextButton(onClick = { rawConfig = !rawConfig }) {
                                Text(stringResource(if (rawConfig) R.string.hide_config else R.string.dump_config))
                            }
                            if (rawConfig) {
                                Text(
                                    ProfileComposer().dump(composed.rows),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
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
                    if (
                        url != null &&
                        (engineStatus.phase == EngineStatus.Phase.READY ||
                            engineStatus.phase == EngineStatus.Phase.DEGRADED)
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (engineStatus.phase == EngineStatus.Phase.DEGRADED) {
                                Text(
                                    engineStatus.message,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                            NativeDshWebView(
                                url,
                                chooseFiles,
                                cancelFileChooser,
                                Modifier.weight(1f).navigationBarsPadding(),
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxSize().navigationBarsPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (engineStatus.phase == EngineStatus.Phase.STARTING) {
                                    CircularProgressIndicator()
                                }
                                Text(
                                    stringResource(R.string.engine_status, engineStatus.message),
                                    modifier = Modifier.padding(16.dp),
                                )
                                if (
                                    engineStatus.phase == EngineStatus.Phase.FAILED ||
                                    engineStatus.phase == EngineStatus.Phase.STOPPED
                                ) {
                                    Button(onClick = startEngine, enabled = binder != null) {
                                        Text(stringResource(R.string.start_original_dsh))
                                    }
                                }
                            }
                        }
                    }
                }

                "Settings" -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.engine_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(stringResource(R.string.engine_status, engineStatus.message))
                                Text(
                                    chatState.connection,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (chatState.engineReady) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Text(
                                    stringResource(
                                        R.string.model_name_label,
                                        chatState.selectedLabel(modelCatalog),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(R.string.model_from_chat),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { controller.refreshCatalog() },
                                        enabled = chatState.engineReady,
                                    ) { Text(stringResource(R.string.refresh)) }
                                    TextButton(
                                        onClick = startEngine,
                                        enabled = binder != null &&
                                            engineStatus.phase != EngineStatus.Phase.STARTING &&
                                            engineStatus.phase != EngineStatus.Phase.READY &&
                                            engineStatus.phase != EngineStatus.Phase.DEGRADED,
                                    ) { Text(stringResource(R.string.engine_start)) }
                                    TextButton(
                                        onClick = { binder?.stop() },
                                        enabled = engineStatus.phase == EngineStatus.Phase.READY ||
                                            engineStatus.phase == EngineStatus.Phase.DEGRADED ||
                                            engineStatus.phase == EngineStatus.Phase.STARTING,
                                    ) { Text(stringResource(R.string.engine_stop)) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { page = "Logs" }) {
                                        Text(stringResource(R.string.open_engine_logs))
                                    }
                                    TextButton(
                                        onClick = startSafeMode,
                                        enabled = binder != null &&
                                            engineStatus.phase != EngineStatus.Phase.STARTING,
                                    ) { Text(stringResource(R.string.safe_mode_restart)) }
                                }
                                Text(
                                    stringResource(R.string.safe_mode_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.idle_stop_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.appearance_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.appearance_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ThemeMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = themeMode == mode,
                                            onClick = { onThemeMode(mode) },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        when (mode) {
                                                            ThemeMode.SYSTEM -> R.string.appearance_system
                                                            ThemeMode.LIGHT -> R.string.appearance_light
                                                            ThemeMode.DARK -> R.string.appearance_dark
                                                        },
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.accent_section),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                AccentColor.entries.chunked(2).forEach { rowAccents ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        rowAccents.forEach { accent ->
                                            FilterChip(
                                                modifier = Modifier.weight(1f),
                                                selected = accentColor == accent,
                                                onClick = { onAccentColor(accent) },
                                                leadingIcon = {
                                                    Box(
                                                        Modifier
                                                            .size(14.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(accent.lightPrimary)),
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        stringResource(
                                                            when (accent) {
                                                                AccentColor.BLUE -> R.string.accent_blue
                                                                AccentColor.TEAL -> R.string.accent_teal
                                                                AccentColor.PURPLE -> R.string.accent_purple
                                                                AccentColor.ORANGE -> R.string.accent_orange
                                                                AccentColor.ROSE -> R.string.accent_rose
                                                                AccentColor.GREEN -> R.string.accent_green
                                                            },
                                                        ),
                                                        maxLines = 1,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.remote_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.remote_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = remoteInput,
                                    onValueChange = {
                                        remoteInput = it
                                        remoteError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    placeholder = { Text("http://192.168.1.10:3080/?token=…") },
                                    label = { Text(stringResource(R.string.remote_url_label)) },
                                )
                                remoteError?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (remoteUrl.isNotBlank()) {
                                    Text(
                                        stringResource(
                                            if (chatState.engineReady) R.string.remote_connected
                                            else R.string.remote_connecting,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (chatState.engineReady) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            val candidate = remoteInput.trim()
                                            val parsed = candidate
                                                .toHttpUrlOrNull()
                                            if (parsed == null || parsed.queryParameter("token").isNullOrBlank()) {
                                                remoteError = context.getString(R.string.remote_invalid)
                                            } else {
                                                scope.launch {
                                                    settings.saveRemoteEngineUrl(candidate)
                                                    remoteUrl = candidate
                                                    status = context.getString(R.string.remote_saved)
                                                }
                                            }
                                        },
                                    ) { Text(stringResource(R.string.remote_connect)) }
                                    TextButton(
                                        onClick = {
                                            remoteInput = ""
                                            remoteUrl = ""
                                            scope.launch {
                                                settings.saveRemoteEngineUrl(null)
                                                status = context.getString(R.string.remote_disconnected)
                                            }
                                        },
                                        enabled = remoteUrl.isNotBlank() || remoteInput.isNotBlank(),
                                    ) { Text(stringResource(R.string.remote_disconnect)) }
                                }
                                Text(
                                    stringResource(R.string.remote_security_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.device_bridge_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.device_bridge_switch))
                                        Text(
                                            stringResource(
                                                if (bridgeEnabled) R.string.device_bridge_on
                                                else R.string.device_bridge_off,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (bridgeEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Switch(
                                        checked = bridgeEnabled,
                                        onCheckedChange = { value ->
                                            bridgeEnabled = value
                                            scope.launch {
                                                settings.setDeviceBridgeEnabled(value)
                                                val running = engineStatus.phase ==
                                                    EngineStatus.Phase.READY ||
                                                    engineStatus.phase == EngineStatus.Phase.STARTING
                                                if (running) {
                                                    binder?.restart()
                                                    bridgePort = binder?.bridgePort()
                                                    status = context.getString(
                                                        R.string.device_bridge_restart_hint,
                                                    )
                                                } else {
                                                    status = context.getString(
                                                        R.string.device_bridge_saved,
                                                    )
                                                }
                                                bridgeRefresh++
                                            }
                                        },
                                    )
                                }
                                bridgePort?.let { port ->
                                    Text(
                                        stringResource(R.string.device_bridge_running, port),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    stringResource(R.string.device_bridge_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(
                                        R.string.accessibility_status,
                                        stringResource(
                                            if (accessibilityOn) R.string.granted
                                            else R.string.not_granted,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                        )
                                    },
                                ) { Text(stringResource(R.string.open_accessibility_settings)) }
                                Text(
                                    stringResource(
                                        R.string.notification_status,
                                        stringResource(
                                            if (notificationsOn) R.string.granted
                                            else R.string.not_granted,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"),
                                        )
                                    },
                                ) { Text(stringResource(R.string.open_notification_settings)) }
                                Text(
                                    stringResource(
                                        R.string.sms_send_status,
                                        stringResource(
                                            if (smsPermissionOn) R.string.granted
                                            else R.string.not_granted,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!smsPermissionOn) {
                                    TextButton(
                                        onClick = {
                                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                                        },
                                    ) { Text(stringResource(R.string.grant_sms_permission)) }
                                }
                                Text(
                                    stringResource(R.string.device_bridge_tools_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val audit = remember(page, bridgeEnabled, bridgeRefresh) {
                                    if (bridgeEnabled) {
                                        DeviceCapabilities.auditEntries().takeLast(6).asReversed()
                                    } else {
                                        emptyList()
                                    }
                                }
                                Text(
                                    stringResource(R.string.device_bridge_audit),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (audit.isEmpty()) {
                                    Text(
                                        stringResource(R.string.device_bridge_audit_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    audit.forEach { entry ->
                                        Text(
                                            entry,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(onClick = { bridgeRefresh++ }) {
                                    Text(stringResource(R.string.device_bridge_audit_refresh))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.wake_section),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.wake_bubble_switch))
                                        Text(
                                            stringResource(
                                                if (bubbleOn) R.string.wake_bubble_on
                                                else R.string.wake_bubble_off,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (bubbleOn) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Switch(
                                        checked = bubbleOn,
                                        onCheckedChange = { value ->
                                            bubbleOn = value
                                            AdshAccessibilityService.setBubbleEnabled(context, value)
                                        },
                                    )
                                }
                                Text(
                                    stringResource(R.string.wake_bubble_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!accessibilityOn) {
                                    Text(
                                        stringResource(R.string.wake_needs_accessibility),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                            )
                                        },
                                    ) { Text(stringResource(R.string.open_accessibility_settings)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.model_and_key_section),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.key_status,
                                stringResource(if (hasKey) R.string.saved_on_device else R.string.not_set),
                            ),
                        )
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
                                    controller.setApiKey(value)
                                } catch (error: Exception) {
                                    status = context.getString(
                                        R.string.key_save_failed,
                                        error.message.orEmpty(),
                                    )
                                }
                            }
                        }) { Text(stringResource(R.string.save_key)) }
                        if (status.isNotBlank()) {
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.local_storage))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium)
                        profileBundles.keys.forEach { name ->
                            TextButton(onClick = {
                                scope.launch {
                                    profile = name
                                    settings.setProfile(name)
                                }
                            }) {
                                Text(
                                    if (name == profile) {
                                        "$name (${context.getString(R.string.profile_selected_suffix)})"
                                    } else {
                                        name
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
