package com.example.adsh

import java.io.File
import java.io.RandomAccessFile

fun hasStartupPluginErrors(text: String): Boolean =
    text.lineSequence().any { "[error]" in it }

fun newStartupPluginErrors(file: File, fromByte: Long): Boolean {
    if (!file.isFile) return false
    val remaining = file.length() - fromByte
    if (remaining < 0) return true
    if (remaining == 0L) return false
    if (remaining > 64 * 1024) return true
    return RandomAccessFile(file, "r").use { handle ->
        handle.seek(fromByte)
        val bytes = ByteArray(remaining.toInt())
        handle.readFully(bytes)
        hasStartupPluginErrors(bytes.toString(Charsets.UTF_8))
    }
}
