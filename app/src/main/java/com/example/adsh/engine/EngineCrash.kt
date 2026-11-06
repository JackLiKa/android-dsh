package com.example.adsh.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Turns a raw engine exit into an actionable attribution for the log console. */
object EngineCrash {
    fun hintFor(exitCode: Int): String = when (exitCode) {
        0 -> "正常退出"
        1 -> "应用级错误：查看日志末尾的异常栈"
        134 -> "进程异常中止（SIGABRT）：插件初始化或断言失败"
        137 -> "进程被系统终止（SIGKILL）：可能内存不足或受后台限制"
        139 -> "原生段错误（SIGSEGV）：Node 运行时或原生插件崩溃"
        143 -> "进程被终止（SIGTERM）"
        else -> "退出代码 $exitCode"
    }

    fun format(
        exitCode: Int,
        startedAtMs: Long,
        endedAtMs: Long,
        tail: List<String>,
    ): String = buildString {
        appendLine("时间：${stamp(endedAtMs)}")
        appendLine("退出代码：$exitCode（${hintFor(exitCode)}）")
        appendLine("运行时长：${seconds(startedAtMs, endedAtMs)} 秒")
        appendTail(tail)
    }.trimEnd()

    fun formatError(
        detail: String,
        startedAtMs: Long,
        endedAtMs: Long,
        tail: List<String>,
    ): String = buildString {
        appendLine("时间：${stamp(endedAtMs)}")
        appendLine("启动失败：${detail.take(500)}")
        appendLine("运行时长：${seconds(startedAtMs, endedAtMs)} 秒")
        appendTail(tail)
    }.trimEnd()

    private fun StringBuilder.appendTail(tail: List<String>) {
        if (tail.isEmpty()) return
        appendLine("日志末尾：")
        tail.takeLast(15).forEach { appendLine(it) }
    }

    private fun seconds(startedAtMs: Long, endedAtMs: Long): Long =
        TimeUnit.MILLISECONDS.toSeconds((endedAtMs - startedAtMs).coerceAtLeast(0))

    private fun stamp(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}
