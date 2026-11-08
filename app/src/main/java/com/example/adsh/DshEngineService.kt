package com.example.adsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import com.example.adsh.engine.EngineCrash
import com.example.adsh.engine.EngineLogStore
import com.example.adsh.engine.EngineRestartPolicy
import com.example.adsh.device.DeviceBridgeConfig
import com.example.adsh.device.DeviceBridgeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EngineStatus(val phase: Phase, val message: String, val url: String? = null) {
    enum class Phase { IDLE, STARTING, READY, DEGRADED, FAILED, STOPPED }
}

fun engineExited(current: EngineStatus, code: Int): EngineStatus =
    if (current.phase == EngineStatus.Phase.FAILED) current
    else EngineStatus(EngineStatus.Phase.STOPPED, "dsh 已退出（代码 $code）")

class DshEngineService : Service() {
    companion object {
        const val ACTION_START = "com.example.adsh.START_DSH"
        const val ACTION_SAFE_START = "com.example.adsh.START_DSH_SAFE"
        val PAYLOAD_SHA256: String get() = BuildConfig.PAYLOAD_SHA256
        const val IDLE_STOP_MS = 5 * 60_000L
        private const val CHANNEL = "adsh-engine"
        private const val NOTIFICATION_ID = 3080
        private const val HOME_URL = "http://127.0.0.1:3080/"
        private val TOKEN_URL = Regex("http://127\\.0\\.0\\.1:3080/\\?token=[^\\s\"']+")
        private val TOKEN_SECRET = Regex("token=[^\\s\"']+")
        private const val LOG_TAIL = 200
    }

    inner class LocalBinder : Binder() {
        val status: StateFlow<EngineStatus> get() = state

        fun touch() = activity()

        fun setBusy(value: Boolean) {
            busy = value
            activity()
        }

        fun setForeground(value: Boolean) {
            foreground = value
            activity()
        }

        fun stop() {
            stopReason = "引擎已手动停止"
            stopRequested = true
            engine?.destroy()
        }

        fun restart() {
            if (engineJob?.isActive == true) {
                log("配置变更，正在重启 dsh 引擎")
                restartRequested = true
                stopRequested = true
                stopReason = "配置变更重启"
                engine?.destroy()
            }
        }

        fun bridgePort(): Int? = bridgeServer?.port

        fun logs(): List<String> = logStore.read()

        fun clearLogs() {
            logStore.clear()
            synchronized(logTail) { logTail.clear() }
            log("日志已清空")
        }
    }

