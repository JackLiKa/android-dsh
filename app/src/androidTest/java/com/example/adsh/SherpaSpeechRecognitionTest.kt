package com.example.adsh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device-level verification of the bundled sherpa-onnx streaming engine.
 *
 * The Zipformer/VAD/punctuation models ship inside the app APK (`assets/sherpa`) and the audio
 * fixture lives in the test APK, so decoding needs neither a microphone nor network.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSpeechRecognitionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    @Test
    fun packagedModelsAreUsedByDefaultEngine() {
        val engine = SherpaSpeechRecognizer(targetContext)
        assertTrue("assets/sherpa 模型未打包进 APK", engine.assetsInstalled())
        assertTrue(
            "默认语音引擎应可用（本地增强或系统服务）",
            VoiceInputController(targetContext).available(),
        )
    }

    @Test
    fun streamsChineseWavWithPartialsAndPunctuation() {
        val engine = SherpaSpeechRecognizer(targetContext)
        assertTrue("assets/sherpa 模型未打包进 APK", engine.assetsInstalled())
        assertTrue("sherpa 模型加载失败", prepareBlocking(engine, 300_000))

        val audio = readWav(testContext.assets.open("test_zh.wav").readBytes())
        assertEquals("测试音频必须是 16 kHz", 16_000, audio.sampleRate)
        assertEquals("测试音频必须是单声道", 1, audio.channels)
        assertEquals("测试音频必须是 16 bit PCM", 2, audio.sampleWidthBytes)

        val text = engine.decodePcm(audio.samples)
        assertNotNull("解码返回 null（模型不可用）", text)
        val recognized = text!!
        val partials = engine.lastPartials()
        println("SHERPA_RESULT=$recognized")
        println("SHERPA_PARTIALS=$partials")

        assertTrue("识别结果为空", recognized.isNotBlank())
        assertTrue(
            "识别结果不含中文：$recognized",
            recognized.any { it.code in 0x4E00..0x9FFF },
        )
        assertTrue("识别文本过短：$recognized", recognized.length >= 4)
        assertTrue("流式识别没有产生增量结果：$partials", partials.size >= 2)
        assertTrue(
            "最终结果缺少标点：$recognized",
            recognized.last() in "，。？！",
        )
        engine.release()
    }

    private fun prepareBlocking(engine: SherpaSpeechRecognizer, timeoutMs: Long): Boolean {
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
