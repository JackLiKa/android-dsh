package com.example.adsh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device-level verification of the bundled Vosk offline Chinese engine.
 *
 * The model is packaged in the app APK (`assets/vosk/model-cn`) and the audio fixture lives in the
 * test APK (`androidTest/assets/test_zh.wav`), so this runs on an emulator without a microphone or
 * network by feeding PCM straight into the recognizer.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechRecognitionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    @Test
    fun bundledModelIsPackagedInApk() {
        val engine = OfflineSpeechRecognizer(targetContext)
        assertTrue("assets/vosk/model-cn 未打包进 APK", engine.assetsInstalled())
        assertTrue("VoiceInputController 应可用离线引擎", VoiceInputController(targetContext).available())
    }

    @Test
    fun loadsModelIntoPrivateStorageAndIsIdempotent() {
        val engine = OfflineSpeechRecognizer(targetContext)
        assertTrue("模型首次加载失败", prepareBlocking(engine, 240_000))
        assertTrue(engine.isModelLoaded())
        val modelDir = File(targetContext.filesDir, "vosk/model-cn")
        assertTrue("模型未复制到私有目录", File(modelDir, "am/final.mdl").isFile)
        assertTrue("模型目录为空", (modelDir.listFiles()?.size ?: 0) > 0)

        assertTrue("第二次 prepare 应直接复用已加载模型", prepareBlocking(engine, 60_000))
        engine.release()
    }

    @Test
    fun decodesBundledChineseWavToText() {        val engine = OfflineSpeechRecognizer(targetContext)
        assertTrue("模型加载失败", prepareBlocking(engine, 240_000))
        val audio = readWav(testContext.assets.open("test_zh.wav").readBytes())
        assertEquals("测试音频必须是 16 kHz", 16_000, audio.sampleRate)
        assertEquals("测试音频必须是单声道", 1, audio.channels)
        assertEquals("测试音频必须是 16 bit PCM", 2, audio.sampleWidthBytes)

        val text = engine.decodePcm(audio.samples)
        assertNotNull("解码返回 null（模型不可用）", text)
        val recognized = text!!
        val partials = engine.lastPartials()
        println("ASR_RESULT=$recognized")
        println("ASR_PARTIALS=$partials")
        assertTrue("识别结果为空", recognized.isNotBlank())
        assertTrue(
            "识别结果不含中文：$recognized",
            recognized.any { it.code in 0x4E00..0x9FFF },
        )
        assertTrue("识别文本过短：$recognized", recognized.length >= 4)
        engine.release()
    }

    @Test
    fun microphoneSessionStartsAndStopsWithoutCrash() {
        assertTrue(
            "需要在运行前授予 RECORD_AUDIO（adb shell pm grant com.example.adsh android.permission.RECORD_AUDIO）",
            targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val engine = OfflineSpeechRecognizer(targetContext)
        assertTrue("模型加载失败", prepareBlocking(engine, 240_000))

        val started = CountDownLatch(1)
        val settled = CountDownLatch(1)
        engine.onListeningChanged = { listening -> if (listening) started.countDown() }
        engine.onFinal = { settled.countDown() }
        engine.onError = { settled.countDown() }

        engine.start()
        assertTrue("麦克风录音未启动", started.await(20, TimeUnit.SECONDS))
        Thread.sleep(1_500)
        engine.stop()
        // Vosk emits a final result or an error after AudioRecord stops; both are acceptable here.
        settled.await(20, TimeUnit.SECONDS)
        engine.release()
    }

    private fun prepareBlocking(engine: OfflineSpeechRecognizer, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ready = false
        engine.prepare { ok ->
            ready = ok
            latch.countDown()
        }
        assertTrue("prepare 超时（${timeoutMs}ms）", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        return ready
    }

}
