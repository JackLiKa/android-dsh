package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileComposerTest {
    private val composer = ProfileComposer()

    @Test
    fun composesBundlesProfileHomeAndOverlayInOrder() {
        val base = """
            - insert:
                - id: llm
                  module: dsh-llm
                  config: { model: one, temperature: 1 }
                - id: tools
                  module: dsh-tools
        """.trimIndent()
        val mode = "- id: llm\n  config: { model: two }\n"
        val profile = "- id: llm\n  disabled: true\n"
        val home = "- id: llm\n  config: { model: three }\n"
        val overlay = "- id: llm\n  disabled: false\n"
        val result = composer.compose(listOf(base, mode, profile, home, overlay))
        assertEquals(2, result.rows.size)
        assertEquals("three", (result.rows[0]["config"] as Map<*, *>)["model"])
        assertFalse((result.rows[0]["config"] as Map<*, *>).containsKey("temperature"))
        assertEquals(false, result.rows[0]["disabled"])
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun insertRowsAreImmediatelyAddressableAndGroupInsertTargetsChildren() {
        val result = composer.compose(listOf("""
            - insert:
                - id: group
                  group: true
                  config: []
            - id: group
              insert:
                - id: child
                  module: test-child
            - id: child
              disabled: true
        """.trimIndent()))
        val group = result.rows.single()
        val child = (group["config"] as List<*>).single() as Map<*, *>
        assertEquals(true, child["disabled"])
    }

    @Test
    fun mismatchedModuleAndMissingIdAreReportedWithoutChangingSibling() {
        val result = composer.compose(listOf("""
            - insert:
                - id: llm
                  module: dsh-llm
            - id: llm
              module: wrong
              disabled: true
            - id: absent
              disabled: true
        """.trimIndent()))
        assertEquals(null, result.rows.single()["disabled"])
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun jsExpressionsRemainExplicitInDump() {
        val result = composer.compose(listOf("""
            - insert:
                - id: hmr
                  module: dsh-hmr
                  disabled: !!js "ctx.get('profileContext') == null"
        """.trimIndent()))
        assertTrue(composer.dump(result.rows).contains("!!js"))
    }

    @Test
    fun profileRowsCanBeInsertedBeforeOverrides() {
        val profileRows = """
            - id: my-hello
              module: com.mine.hello/.MyPlugin
              config: { greeting: hi }
        """.trimIndent()
        val result = composer.compose(listOf(
            composer.asInsertPatch(profileRows),
            "- id: my-hello\n  config: { greeting: hello }\n",
        ))
        assertEquals("hello", (result.rows.single()["config"] as Map<*, *>)["greeting"])
    }
}
