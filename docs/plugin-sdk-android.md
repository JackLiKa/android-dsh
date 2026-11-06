# Android Plugin SDK 接口草案

状态：Kotlin 宿主侧的目标接口；当前可编译实现涵盖 `Service`、`PluginContext`、`effect`、五种事件分发与卸载，能力 Service Definition 仍待接入。Compose 对话已统一到内置 dsh 引擎（见[架构](architecture-android.md)），本 SDK 描述的是未来 Kotlin/TS 插件与宿主 dsh Service 图对齐的契约。差异与待实现项见 [实现状态](../README.md)。语义取自 [Cordis primer](../../upstream/docs/cordis-primer.md)、[插件扩展手册](../../upstream/docs/cookbook/extension-cookbook.md)、[capability seams](../../upstream/docs/capability-seams.md) 与 [Agent 生命周期](../../upstream/docs/agent-lifecycle.md)。

## Kotlin 宿主契约

下列片段描述公开接口草案；`Context` 只暴露 Service Definition，Provider 通过注册进入容器。`Disposable` 与 `EffectScope` 的资源由挂载 fiber 持有；关闭时等待子任务并逆序调用 disposer。

```kotlin
interface Service {
    val inject: Set<ServiceKey<*>>
    suspend fun apply(ctx: Context)
}

data class ServiceKey<T : Any>(val name: String)
fun interface Disposable {
    suspend fun dispose()
}

interface EffectScope {
    fun own(disposable: Disposable)
}

interface Context {
    fun <T : Any> require(key: ServiceKey<T>): T
    fun <T : Any> optional(key: ServiceKey<T>): T?
    fun effect(label: String, install: EffectScope.() -> Unit): Disposable
    fun <T : Any> on(event: EventKey<T>, listener: suspend (T) -> Unit): Disposable
    fun <I : Any, O : Any> onWaterfall(
        event: WaterfallKey<I, O>,
        listener: suspend (I, next: suspend (I) -> O) -> O,
    ): Disposable
}

enum class EventMode { EMIT, WATERFALL, PARALLEL, SERIAL, BAIL }
sealed interface EventKey<T : Any> {
    val name: String
    val mode: EventMode
}
interface WaterfallKey<I : Any, O : Any> {
    val name: String
    val mode: EventMode get() = EventMode.WATERFALL
}
```

`emit`、`parallel`、`serial` 和 `bail` 分别由各自的 typed dispatcher 提供；waterfall 监听器收到 `next`，调用时进入下游并可包装结果，直接返回时短路。SDK 在注册和分发时检查 key/mode。`ctx.tools` 等属性是 `require(ServiceKeys.Tools)` 的扩展属性；插件使用 key 引用 Service Definition。异步回调、文件观察者、CoroutineScope、模型 adapter 与系统资源都由 effect 拥有。

```kotlin
object ServiceKeys {
    val Tools = ServiceKey<ToolRegistry>("tools")
    val Agents = ServiceKey<AgentRegistry>("agents")
    val Llm = ServiceKey<LlmRegistry>("llm")
    val Sessions = ServiceKey<SessionStore>("sessions")
    val Commands = ServiceKey<CommandRegistry>("commands")
    val Jobs = ServiceKey<JobRegistry>("jobs")
}

val Context.tools: ToolRegistry get() = require(ServiceKeys.Tools)
val Context.agents: AgentRegistry get() = require(ServiceKeys.Agents)
val Context.llm: LlmRegistry get() = require(ServiceKeys.Llm)
val Context.sessions: SessionStore get() = require(ServiceKeys.Sessions)
```

注册接口返回 disposer，作用域采用注册时 Context 的 Agent scope；跨 scope 的 Provider 接口只传递必要的 typed handle。

```kotlin
interface ToolRegistry {
    fun register(tool: ToolDefinition): Disposable
}
interface LlmRegistry {
    fun register(adapter: LlmAdapter): Disposable
}
interface SessionStore {
    suspend fun append(sessionId: String, event: SessionEvent): SessionEvent
    suspend fun open(sessionId: String): SessionHandle
}
interface SessionProjectionRegistry {
    fun <S : Any> register(definition: ProjectionDefinition<S>): Disposable
    fun <S : Any> stateOf(sessionId: String, key: ProjectionKey<S>): S
    fun snapshot(sessionId: String): ProjectionSnapshot
}
interface ToolDefinition {
    val name: String
    val description: String
    val parameters: JsonSchema
    suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult
}
```

