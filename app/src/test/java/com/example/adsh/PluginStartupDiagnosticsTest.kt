package com.example.adsh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginStartupDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test
    fun warningsDoNotHideImportFailures() {
        assertTrue(hasStartupPluginErrors(
            "[warn] tool-vscreen: authorization unavailable\n" +
                "[error] include: Error [ERR_MODULE_NOT_FOUND]: missing module\n",
        ))
        assertFalse(hasStartupPluginErrors("[warn] tool-vscreen: authorization unavailable\n"))
    }

    @Test
    fun anyRecordedPluginErrorNeedsVisibleDegradedStatus() {
        assertTrue(hasStartupPluginErrors("[error] ui-conversation: failed to activate\n"))
    }

    @Test
    fun oldErrorsAreExcludedFromTheCurrentBoot() {
        val log = temp.newFile("plugins.log")
        log.writeText("[error] older startup failure\n")
        val before = log.length()
        log.appendText("[warn] optional backend disabled\n")
        assertFalse(newStartupPluginErrors(log, before))
        log.appendText("[error] include: missing runtime dependency\n")
        assertTrue(newStartupPluginErrors(log, before))
    }
}
