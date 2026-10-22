package com.example.adsh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRuntimeTest {
    private val key = ServiceKey<String>("name")
    private val event = EventKey<String>("test/message", EventMode.EMIT)

    @Test
    fun effectsUnwindInReverseOrder() = runBlocking {
        val disposed = mutableListOf<String>()
        val runtime = PluginRuntime(mapOf(key to "registered"))
        val fiber = runtime.mount(object : Service {
            override val inject = setOf(key)
            override suspend fun apply(ctx: PluginContext) {
                ctx.effect { Disposable { disposed.add("first") } }
                ctx.effect { Disposable { disposed.add("second") } }
                assertEquals("registered", ctx.require(key))
            }
        })
        fiber.dispose()
        fiber.dispose()
        assertEquals(listOf("second", "first"), disposed)
    }

    @Test
    fun unloadDetachesListeners() = runBlocking {
        val runtime = PluginRuntime(emptyMap())
        val observed = mutableListOf<String>()
        val fiber = runtime.mount(object : Service {
            override val inject = emptySet<ServiceKey<*>>()
            override suspend fun apply(ctx: PluginContext) {
                ctx.on(event) { observed.add(it) }
            }
        })
        runtime.events.emit(event, "before")
        fiber.dispose()
        runtime.events.emit(event, "after")
        assertEquals(listOf("before"), observed)
    }

    @Test
    fun missingRequiredServiceRejectsMount() {
        val plugin = object : Service {
            override val inject = setOf(key)
            override suspend fun apply(ctx: PluginContext) = Unit
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { PluginRuntime(emptyMap()).mount(plugin) }
        }
    }

    @Test
    fun waterfallDelegatesAndShortCircuits() = runBlocking {
        val runtime = PluginRuntime(emptyMap())
        val order = mutableListOf<String>()
        val key = WaterfallKey<String>("agent/pre-step")
        val outer = runtime.mount(object : Service {
            override val inject = emptySet<ServiceKey<*>>()
            override suspend fun apply(ctx: PluginContext) {
                ctx.onWaterfall(key) { input, next ->
                    order.add("outer-enter")
                    val output = next("$input+outer")
                    order.add("outer-exit")
                    "$output+after"
                }
            }
        })
        val inner = runtime.mount(object : Service {
            override val inject = emptySet<ServiceKey<*>>()
            override suspend fun apply(ctx: PluginContext) {
                ctx.onWaterfall(key) { input, _ ->
                    order.add("inner")
                    "$input+inner"
                }
            }
        })
        assertEquals("start+outer+inner+after", runtime.events.waterfall(key, "start"))
        assertEquals(listOf("outer-enter", "inner", "outer-exit"), order)
        inner.dispose()
        outer.dispose()
        assertEquals("start", runtime.events.waterfall(key, "start"))
    }

    @Test
    fun bailStopsAtFirstAnswer() = runBlocking {
        val key = BailKey<String, String>("policy/decision")
        val runtime = PluginRuntime(emptyMap())
        val result = mutableListOf<String>()
        val fiber = runtime.mount(object : Service {
            override val inject = emptySet<ServiceKey<*>>()
            override suspend fun apply(ctx: PluginContext) {
                ctx.onBail(key) { result.add("first"); null }
                ctx.onBail(key) { result.add("second"); "approved" }
                ctx.onBail(key) { result.add("third"); "denied" }
            }
        })
        assertEquals("approved", runtime.events.bail(key, "request"))
        assertEquals(listOf("first", "second"), result)
        fiber.dispose()
    }
}
