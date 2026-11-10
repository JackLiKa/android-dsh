# adsh Android 工程

包名 `com.example.adsh`，最低 Android 10（API 29），arm64-v8a。应用把原版 dsh Web 引擎作为唯一会话与工具运行时，Compose 界面通过本机 HTTP + WebSocket 直接读写该引擎；不再维护第二套 Chat Completions 对话链路。

- [架构与实现](docs/architecture-android.md)
- [功能对齐状态](docs/parity-status.md)
- [Kotlin 插件 SDK 契约](docs/plugin-sdk-android.md)
- [运行时来源与校验](docs/runtime-provenance.md)
- [真机验证记录](docs/real-device-verification.md)
- [离线语音识别（Vosk）](docs/vosk-offline-speech.md)
- [本地增强语音识别（sherpa-onnx）](docs/sherpa-offline-speech.md)

## 已实现能力

### 1. 统一对话运行时

- `DshEngineService` 以前台服务启动随附的 Node/dsh：`node --expose-internals bin.js --profile web --no-open`。payload 仅在首次启动（或 SHA 变化）时校验并解压，之后按 SHA 目录直接复用；系统 CA 证书包也只在信任库变化时重建。打开应用会自动启动引擎（可在设置页关闭），手动停止后自动启动暂停，直到再次点“启动引擎”。
- `DshWebClient` 完成带 token 的登录、健康探测、`POST /api/<endpoint>` 一元 RPC，以及 `/api/remote.mux` 多路 WebSocket。断线按 `500ms/1s/2s/4s/8s/10s` 退避重连，并重开全部流。
- `DshChatController` 负责 `session/list|create|prompt|follow|cancel|modelCatalog|selectModel`、`credentials/set`，消费 follow 流的 `snapshot`、`event`、`assistant-stream` 帧，投影出消息、工具调用、用量、运行态与错误。
- 审批与提问通过 mux 的 `waterfall` 事件送达 Compose，用户选择后以 `approval/request` 或 `user-questions/request` 的 result 回写；断线重连时按 `eventId` 去重。
- 以 `/` 开头的输入按引擎斜杠命令处理，经 `commands/execute` 下发；`command/run` 与 `command/done` 投影为独立的命令气泡，失败时标红并展示引擎返回文本。
- 模型目录与推理强度来自引擎 `session/modelCatalog`；选择结果写入引擎会话，而非本地偏好。
- Compose 对话页支持流式 Markdown、工具卡片、停止生成、错误重试、会话切换、空态建议项和模型标签截断。

### 2. 引擎生命周期、日志与恢复

- 打开应用且引擎空闲或已休眠时自动启动，避免每次手动初始化；“打开应用时自动启动引擎”开关可关闭，手动停止会暂停自动启动。`EngineStartPolicy` 有单元测试。
- 设置页可启动、停止、刷新与重启引擎，并显示引擎、连接、模型和空闲状态。
- 前台引擎在应用退到后台且连续 5 分钟无任务时自动休眠；回到应用会自动恢复。`EngineIdlePolicy` 有单元测试。前台标志在 `MainActivity.onStart/onStop` 中直接写入服务，避免解绑后状态滞留。
- 应用回到前台时会重新订阅当前会话，用新快照重建消息，避免后台期间丢失最后一条回复。
- 异常退出按 1 秒、2 秒重启；连续 3 次失败后停止并给出中文提示，可在日志页一键进入安全模式。
- 安全模式执行 `--profile rescue --from-default-profile web`，从随附模板重建干净的 rescue 配置。
- 引擎日志持久化并按 1.5 秒轮询展示；支持刷新、清空、复制到剪贴板、错误/警告着色；日志中的 token 已脱敏。
- 退出代码、运行时长与日志末尾会记录在崩溃归因中。

### 3. 设备能力桥（可选）

设置页的“设备能力”开关是显式 opt-in，关闭后立即撤销：

