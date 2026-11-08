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
 * Engine order:
 * 1. Bundled offline Vosk model (`assets/vosk/model-cn`) — works without Google/system speech services,
 *    fully on device, streaming partial results.
 * 2. System [SpeechRecognizer] as a fallback when the offline model is not packaged in the APK.
 */
class VoiceInputController(context: Context) {
    private val appContext = context.applicationContext
    private val offline = OfflineSpeechRecognizer(appContext)
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var usingOffline = false

    var onResult: ((VoiceResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** True when the bundled offline model is present or a system recognizer exists. */
    fun available(): Boolean =
        offline.assetsInstalled() || SpeechRecognizer.isRecognitionAvailable(appContext)

    /** True when the current/last session used the offline engine (diagnostics). */
    fun usingOfflineEngine(): Boolean = usingOffline

    fun isListening(): Boolean = running

    fun start() {
        if (!available()) {
            onError?.invoke("当前设备没有可用的语音识别服务")
            return
        }
        if (offline.assetsInstalled()) startOffline() else startSystem()
    }

    private fun startOffline() {
        releaseRecognizer()
        usingOffline = true
        offline.onListeningChanged = { listening -> setListening(listening) }
        offline.onPartial = { text ->
            if (text.isNotBlank()) onResult?.invoke(VoiceResult(text, isFinal = false))
        }
        offline.onFinal = { text ->
            setListening(false)
            if (text.isBlank()) onError?.invoke("没有识别到语音内容")
            else onResult?.invoke(VoiceResult(text, isFinal = true))
        }
        offline.onError = { message ->
            setListening(false)
            onError?.invoke(message)
        }
        setListening(true)
        offline.start()
    }

    private fun startSystem() {
        releaseRecognizer()
        usingOffline = false
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = setListening(false)

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { onResult?.invoke(VoiceResult(it, isFinal = false)) }
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                val text = firstResult(results)
                if (text.isNullOrBlank()) onError?.invoke("没有识别到语音内容")
                else onResult?.invoke(VoiceResult(text, isFinal = true))
            }

            override fun onError(error: Int) {
                setListening(false)
                onError?.invoke(messageFor(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setListening(true)
        created.startListening(intent)
    }

    fun stop() {
        if (!running) return
        if (usingOffline) offline.stop() else recognizer?.stopListening()
        setListening(false)
    }

    fun release() {
        setListening(false)
        if (usingOffline) {
            offline.onPartial = null
            offline.onFinal = null
            offline.onError = null
            offline.onListeningChanged = null
            offline.release()
        }
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
