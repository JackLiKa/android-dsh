package com.example.adsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineLogStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `appends and reads the tail in order`() {
        val store = EngineLogStore(folder.newFile("engine.log"), maxLines = 10)
        store.append("one")
        store.append("two")
        assertEquals(listOf("one", "two"), store.read())
        store.clear()
        assertFalse(store.fileForTest().exists())
    }

    @Test
    fun `rotation keeps only the newest lines`() {
        val store = EngineLogStore(folder.newFile("engine.log"), maxBytes = 1, maxLines = 3)
        store.append("one")
        store.append("two")
        store.append("three")
        store.append("four")
        assertEquals(listOf("two", "three", "four"), store.read())
    }

    private fun EngineLogStore.fileForTest() = folder.root.resolve("engine.log")
}