- 应用在本机回环地址启动临时端口的 broker，使用 32 字节随机 token 做常量时间校验。
- 引擎通过 `--patch` 叠加 `@deepseek-ai/dsh-mcp-client`，以 stdio 启动随附的零依赖 MCP server；工具统一暴露为 `mcp__adsh_device__*`。
- 提供 21 个工具：设备信息、通知列表、界面树快照、截图 OCR、按坐标/文本/标签点击、长按、滑动、输入文本、剪贴板写入、分享文本、撰写邮件、打开应用、应用列表、设置闹钟、设置计时器、查询天气、预填短信、直接发送短信、打开拨号、朗读文本、停止朗读。截图 OCR 使用随附的中文 ML Kit（完全离线），用于微信、抖音等屏蔽无障碍节点的应用；点击与长按在审批后若目标应用仍在前台会复用原坐标，否则重新定位。天气查询使用 Open-Meteo（免 API Key），一次调用返回当前天气与三天预报。
- 界面树限制节点数与深度；点击按“精确文本 → 部分文本 → 索引 → 可点击祖先 → 坐标手势”降级。
- 无障碍与通知监听权限由系统单独授予；设置页展示授权状态、broker 端口和最近 6 条设备调用记录。
- 所有会产生副作用的调用在执行前先经过本地安全策略，严格程度为“拒绝 > 询问 > 允许”：支持“仅规则 / 严格 / 标准 / 每次确认”四档预设、只读模式、金融与密码管理应用保护、密码/验证码输入保护，以及按应用、类别、动作、标签的规则。
- 需要“询问”的调用会阻塞对应的 broker worker，最多 60 秒；只有应用内弹窗或通知栏的“允许一次 / 始终允许 / 拒绝”能完成审批，超时按拒绝处理，代理无法自行批准。Android 13+ 需要 `POST_NOTIFICATIONS` 才能在后台收到审批通知，设置页会提示并引导授权；选择“始终允许”会写入一条 ALLOW 规则（仅当请求带包名时可用）。
- 每次决策追加到 `files/device-audit.jsonl`，只记录脱敏后的摘要（不记录短信正文或输入文本）；设置页的“安全决策记录”可查看与清空。
- 关闭开关会删除 patch、停止 broker 并重启引擎；引擎未运行时只保存设置。

安全边界：broker 仅监听 `127.0.0.1`；token 只写入应用私有 DataStore，并通过 MCP 配置的 `config.env` 注入（子进程环境会清除凭据型变量与 `DSH_*`）；界面树与通知可能包含敏感信息，默认关闭。策略完全在手机本地执行；MCP 客户端单次调用超时 90 秒，覆盖 60 秒审批与 15 秒短信回执。

### 4. 语音、唤醒与自动化

- 对话页麦克风改为**长按说话**：按住约 350ms 开始识别，松手结束；识别中显示 8 段动态音量条与实时部分结果，最终文本写入输入框后仍可继续编辑，选择建议可替换为最终结果。首次使用请求 `RECORD_AUDIO`。
- 语音引擎可在设置页选择“自动 / 本地增强 / Vosk 离线 / 系统”，语言可选普通话或粤语。“自动”在普通话下优先随附的 sherpa-onnx 流式模型（实时上屏、热词与标点），不可用时回退系统 `SpeechRecognizer`，再回退 Vosk；粤语仍依赖系统语音服务。本地增强默认开启“数字与时间格式化”（明确的日期、时间、百分比、数量转阿拉伯数字）和“最终结果二次解码”（松手后用 Paraformer 离线模型重解整句，模型后台预热）。进入对话页会后台预热本地模型。集成、性能与验证见[本地增强语音识别](docs/sherpa-offline-speech.md)。
- 流式回复同时展示思考摘要与正文，并给出阶段提示（正在思考 / 正在整理答案）、已生成字数、耗时和进度条；长回答边生成边渲染，减少等待焦虑。
- 助手回复可通过标题栏的朗读开关交给系统 TTS 播报；只朗读已完成的回复，按消息去重，停止生成或出错时不朗读。
- 无障碍悬浮球默认关闭。开启后常驻屏幕边缘并可拖动：轻点打开对话并开始语音，长按只打开对话，不引入 `SYSTEM_ALERT_WINDOW` 权限。
- 手势唤醒：设置页可开启“主屏幕键长按 3 秒唤醒”。应用声明 `ACTION_ASSIST` 并可申请默认助手角色；在转发按键事件的系统上由无障碍 `onKeyEvent` 计时触发，否则由系统默认助手长按入口触发，均直接打开对话并开始聆听。短按 Home 不会误触发。
- “自动化”页可保存常用任务并一键执行；内置闹钟、计时器、短信预填、查看屏幕、总结通知等示例模板。
- 设置页新增“远程控制电脑端”：填入电脑上 `dsh web` 打印的带 token 地址后，Compose 直接连接该远程引擎；地址加密保存在本机，可随时断开回到本机引擎。
- 闹钟/计时器/拨号走系统公开 Intent；`compose_sms` 只打开信息应用并预填，`send_sms` 通过 `SmsManager` 直接发送，需在设置页单独授予“短信发送”权限，并且只在用户明确要求发送时调用。`send_sms` 会等待最多 15 秒的发送回执（从调用开始计时，包含系统确认耗时），返回“短信发送成功”或具体失败原因（含运营商错误码）。一加/ColorOS 等系统会在发送时弹出系统确认（约 8 秒倒计时，连续发送均观察到该确认），需在手机上点“发送”后才会发出；未确认时系统会取消该次发送，工具返回“未收到完整发送回执”。
- 闹钟/计时器在清单中同时声明 `android.permission.SET_ALARM` 与 `com.android.alarm.permission.SET_ALARM`；系统时钟实际校验的是后者，缺少时发送 Intent 会被 `Permission Denial` 拒绝。