`ToolRegistry` 在执行入口执行业务审批和沙箱校验，接着分派 `tools/pre-execute`、`tools/execute`、`tools/post-execute` waterfall；调用失败和结果归入 Session 事件。`SessionProjectionRegistry` 只折叠已提交事件；插件持久态通过注册 `SessionEventMap` 对应事件和投影恢复。具体 `SessionEvent`、`ToolCall`、`ToolResult`、LLM message/stream 数据类以原仓库 schema 对齐后冻结。

## Kotlin 插件示例

安装来源为用户明确选择的已安装插件 APK；manifest 声明插件入口 component、SDK/ABI 版本及权限。宿主验证签名、包名与显式授权后通过该 package 的 ClassLoader 装载 `Service`；插件可依赖宿主 SDK 接口，无需修改宿主源码。

```kotlin
class MyPlugin : Service {
    override val inject = setOf(ServiceKeys.Tools, ServiceKeys.Agents)

    override suspend fun apply(ctx: Context) {
        ctx.effect("my-hello") {
            own(ctx.tools.register(HelloTool()))
            own(ctx.on(AgentEvents.TurnEnded) { event ->
                recordCompletedTurn(event.agentId)
            })
        }
    }
}
```

`HelloTool` 与 `recordCompletedTurn` 属于用户插件实现，事件 key 由 SDK 声明；该片段展示注册的资源归属。加 prompt-section 时注入 `systemPrompt` 并注册 section；加 Settings 卡片时注册 Android UI contribution，审批演示经 `approval` Service 提交请求并由人确认。

## TS bundle 入口与 patch

JS 引擎承载已预编译的 JS；安装器校验 bundle 声明和文件摘要，再按受信任模块映射加载。TypeScript 源码保留在包内供作者维护，编译产物与 package manifest 一同交付。以下两行是用户要求的 Android `cordis.yml` **解析后树表示**；外部 bundle 的 `cordis.patch.yml` 使用原仓库的 `insert` 语法插入这两行。

```yaml
- id: my-hello
  module: com.mine.hello/.MyPlugin
  config: { greeting: hi }
  disabled: false
- id: tool-my-search
  module: dsh-tool-my-search
  config: { timeoutMs: 8000 }
```

```json
{
  "name": "adsh-plugin-hello",
  "version": "1.0.0",
  "dsh": { "bundle": { "patch": "./cordis.patch.yml" } }
}
```

```yaml
- insert:
    - id: my-hello
      module: com.mine.hello/.MyPlugin
      config: { greeting: hi }
      disabled: false
    - id: tool-my-search
      module: dsh-tool-my-search
      config: { timeoutMs: 8000 }
```

原 Node Loader 的行字段为 `name`；Android 解析器接受上面的 `module`，在加载时解析为受信任 APK component 或已安装 JS package，保持 patch 对 `id`/`config`/`disabled` 的语义。TS 兼容层保留 `src/index.ts` 的 `inject`/`apply(ctx)` 形状、typed events 与 effect 回收；Node 内建模块和原生依赖逐包经兼容测试验收。`adsh-plugin-hello` 将同时含 tool、prompt-section、Settings 卡片与 approval 演示，并覆盖 inspect/install/enable/disable/unload 的真实 profile 集成测试。

## 管理接口与作者验收

管理接口保留 `listBundles()`、`inspect(spec)`、`installBundle(spec, options)`、`removeBundle(name)`、enable/disable、install log/state、`cancelInstall(requestId)` 和 `waitForInstall(requestId)` 的结果状态。安装构建脚本要求单独批准；兼容性豁免绑定精确 package/runtime 版本并要求 `acceptRisk`。每项注册以 fiber disposer 释放，测试从 profile 启动、禁用插件、校验工具/schema/监听器/设置卡/后台任务均已移除。

供评审重点：外部 Kotlin APK 的签名信任和生命周期；JS 引擎与 TS/Node 模块兼容集合；Android `module` 对 Node `name` 的映射；SessionEvent/Room 一致性协议。评审通过后再提交可编译 SDK、模板、cookbook 和示例插件。
