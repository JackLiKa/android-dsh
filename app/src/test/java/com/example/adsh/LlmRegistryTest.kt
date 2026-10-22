package com.example.adsh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlmRegistryTest {
    private val adapter = object : ModelAdapter {
        override val models = listOf("test-model")
        override fun stream(model: String, apiKey: String, messages: List<SessionEventRow>): Flow<String> =
            flowOf("ok")
    }

    @Test
    fun adapterRoutesAreReversibleAndCannotBeOverwritten() = runBlocking {
        val registry = LlmRegistry()
        val release = registry.registerAdapter(listOf("test-provider"), adapter)
        assertEquals(listOf("test-model"), registry.adapterFor("test-provider").models)
        assertThrows(IllegalStateException::class.java) {
            registry.registerAdapter(listOf("test-provider"), adapter)
        }
        release.dispose()
        assertThrows(IllegalStateException::class.java) {
            registry.adapterFor("test-provider")
        }
        Unit
    }

    @Test
    fun duplicateInMultiProviderRegistrationIsAtomic() = runBlocking {
        val registry = LlmRegistry()
        registry.registerAdapter(listOf("existing"), adapter)
        assertThrows(IllegalStateException::class.java) {
            registry.registerAdapter(listOf("new", "existing"), adapter)
        }
        assertEquals(emptyList<String>(), registry.providers().filter { it == "new" })
    }

    @Test
    fun pluginMountAndUnloadRemoveProvider() = runBlocking {
        val registry = LlmRegistry()
        val runtime = PluginRuntime(mapOf(ModelServices.Llm to registry))
        val fiber = runtime.mount(DeepSeekProviderPlugin(adapter))
        assertEquals(listOf("test-model"), registry.adapterFor("deepseek-chat-api").models)
        fiber.dispose()
        assertEquals(emptyList<String>(), registry.providers())
    }
}
