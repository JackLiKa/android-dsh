package com.example.adsh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/**
 * Offline Chinese speech recognition backed by Vosk (Apache-2.0).
 *
 * The acoustic model ships as Android assets under `assets/vosk/model-cn/` and is copied once into
 * `filesDir/vosk/model-cn` before `org.vosk.Model` is loaded (Vosk requires a real directory path).
 * Recognition runs fully on device: no network, no cloud speech service.
 */
class OfflineSpeechRecognizer(context: Context) {
    companion object {
        private const val TAG = "OfflineSpeechRecognizer"
        private const val ASSET_ROOT = "vosk"
        private const val MODEL_NAME = "model-cn"
        private const val MODEL_MARKER = "am/final.mdl"
        private const val SAMPLE_RATE = 16_000f
        private const val CHUNK_SAMPLES = 1_600 // ~100 ms
        const val MODEL_ASSET_PATH = "$ASSET_ROOT/$MODEL_NAME"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adsh-vosk-loader").apply { isDaemon = true }
    }

    @Volatile private var model: Model? = null
    @Volatile private var loading = false
    @Volatile private var playing = false
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var lastText: String = ""

    /** Fires on the main thread. */
    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** Bundled model present in APK assets (decides whether this engine can be used). */
    fun assetsInstalled(): Boolean = runCatching {
        appContext.assets.list("$MODEL_ASSET_PATH/am")?.contains("final.mdl") == true
    }.getOrDefault(false)

    fun isModelLoaded(): Boolean = model != null

    fun isListening(): Boolean = playing

    /** True once [Model] is loaded (or the load is in flight). */
    fun isBusyLoading(): Boolean = loading

    /**
     * Copy the asset model (first run only) and load it in the background.
     * [onReady] is invoked on the main thread with `true` when the model is usable.
     */
    fun prepare(onReady: ((Boolean) -> Unit)? = null) {
        if (model != null) {
            onReady?.let { mainHandler.post { it(true) } }
            return
        }
        if (loading) {
            onReady?.let { callback ->
                pendingReady.add(callback)
            }
            return
        }
        loading = true
        worker.execute {
            val ok = runCatching { loadModel() }.onFailure {
                Log.w(TAG, "prepare failed", it)
                lastError = it.message ?: it.javaClass.simpleName
            }.isSuccess
            loading = false
            val callbacks = synchronized(pendingReady) {
                val copy = pendingReady.toList()
                pendingReady.clear()
                copy
            }
            mainHandler.post {
                onReady?.invoke(ok)
                callbacks.forEach { it(ok) }
            }
        }
    }

    /** Start microphone recognition. Model loading (if needed) happens before recording starts. */
    fun start() {
        if (playing) return
        if (!assetsInstalled()) {
            mainHandler.post { onError?.invoke("离线语音模型缺失，无法启动识别") }
            return
        }
        mainHandler.post { setListening(true) }
        prepare { ready ->
            if (!ready) {
                mainHandler.post {
                    setListening(false)
                    onError?.invoke(lastError ?: "离线语音模型加载失败")
                }
                return@prepare
            }
            worker.execute {
                val started = runCatching { startService() }
                    .onFailure { Log.w(TAG, "start failed", it) }
                    .isSuccess
                if (!started) {
                    mainHandler.post {
                        setListening(false)
                        onError?.invoke(lastError ?: "无法启动麦克风录音，请检查麦克风是否被占用")
                    }
                }
            }
        }
    }

    /** Stop recording; Vosk then emits its final result (possibly empty) on the main thread. */
    fun stop() {
        service?.let { active -> worker.execute { runCatching { active.stop() } } }
        setListening(false)
    }

    fun cancel() {
        service?.let { active -> worker.execute { runCatching { active.cancel() } } }
        setListening(false)
    }

    fun release() {
        stop()
        worker.execute {
            runCatching { service?.cancel() }
            runCatching { service?.shutdown() }
            service = null
            runCatching { recognizer?.close() }
            recognizer = null
            runCatching { model?.close() }
            model = null
            lastText = ""
        }
    }