    private val binder = LocalBinder()
    private val state = MutableStateFlow(EngineStatus(EngineStatus.Phase.IDLE, "引擎尚未启动"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val policy = EngineRestartPolicy()
    private val logStore by lazy { EngineLogStore(File(filesDir, "logs/engine.log")) }
    private val logTail = ArrayDeque<String>()
    private val stampFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var engineJob: Job? = null
    private var engine: Process? = null
    private var bridgeServer: DeviceBridgeServer? = null

    @Volatile private var stopRequested = false
    @Volatile private var restartRequested = false
    @Volatile private var safeModeRequested = false
    @Volatile private var startupTimedOut = false
    @Volatile private var stopReason = "引擎已停止"
    @Volatile private var busy = false
    @Volatile private var foreground = false
    @Volatile private var lastActivityMs = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        startIdleWatchdog()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action != ACTION_START && action != ACTION_SAFE_START) return START_NOT_STICKY
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(if (action == ACTION_SAFE_START) "正在以安全模式启动 dsh" else "正在启动 dsh"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        activity()
        if (engineJob?.isActive == true) {
            if (action == ACTION_SAFE_START) {
                log("收到安全模式重启请求")
                safeModeRequested = true
                restartRequested = true
                stopReason = "正在以安全模式重启"
                stopRequested = true
                engine?.destroy()
            }
            return START_NOT_STICKY
        }
        policy.reset()
        stopRequested = false
        restartRequested = false
        stopReason = "引擎已停止"
        safeModeRequested = action == ACTION_SAFE_START
        engineJob = scope.launch { engineLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        stopReason = "服务已销毁"
        engine?.destroy()
        bridgeServer?.stop()
        bridgeServer = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun engineLoop() {
        while (true) {
            val safe = safeModeRequested
            safeModeRequested = false
            startupTimedOut = false
            val startedAt = System.currentTimeMillis()
            var exitCode: Int? = null
            var failure: String? = null
            try {
                exitCode = runAttempt(safe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            }
            val endedAt = System.currentTimeMillis()
            if (!restartRequested) {
                // The bridge only serves the engine child; do not keep the
                // loopback socket open while no engine is running.
                bridgeServer?.stop()
                bridgeServer = null
            }

            if (restartRequested) {
                restartRequested = false
                stopRequested = false
                policy.reset()
                state.value = EngineStatus(EngineStatus.Phase.STARTING, "正在重启 dsh 引擎")
                continue
            }
            if (stopRequested) {
                log("$stopReason（运行 ${elapsedSeconds(startedAt, endedAt)} 秒）")
                state.value = EngineStatus(EngineStatus.Phase.STOPPED, stopReason)
                break
            }

            val timeout = startupTimedOut
            val detail = when {
                failure != null -> failure
                timeout -> "启动超时：${BuildConfig.STARTUP_TIMEOUT_MS / 1000} 秒内未监听本地端口"
                else -> null
            }
            val report = if (detail != null) {
                EngineCrash.formatError(detail, startedAt, endedAt, tailLines())
            } else {
                EngineCrash.format(exitCode ?: -1, startedAt, endedAt, tailLines())
            }
            log(report)

            val decision = policy.onExit(endedAt, intentional = false)
            if (!decision.restart) {
                state.value = EngineStatus(
                    EngineStatus.Phase.FAILED,
                    "引擎连续异常退出 ${decision.failures} 次，已停止自动重启；请查看日志或尝试安全模式",
                )
                break
            }
            val seconds = decision.delayMs / 1000
            state.value = EngineStatus(
                EngineStatus.Phase.STARTING,
                "引擎异常退出（${detail ?: "代码 $exitCode"}），${seconds} 秒后自动重启",
            )
            log("${seconds} 秒后自动重启（连续第 ${decision.failures} 次）")
            delay(decision.delayMs)
            if (stopRequested) {
                state.value = EngineStatus(EngineStatus.Phase.STOPPED, stopReason)
                break
            }
        }
        log("服务结束：${state.value.message}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!restartRequested) stopSelf()
    }

    /** Boots one engine process and returns its exit code. */
    private suspend fun runAttempt(safe: Boolean): Int {
        state.value = EngineStatus(
            EngineStatus.Phase.STARTING,
            if (safe) "正在以安全模式启动 dsh" else "正在校验并解压 dsh 运行时",
        )
        log(if (safe) "安全模式：从随附 Web 模板重建 rescue 配置" else "启动 dsh 引擎")
        val installed = RuntimePayloadInstaller(filesDir.resolve("dsh-runtime"))
            .install(assets.open("dsh/payload.zip"), PAYLOAD_SHA256)
        val home = prepareHome(installed.resolve("dshhome"))
        val node = installed.resolve("runtime/bin/node")
        val cli = installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        val caBundle = prepareCaBundle(home)
        val settings = SecureSettings(this)
        val workspaceStore = WorkspaceStore(filesDir.resolve("dsh-home/workspaces"))
        workspaceStore.list()
        val selectedWorkspace = workspaceStore.get(settings.workspaceId())
        val temp = cacheDir.resolve("dsh").apply { check(mkdirs() || isDirectory) }
        val pluginLog = home.resolve("logs/plugins.log")
        val pluginLogOffset = pluginLog.length()
        state.value = EngineStatus(
            EngineStatus.Phase.STARTING,
            if (safe) "正在启动安全模式 Web 服务" else "正在启动 dsh Web 服务",
        )
        val bridgePatch = prepareDeviceBridge(installed, home, settings)
        val options = if (safe) {
            mutableListOf("--profile", "rescue", "--from-default-profile", "web")
        } else {
            mutableListOf("--profile", "web")
        }
        if (bridgePatch != null) {
            options += listOf("--patch", bridgePatch.absolutePath)
        }
        // Everything after the first app argument passes through verbatim, so
        // dsh options must precede --no-open.
        val appArgs = options + "--no-open"
        log("命令：node --expose-internals bin.js ${appArgs.joinToString(" ")}")
        val process = ProcessBuilder(listOf(node.path, "--expose-internals", cli.path) + appArgs)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    this["LD_LIBRARY_PATH"] = installed.resolve("runtime/lib").absolutePath
                    this["DSH_HOME"] = home.absolutePath
                    this["HOME"] = filesDir.absolutePath
                    this["DSH_WORKSPACE"] = selectedWorkspace.directory.absolutePath
                    this["OPENSSL_CONF"] = installed.resolve("runtime/etc/openssl.cnf").absolutePath
                    this["TMPDIR"] = temp.absolutePath
                    this["TERM"] = "xterm"
                    this["SHELL"] = "/system/bin/sh"
                    this["GIT_EXEC_PATH"] = installed.resolve("git/libexec/git-core").absolutePath
                    this["GIT_TEMPLATE_DIR"] = installed.resolve("git/templates").absolutePath
                    this["GIT_PAGER"] = "cat"
                    this["GIT_TERMINAL_PROMPT"] = "0"
                    this["GIT_CONFIG_NOSYSTEM"] = "1"
                    this["GIT_ATTR_NOSYSTEM"] = "1"
                    this["SHIZUKU_AVAILABLE"] = "0"
                    this["ROOT_AVAILABLE"] = "0"
                    if (caBundle != null) {
                        this["SSL_CERT_FILE"] = caBundle.absolutePath
                        this["CURL_CA_BUNDLE"] = caBundle.absolutePath
                        this["GIT_SSL_CAINFO"] = caBundle.absolutePath
                    }
                    this["PATH"] = listOf(
                        installed.resolve("bin").absolutePath,
                        installed.resolve("runtime/bin").absolutePath,
                        filesDir.resolve(".npm-global/bin").absolutePath,
                        "/system/bin",
                        "/system/xbin",
                    ).joinToString(":")
                }
            }.start()
        engine = process
        val watchdog = scope.launch {
            delay(BuildConfig.STARTUP_TIMEOUT_MS)
            if (engine === process && state.value.phase == EngineStatus.Phase.STARTING) {
                startupTimedOut = true
                state.value = EngineStatus(
                    EngineStatus.Phase.FAILED,
                    "dsh 启动超过 ${BuildConfig.STARTUP_TIMEOUT_MS / 1000} 秒；检查插件配置和本机日志后重试",
                )
                log("启动超时，终止进程")
                process.destroy()
                delay(3_000)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    log(line)
                    val tokenUrl = TOKEN_URL.find(line)?.value
                    if (tokenUrl != null && waitForLocalHost()) {
                        val failedPlugins = newStartupPluginErrors(pluginLog, pluginLogOffset)
                        state.value = if (failedPlugins) {
                            EngineStatus(
                                EngineStatus.Phase.DEGRADED,
                                "服务已监听，部分插件加载失败；请检查本机插件日志",
                                tokenUrl,
                            )
                        } else {
                            EngineStatus(EngineStatus.Phase.READY, "dsh Web 已就绪", tokenUrl)
                        }
                        log(if (failedPlugins) "服务已监听，部分插件加载失败" else "dsh Web 已就绪")
                        notify(if (failedPlugins) "dsh 正在运行（部分插件失败）" else "dsh 正在运行")
                        activity()
                    }
                }
            }
            val code = process.waitFor()
            log("dsh 进程退出，代码 $code")
            return code
        } finally {
            watchdog.cancel()
            engine = null
        }
    }

    private fun startIdleWatchdog() {
        scope.launch {
            while (isActive) {
                delay(20_000)
                val phase = state.value.phase
                if (
                    !stopRequested &&
                    com.example.adsh.engine.EngineIdlePolicy.shouldStop(
                        ready = phase == EngineStatus.Phase.READY,
                        busy = busy,
                        foreground = foreground,
                        idleMs = System.currentTimeMillis() - lastActivityMs,
                        limitMs = IDLE_STOP_MS,
                    )
                ) {
                    log("闲置 5 分钟，停止引擎以节省电量")
                    stopReason = "闲置 5 分钟，引擎已停止以节省电量"
                    stopRequested = true
                    engine?.destroy()
                }
            }
        }
    }

    private fun activity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun log(raw: String) {
        val line = raw.trimEnd().take(4_000).replace(TOKEN_SECRET, "token=***")
        if (line.isEmpty()) return
        val stamped = "${stampFormat.format(Date())} $line"
        synchronized(logTail) {
            logTail.addLast(stamped)
            while (logTail.size > LOG_TAIL) logTail.removeFirst()
        }
        logStore.append(stamped)
    }

    private fun tailLines(): List<String> = synchronized(logTail) { logTail.toList() }

    private fun elapsedSeconds(start: Long, end: Long): Long =
        TimeUnit.MILLISECONDS.toSeconds((end - start).coerceAtLeast(0))

    private suspend fun prepareDeviceBridge(
        installed: File,
        home: File,
        settings: SecureSettings,
    ): File? {
        if (!settings.deviceBridgeEnabled()) {
            bridgeServer?.stop()
            bridgeServer = null
            home.resolve(DeviceBridgeConfig.PATCH_FILE).delete()
            return null
        }
        return try {
            val directory = File(filesDir, "android-bridge").apply {
                check(mkdirs() || isDirectory) { "无法创建设备桥目录" }
            }
            val script = directory.resolve("mcp-server.mjs")
            assets.open("dsh-bridge/mcp-server.mjs").use { input ->
                script.outputStream().use { input.copyTo(it) }
            }
            val token = settings.deviceBridgeToken()
            val server = bridgeServer ?: DeviceBridgeServer(this, token).also {
                it.start()
                bridgeServer = it
            }
            val patch = DeviceBridgeConfig.writePatch(
                home = home,
                nodePath = installed.resolve("runtime/bin/node").absolutePath,
                scriptPath = script.absolutePath,
                workingDirectory = directory.absolutePath,
                url = "http://127.0.0.1:${server.port}",
                token = token,
            )
            log("设备能力桥已启用，端口 ${server.port}，工具前缀 mcp__${DeviceBridgeConfig.SERVER_NAME}__")
            patch
        } catch (error: Exception) {
            log("设备能力桥启动失败：${error.message ?: error.javaClass.simpleName}")
            bridgeServer?.stop()
            bridgeServer = null
            null
        }
    }

    private fun prepareHome(source: File): File {
        val home = filesDir.resolve("dsh-native-home")
        if (home.isDirectory) return home
        val staged = filesDir.resolve(".dsh-native-home-${UUID.randomUUID()}")
        check(source.copyRecursively(staged, overwrite = false)) { "无法初始化 dsh home" }
        if (!home.exists()) check(staged.renameTo(home)) { "无法发布 dsh home" }
        return home
    }

    private fun prepareCaBundle(home: File): File? {
        val certificates = File("/system/etc/security/cacerts").listFiles()
            ?.filter { it.isFile && it.length() in 1L..1_000_000L }
            ?.sortedBy { it.name }
            .orEmpty()
        if (certificates.isEmpty()) return null
        val bundle = home.resolve("system-ca.pem")
        bundle.outputStream().buffered().use { output ->
            certificates.forEach { file ->
                file.inputStream().use { it.copyTo(output) }
                output.write('\n'.code)
            }
        }
        return bundle
    }

    private fun waitForLocalHost(): Boolean {
        repeat(15) {
            if (checkLocalHost()) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun checkLocalHost(): Boolean = try {
        val request = URL(HOME_URL).openConnection() as HttpURLConnection
        try {
            request.connectTimeout = 2_000
            request.readTimeout = 2_000
            request.instanceFollowRedirects = false
            request.responseCode == 401 || request.responseCode == 200
        } finally {
            request.disconnect()
        }
    } catch (_error: Exception) {
        false
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "dsh 引擎", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val reopen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("深度求索助手")
            .setContentText(text)
            .setContentIntent(reopen)
            .setOngoing(true)
            .build()
    }
}
