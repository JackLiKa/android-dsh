package com.example.adsh.engine

import java.io.File

/** Small bounded log file so the engine console survives process restarts. */
class EngineLogStore(
    private val file: File,
    private val maxBytes: Long = 1_500_000L,
    private val maxLines: Int = 600,
) {
    fun append(line: String) {
        synchronized(this) {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) rotate()
            file.appendText(line.take(4_000) + "\n")
        }
    }

    fun read(): List<String> = synchronized(this) {
        if (!file.isFile) emptyList() else file.readLines().takeLast(maxLines)
    }

    fun clear() {
        synchronized(this) { file.delete() }
    }

    private fun rotate() {
        if (!file.isFile) return
        val keep = file.readLines().takeLast(maxLines)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
    }
}
