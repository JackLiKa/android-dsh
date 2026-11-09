package com.example.adsh

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class WorkspaceRef(val id: String, val name: String, val directory: File)

class WorkspaceStore(private val root: File) {
    companion object {
        const val DEFAULT_ID = "default"

        private val ASSISTANT_GUIDE = """
            # 助手行为约定

            - 用户意图明确时立即执行，不要先讲计划，也不要反复确认。
            - 闹钟用 set_alarm，计时器用 set_timer，打开应用用 app_open，查天气用 get_weather。
            - 短信：只有用户明确要求发送时才调用 send_sms，并报告发送成功或失败。
            - 简单问题（天气、时间、设备状态、单步操作）只给简短结论，推理过程保持最少。
            - 缺少必要信息时只问一个最关键的问题，例如城市、联系人或具体时间。
            - 回复先给结论，再给必要细节；较长内容分段输出，避免大段文字。
        """.trimIndent()
    }

    fun isValidName(value: String): Boolean {
        val name = value.trim()
        return name.isNotEmpty() && name.length <= 60 &&
            name.none { it.isISOControl() || it == '/' || it == '\\' } &&
            name != "." && name != ".." && !name.contains("..")
    }

    fun list(): List<WorkspaceRef> {
        ensureDefault()
        val created = root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != DEFAULT_ID && isWorkspaceId(it.name) &&
                it.resolve("name.txt").isFile }
            .map { directory ->
                WorkspaceRef(directory.name, readName(directory), directory)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return listOf(get(DEFAULT_ID)) + created
    }

    fun create(value: String): WorkspaceRef {
        require(isValidName(value)) { "工作区名称无效" }
        ensureDefault()
        val name = value.trim()
        require(list().none { it.name.equals(name, ignoreCase = true) }) {
            "工作区名称已存在"
        }
        val directory = root.resolve(UUID.randomUUID().toString())
        check(directory.mkdir()) { "无法创建工作区目录" }
        writeName(directory, name)
        return WorkspaceRef(directory.name, name, directory)
    }

    fun get(id: String): WorkspaceRef {
        require(isWorkspaceId(id)) { "工作区标识无效" }
        if (id == DEFAULT_ID) ensureDefault()
        val directory = root.resolve(id)
        require(directory.isDirectory) { "工作区不存在：$id" }
        writeAssistantGuide(directory)
        return WorkspaceRef(id, readName(directory), directory)
    }

    private fun isWorkspaceId(value: String): Boolean =
        value == DEFAULT_ID || runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun ensureDefault() {
        check(root.mkdirs() || root.isDirectory) { "无法创建工作区根目录" }
        val directory = root.resolve(DEFAULT_ID)
        check(directory.mkdirs() || directory.isDirectory) { "无法创建默认工作区" }
        if (!directory.resolve("name.txt").exists()) writeName(directory, "默认工作区")
    }

    /** Seeds an AGENTS.md once; an existing user-authored file is never overwritten. */
    private fun writeAssistantGuide(directory: File) {
        val file = directory.resolve("AGENTS.md")
        if (file.exists()) return
        runCatching { file.writeText(ASSISTANT_GUIDE, Charsets.UTF_8) }
    }

    private fun writeName(directory: File, name: String) {
        FileOutputStream(directory.resolve("name.txt")).use { stream ->
            stream.write(name.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun readName(directory: File): String {
        val file = directory.resolve("name.txt")
        require(file.isFile && file.length() <= 256L) { "工作区元数据损坏：${directory.name}" }
        val name = file.readText(Charsets.UTF_8)
        require(isValidName(name)) { "工作区名称损坏：${directory.name}" }
        return name
    }
}
