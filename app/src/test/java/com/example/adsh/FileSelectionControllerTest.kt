package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSelectionControllerTest {
    @Test
    fun replacementCancelsEarlierWebViewRequest() {
        val selections = mutableListOf<String?>()
        val controller = FileSelectionController<String>()
        controller.offer { selections += "old:${it ?: "cancel"}" }
        controller.offer { selections += "new:${it ?: "cancel"}" }
        controller.complete("content://chosen")
        controller.complete("content://late")
        assertEquals(listOf("old:cancel", "new:content://chosen"), selections)
    }

    @Test
    fun closingChooserCompletesRequestOnlyOnce() {
        var calls = 0
        val controller = FileSelectionController<String>()
        controller.offer { value -> if (value == null) calls++ }
        controller.cancel()
        controller.cancel()
        assertEquals(1, calls)
    }
}