    /**
     * Decode raw 16 kHz mono PCM (little-endian shorts) without touching the microphone.
     * Used by instrumented tests and future "voice self-test" tooling.
     *
     * @return final recognized text (may be empty), or null when the model is unavailable.
     */
    fun decodePcm(samples: ShortArray, chunkSize: Int = CHUNK_SAMPLES): String? {
        val loaded = model ?: run {
            prepare()
            return null
        }
        val local = runCatching { Recognizer(loaded, SAMPLE_RATE) }.getOrNull() ?: return null
        var offset = 0
        val partials = mutableListOf<String>()
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            val chunk = samples.copyOfRange(offset, end)
            val endOfUtterance = runCatching { local.acceptWaveForm(chunk, chunk.size) }.getOrDefault(false)
            if (!endOfUtterance) {
                textOf(local.partialResult, "partial")?.takeIf { it.isNotBlank() }?.let { partials += it }
            }
            offset = end
        }
        val final = textOf(runCatching { local.finalResult }.getOrNull(), "text").orEmpty()
        lastPartials = partials
        runCatching { local.close() }
        return final
    }

    @Volatile private var lastPartials: List<String> = emptyList()

    /** Partials observed during the most recent [decodePcm] call (test/diagnostic helper). */
    fun lastPartials(): List<String> = lastPartials

    private fun startService() {
        if (service != null) return
        val current = model ?: error("model missing")
        val created = Recognizer(current, SAMPLE_RATE)
        recognizer = created
        val speech = SpeechService(created, SAMPLE_RATE)
        service = speech
        lastText = ""
        speech.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val text = textOf(hypothesis, "partial") ?: return
                if (text.isNotBlank()) {
                    lastText = text
                    onPartial?.invoke(text)
                }
            }

            override fun onResult(hypothesis: String?) {
                // Vosk emits a result after a silence: keep listening and update the draft text.
                val text = textOf(hypothesis, "text") ?: return
                if (text.isNotBlank()) {
                    lastText = text
                    onPartial?.invoke(text)
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                setListening(false)
                val text = textOf(hypothesis, "text").orEmpty().ifBlank { lastText }
                if (text.isBlank()) onError?.invoke("没有识别到语音内容")
                else onFinal?.invoke(text)
            }

            override fun onError(exception: Exception?) {
                setListening(false)
                onError?.invoke(
                    when {
                        exception == null -> "语音识别失败"
                        exception.message?.contains("Microphone", ignoreCase = true) == true ->
                            "无法启动麦克风录音，请检查麦克风是否被占用"
                        else -> "语音识别失败：${exception.message ?: exception.javaClass.simpleName}"
                    },
                )
            }

            override fun onTimeout() {
                setListening(false)
                val text = lastText
                if (text.isBlank()) onError?.invoke("没有检测到语音输入") else onFinal?.invoke(text)
            }
        })
    }

    @Volatile private var lastError: String? = null
    private val pendingReady = mutableListOf<(Boolean) -> Unit>()

    private fun loadModel(): Boolean {
        val root = File(appContext.filesDir, "vosk")
        val target = File(root, MODEL_NAME)
        if (!File(target, MODEL_MARKER).isFile) {
            val staging = File(root, "$MODEL_NAME.staging")
            staging.deleteRecursively()
            staging.mkdirs()
            copyAssetTree(MODEL_ASSET_PATH, staging)
            check(File(staging, MODEL_MARKER).isFile) { "模型文件不完整：缺少 $MODEL_MARKER" }
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        }
        val loaded = Model(target.absolutePath)
        model = loaded
        Log.i(TAG, "Vosk model loaded from ${target.absolutePath}")
        return true
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(target, child))
    }

    private fun setListening(value: Boolean) {
        playing = value
        mainHandler.post { onListeningChanged?.invoke(value) }
    }

    private fun textOf(json: String?, key: String): String? = runCatching {
        JSONObject(json ?: return null).optString(key).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
