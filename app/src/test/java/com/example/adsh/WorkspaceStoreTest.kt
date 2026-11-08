package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkspaceStoreTest {
    @Test
    fun createsAndReloadsNamedPrivateWorkspaces() {
        val root = Files.createTempDirectory("adsh-workspaces").toFile()
        val store = WorkspaceStore(root)
        val initial = store.list()
        assertEquals(listOf("default"), initial.map { it.id })
        val created = store.create("  测试项目  ")
        assertEquals("测试项目", created.name)
        assertEquals(listOf("default", created.id), WorkspaceStore(root).list().map { it.id })
        assertTrue(
            created.directory.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator),
        )
        assertThrows(IllegalArgumentException::class.java) { store.create("测试项目") }
    }

    @Test
    fun invalidWorkspaceNamesStayOutOfTheDirectory() {
        val root = Files.createTempDirectory("adsh-workspaces").toFile()
        val store = WorkspaceStore(root)
        assertFalse(store.isValidName("../outside"))
        assertFalse(store.isValidName("  "))
        assertFalse(store.isValidName("line\nbreak"))
        assertEquals(1, store.list().size)
    }
}