### 5. 界面与主题

- Material 3 浅色/深色主题，支持跟随系统、浅色、深色三档，选择持久化到 DataStore。
- 品牌主色 `0xFF2E5CE6`，统一中文字体行高、6/10/16/20/28dp 圆角与 4dp 栅格。
- 6 套强调色（经典蓝、青绿、紫罗兰、琥珀橙、玫红、森林绿）持久化保存；强调色同时驱动 primary 与 secondary 系列，主题选择、导航选中态、输入框与按钮同步换色。
- 对话头部改为两行布局：标题独占一行，连接状态与模型标签在第二行，1.3 倍字体下不再互相挤压。
- 助手回复按 Markdown 渲染：标题、无序/有序列表、引用、分隔线、行内粗体/斜体/删除线/代码/链接；代码块显示语言标签并支持一键复制，正文可用系统选择器复制。
- 每条助手消息提供“复制消息/分享消息”；流式回复底部显示已生成字数与耗时。
- 生成过程中可继续输入下一条消息：发送后进入“已排队，本轮结束后自动发送”状态，可随时取消；本轮结束后自动发出。
- 底部导航在 360dp + 1.3 倍字体下保持单行标签。
- 对话、历史、工作区、自动化、插件、设置、日志均有加载/空/错误态；设置页按卡片分组（引擎与模型、外观、远程控制、设备能力、手势唤醒、模型与密钥）。
- 系统状态栏与导航栏设为透明，由应用背景延伸绘制；深浅色切换时同步图标明暗。
- 界面文案使用应用专属资源键，避免与依赖库的同名翻译冲突（例如繁体中文设备上的“已选”）。
- 已按 360dp 宽 + 1.3 倍字体验证无横向溢出，深色与浅色主题均做过运行截图检查。

## 验证状态

