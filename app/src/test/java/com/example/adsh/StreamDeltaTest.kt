package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamDeltaTest {
    @Test
    fun nullableProviderContentDoesNotBecomeVisibleNullText() {
        assertNull(textDelta(null))
        assertNull(textDelta(object {
            override fun toString() = "null"
        }))
        assertNull(textDelta(""))
        assertEquals("答案", textDelta("答案"))
    }

    @Test
    fun literalNullTextIsStillValidText() {
        assertEquals("null", textDelta("null"))
    }
}
