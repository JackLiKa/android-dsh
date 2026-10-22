# Android 与 dsh 的功能对齐状态

本表以同工作区的 [原架构](../../upstream/docs/architecture.md)、[能力图](../../upstream/docs/capability-seams.md)、[事件表](../../upstream/docs/event-producer-consumer.md) 和各包 README 为验收依据。`配置预览` 表示已生成可检查的启动树；只有真实 Provider、Consumer 和跨重启测试同时通过时才标为完成。原仓库的 `packages/` 下当前有 342 个两级包目录，清单会随上游修订变化。

| 功能域 | 当前 Android 证据 | 下一项验收 |
|---|---|---|
| Cordis Service 注入、effect、五种事件 | `PluginSdk.kt` 与 `PluginRuntimeTest.kt`；生命周期局部实现 | 从真实 profile 启动，验证依赖等待、fiber teardown 与 HMR 无残留 |
| Profile、bundle、有序 patch、`dump-config` | `ProfileCatalog.kt`、`ProfileComposer.kt`；支持 YAML 解析和 Android 界面预览 | 将配置行绑定已验证的实际 Service；preset/isolate、`!!js` 与启动失败规则对齐 |
| Session append-only、Room 镜像、generation 和 migration | `LocalSessions.kt` 提供本地格式；`MessageProjection.kt` 仅投影文本 user/assistant；历史页可查看和恢复本地会话 | 原格式 v0 到当前版本读写、原子发布、恢复、标题/投影与断电测试 |
| System prompt、工具 schema、Agent、AgentLoop | 内置 dsh Web 已在独立运行时启动；Compose 对话仍直接调用文本适配器 | 真实 Session、请求/工具/取消/重试的 Android 设备端实测与两套界面数据迁移 |
| LLM Provider 与流 | 原版 Web 携带 dsh 的 Messages 适配器和原版模型选择器；Compose `LlmRegistry.kt` 的 DeepSeek Chat Completions 文本适配器支持手动选择 `deepseek-flash` / `deepseek-v4-pro`，本地会话记录路由；SSE 的 null content 已按类型跳过 | 统一凭证/供应商配置、Messages/工具/usage 实际请求、跨界面数据与离线 Provider 验收 |
| Shell、subprocess、terminal、fs、sandbox、LSP | 配置外尚无运行时 Provider/Consumer | 同一私有执行世界、策略事件、审批与真实命令/文件测试 |
| Skill、web search/fetch、compaction、context | 运行时待实现 | 每项 Service Definition、Provider、Consumer 及模型可见事件 |
| Subagent、Agent Teams、workflow、webhook、jobs | 运行时待实现 | roster/inbox/task replay、Worker 取消与可信 ingress 测试 |
| Todo、plan、preset、guard、self-modification、hooks | 运行时待实现 | 日志投影、isolate、policy、热卸载与回放 |
| Identity、settings、credentials、approval、interaction | `SecureSettings.kt` 实现 API 密钥本地加密；其余运行时待实现 | 配置 Provider、弹窗审批、持久审计和敏感操作保护 |
| Session persistence/title/telemetry、feedback、统计 UI | Room 本地事件镜像；原版 payload 内置 `sessionStats`、`tokenUsage`、`ui-trajectory`，Compose 输入区可直达原版会话 | 用原版真实会话验收 Token/缓存命中、速度/耗时、轨迹/工具调用数值与标题、遥测授权；Compose 会话数据迁移 |
| Plugin Manager 与用户 Kotlin/TS 插件 | 原版 Web 运行时含 dsh-plugin-manager；内置 pnpm 已在隔离测试 profile 安装本地 fixture bundle，配置树含其三个行；Compose 插件页仍只预览配置 | 在实际 Web profile 走通搜索/inspect/install/remove/enable/disable、build 审批、版本豁免、HMR/重启，验证用户 Kotlin/TS 插件 |
| Web localhost、Compose/WebView、headless、SDK、ACP | `DshEngineService.kt` 与 `NativeDshWebView.kt` 已在设备上启动 Web；旧版插件加载有 `ERR_MODULE_NOT_FOUND`，新 payload 在隔离探针成功导入缺失模块；启动流程增加 75 秒超时和降级提示 | 安装修复版后验证真实 Web 对话与插件/模型/工具工作流，并补 JSON-RPC/AIDL、ACP、headless 真实 profile 端到端测试 |
| 工作区、工作目录 | `WorkspaceStore.kt` 创建应用私有目录；`SessionWorkspaceLink.kt` 固定 Compose 会话所属工作区；引擎启动时传 `DSH_WORKSPACE`，对应参考项目初始 cwd patch | 外部项目 SAF 选择、原版 Web 创建/恢复会话时实际项目身份与文件/进程 Provider 设备测试 |
| 多模态附件与识别 | WebView 实现 `onShowFileChooser` 和 SAF URI 回调，保持 `allowFileAccess=false`；原版 DSH 包含图片输入与不支持模型时的诊断 | Android 设备端选取多图/音频并验证上传事件；经用户批准的视觉模型真实请求及识别输出 |
| 其他原仓库组件（API/host/client/MCP/attachment/storage 等） | 尚无对应 Android 集成证据 | 逐包登记入口、用户可见行为、平台映射、回归测试和模块所有者 |

用户界面的数据来源必须与相应运行时 Service 的持久事件一致。桌面页面目前支持的安装事务、工具卡、agent/Session 状态、远程连接与设置表单还需要绑定这些 Provider 后再验收。构建和设备安装证明 APK 可以运行，完整 dsh 功能的验收以本表所有域有对应测试为准。

当前发布阻断顺序：安装修复版 payload 并用真实 profile 验证插件加载、Web 对话和回放，随后校验内置 Android payload 的完整源码重建与许可、插件/模型/工具事务，再规划 Compose 本地事件/凭证迁移；逐一接入 Android 私有沙箱、headless/sdk/acp 与前台服务生命周期。`targetSdk 28` 的平台执行策略在 Android 10+ 设备上需单独评审；HTTP 401 或登录页可见仅验证服务监听与局部页面加载。
