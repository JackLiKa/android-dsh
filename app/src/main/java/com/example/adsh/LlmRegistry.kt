package com.example.adsh

import kotlinx.coroutines.flow.Flow

interface ModelAdapter {
    val models: List<String>
    fun stream(model: String, apiKey: String, messages: List<SessionEventRow>): Flow<String>
}

object BuiltInModels {
    const val DEEPSEEK_PROVIDER = "deepseek-chat-api"
    const val DEEPSEEK_FLASH = "deepseek-flash"
    const val DEEPSEEK_PRO = "deepseek-v4-pro"
}

class LlmRegistry {
    private val adapters = mutableMapOf<String, ModelAdapter>()

    fun registerAdapter(providers: List<String>, adapter: ModelAdapter): Disposable {
        require(providers.isNotEmpty() && providers.all { it.isNotBlank() }) {
            "至少声明一个供应商"
        }
        require(providers.size == providers.distinct().size) { "供应商名称重复" }
        synchronized(adapters) {
            check(providers.none { it in adapters }) { "供应商已有适配器" }
            providers.forEach { adapters[it] = adapter }
        }
        return Disposable {
            synchronized(adapters) {
                providers.forEach { provider ->
                    if (adapters[provider] === adapter) adapters.remove(provider)
                }
            }
        }
    }

    fun adapterFor(provider: String): ModelAdapter = synchronized(adapters) {
        adapters[provider] ?: error("供应商未就绪：$provider")
    }

    fun providers(): List<String> = synchronized(adapters) { adapters.keys.toList() }
}

object ModelServices {
    val Llm = ServiceKey<LlmRegistry>("llm")
}

val PluginContext.llm: LlmRegistry
    get() = require(ModelServices.Llm)

class DeepSeekProviderPlugin(private val adapter: ModelAdapter = DeepSeekClient()) : Service {
    override val inject = setOf(ModelServices.Llm)

    override suspend fun apply(ctx: PluginContext) {
        ctx.effect { ctx.llm.registerAdapter(listOf(BuiltInModels.DEEPSEEK_PROVIDER), adapter) }
    }
}
