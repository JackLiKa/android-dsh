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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class EngineStatus(val phase: Phase, val message: String, val url: String? = null) {
    enum class Phase { IDLE, STARTING, READY, DEGRADED, FAILED, STOPPED }
}

fun engineExited(current: EngineStatus, code: Int): EngineStatus =
    if (current.phase == EngineStatus.Phase.FAILED) current
    else EngineStatus(EngineStatus.Phase.STOPPED, "dsh 已退出（代码 $code）")

class DshEngineService : Service() {
    companion object {
        const val ACTION_START = "com.example.adsh.START_DSH"
        const val PAYLOAD_SHA256 = "6f5003bd7729135683460d9d85cb0c1b2673dbfea6584046a0de8d6968345fb7"
        private const val CHANNEL = "adsh-engine"
        private const val NOTIFICATION_ID = 3080
        private const val HOME_URL = "http://127.0.0.1:3080/"
        private val TOKEN_URL = Regex("http://127\\.0\\.0\\.1:3080/\\?token=[^\\s\"']+")
    }

    inner class LocalBinder : Binder() {
        val status: StateFlow<EngineStatus> get() = state
    }

    private val binder = LocalBinder()
    private val state = MutableStateFlow(EngineStatus(EngineStatus.Phase.IDLE, "引擎尚未启动"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startup: Job? = null
    private var engine: Process? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID, notification("正在启动 dsh"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (startup?.isActive != true && engine?.isAlive != true) {
            startup = scope.launch { startEngine() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startEngine() {
        state.value = EngineStatus(EngineStatus.Phase.STARTING, "正在校验并解压 dsh 运行时")
        try {
            val installed = RuntimePayloadInstaller(filesDir.resolve("dsh-runtime"))
                .install(assets.open("dsh/payload.zip"), PAYLOAD_SHA256)
            val home = prepareHome(installed.resolve("dshhome"))
            val node = installed.resolve("runtime/bin/node")
            val cli = installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            val caBundle = prepareCaBundle(home)
            val settings = SecureSettings(this)
            val apiKey = settings.apiKey()
            val workspaceStore = WorkspaceStore(filesDir.resolve("dsh-home/workspaces"))
            workspaceStore.list()
            val selectedWorkspace = workspaceStore.get(settings.workspaceId())
            val temp = cacheDir.resolve("dsh").apply { check(mkdirs() || isDirectory) }
            val pluginLog = home.resolve("logs/plugins.log")
            val pluginLogOffset = pluginLog.length()
            state.value = EngineStatus(EngineStatus.Phase.STARTING, "正在启动 dsh Web 服务")
            val process = ProcessBuilder(node.path, "--expose-internals", cli.path,
                "--profile", "web", "--no-open")
                .directory(home)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        this["LD_LIBRARY_PATH"] = installed.resolve("runtime/lib").absolutePath
                        this["DSH_HOME"] = home.absolutePath
                        this["HOME"] = filesDir.absolutePath
                        this["DSH_WORKSPACE"] = selectedWorkspace.directory.absolutePath
                        if (!apiKey.isNullOrBlank()) this["DEEPSEEK_API_KEY"] = apiKey
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
                delay(75_000)
                if (engine === process && state.value.phase == EngineStatus.Phase.STARTING) {
                    state.value = EngineStatus(EngineStatus.Phase.FAILED,
                        "dsh 启动超过 75 秒；检查插件配置和本机日志后重试")
                    process.destroy()
                    delay(3_000)
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tokenUrl = TOKEN_URL.find(line)?.value
                        if (tokenUrl != null && waitForLocalHost()) {
                            val failedPlugins = newStartupPluginErrors(pluginLog, pluginLogOffset)
                            state.value = if (failedPlugins) {
                                EngineStatus(EngineStatus.Phase.DEGRADED,
                                    "服务已监听，部分插件加载失败；请检查本机插件日志", tokenUrl)
                            } else {
                                EngineStatus(EngineStatus.Phase.READY, "dsh Web 已就绪", tokenUrl)
                            }
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                                .notify(NOTIFICATION_ID, notification("dsh 正在运行"))
                        }
                    }
                }
                val code = process.waitFor()
                engine = null
                state.value = engineExited(state.value, code)
            } finally {
                watchdog.cancel()
            }
        } catch (error: Exception) {
            val detail = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            if (state.value.phase != EngineStatus.Phase.FAILED) {
                state.value = EngineStatus(EngineStatus.Phase.FAILED,
                    "dsh 启动失败：${detail.takeLast(180)}")
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            "dsh 引擎", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val reopen = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("深度求索助手")
            .setContentText(text)
            .setContentIntent(reopen)
            .setOngoing(true)
            .build()
    }
}
