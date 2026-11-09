package com.example.adsh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

data class VoiceResult(val text: String, val isFinal: Boolean)

/**
 * Voice input facade; callbacks fire on the main thread.
 *
 * Engine selection:
 * - `auto` prefers the bundled sherpa-onnx streaming model for Mandarin (local, low latency),
 *   falls back to the platform recognizer and then the Vosk model.
 * - `sherpa` forces the bundled enhanced model (Mandarin only).
 * - `offline` forces the bundled Vosk model (Mandarin only).
 * - `system` forces the platform recognizer (Mandarin or Cantonese).
 */
class VoiceInputController(
    context: Context,
    private val languageProvider: () -> String = { "zh-CN" },
    private val engineProvider: () -> String = { "auto" },
    private val itnProvider: () -> Boolean = { true },
    private val secondPassProvider: () -> Boolean = { false },
) {
    private enum class Engine { SHERPA, OFFLINE, SYSTEM, NONE }

    private val appContext = context.applicationContext
    private val sherpa = SherpaSpeechRecognizer(appContext)
    private val offline = OfflineSpeechRecognizer(appContext)
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var active = Engine.NONE

    var onResult: ((VoiceResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** 0..1 microphone level for the listening animation. */
    var onLevel: ((Float) -> Unit)? = null

    private fun language(): String = when (languageProvider()) {
        "zh-HK", "yue-HK", "yue" -> "zh-HK"
        else -> "zh-CN"
    }

    private fun resolveEngine(): Engine {
        val preference = engineProvider()
        val system = SpeechRecognizer.isRecognitionAvailable(appContext)
        val mandarin = language() == "zh-CN"
        val sherpaReady = mandarin && sherpa.assetsInstalled()
        val offlineReady = mandarin && offline.assetsInstalled()
        return when {
            preference == "sherpa" && sherpaReady -> Engine.SHERPA
            preference == "offline" && offlineReady -> Engine.OFFLINE
            preference == "system" && system -> Engine.SYSTEM
            preference == "auto" && sherpaReady -> Engine.SHERPA
            preference == "auto" && system -> Engine.SYSTEM
            sherpaReady -> Engine.SHERPA
            offlineReady -> Engine.OFFLINE
            system -> Engine.SYSTEM
            else -> Engine.NONE
        }
    }

    /** True when the selected language/engine combination can recognize speech. */
    fun available(): Boolean = resolveEngine() != Engine.NONE

    /** True when the current/last session used the Vosk engine (diagnostics). */
    fun usingOfflineEngine(): Boolean = active == Engine.OFFLINE

    fun isListening(): Boolean = running

    /** Warm up the enhanced model in the background so the first hold-to-talk is responsive. */
    fun prepare() {
        if (resolveEngine() == Engine.SHERPA) {
            sherpa.setSecondPassEnabled(secondPassProvider())
            sherpa.prepare()
        }
    }

    /** True when the optional second-pass model is bundled. */
    fun secondPassAvailable(): Boolean = sherpa.secondPassInstalled()

    fun start(autoStop: Boolean = false) {
        when (resolveEngine()) {
            Engine.NONE -> {
                onError?.invoke(
                    if (language() == "zh-HK") {
                        "粤语识别需要系统语音服务，当前设备未提供"
                    } else {
                        "当前设备没有可用的语音识别服务"
                    },
                )
                return
            }
            Engine.SHERPA -> startSherpa(autoStop)
            Engine.OFFLINE -> startOffline()
            Engine.SYSTEM -> startSystem()
        }
    }

    private fun startSherpa(autoStop: Boolean) {
        releaseRecognizer()
        active = Engine.SHERPA
        sherpa.setSecondPassEnabled(secondPassProvider())
        sherpa.onListeningChanged = { listening -> setListening(listening) }
        sherpa.onPartial = { text ->
            if (text.isNotBlank()) onResult?.invoke(VoiceResult(text, isFinal = false))
        }
        sherpa.onFinal = { text ->
            setListening(false)
            onLevel?.invoke(0f)
            val final = normalizeFinal(text)
            if (final.isBlank()) onError?.invoke("没有识别到语音内容")
            else onResult?.invoke(VoiceResult(final, isFinal = true))
        }
        sherpa.onError = { message ->
            setListening(false)
            onLevel?.invoke(0f)
            onError?.invoke(message)
        }
        sherpa.onLevel = { level -> onLevel?.invoke(level) }
        setListening(true)
        sherpa.start(autoStop)
    }

    private fun startOffline() {
        releaseRecognizer()
        active = Engine.OFFLINE
        offline.onListeningChanged = { listening -> setListening(listening) }
        offline.onPartial = { text ->
            if (text.isNotBlank()) onResult?.invoke(VoiceResult(text, isFinal = false))
        }
        offline.onFinal = { text ->
            setListening(false)
            onLevel?.invoke(0f)
            val final = normalizeFinal(text)
            if (final.isBlank()) onError?.invoke("没有识别到语音内容")
            else onResult?.invoke(VoiceResult(final, isFinal = true))
        }
        offline.onError = { message ->
            setListening(false)
            onLevel?.invoke(0f)
            onError?.invoke(message)
        }
        offline.onLevel = { level -> onLevel?.invoke(level) }
        setListening(true)
        offline.start()
    }

    private fun startSystem() {
        releaseRecognizer()
        active = Engine.SYSTEM
        val language = language()
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = setListening(false)

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { onResult?.invoke(VoiceResult(it, isFinal = false)) }
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                onLevel?.invoke(0f)
                val text = firstResult(results)?.let(::normalizeFinal)
                if (text.isNullOrBlank()) onError?.invoke("没有识别到语音内容")
                else onResult?.invoke(VoiceResult(text, isFinal = true))
            }

            override fun onError(error: Int) {
                setListening(false)
                onLevel?.invoke(0f)
                onError?.invoke(messageFor(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setListening(true)
        created.startListening(intent)
    }

    fun stop() {
        if (!running) return
        when (active) {
            Engine.SHERPA -> sherpa.stop()
            Engine.OFFLINE -> offline.stop()
            Engine.SYSTEM -> recognizer?.stopListening()
            Engine.NONE -> Unit
        }
        setListening(false)
        onLevel?.invoke(0f)
    }

    fun cancel() {
        when (active) {
            Engine.SHERPA -> sherpa.cancel()
            Engine.OFFLINE -> offline.cancel()
            Engine.SYSTEM -> runCatching { recognizer?.cancel() }
            Engine.NONE -> Unit
        }
        setListening(false)
        onLevel?.invoke(0f)
    }

    fun release() {
        setListening(false)
        sherpa.onPartial = null
        sherpa.onFinal = null
        sherpa.onError = null
        sherpa.onListeningChanged = null
        sherpa.onLevel = null
        sherpa.release()
        offline.onPartial = null
        offline.onFinal = null
        offline.onError = null
        offline.onListeningChanged = null
        offline.onLevel = null
        offline.release()
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun setListening(value: Boolean) {
        if (running == value) return
        running = value
        onListeningChanged?.invoke(value)
    }

    private fun normalizeFinal(text: String): String =
        if (itnProvider()) SpeechTextNormalizer.normalize(text) else text

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风是否被占用"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别已取消"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务返回错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音输入"
        else -> "语音识别失败（错误码 $error）"
    }
}

/** TextToSpeech helper that waits for engine init and chunks long replies. */
class ReplySpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (ready) {
            speakNow(clean)
            return
        }
        pending = clean
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    tts?.setSpeechRate(1.0f)
                    pending?.let {
                        pending = null
                        speakNow(it)
                    }
                } else {
                    pending = null
                }
            }
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        val limit = 3_000
        if (text.length <= limit) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply-${System.nanoTime()}")
            return
        }
        var offset = 0
        var first = true
        while (offset < text.length) {
            val end = minOf(offset + limit, text.length)
            val chunk = text.substring(offset, end)
            engine.speak(
                chunk,
                if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "reply-${System.nanoTime()}-$offset",
            )
            first = false
            offset = end
        }
    }
}