- `./gradlew --offline :app:testDebugUnitTest`：57 项 JVM 测试通过。
- `./gradlew --offline :app:lintDebug`：Android Lint 0 error、45 warning、4 information；Compose 检查器的二进制崩溃已通过把 Compose BOM 固定到 `2024.12.01`、lifecycle 固定到 `2.8.7`（与 Kotlin 2.0.21 / AGP 8.7.3 同代）根除，不再禁用会崩溃的检查器，仅保留 `ExpiredTargetSdkVersion` 豁免。
- `./gradlew --offline :app:assembleDebug`：调试 APK 构建通过（约 561 MiB，含 Node/dsh payload、Vosk、sherpa 流式模型与 Paraformer 二次解码模型）。
- 2026-11-09 离线语音在 arm64 模拟器 `emulator-5554`（Pixel 6，Android 14 / API 34）验证：`SherpaSpeechRecognitionTest` 3 项与 `OfflineSpeechRecognitionTest` 4 项 instrumentation 全部通过。sherpa 直接解码 16 kHz 中文 WAV，流式结果为 `欢迎大家来体验达沿推出的语音识别模。`（12 个部分结果、带标点），Paraformer 二次解码结果为 `欢迎大家来体验达摩院推出的语音识别模型。`；Vosk 回归输出 `欢迎 大家 来 体验 打磨 远 推出 的 语音识别 模型`。设置页 ITN/二次解码开关默认开启，关闭后强制停止并重启仍保持关闭。详见[本地增强语音识别](docs/sherpa-offline-speech.md)与[离线语音识别](docs/vosk-offline-speech.md)。
- 2026-11-09 在 arm64 模拟器完成引擎链路验证：安装器完成 payload 校验/解压与 Node 启动，界面显示“已连接 dsh 引擎”；长按麦克风进入“正在聆听…”动态音量条，松手结束，无语音输入时提示“没有识别到语音内容”。引擎在默认 75 秒启动超时内就绪。
- 模拟器 `emulator-5554`（Pixel 6，Android 14，360dp + 1.3 字体）实测：
  - 引擎启动、登录、健康探测成功；模型目录显示 `DeepSeek-V4.1-Flash`。
  - 会话、follow 流、历史列表、日志复制、深色主题保持与设备桥开关撤销均正常。
  - Markdown 回复的标题、有序/无序列表、代码块（语言标签与复制按钮）渲染正确；`Reply__OK__only` 中的下划线未被误判为斜体。
  - 助手消息“复制消息”写入系统剪贴板并弹出提示；系统剪贴板预览显示完整正文。
  - 长回复生成中发送第二条消息会显示“已排队，本轮结束后自动发送”，本轮结束后自动发出。
  - 主题色切换为紫罗兰后，主题选择、导航选中态与强调色同步变化；浅色主题显示正常，重启后保留选择。
  - 对话头部标题、连接状态与模型标签在两行布局下完整显示，底部导航标签保持单行。
