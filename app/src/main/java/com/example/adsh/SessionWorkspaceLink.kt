package com.example.adsh

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SessionWorkspaceLink(private val sessionRoot: File) {
    fun workspaceIdOf(sessionId: String): String {
        val file = linkFile(sessionId)
        if (!file.exists()) return WorkspaceStore.DEFAULT_ID
        require(file.length() in 1L..80L) { "会话工作区标识损坏" }
        return file.readText(Charsets.UTF_8).trim().also(::checkWorkspaceId)
    }

    fun associate(sessionId: String, workspaceId: String, hasExistingEvents: Boolean) {
        checkWorkspaceId(workspaceId)
        val file = linkFile(sessionId)
        if (file.exists()) {
            require(workspaceIdOf(sessionId) == workspaceId) { "会话原始工作区保持不变" }
            return
        }
        require(!hasExistingEvents || workspaceId == WorkspaceStore.DEFAULT_ID) {
            "已有会话保留原始默认工作区"
        }
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        if (file.createNewFile()) {
            FileOutputStream(file).use { stream ->
                stream.write((workspaceId + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        } else {
            require(workspaceIdOf(sessionId) == workspaceId) { "会话工作区已被其他进程写入" }
        }
    }

    private fun linkFile(sessionId: String): File {
        require(runCatching { UUID.fromString(sessionId).toString() == sessionId }.getOrDefault(false)) {
            "会话标识无效"
        }
        return sessionRoot.resolve(sessionId).resolve("workspace-id.txt")
    }

    private fun checkWorkspaceId(id: String) {
        require(id == WorkspaceStore.DEFAULT_ID ||
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "工作区标识无效"
        }
    }
}
