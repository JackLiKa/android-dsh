package com.example.adsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshJsonTest {
    @Test
    fun `parses nested objects arrays and escapes`() {
        val value = DshJson.parse(
            """
            {"ok":true,"n":12,"ratio":1.5,"items":[1,"a",null],"nested":{"quote":"a\"b"}}
            """.trimIndent(),
        ).asDshMap()!!
        assertEquals(true, value["ok"])
        assertEquals(12L, value["n"])
        assertEquals(1.5, value["ratio"])
        assertEquals(3, value["items"].asDshList()!!.size)
        assertEquals("a\"b", value["nested"].asDshMap()!!["quote"])
    }

    @Test
    fun `round trips unicode and control characters`() {
        val original = mapOf(
            "text" to "中文\n\t\"引号\"",
            "empty" to emptyList<String>(),
            "ok" to false,
        )
        val decoded = DshJson.parse(DshJson.stringify(original)).asDshMapOrEmpty()
        assertEquals(original["text"], decoded["text"])
        assertEquals(original["ok"], decoded["ok"])
        assertTrue(decoded["empty"].asDshList()!!.isEmpty())
    }

    @Test
    fun `rejects truncated documents`() {
        assertNull(runCatching { DshJson.parse("{\"a\":") }.getOrNull())
        assertNull(runCatching { DshJson.parse("[1,]") }.getOrNull())
    }

    @Test
    fun `helpers tolerate wrong types`() {
        assertEquals("x", DshJson.parse("\"x\"").dshString())
        assertEquals(null, DshJson.parse("12").dshString())
        assertEquals(12, DshJson.parse("12").dshInt())
        assertEquals(true, DshJson.parse("true").dshBool())
        assertTrue(DshJson.parse("[]").asDshMapOrEmpty().isEmpty())
    }
}
