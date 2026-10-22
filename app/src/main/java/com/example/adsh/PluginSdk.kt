package com.example.adsh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class ServiceKey<T : Any>(val name: String)
fun interface Disposable {
    suspend fun dispose()
}

interface Service {
    val inject: Set<ServiceKey<*>>
    suspend fun apply(ctx: PluginContext)
}

enum class EventMode { EMIT, WATERFALL, PARALLEL, SERIAL, BAIL }
data class EventKey<T : Any>(val name: String, val mode: EventMode)
data class WaterfallKey<T : Any>(val name: String)
data class BailKey<T : Any, R : Any>(val name: String)

class PluginContext internal constructor(
    private val services: Map<ServiceKey<*>, Any>,
    private val owner: Fiber,
    private val events: EventBus,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> require(key: ServiceKey<T>): T =
        (services[key] ?: error("Missing service: ${key.name}")) as T

    fun effect(install: () -> Disposable): Disposable = owner.own(install())
    fun <T : Any> on(event: EventKey<T>, listener: suspend (T) -> Unit): Disposable =
        owner.own(events.on(event, listener))
    fun <T : Any> onWaterfall(
        event: WaterfallKey<T>,
        listener: suspend (T, suspend (T) -> T) -> T,
    ): Disposable = owner.own(events.onWaterfall(event, listener))
    fun <T : Any, R : Any> onBail(event: BailKey<T, R>, listener: suspend (T) -> R?): Disposable =
        owner.own(events.onBail(event, listener))
}

class Fiber internal constructor() {
    private val disposers = mutableListOf<Disposable>()
    private var closed = false
    internal fun own(disposer: Disposable): Disposable {
        check(!closed)
        disposers.add(disposer)
        return disposer
    }
    suspend fun dispose() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (disposer in disposers.asReversed()) {
            try {
                disposer.dispose()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        disposers.clear()
        failure?.let { throw it }
    }
}

class EventBus {
    private val listeners = mutableMapOf<String, MutableList<suspend (Any) -> Unit>>()
    private val wrappers = mutableMapOf<String, MutableList<suspend (Any, suspend (Any) -> Any) -> Any>>()
    private val decisions = mutableMapOf<String, MutableList<suspend (Any) -> Any?>>()
    private val modes = mutableMapOf<String, EventMode>()

    private fun validate(name: String, mode: EventMode) = synchronized(modes) {
        check(modes[name] == null || modes[name] == mode) { "Conflicting mode for $name" }
        modes[name] = mode
    }

    internal fun <T : Any> on(key: EventKey<T>, listener: suspend (T) -> Unit): Disposable {
        check(key.mode != EventMode.WATERFALL && key.mode != EventMode.BAIL)
        validate(key.name, key.mode)
        val bridge: suspend (Any) -> Unit = { value -> @Suppress("UNCHECKED_CAST") listener(value as T) }
        synchronized(listeners) { listeners.getOrPut(key.name) { mutableListOf() }.add(bridge) }
        return Disposable { synchronized(listeners) { listeners[key.name]?.remove(bridge) }; Unit }
    }

    internal fun <T : Any> onWaterfall(
        key: WaterfallKey<T>,
        listener: suspend (T, suspend (T) -> T) -> T,
    ): Disposable {
        validate(key.name, EventMode.WATERFALL)
        val bridge: suspend (Any, suspend (Any) -> Any) -> Any = { input, next ->
            @Suppress("UNCHECKED_CAST")
            listener(input as T) { next(it) as T }
        }
        synchronized(wrappers) { wrappers.getOrPut(key.name) { mutableListOf() }.add(bridge) }
        return Disposable { synchronized(wrappers) { wrappers[key.name]?.remove(bridge) }; Unit }
    }

    internal fun <T : Any, R : Any> onBail(
        key: BailKey<T, R>,
        listener: suspend (T) -> R?,
    ): Disposable {
        validate(key.name, EventMode.BAIL)
        val bridge: suspend (Any) -> Any? = { input ->
            @Suppress("UNCHECKED_CAST")
            listener(input as T)
        }
        synchronized(decisions) { decisions.getOrPut(key.name) { mutableListOf() }.add(bridge) }
        return Disposable { synchronized(decisions) { decisions[key.name]?.remove(bridge) }; Unit }
    }

    suspend fun <T : Any> emit(key: EventKey<T>, value: T) {
        check(key.mode == EventMode.EMIT)
        validate(key.name, key.mode)
        val snapshot = synchronized(listeners) { listeners[key.name]?.toList().orEmpty() }
        snapshot.forEach { it(value) }
    }

    suspend fun <T : Any> serial(key: EventKey<T>, value: T) {
        check(key.mode == EventMode.SERIAL)
        validate(key.name, key.mode)
        synchronized(listeners) { listeners[key.name]?.toList().orEmpty() }.forEach { it(value) }
    }

    suspend fun <T : Any> parallel(key: EventKey<T>, value: T) = coroutineScope {
        check(key.mode == EventMode.PARALLEL)
        validate(key.name, key.mode)
        synchronized(listeners) { listeners[key.name]?.toList().orEmpty() }
            .map { async { it(value) } }.awaitAll()
        Unit
    }

    suspend fun <T : Any> waterfall(key: WaterfallKey<T>, value: T): T {
        validate(key.name, EventMode.WATERFALL)
        val chain = synchronized(wrappers) { wrappers[key.name]?.toList().orEmpty() }
        suspend fun dispatch(index: Int, input: Any): Any =
            if (index == chain.size) input else chain[index](input) { dispatch(index + 1, it) }
        @Suppress("UNCHECKED_CAST")
        return dispatch(0, value) as T
    }

    suspend fun <T : Any, R : Any> bail(key: BailKey<T, R>, value: T): R? {
        validate(key.name, EventMode.BAIL)
        val chain = synchronized(decisions) { decisions[key.name]?.toList().orEmpty() }
        for (listener in chain) {
            @Suppress("UNCHECKED_CAST")
            val answer = listener(value) as R?
            if (answer != null) return answer
        }
        return null
    }
}

class PluginRuntime(private val services: Map<ServiceKey<*>, Any>, val events: EventBus = EventBus()) {
    suspend fun mount(plugin: Service): Fiber {
        plugin.inject.forEach { check(services.containsKey(it)) { "Missing service: ${it.name}" } }
        val fiber = Fiber()
        try {
            plugin.apply(PluginContext(services, fiber, events))
        } catch (error: Throwable) {
            fiber.dispose()
            throw error
        }
        return fiber
    }
}
