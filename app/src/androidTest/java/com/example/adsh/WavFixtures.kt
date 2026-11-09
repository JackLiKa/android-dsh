package com.example.adsh

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavAudio(
    val sampleRate: Int,
    val channels: Int,
    val sampleWidthBytes: Int,
    val samples: ShortArray,
)

/** Minimal RIFF/WAV reader for the 16 kHz PCM fixtures under androidTest/assets. */
fun readWav(bytes: ByteArray): WavAudio {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check(bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
        "不是 RIFF/WAV 文件"
    }
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1
    var dataLength = 0
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = buffer.getInt(offset + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> {
                channels = buffer.getShort(body + 2).toInt()
                sampleRate = buffer.getInt(body + 4)
                bitsPerSample = buffer.getShort(body + 14).toInt()
            }
            "data" -> {
                dataOffset = body
                dataLength = minOf(size, bytes.size - body)
                offset = bytes.size
                break
            }
        }
        offset = body + size + (size and 1)
    }
    check(dataOffset >= 0) { "WAV 缺少 data chunk" }
    val samples = ShortArray(dataLength / 2)
    buffer.position(dataOffset)
    buffer.asShortBuffer().get(samples)
    return WavAudio(
        sampleRate = sampleRate,
        channels = channels,
        sampleWidthBytes = bitsPerSample / 8,
        samples = samples,
    )
}
