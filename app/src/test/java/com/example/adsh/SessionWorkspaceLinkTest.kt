package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class SessionWorkspaceLinkTest {
    private val sessionId = UUID.randomUUID().toString()
    private val workspaceId = UUID.randomUUID().toString()

    @Test
    fun remembersOriginalWorkspaceAcrossReopen() {
        val root = Files.createTempDirectory("adsh-session-link").toFile()
        SessionWorkspaceLink(root).associate(sessionId, workspaceId, false)
        assertEquals(workspaceId, SessionWorkspaceLink(root).workspaceIdOf(sessionId))
        assertThrows(IllegalArgumentException::class.java) {
            SessionWorkspaceLink(root).associate(sessionId, WorkspaceStore.DEFAULT_ID, true)
        }
    }

    @Test
    fun oldUnlinkedSessionRetainsDefaultProjectIdentity() {
        val root = Files.createTempDirectory("adsh-legacy-session").toFile()
        assertEquals(WorkspaceStore.DEFAULT_ID, SessionWorkspaceLink(root).workspaceIdOf(sessionId))
        assertThrows(IllegalArgumentException::class.java) {
            SessionWorkspaceLink(root).associate(sessionId, workspaceId, true)
        }
    }
}