- 真机 `PJA110`（Android 16，1240×2772）使用有效 DeepSeek API key 实测：
  - 流式中文回复逐字渲染并最终收敛；关闭思考过程/展开工具卡片正常。
  - 会话持久化后从历史列表重开，消息与工具结果完整还原。
  - 工具调用链 `mcp__adsh_device__device_info`、`notifications_list`、`screen_snapshot`、`screen_tap`、`screen_swipe`、`screen_input`、`app_open` 全部成功；审计列表记录调用。
  - 早期版本 MCP `tools/list` 返回 16 个工具；通过 adb forward 以真实 MCP stdio 协议逐一调用全部 16 个工具：设备信息、应用列表、通知、界面树、点击、滑动、输入、打开应用、闹钟、计时器、短信预填、短信直发、天气、拨号、朗读与停止朗读均返回成功；模拟器与真机的 `send_sms` 均返回“短信发送成功”，模拟器发出的短信可在系统 `content://sms/sent` 中查到，真机无线电日志记录 `Status = Success`；空收件人返回中文校验错误，失败回执按运营商错误码解释，15 秒未收到完整回执返回超时提示；真机 ColorOS 在系统确认浮层点“发送”后返回成功；不确认时约 10 秒后系统拒绝发送，工具在 15 秒预算内返回“未收到完整发送回执”；无 token 返回 401。
  - 麦克风运行时授权弹窗正常；真机 PJA110 未安装语音识别服务，界面显示“当前设备没有可用的语音识别服务”。悬浮球轻点可打开对话并触发语音请求，长按只打开对话。
  - 自动化页可创建/删除任务并显示模板；设置页“远程控制电脑端”与手势唤醒开关渲染正常。
  - 切到系统设置再回到应用后，最终回复会实时补齐（前台重新订阅快照的修复已生效）。
  - 深色主题下状态栏与导航栏颜色与界面一致。
  - 应用退到后台并保持无任务 5 分钟后，引擎日志记录“闲置 5 分钟，引擎进入休眠以节省电量”，服务与 Node 进程均退出；再次打开应用会自动恢复。
  - 启动体验验证：冷启动、强制停止后重新打开均在无点击时自动启动引擎，约 9 秒就绪，日志记录“运行时校验通过，复用已解压版本”；`system-ca.pem` 仅首次重建，后续启动 mtime 不变；手动停止后重新打开不会自动启动，点“启动引擎”后自动启动恢复；关闭“打开应用时自动启动引擎”后不再自动启动，重新开启会立即启动。
  - 系统语言为繁体中文（zh-Hant-HK）时，启动配置显示“web (已选)”，不再被依赖库翻译覆盖。
  - 2026-11-09 按真实用户口吻完成端到端场景验收：① “10 分钟后、标签测试闹钟”的闹钟创建成功并按时响铃；② 2 分钟计时器创建成功并响铃（两项均由用户手动关闭）；③ 澳门天气查询返回 17:15 晴、22.8°C、降水概率 98%，并给出跑步建议；④ Web 搜索与摘要返回三条要点和可点击来源；⑤ 要求编写 `collatz.py` 并用 27 运行，文件写入工作区且输出（长度 112、峰值 9232）正确；⑥ Gmail 邮件全流程：模型通过设备桥读屏、点击“撰寫”、填写收件人/主题/正文并点“傳送”，期间在 ColorOS“想要開啟 Gmail”弹窗上选择“僅本次允許”，最终收件箱出现 17:36 的未读自发邮件，主题与正文一致。⑦ 审批拦截面：系统语言为繁体时，策略现在把“傳送”“發送”“刪除”“購買”等繁体标签归类为发送/破坏性操作（此前只认简体，Gmail 的“傳送”会被当作普通点击直接放行）。实测 `screen_tap {"text":"傳送"}` 弹出“发送消息需要确认”：点“拒绝”返回“操作已被安全策略阻止……”，点击未执行；点“允许一次”后应用先把 Gmail 拉回前台，再重新定位并点击“刪除”，Gmail 显示“已刪除 1 個對話”。
  - 2026-11-09 多应用消息与发布验收（PJA110）：微信朋友圈（图片 + 文案，设为私人）、QQ 空间（仅自己可见）、抖音图文（私密，随后在“我 → 作品 → 私密作品”核对文案）均发布成功；微信、QQ、Telegram、QQ 邮箱与 Gmail 邮件均发送成功。Gmail 自发邮件出现在收件箱，界面提示“已傳送”，审计分类为 `MESSAGING`。
  - 策略门禁补充：繁体“發佈”、简体/繁体“发作品/發作品/限时日常”等统一归为 `SEND_MESSAGE`；实测“私人發佈”“限时日常”“发作品”都会弹出“发送消息需要确认”，拒绝时不执行并写入 `DENY` 审计，避免发布类按钮绕过审批。
  - 审批浮层改为无障碍悬浮窗，审批期间目标应用保持前台、键盘与布局不漂移；允许后桥在重放点击前等待 350ms，避免右上角发布按钮被浮层吞掉。
  - 截图 OCR 回退：微信、抖音等无障碍树被屏蔽的应用通过 `screen_ocr` 识别后按坐标点击；文本输入先在 adsh 前台写入剪贴板，再回到目标应用长按输入框点“貼上”，避免后台剪贴板写入被忽略。
  - 轨迹：每轮结束追加到 `files/logs/trace.jsonl`，记录状态、耗时、token/缓存、逐步 TTFT、TPS、工具耗时与截断后的文本预览；启动后按 `sessionId#turn` 去重并补写已完成轮次。真机轨迹页显示“已归档 18 轮 · logs/trace.jsonl”，摘要新增缓存命中率、上下文占用（如“26% · 260.2k / 1M”）与会话 Token，数据读取 Host 的 `session_projcache`，与 Web 轨迹页数字一致；仍支持复制与分享 Markdown。
  - 原版 Web 轨迹页：新增用量条，显示缓存命中率（`cacheRead / (uncachedInput + cacheRead + cacheWrite)`，无计费输入显示 `—`，部分命中不会四舍五入成 100%）、上下文占用、会话 Token 与请求数；明细表每行也显示缓存命中率。真机实测“缓存命中 98.6% · 上下文 10% · 104k / 1M · 会话 Token 11.3M”。
  - WebView 白屏修复：Compose `AndroidView` 创建 WebView 时尚未获得高度，Blink 视口被初始化为 0，`html, body, #root { height: 100% }` 因此全部塌陷；现在先用 `onGloballyPositioned` 量出可用高度，再以显式像素高度创建 WebView，真机 `100vh` 恢复为 640 CSS px。
- 最终调试 APK：`apks/adsh-debug.apk`（609,694,613 字节），SHA-256 `f93044812a7092218b980d30c3910d01a61a21924acc1df00214c7d1225ad1bc`。

