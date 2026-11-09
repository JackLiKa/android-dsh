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
| 设备能力桥（Android 专属） | 可选 MCP stdio server + 回环 broker；16 个工具（新增 `get_weather`）；无障碍/通知监听；审计列表；关闭即撤销。按真实 MCP stdio 协议逐一调用工具成功；`get_weather` 经回环桥与 MCP stdio 双重验证（广州/深圳返回当前天气与三天预报）；闹钟/计时器已确认落入系统时钟，`send_sms` 经 `SMS_SENT` 广播返回发送成功/失败/超时回执，模拟器真实写入系统已发送库，真机无线电日志确认发送成功；真机 ColorOS 发送时需在系统确认浮层点“发送”（连续发送均出现，约 8 秒倒计时，否则系统取消），应用不绕过该确认；回执总预算 15 秒，未确认时按超时返回 | 闹钟/短信/拨号在更多 ROM 上的 Intent 兼容性、运营商对短号/敏感号码的拦截、其他 ROM 的权限稳定性、敏感信息遮罩、长时运行与审计导出、天气服务在城市名歧义与离线时的降级 |
| 语音输入与播报 | 长按麦克风约 350ms 开始识别、松手结束；识别中显示 8 段动态音量条、实时部分结果和“松开结束”提示；最终文本可继续编辑，并可一键替换为最终识别结果。设置页支持普通话/粤语与“自动/本地增强/Vosk 离线/系统”引擎选择：“自动”在普通话下优先随附 sherpa-onnx 流式 Zipformer（int8，约 168 MiB 模型 + 72 MiB 标点，实时上屏、Silero VAD 端点、热词偏置、最终加标点，标点与内存开销常驻），否则系统 `SpeechRecognizer`，再否则 Vosk（`vosk-model-small-cn-0.22`，65 MiB）；粤语只走系统。默认开启数字/时间格式化（ITN）与 Paraformer small int8（78 MiB）最终结果二次解码：松手后用整段音频重解，模型在后台预热，预热后约 0.1 秒。进入对话页后台预热模型。`TextToSpeech` 按消息去重播报已完成回复。2026-11-09 模拟器（arm64 / Android 14 / API 34）验证：7 项语音 instrumentation（sherpa 3 + Vosk 4）全部通过；流式解码输出“欢迎大家来体验达沿推出的语音识别模。”（12 个部分结果），二次解码输出“欢迎大家来体验达摩院推出的语音识别模型。”；开关状态经强制停止并重启后保持；长按录音、权限、无语音提示、默认助手 `ACTION_ASSIST` 自动聆听、57 项单元测试通过 | 真机长句听写与麦克风实测、不同 ROM 的录音设备与权限行为、更大声学模型精度对比、低内存设备按需关闭二次解码 |
| 手势唤醒 | 无障碍悬浮球（`TYPE_ACCESSIBILITY_OVERLAY`，默认关闭）：轻点打开对话并发起语音，长按只打开对话，可拖动并持久化位置。设置页新增“主屏幕键长按 3 秒唤醒”开关；应用声明 `ACTION_ASSIST` 并提供“设为默认助手”入口。模拟器（Android 14）注入 3.6 秒 Home 按下后成功打开对话并进入聆听；0.2 秒短按保持桌面、不误触发。按键过滤仅在系统转发事件时生效 | 不同 ROM 对 Home/助理键的拦截策略、更多默认助手实现、悬浮层深色模式与多任务恢复 |
| 自动化与远程引擎 | 应用内“自动化”页保存一键任务并发送给引擎（模拟器验证创建、保存与执行回跳对话）；设置页填写 `dsh web` 的 token 地址即可连接电脑端引擎，地址加密保存并可断开；已用电脑端真实引擎验证会话创建与错误回传 | 定时触发/条件触发；真实远程主机的长连接、断网重连与多设备并发 |
| 工作区、工作目录 | `WorkspaceStore.kt` 创建应用私有目录；设置页选择默认工作区；引擎启动时传入 `DSH_WORKSPACE` | 外部 SAF 项目目录、原版 Web 会话实际 cwd 与文件 Provider 验证 |
| 多模态附件 | WebView 使用系统文件选择器，保持 `allowFileAccess=false` | 设备端多图/音频上传与视觉模型真实识别 |
| 引擎生命周期与恢复 | 前台服务、空闲 5 分钟停止、1s/2s 重启、连续 3 次失败停止、安全模式 rescue、日志轮询/复制/着色；模拟器与真机均已验证启动、桥重启与日志；真机验证后台 5 分钟后服务与 Node 进程自动退出；回到前台会重新订阅会话 | 低内存/后台限制下的厂商差异；安全模式下的插件兼容 |
| 主题与可访问性 | 浅色/深色/跟随系统、持久化、自定义字体与圆角；6 套强调色同时驱动 primary 与 secondary 系列（主题选择、导航选中态、输入框同步换色）；助手回复按 Markdown 渲染（标题/列表/引用/代码块复制），消息支持复制与分享，生成中可排队下一条消息；真机验证深浅切换与系统栏颜色一致；模拟器在 360dp + 1.3 字体下验证两行头部、单行导航标签与紫罗兰/浅色主题；繁体中文下文案显示正确 | 横屏、平板、超大字体（2.0）与 TalkBack 标签 |

## 当前发布阻断

1. 随附 payload 来自第三方 APK 并由本地脚本修复，升级前需复核许可证、校验值与完整性。
2. 设备桥涉及屏幕内容与通知，仍需在更多厂商 ROM 上复核权限持久性与审计可见性；重装 APK 后系统可能重置无障碍/通知授权。
3. Android Lint 已纳入 `:app:check`；将 Compose BOM 固定到 `2024.12.01`、lifecycle 固定到 `2.8.7`（与 Kotlin 2.0.21 / AGP 8.7.3 同代）后，不再需要禁用会崩溃的 Compose 检查器，仅保留 targetSdk 28 的 `ExpiredTargetSdkVersion` 豁免。升级 AGP 后可重新评估 targetSdk 与新版 Compose 组合。
