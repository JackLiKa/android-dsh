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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming Mandarin recognition backed by sherpa-onnx (Apache-2.0).
 *
 * A Zipformer streaming transducer produces partial results while the user speaks, Silero VAD
 * provides hands-free end-pointing, and a CT-Transformer punctuation model is applied once to the
 * final text. Hotwords are one phrase per line in cjkchar form and bias the modified beam search
 * toward app names, cities and commands.
 *
 * The models are loaded straight from `assets/sherpa` through AssetManager, so nothing is copied to
 * app storage. `androidResources.noCompress` keeps the ONNX files stored in the APK, which
 * AAsset_getBuffer requires.
 */
class SherpaSpeechRecognizer(context: Context) {
    companion object {
        private const val TAG = "SherpaSpeechRecognizer"
        private const val ASSET_ROOT = "sherpa"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600 // ~100 ms
        private const val CHUNK_MS = CHUNK_SAMPLES * 1000 / SAMPLE_RATE
        private const val ENDPOINT_SILENCE_MS = 500
        private const val MIN_SPEECH_MS = 300
        private const val NO_SPEECH_TIMEOUT_MS = 8_000
        private const val HOTWORDS = "$ASSET_ROOT/hotwords.txt"
        private const val ENCODER = "$ASSET_ROOT/model/encoder.int8.onnx"
        private const val DECODER = "$ASSET_ROOT/model/decoder.onnx"
        private const val JOINER = "$ASSET_ROOT/model/joiner.int8.onnx"
        private const val TOKENS = "$ASSET_ROOT/model/tokens.txt"
        private const val VAD_MODEL = "$ASSET_ROOT/vad/silero_vad.int8.onnx"
        private const val PUNCT_MODEL = "$ASSET_ROOT/punct/model.int8.onnx"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adsh-sherpa-loader").apply { isDaemon = true }
    }

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var punctuation: OfflinePunctuation? = null
    @Volatile private var vad: Vad? = null
    @Volatile private var hotwordsText: String = ""
    @Volatile private var loading = false
    @Volatile private var playing = false
    @Volatile private var recording = false
    @Volatile private var startRequested = false
    @Volatile private var cancelled = false
    @Volatile private var autoStop = false
    @Volatile private var timedOut = false
    @Volatile private var lastPartial: String = ""
    @Volatile private var lastError: String? = null
    @Volatile private var lastPartials: List<String> = emptyList()

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var sessionThread: Thread? = null
    private val pendingReady = mutableListOf<(Boolean) -> Unit>()

    /** Fires on the main thread. [onLevel] is a 0..1 normalized microphone level. */
    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null

    /** Bundled models present in APK assets (decides whether this engine can be used). */
    fun assetsInstalled(): Boolean = runCatching {
        val modelFiles = appContext.assets.list("$ASSET_ROOT/model").orEmpty().toSet()
        val vadFiles = appContext.assets.list("$ASSET_ROOT/vad").orEmpty().toSet()
        setOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
            .all { it in modelFiles } && "silero_vad.int8.onnx" in vadFiles
    }.getOrDefault(false)

    fun isModelLoaded(): Boolean = recognizer != null

    fun isListening(): Boolean = playing

    fun isBusyLoading(): Boolean = loading