## 构建与安装

### 准备运行时

本仓库只跟踪代码和文档。APK、Node/dsh payload、参考 payload 备份与 Vosk 中文模型均超过 GitHub 普通文件限制，由 `.gitignore` 排除。首次克隆后先准备运行时；本地生成的 APK 包含完整 payload，运行时不依赖再次下载。参考发布包的来源、许可证与 SHA-256 见[运行时来源](docs/runtime-provenance.md)。

离线语音模型（65 MiB）在构建前单独准备：

```sh
python3 tools/fetch_vosk_model.py
```

脚本会下载 `vosk-model-small-cn-0.22`、校验 SHA-256 并解压到 `app/src/main/assets/vosk/model-cn`，详见[离线语音识别](docs/vosk-offline-speech.md)。

本地增强引擎（sherpa-onnx AAR + 约 310 MiB 模型）同样不提交仓库，首次构建前运行：

```sh
python3 tools/fetch_sherpa_models.py
# 不需要最终结果二次解码时，可跳过 78 MiB 的 Paraformer 模型：
# python3 tools/fetch_sherpa_models.py --skip-second-pass
```

脚本会下载并校验 AAR、流式 Zipformer、Silero VAD、标点模型与 Paraformer small int8，已存在且哈希一致时跳过；详见[本地增强语音识别](docs/sherpa-offline-speech.md)。

在本仓库同级检出上游源码并安装依赖（源码修复脚本使用其 `restructure@3.0.2` 完整包）：

```sh
git clone https://github.com/deepseek-ai/deepseek-harness.git ../upstream
git -C ../upstream checkout 687f282e
(cd ../upstream && pnpm install --frozen-lockfile --ignore-scripts)
```

在 Android 工程根目录获取参考发布 APK，按指纹核验后生成修复过的 payload：

```sh
mkdir -p runtime/previous app/src/main/assets/dsh
gh release download v1.20.1 --repo woaiys3/deepseek-harness-android-app \
  --pattern DeepSeekHarness.apk --dir runtime/previous
echo 'c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa  runtime/previous/DeepSeekHarness.apk' | shasum -a 256 -c -
unzip -p runtime/previous/DeepSeekHarness.apk assets/payload.zip \
  > runtime/previous/payload-975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab.zip
python3 tools/repair_runtime_dependency.py \
  --source runtime/previous/payload-975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab.zip \
  --package ../upstream/node_modules/.pnpm/restructure@3.0.2/node_modules/restructure \
  --output app/src/main/assets/dsh/payload.zip
echo '6f5003bd7729135683460d9d85cb0c1b2673dbfea6584046a0de8d6968345fb7  app/src/main/assets/dsh/payload.zip' | shasum -a 256 -c -
```

### 构建

