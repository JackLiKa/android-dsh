# Android 与 dsh 的功能对齐状态

本表以同工作区的 [原架构](../../upstream/docs/architecture.md)、[能力图](../../upstream/docs/capability-seams.md)、[事件表](../../upstream/docs/event-producer-consumer.md) 和各包 README 为验收依据。`已接入` 表示 Compose 与设备端运行验证同时通过；仅编译、仅配置预览或仅有隔离探针的条目不计为完成。

| 功能域 | 当前 Android 证据 | 待验收 |
|---|---|---|
| Cordis Service 注入、effect、五种事件 | `PluginSdk.kt` 提供 Kotlin 侧容器、依赖等待、fiber 卸载与五种事件分发；`PluginRuntimeTest.kt` 覆盖 | 与真实 dsh profile 的 Service 图对齐；Kotlin 插件安装与热卸载 |
| Profile、bundle、有序 patch、`dump-config` | `ProfileCatalog.kt`、`ProfileComposer.kt`、`ProfileStartupDiagnostics.kt`；设置页与插件页可查看解析树 | 将配置行绑定到实际 Service；preset/isolate、`!!js`、启动失败规则 |
| Session、事件流、恢复 | Compose 通过 `DshWebClient` 调用 `session/list|create|prompt|follow|cancel`；`/` 开头的输入经 `commands/execute` 执行并以命令气泡展示 `command/run|done`；真机已验证创建会话、流式回复、历史重开还原、斜杠命令切换审批策略，以及前后台切换后由新快照补齐消息 | 长会话、断电/进程崩溃后的 generation 回放 |
| System prompt、工具 schema、AgentLoop | 由内置 dsh Web profile 提供；Compose 不再自行拼装 prompt；审批与提问卡片已真机闭环 | 长上下文压缩与工具 schema 变更的设备端回归 |
| LLM Provider 与流 | `session/modelCatalog`、`selectModel`、`credentials/set`；真机用有效密钥完成中文流式回复并收敛，无效密钥的认证失败也会原样回显 | 推理强度、多供应商与离线 Provider |
| 审批与提问 | mux `waterfall` 事件驱动 Compose 审批/提问卡片，结果按 `eventId` 回写；真机切到 `workspace-write` 后，`bash` 提权请求弹出“工具需要审批”，点“允许一次”后命令实际执行；`ask_user_question` 问题卡片可选可提交，选择结果回传引擎；重连去重 | 拒绝路径、多问题/多选、会话级“始终允许” |
| Shell、subprocess、terminal、fs、sandbox、LSP | 由 dsh runtime 在应用私有目录执行；Compose 不直接暴露 | 沙箱策略、文件权限、审批与敏感路径测试 |
| Skill、web search/fetch、compaction、context | 随 dsh Web profile 提供 | 逐项设备端真实调用与 UI 呈现 |
| Subagent、Agent Teams、workflow、webhook、jobs | 随 dsh runtime 提供，Compose 暂无专用界面 | 任务板/工作流可视性与后台取消 |
| Todo、plan、preset、guard、self-modification、hooks | 随 dsh runtime 提供 | 日志投影、策略事件与用户可见状态 |
| Identity、settings、credentials、approval | API key 通过 `credentials/set` 写入引擎；本地仅保存主题、工作区、设备桥 token | 引擎设置页统一、凭据轮换与审计 |
| 统计 UI、Token、轨迹 | Compose 展示 `assistant/message` 的 usage；原版 Web 内置统计面板 | 设备端真实会话的 Token/缓存/耗时与工具轨迹核对 |
| Plugin Manager 与用户插件 | 原版 Web 运行时含 dsh-plugin-manager；Compose 插件页可查看配置树 | 搜索/inspect/install/remove/enable/disable、构建审批与版本豁免 |
| Web localhost、Compose/WebView | `DshEngineService` 启动引擎；`DshWebClient` 通过 token 登录一元 RPC 与 mux；Compose 对话全部走该链路；设置页可切换到电脑端远程引擎 | headless/sdk/acp profile、JSON-RPC/AIDL；真实远程主机联调 |
| 设备能力桥（Android 专属） | 可选 MCP stdio server + 回环 broker；15 个工具；无障碍/通知监听；审计列表；关闭即撤销。按真实 MCP stdio 协议逐一调用 15 个工具全部成功；闹钟/计时器已确认落入系统时钟，`send_sms` 经 `SMS_SENT` 广播返回发送成功/失败/超时回执，模拟器真实写入系统已发送库，真机无线电日志确认发送成功；真机 ColorOS 发送时需在系统确认浮层点“发送”（连续发送均出现，约 8 秒倒计时，否则系统取消），应用不绕过该确认；回执总预算 15 秒，未确认时按超时返回 | 闹钟/短信/拨号在更多 ROM 上的 Intent 兼容性、运营商对短号/敏感号码的拦截、其他 ROM 的权限稳定性、敏感信息遮罩、长时运行与审计导出 |
| 语音输入与播报 | `VoiceController.kt` 使用 `SpeechRecognizer`（zh-CN、部分结果），短按填输入框、长按识别后直发；运行时请求麦克风；`TextToSpeech` 按消息去重播报已完成回复；真机验证授权弹窗与无识别服务时的错误提示，模拟器验证识别流程与 `zho-CHN` 合成请求 | 带识别服务的 ROM 上验证中文识别、长按直发与 TTS 中断/恢复 |
| 手势唤醒 | 无障碍悬浮球（`TYPE_ACCESSIBILITY_OVERLAY`，默认关闭）：轻点打开对话并发起语音，长按只打开对话，可拖动并持久化位置；真机与模拟器均验证轻点跳转、授权触发 | 不同 ROM 上的悬浮层触摸、深色模式与多任务返回后的恢复 |
| 自动化与远程引擎 | 应用内“自动化”页保存一键任务并发送给引擎（模拟器验证创建、保存与执行回跳对话）；设置页填写 `dsh web` 的 token 地址即可连接电脑端引擎，地址加密保存并可断开；已用电脑端真实引擎验证会话创建与错误回传 | 定时触发/条件触发；真实远程主机的长连接、断网重连与多设备并发 |
| 工作区、工作目录 | `WorkspaceStore.kt` 创建应用私有目录；设置页选择默认工作区；引擎启动时传入 `DSH_WORKSPACE` | 外部 SAF 项目目录、原版 Web 会话实际 cwd 与文件 Provider 验证 |
| 多模态附件 | WebView 使用系统文件选择器，保持 `allowFileAccess=false` | 设备端多图/音频上传与视觉模型真实识别 |
| 引擎生命周期与恢复 | 前台服务、空闲 5 分钟停止、1s/2s 重启、连续 3 次失败停止、安全模式 rescue、日志轮询/复制/着色；模拟器与真机均已验证启动、桥重启与日志；真机验证后台 5 分钟后服务与 Node 进程自动退出；回到前台会重新订阅会话 | 低内存/后台限制下的厂商差异；安全模式下的插件兼容 |
| 主题与可访问性 | 浅色/深色/跟随系统、持久化、自定义字体与圆角；真机验证深浅切换与系统栏颜色一致；繁体中文下文案显示正确；360dp + 1.3 字体无溢出 | 横屏、平板、超大字体（2.0）与 TalkBack 标签 |

## 当前发布阻断

1. 随附 payload 来自第三方 APK 并由本地脚本修复，升级前需复核许可证、校验值与完整性。
2. 设备桥涉及屏幕内容与通知，仍需在更多厂商 ROM 上复核权限持久性与审计可见性；重装 APK 后系统可能重置无障碍/通知授权。
3. Android Lint 已纳入 `:app:check`，但受 AGP 8.7.3 与新版 Compose/lifecycle 检查器的二进制不兼容影响，精确禁用了 4 个会崩溃的检查；升级 AGP/lint 后可重新评估。
