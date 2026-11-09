package com.example.adsh.device

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bundled ML Kit Chinese recognizer. Runs fully offline and also recognizes
 * Latin text, so it covers both Chinese and English UI labels.
 */
object ScreenOcr {
    private const val TIMEOUT_SECONDS = 20L

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(bitmap: Bitmap, maxBlocks: Int): List<Map<String, Any?>> {
        val latch = CountDownLatch(1)
        var blocks: List<Map<String, Any?>> = emptyList()
        var failure: Exception? = null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                blocks = text.textBlocks
                    .mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        val label = block.text
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .trim()
                        if (label.isEmpty()) return@mapNotNull null
                        linkedMapOf<String, Any?>(
                            "text" to label.take(120),
                            "x" to box.centerX(),
                            "y" to box.centerY(),
                        )
                    }
                    .sortedWith(compareBy({ it["y"] as Int }, { it["x"] as Int }))
                    .take(maxBlocks)
                latch.countDown()
            }
            .addOnFailureListener { error ->
                failure = error
                latch.countDown()
            }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("OCR 识别超时，请重试")
        }
        failure?.let { throw IllegalStateException("OCR 识别失败：${it.message ?: it.javaClass.simpleName}") }
        return blocks
    }
}