工程使用 Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Compose BOM 2026.06.01、DataStore 1.1.1、SnakeYAML 2.2、OkHttp 4.12.0，JDK 21，compileSdk 35。

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:check :app:assembleDebug
```

`targetSdk` 固定为 28，是为了让随附 Node ELF 在应用私有目录中可执行；升级前必须重新验证运行时与平台策略。

若工作区暂时没有 `app/src/main/assets/dsh/payload.zip`（仓库不跟踪），仅做界面/语音验证可在构建命令后加 `-x checkRuntimePayload`；此时 dsh 引擎不可用，语音识别等不依赖引擎的功能仍可验证。

### 使用替代 payload 打包（可选）

如果拿不到固定指纹的参考 payload，可以把任一满足 `runtime/bin/node`、`dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`、`dshhome/profiles/web/package.json` 布局的 arm64 运行时放到 `app/src/main/assets/dsh/payload.zip`，并用 `-PpayloadSha=<sha256>` 覆盖校验值：

```sh
sha=$(shasum -a 256 app/src/main/assets/dsh/payload.zip | cut -d' ' -f1)
./gradlew :app:assembleDebug -PpayloadSha=$sha
```

安装器会自动恢复 `runtime/lib/LINKS.txt` 里的 soname 别名，并把 ELF 的 Termux `DT_RUNPATH` 改写为 `$ORIGIN`（详见[运行时来源](docs/runtime-provenance.md)）。这样打出的 APK 的运行时并非固定指纹版本，发布前必须重新核对来源、许可证与校验值。

`:app:check` 同时执行单元测试与 `lintDebug`。AGP 8.7.3 自带的 Kotlin 分析 API 与新 Compose/lifecycle lint 检查器存在二进制不兼容（`IncompatibleClassChangeError`），因此精确禁用会崩溃的 7 个检查：`NullSafeMutableLiveData`、`FlowOperatorInvokedInComposition`、`UnrememberedMutableState`、`RememberInComposition`、`AutoboxingStateCreation`、`FrequentlyChangingValue`、`StateFlowValueCalledInComposition`；`targetSdk = 28` 是运行时硬性要求，故豁免 `ExpiredTargetSdkVersion`。其余检查保持开启。

### 安装与启动

```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.example.adsh/.MainActivity
```

MainActivity 支持 adb extras `profile`、`task` 与 `dump-config`：

```sh
adb -s <serial> shell am start -n com.example.adsh/.MainActivity --es profile web --es task "hello"
adb -s <serial> shell am start -n com.example.adsh/.MainActivity --es profile web --ez dump-config true
```

设备桥需要手动授予无障碍与通知监听权限，可在设置页跳转系统页面。

## 已知限制

- 重装 APK 后，系统可能重置无障碍/通知监听授权，需要重新在设置页开启；应用会显示当前授权状态。
- 随附运行时自带的 `android_*` 工具插件（无障碍、虚拟屏、剪贴板、通知发送等）依赖官方 App 的本地 3181/3081 服务，adsh 不提供这些服务，调用只会误报“屏幕助手不可用”。引擎每次启动都会写入 `adsh-unavailable-tools.yml`，禁用 `tool-accessibility`、`tool-vscreen`、`tool-android`、`tool-shizuku`；所有设备操作统一走 `mcp__adsh_device__*` 设备桥。
- 设备桥依赖无障碍与通知监听权限，不同厂商 ROM 可能限制后台前台服务或无障碍连接（真机 PJA110 上已通过）。
- 审批弹窗属于应用内 Dialog：在应用内点“允许一次”时，应用会先把发起操作的应用切回前台再执行；ColorOS 首次会弹出“想要開啟 X”确认，需在 15 秒内允许（选“30 天內允許”可避免重复弹窗），否则该次操作返回“已允许，但无法把 X 切回前台”。在通知栏直接点“允许一次”不会打断前台应用，无需切换。
- payload 来自第三方发布 APK 并由本地脚本修复，升级与再分发前需重新核对许可证和校验值；详见[运行时来源](docs/runtime-provenance.md)。
- 历史会话与工作区由 dsh 引擎持久化；应用内“工作区”页只负责选择新会话的初始目录，不迁移引擎已有会话。
- 本地增强引擎仅支持普通话；流式模型仍可能把“达摩院”识别为“达沿”，默认开启的 Paraformer 二次解码会在松手后纠正这类整句错误。热词受 2002 字符词表约束。粤语仍只能走系统语音服务。
- 流式模型、标点与 Paraformer 常驻内存：默认（二次解码开启）Native Heap 约 461 MB、应用总 PSS 约 570 MB；关闭二次解码约 216 MB / 315 MB。低内存设备可在设置页关闭二次解码，并用 `--skip-second-pass` 不打包该模型。
- sherpa 资产约 310 MiB（含 Paraformer）、AAR 47 MiB，Vosk 模型 65 MiB；三者与 payload 一样不入库，首次构建需分别运行 `tools/fetch_vosk_model.py` 与 `tools/fetch_sherpa_models.py`。
- 当前只打包 `arm64-v8a`；x86_64 模拟器无法运行 sherpa 原生库，只能使用系统识别或 Vosk。
- 随附 dsh payload 只含 arm64 的 Node ELF；arm64 模拟器可完整启动引擎，x86_64 模拟器仍需关闭 payload 校验或用 instrumentation 覆盖识别层。
- Home 键长按依赖系统是否把按键事件转发给无障碍服务；Android 12+ 上更可靠的方式是把本应用设为默认助手。未授予任一条件时长按不会唤醒。
- 天气查询需要网络，使用 Open-Meteo 公开接口；城市名需能在其地理编码库中匹配，查询失败会返回明确中文错误。