    /** Load the models in the background; [onReady] runs on the main thread. */
    fun prepare(onReady: ((Boolean) -> Unit)? = null) {
        if (recognizer != null) {
            onReady?.let { mainHandler.post { it(true) } }
            return
        }
        if (loading) {
            onReady?.let { callback ->
                synchronized(pendingReady) { pendingReady += callback }
            }
            return
        }
        loading = true
        worker.execute {
            val ok = runCatching { loadModels() }.onFailure {
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

    /**
     * Start microphone recognition. [autoStop] enables VAD end-pointing for hands-free use;
     * hold-to-talk leaves it off and finishes on [stop].
     */
    fun start(autoStop: Boolean = false) {
        if (playing) return
        if (!assetsInstalled()) {
            mainHandler.post { onError?.invoke("本地增强语音模型缺失，无法启动识别") }
            return
        }
        this.autoStop = autoStop
        timedOut = false
        startRequested = true
        cancelled = false
        mainHandler.post { setListening(true) }
        prepare { ready ->
            if (!ready) {
                startRequested = false
                mainHandler.post {
                    setListening(false)
                    onError?.invoke(lastError ?: "本地增强语音模型加载失败")
                }
                return@prepare
            }
            if (!startRequested) {
                setListening(false)
                return@prepare
            }
            worker.execute {
                if (!startRequested) {
                    setListening(false)
                    return@execute
                }
                val started = runCatching { startSession() }
                    .onFailure { Log.w(TAG, "start failed", it) }
                    .isSuccess
                if (!started) {
                    startRequested = false
                    mainHandler.post {
                        setListening(false)
                        onError?.invoke(lastError ?: "无法启动麦克风录音，请检查麦克风是否被占用")
                    }
                }
            }
        }
    }

    /** Stop recording; the final text (possibly empty) arrives on the main thread. */
    fun stop() {
        startRequested = false
        autoStop = false
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
        autoStop = false
        recording = false
        setListening(false)
    }

    fun release() {
        cancelled = true
        startRequested = false
        autoStop = false
        recording = false
        sessionThread?.let { runCatching { it.join(3_000) } }
        sessionThread = null
        worker.execute {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            releaseAudioEffects()
            runCatching { vad?.release() }
            vad = null
            runCatching { punctuation?.release() }
            punctuation = null
            runCatching { recognizer?.release() }
            recognizer = null
            hotwordsText = ""
            lastPartial = ""
        }
    }

    /**
     * Decode raw 16 kHz mono PCM (little-endian shorts) without touching the microphone.
     * Used by instrumented tests and diagnostic tooling.
     *
     * @return final recognized text (possibly empty), or null when the model is still loading.
     */
    fun decodePcm(samples: ShortArray, chunkSize: Int = CHUNK_SAMPLES): String? {
        val local = recognizer ?: run {
            prepare()
            return null
        }
        val stream = local.createStream(hotwordsText)
        val floats = FloatArray(chunkSize)
        val partials = mutableListOf<String>()
        var previous = ""
        try {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(chunkSize, samples.size - offset)
                for (index in 0 until count) floats[index] = samples[offset + index] / 32768f
                // Second argument is the sample rate, not the sample count.
                val chunk = if (count == chunkSize) floats else floats.copyOf(count)
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                while (local.isReady(stream)) local.decode(stream)
                val partial = formatText(local.getResult(stream).text)
                if (partial.isNotBlank() && partial != previous) {
                    previous = partial
                    partials += partial
                }
                offset += count
            }
            stream.inputFinished()
            while (local.isReady(stream)) local.decode(stream)
            lastPartials = partials
            return punctuate(local.getResult(stream).text)
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Partials observed during the most recent [decodePcm] call (test/diagnostic helper). */
    fun lastPartials(): List<String> = lastPartials

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (recording) return
        val local = recognizer ?: error("model missing")
        val stream = local.createStream(hotwordsText)
        var record: AudioRecord? = null
        try {
            vad?.reset()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = max(minBuffer, CHUNK_SAMPLES * 2 * 4)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("无法初始化麦克风，请检查录音权限或麦克风占用")
            }
            audioRecord = record
            createAudioEffects(record)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("麦克风没有开始录音，请检查是否被其他应用占用")
            }
            lastPartial = ""
            timedOut = false
            recording = true
            if (!startRequested) {
                recording = false
                runCatching { record.stop() }
                runCatching { record.release() }
                if (audioRecord === record) audioRecord = null
                releaseAudioEffects()
                stream.release()
                setListening(false)
                return
            }
            val active = record
            sessionThread = Thread({ recordLoop(local, stream, active) }, "adsh-sherpa-audio").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            if (audioRecord === record) audioRecord = null
            releaseAudioEffects()
            runCatching { stream.release() }
            recording = false
            throw error
        }
    }

    private fun recordLoop(local: OnlineRecognizer, stream: OnlineStream, record: AudioRecord) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        val floats = FloatArray(CHUNK_SAMPLES)
        var heardSpeech = false
        var speechMs = 0
        var silenceMs = 0
        var elapsedMs = 0
        try {
            while (recording && !cancelled) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw IllegalStateException("麦克风读取失败（$read）")
                if (read == 0) continue
                for (index in 0 until read) floats[index] = buffer[index] / 32768f
                val chunk = if (read == CHUNK_SAMPLES) floats else floats.copyOf(read)
                postLevel(rmsLevel(buffer, read))
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                while (local.isReady(stream)) local.decode(stream)
                val text = formatText(local.getResult(stream).text)
                if (text.isNotBlank() && text != lastPartial) {
                    lastPartial = text
                    postPartial(text)
                }
                elapsedMs += CHUNK_MS
                val detector = vad
                if (autoStop && detector != null) {
                    detector.acceptWaveform(chunk)
                    while (!detector.empty()) detector.pop()
                    if (detector.isSpeechDetected()) {
                        heardSpeech = true
                        speechMs += CHUNK_MS
                        silenceMs = 0
                    } else if (heardSpeech) {
                        silenceMs += CHUNK_MS
                    }
                    if (heardSpeech && speechMs >= MIN_SPEECH_MS && silenceMs >= ENDPOINT_SILENCE_MS) {
                        recording = false
                    } else if (!heardSpeech && elapsedMs >= NO_SPEECH_TIMEOUT_MS) {
                        timedOut = true
                        recording = false
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
            finishSession(local, stream, record)
        }
    }

    private fun finishSession(local: OnlineRecognizer, stream: OnlineStream, record: AudioRecord) {
        runCatching { record.stop() }
        runCatching { record.release() }
        if (audioRecord === record) audioRecord = null
        releaseAudioEffects()
        val raw = runCatching {
            stream.inputFinished()
            while (local.isReady(stream)) local.decode(stream)
            local.getResult(stream).text
        }.getOrDefault("")
        runCatching { stream.release() }
        setListening(false)
        postLevel(0f)
        lastPartial = ""
        if (cancelled) {
            cancelled = false
            return
        }
        val text = punctuate(raw)
        if (text.isBlank()) {
            postError(if (timedOut) "没有检测到语音输入" else "没有识别到语音内容")
        } else {
            postFinal(text)
        }
    }

    private fun createAudioEffects(record: AudioRecord) {
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
    }

    private fun releaseAudioEffects() {
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { gainControl?.release() }
        gainControl = null
    }

    private fun loadModels() {
        if (!assetsInstalled()) throw IllegalStateException("本地增强语音模型缺失")
        // The model's token table only covers a subset of characters; a hotword containing any
        // unknown character makes sherpa skip *all* hotwords, so filter them up front.
        val tokens = runCatching {
            appContext.assets.open(TOKENS).bufferedReader().useLines { lines ->
                lines.map { it.substringBefore(' ').trim() }
                    .filter { it.isNotEmpty() }
                    .toHashSet()
            }
        }.getOrDefault(emptySet())
        hotwordsText = runCatching {
            appContext.assets.open(HOTWORDS).bufferedReader().use { it.readText() }
        }.getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    (tokens.isEmpty() || line.all { it.toString() in tokens })
            }
            .joinToString("\n")

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ENCODER,
                    decoder = DECODER,
                    joiner = JOINER,
                ),
                tokens = TOKENS,
                numThreads = 2,
                provider = "cpu",
                // Required for hotword encoding: this model's tokens are one CJK character each.
                modelingUnit = "cjkchar",
            ),
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
            enableEndpoint = false,
        )
        val loaded = OnlineRecognizer(appContext.assets, config)
        recognizer = loaded

        vad = runCatching {
            Vad(
                appContext.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VAD_MODEL,
                        threshold = 0.5f,
                        minSilenceDuration = 0.45f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        }.onFailure { Log.w(TAG, "Silero VAD unavailable", it) }.getOrNull()

        punctuation = runCatching {
            OfflinePunctuation(
                appContext.assets,
                OfflinePunctuationConfig(
                    model = OfflinePunctuationModelConfig(
                        ctTransformer = PUNCT_MODEL,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "punctuation model unavailable", it) }.getOrNull()

        Log.i(
            TAG,
            "sherpa models loaded (vad=${vad != null}, punctuation=${punctuation != null})",
        )
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

    private fun punctuate(raw: String): String {
        val clean = formatText(raw)
        if (clean.isEmpty()) return ""
        val engine = punctuation ?: return clean
        return runCatching { formatText(engine.addPunctuation(clean)) }.getOrDefault(clean)
    }

    private fun postPartial(text: String) {
        mainHandler.post { onPartial?.invoke(text) }
    }

    private fun postFinal(text: String) {
        mainHandler.post { onFinal?.invoke(text) }
    }

    private fun postError(message: String) {
        mainHandler.post { onError?.invoke(message) }
    }

    private fun postLevel(level: Float) {
        mainHandler.post { onLevel?.invoke(level) }
    }

    private fun setListening(value: Boolean) {
        playing = value
        mainHandler.post { onListeningChanged?.invoke(value) }
    }
}
