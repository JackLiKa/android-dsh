package com.example.adsh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LocalSessions(private val root: File, private val dao: SessionEventDao) {
    private val writeMutex = Mutex()
    private val workspaceLinks = SessionWorkspaceLink(root)
    fun newId(): String = UUID.randomUUID().toString()
    suspend fun list(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val fromDisk = root.listFiles().orEmpty()
            .filter { it.isDirectory && runCatching { UUID.fromString(it.name).toString() == it.name }
                .getOrDefault(false) && it.resolve("local-events.jsonl").isFile }
            .map { it.name }
        (dao.sessions() + fromDisk).distinct()
            .map { id -> summarize(id, events(id), workspaceLinks.workspaceIdOf(id)) }
            .sortedByDescending { it.lastEventTime }
    }

    suspend fun workspaceIdOf(id: String): String = withContext(Dispatchers.IO) {
        workspaceLinks.workspaceIdOf(id)
    }

    suspend fun associate(id: String, workspaceId: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val hasEvents = File(File(root, id), "local-events.jsonl").length() > 0
            workspaceLinks.associate(id, workspaceId, hasEvents)
        }
    }

    suspend fun append(id: String, type: String, data: String): SessionEventRow =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val previous = events(id)
                val directory = File(root, id).apply { check(mkdirs() || isDirectory) }
                val file = File(directory, "local-events.jsonl")
                val seq = previous.size.toLong()
                val event = SessionEventRow(id, seq, type, System.currentTimeMillis(), data)
                val json = JSONObject()
                    .put("seq", seq).put("type", type).put("time", event.time)
                    .put("data", data).toString() + "\n"
                FileOutputStream(file, true).use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                dao.append(event)
                event
            }
        }

    suspend fun events(id: String): List<SessionEventRow> = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val file = File(File(root, id), "local-events.jsonl")
        if (!file.exists()) {
            check(dao.events(id).isEmpty()) { "持久日志缺失：$id" }
            return@withContext emptyList()
        }
        val rows = file.readLines().filter { it.isNotBlank() }.mapIndexed { index, line ->
            val json = JSONObject(line)
            check(json.getLong("seq") == index.toLong()) { "Local event sequence gap" }
            SessionEventRow(id, index.toLong(), json.getString("type"), json.getLong("time"), json.getString("data"))
        }
        val mirrored = dao.events(id)
        check(mirrored.size <= rows.size && mirrored == rows.take(mirrored.size)) {
            "Local event mirror differs from durable log"
        }
        for (row in rows.drop(mirrored.size)) dao.append(row)
        rows
    }
}
