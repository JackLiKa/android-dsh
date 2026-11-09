package com.example.adsh

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

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
    @Volatile private var recording = false
    @Volatile private var startRequested = false
    @Volatile private var cancelled = false
    @Volatile private var lastText: String = ""
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var sessionThread: Thread? = null

    /** Fires on the main thread. [onLevel] is a 0..1 normalized microphone level. */
    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null

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
        startRequested = true
        cancelled = false
        mainHandler.post { setListening(true) }
        prepare { ready ->
            if (!ready) {
                startRequested = false
                mainHandler.post {
                    setListening(false)
                    onError?.invoke(lastError ?: "离线语音模型加载失败")
                }
                return@prepare
            }
            if (!startRequested) {
                setListening(false)
                return@prepare
            }
            worker.execute {
                val started = runCatching { startSession() }
                    .onFailure { Log.w(TAG, "start failed", it) }
                    .isSuccess
                if (!started) {
                    startRequested = false
                    mainHandler.post {
                        setListening(false)
                        onError?.invoke(lastError ?: "无法启动麦克风录音，请检查麦克风是否被占用")
                    }
                } else if (!startRequested) {
                    recording = false
                }
            }
        }
    }

    /** Stop recording; the final text (possibly empty) arrives on the main thread. */
    fun stop() {
        startRequested = false
        if (!recording) {
            if (playing) setListening(false)
            return
        }
        recording = false
        setListening(false)
    }

    fun cancel() {
        cancelled = true
        startRequested = false
        recording = false
        setListening(false)
    }

    fun release() {
        cancelled = true
        startRequested = false
        recording = false
        sessionThread?.let { runCatching { it.join(1_500) } }
        sessionThread = null
        worker.execute {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { noiseSuppressor?.release() }
            noiseSuppressor = null
            runCatching { gainControl?.release() }
            gainControl = null
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

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (recording) return
        val current = model ?: error("model missing")
        val created = Recognizer(current, SAMPLE_RATE)
        recognizer = created
        cancelled = false
        lastText = ""
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = max(minBuffer, CHUNK_SAMPLES * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("无法初始化麦克风，请检查录音权限或麦克风占用")
        }
        audioRecord = record
        runCatching {
            NoiseSuppressor.create(record.audioSessionId)?.also {
                it.enabled = true
                noiseSuppressor = it
            }
            AutomaticGainControl.create(record.audioSessionId)?.also {
                it.enabled = true
                gainControl = it
            }
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audioRecord = null
            throw IllegalStateException("麦克风没有开始录音，请检查是否被其他应用占用")
        }
        recording = true
        sessionThread = Thread({ recordLoop(created, record) }, "adsh-vosk-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun recordLoop(local: Recognizer, record: AudioRecord) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        val segments = StringBuilder()
        try {
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw IllegalStateException("麦克风读取失败（$read）")
                if (read == 0) continue
                postLevel(rmsLevel(buffer, read))
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val endOfUtterance = runCatching { local.acceptWaveForm(chunk, read) }
                    .getOrDefault(false)
                if (endOfUtterance) {
                    textOf(local.result, "text")?.let { text ->
                        if (segments.isNotEmpty()) segments.append(' ')
                        segments.append(text)
                    }
                    lastText = segments.toString()
                    if (lastText.isNotBlank()) postPartial(lastText)
                } else {
                    textOf(local.partialResult, "partial")?.let { text ->
                        lastText = if (segments.isEmpty()) text else "$segments $text"
                        postPartial(lastText)
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "audio session failed", error)
            if (!cancelled) {
                postError(
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "语音识别失败，请检查麦克风是否被占用",
                )
            }
        } finally {
            finishSession(local, record, segments.toString())
        }
    }

    private fun finishSession(local: Recognizer, record: AudioRecord, segments: String) {
        runCatching { record.stop() }
        runCatching { record.release() }
        if (audioRecord === record) audioRecord = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { gainControl?.release() }
        gainControl = null
        val trailing = runCatching { textOf(local.finalResult, "text") }.getOrNull()
        runCatching { local.close() }
        if (recognizer === local) recognizer = null
        setListening(false)
        if (cancelled) {
            cancelled = false
            return
        }
        val text = listOf(segments, trailing.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { lastText }
        if (text.isBlank()) postError("没有识别到语音内容") else postFinal(text)
    }

    private fun rmsLevel(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (index in 0 until count) {
            val value = buffer[index].toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / count)
        val decibels = 20.0 * log10(max(rms, 1.0) / 32768.0)
        return ((decibels + 55.0) / 45.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun formatText(raw: String): String = raw
        .replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun postPartial(text: String) {
        val formatted = formatText(text)
        mainHandler.post { onPartial?.invoke(formatted) }
    }

    private fun postFinal(text: String) {
        val formatted = formatText(text)
        mainHandler.post { onFinal?.invoke(formatted) }
    }

    private fun postError(message: String) {
        mainHandler.post { onError?.invoke(message) }
    }

    private fun postLevel(level: Float) {
        mainHandler.post { onLevel?.invoke(level) }
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
