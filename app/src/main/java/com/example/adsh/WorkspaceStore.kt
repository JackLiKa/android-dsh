package com.example.adsh

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class WorkspaceRef(val id: String, val name: String, val directory: File)

class WorkspaceStore(private val root: File) {
    companion object {
        const val DEFAULT_ID = "default"
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
