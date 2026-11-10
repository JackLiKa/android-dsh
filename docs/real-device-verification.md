# 真机验证记录

状态：2026-11-06 18:40，最终调试 APK 在 OnePlus 真机与 Android 14 模拟器完成全量回归（含短信直发与发送回执）。构建与运行步骤见 [README](../README.md)，实现细节见 [架构](architecture-android.md)，功能对齐见 [对齐状态](parity-status.md)。

## 设备与构建

| 项目 | 值 |
|---|---|
| 设备 | OnePlus `PJA110`（型号代码 `OP5943L1`） |
| 系统 | Android 16（API 36） |
| 屏幕 | 物理 1240×2772，可用内容区 1240×2479，密度 560（约 3.5x / 354dp） |
| 系统语言 | `zh-Hant-HK`（产品语言 `zh-CN`） |
| 应用 | `com.example.adsh` 0.1.0（versionCode 1） |
| 构建 | `:app:check`（47 项 JVM 测试 + Android Lint）+ `:app:assembleDebug` 通过 |
| 产物 | `apks/adsh-debug.apk`，约 175 MB（183,131,742 字节） |
| SHA-256 | `55f5739827fcf7b56a3068380be1ec993ca416751809c251ef5279d7fbf2f335` |

验证使用的 DeepSeek API key 只通过设置页密码框写入一次，由 `SecureSettings` 加密保存到应用私有 DataStore；未写入源码、文档、日志或截图。引擎日志中的启动 token 统一显示为 `token=***`。

## 验收结果

| 场景 | 结果与证据 |
|---|---|
| 成功流式回复 | 有效密钥下发送中文提示，约 4 秒出现首批流式文本，最终回复逐字收敛；模型标签 `DeepSeek-V4.1-Flash`。 |
| 会话持久化与重开 | 会话写入引擎历史，列表显示标题、时间、工作区与模型；重开后消息与工具结果完整还原。 |
| 前后台恢复 | 回复过程中按 Home 再回到应用，`ON_START` 重新订阅当前会话并用新快照补齐最终回复，不再需要退出重进。 |
| 设备桥挂载 | 引擎日志显示 `initialize（2025 协议）`；包含短信直发工具后 MCP `tools/list` 返回 15 个工具，随机回环端口与 `mcp__adsh_device__` 前缀正确。 |
| 设备桥工具 | `device_info`、`notifications_list`、`screen_snapshot`、`screen_tap`、`screen_swipe`、`screen_input`、`app_open` 均通过模型调用成功；坐标点击与文本输入在真实界面上生效。 |
| 自动化工具 | `list_apps` 通过 MCP 清单校验；broker 冒烟中 `/apps?limit=3` 返回 58 同城等应用，`set_alarm`/`compose_sms`/`dial_number`/`speak_text` 缺参时返回中文错误，未带 token 返回 401。 |
| MCP 全量工具 | 模拟器与真机上按真实 MCP stdio 协议连接应用内 `mcp-server.mjs`：`tools/list` 返回 15 个工具，逐个调用 `device_info`、`notifications_list`、`screen_snapshot`、`screen_tap`、`screen_swipe`、`screen_input`、`app_open`、`list_apps`、`set_alarm`、`set_timer`、`compose_sms`、`send_sms`、`dial_number`、`speak_text`、`stop_speaking` 全部成功；`set_alarm` 越界、`compose_sms` 缺正文与 `send_sms` 空收件人均返回中文校验错误。 |
| 短信直发与回执 | 模拟器与真机运行时授予“短信发送”后，MCP `send_sms` 均返回“短信发送成功：10086/10001（1 条）”；模拟器 `content://sms/sent` 可查到记录，真机无线电日志为 `SmsMmsAnalytics Status = Success` 且 10001 回了短信。发送回执通过 `SMS_SENT` 广播按分片收集：全部成功返回“短信发送成功”，运营商拒绝返回具体错误码说明，15 秒内未收齐（从调用开始计时，包含系统确认耗时）返回“未收到完整发送回执”；审计记录分别带 `result=ok/fail/timeout`。真机 ColorOS 在发送时弹出繁中确认浮层“取消（8）/發送”（连续两次发送均出现），点击“發送”后约 4 秒收到成功回执；不点击则约 10 秒后系统取消，随后出现 `Permission failure: android.permission.SEND_SMS` / `can't send sms`，未产生已发送记录，工具在 15 秒总预算内返回“未收到完整发送回执”。该确认会阻塞 `sendTextMessage`，因此回执预算从调用开始计时而非发送返回后计时；MCP 侧请求超时为 25 秒。 |
| 系统 Intent 实测 | `set_alarm` 06:45 出现在系统时钟列表；`set_timer` 120 秒后 `dumpsys alarm` 出现 `TIMER_EXPIRED`；`compose_sms` 打开信息应用并预填收件人 `10086` 与正文，未自动发送；`dial_number` 打开拨号盘并填入 `10086`。 |
| 语音输入与朗读 | `speak_text` 返回“已开始朗读 8 个字符”，logcat 出现 `GoogleTTSServiceImpl: Synthesis request for locale zho-CHN`；点击麦克风进入识别流程，模拟器无有效麦克风输入时显示“没有听清，请再说一次”，应用不崩溃。 |
| 悬浮球唤醒 | 模拟器开启悬浮球后回到桌面仍可见；轻点后 `MainActivity` 回到前台并保留当前对话，未申请 `SYSTEM_ALERT_WINDOW`。 |
| 自动化页执行 | 在“自动化”页创建“语音闹钟验证”任务并点“执行”，应用自动切回对话页并发送任务提示词；模型请求发出后因模拟器测试 Key 无效返回鉴权错误并原样展示，发送链路正常。 |
| 远程引擎链路 | 设置页填入电脑端 `dsh web` 地址后显示“已连接远程引擎”；发送提示后电脑端创建真实会话并返回模型鉴权错误，手机端原样显示；带 cookie 的 RPC 成功，无 token 请求返回 401。 |
| 语音输入 | 首次触发麦克风弹出版权对话框；允许后进入识别流程。PJA110 当前没有可用的语音识别服务，界面显示对应错误条，不影响输入框与引擎。 |
| 悬浮球唤醒 | 开启后悬浮球显示在屏幕右侧；轻点跳到对话页并触发语音请求，长按仅打开对话；拖动位置写入本地偏好。 |
| 自动化与远程 UI | “自动化”页可显示模板、创建与删除任务；设置页“远程控制电脑端”输入框、连接/断开按钮与“手势唤醒”开关均正常渲染。 |
| 审计记录 | 设置页显示最近设备调用；重装应用会清空历史审计。 |
| 深色主题 | 浅色/深色切换即时生效；系统状态栏与导航栏改为透明并与界面背景一致；选择持久化。 |
| 空闲自动停止 | 退到后台且无任务 5 分钟后，日志记录“闲置 5 分钟，引擎已停止以节省电量”，`DshEngineService` 与 Node 进程均退出。 |
| 繁体中文文案 | 系统语言为 `zh-Hant-HK` 时启动配置显示 `web (已选)`；资源键改为应用专属的 `profile_selected_suffix`，不再被依赖库同名翻译覆盖。 |
| 引擎日志页 | 90 行日志正常展示，`token=***` 脱敏；刷新、复制、清空按钮可用，复制后应用无异常。 |
| 历史/工作区/插件 | 历史显示 4 个真实会话；工作区显示默认工作区与测试工作区及会话计数；插件页展示 11 项配置树。 |
| 斜杠命令 | 输入 `/permission workspace-write` 后经 `commands/execute` 执行，界面出现命令气泡与 `preset workspace-write` 结果，后续 `bash` 提权触发审批弹窗。 |
| 审批允许 | `workspace-write` + ask 策略下，模型以 `sandbox_permissions: danger-full-access` 请求 `bash`；弹出“工具需要审批”，点“允许一次”后 `id` 实际执行并回显 `uid=10479(u0_a479)`。 |
| 审批拒绝 | 同一条提权请求改点“拒绝”，模型确认命令未执行，界面无 `uid=` 输出。 |
| 问题卡片 | `ask_user_question` 弹出“Pick a Colour / Red or Blue?”；选择 Blue 并提交后，引擎向模型回传答案并继续回复。 |
| 问题自定义回答 | 在“自定义回答”框输入 Green 并提交，引擎回传 `selected: []` + `custom: "Green"`，模型确认收到。 |
| 多应用发送 | WeChat、QQ、Telegram、QQ 邮箱与 Gmail 均完成文本发送。Gmail 自发邮件出现在收件箱并提示“已傳送”；Telegram 显示“已发送于 18:18, 已读”；各次发送均产生 `action=SEND_MESSAGE`、`category=MESSAGING` 的 ALLOW 审计。 |
| 朋友圈与空间发布 | 微信朋友圈选择图片、写入文案并设为“私人”后发布成功；QQ 空间“说说”发布成功，权限显示“仅自己可见”。两者发布按钮（“私人發佈”“发表”）均触发审批。 |
| 抖音图文发布 | 通过“相册 → 图片 → 下一步 → 下一步 → 发作品”发布图文，权限设为“私密 · 仅自己可见”；随后在“我 → 作品 → 私密作品”打开作品，文案与“私密”标记一致。 |
| 发布类审批门禁 | 繁体“發佈”、简体/繁体“发作品”、以及“限时日常”均被归类为 `SEND_MESSAGE`。实测三者都会弹出“发送消息需要确认”；点“拒绝”后工具返回“操作已被安全策略阻止”，点击未执行，并写入 `decision=DENY` 审计。 |
| 截图 OCR 与剪贴板 | 微信、抖音的无障碍树为空或不可用时，`screen_ocr` 返回离线中文识别结果，配合坐标点击完成发送与发布；文本输入采用“前台写入剪贴板 → 回目标应用长按输入框 → 点貼上”，微信与朋友圈文案均成功落入输入框。 |
| 轨迹归档 | 设置 `TraceLogStore(files/logs/trace.jsonl)` 后，每轮结束追加一条 JSONL；重启后按 `sessionId#turn` 读取已有键并补写已完成轮次，真机文件从 1 条补到 8 条。轨迹页显示“已归档 18 轮 · logs/trace.jsonl”，轮次卡片可展开查看逐步 TTFT、生成耗时、输出 TPS、token 与工具耗时，并支持复制/分享 Markdown。 |
| 原生轨迹用量摘要 | 2026-11-10 真机摘要新增“缓存命中 97.6% / 上下文 26% · 260.2k / 1M / 会话 Token 9.2M”，token 行改为“缓存读取 8.9M”，数字与 `dsh-native-home/storages/session_projcache/sessions/session-<id>.json` 的 `tokenUsage`/`contextPressure` 一致；顶部返回、标题与分享不再与状态栏重叠。 |
| 原版 Web 轨迹页 | 打开“原版 dsh 界面”后对话与轨迹正常渲染。轨迹页用量条实测“缓存命中 98.6% · 上下文 10% · 104k / 1M · 会话 Token 11.3M · 请求 58 次”，横向可滚动查看；修前整页空白，DOM 中 `html`/`body`/`#root` 高度均为 0。 |

## 模拟器离线语音验证（2026-11-08）

环境：`Medium_Phone_API_37.0`（Android 17 / API 37、x86_64、1080×2400、WHPX 加速）；Gradle 8.9 + JDK 21；compileSdk 35；SDK `platforms;android-35` 与 `build-tools;35.0.0` 已安装。本工作区没有 `assets/dsh/payload.zip`（仓库不跟踪），因此 App 以 `-x checkRuntimePayload` 构建：debug APK 115,443,132 B（110.1 MiB，含 65 MiB Vosk 模型），dsh 引擎不可用；验证范围为离线语音与 UI 壳层。

| 场景 | 结果与证据 |
|---|---|
| 构建 | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -x checkRuntimePayload` 通过；测试 APK 1,008,018 B |
| App 启动 | `am start -n com.example.adsh/.MainActivity` 成功，`Displayed ... +4s201ms`；无 FATAL/ANR；UI 显示“引擎尚未启动 / 启动引擎”与 6 个底部 tab |
| 语音模型打包 | instrumentation `bundledModelIsPackagedInApk` 通过（`assets/vosk/model-cn` 存在，`VoiceInputController.available()` 为真） |
| 模型加载/幂等 | `loadsModelIntoPrivateStorageAndIsIdempotent` 通过；日志 `OfflineSpeechRecognizer: Vosk model loaded from /data/user/0/com.example.adsh/files/vosk/model-cn`，二次加载直接复用 |
| 中文 WAV 解码 | `decodesBundledChineseWavToText` 通过；FunASR 示例音频（16 kHz/单声道/16 bit，5.547 s）识别为 `欢迎 大家 来 体验 打磨 远 推出 的 语音识别 模型`，产生 41 个部分结果；小模型把“达摩院”识别为“打磨远” |
| 麦克风会话 | `microphoneSessionStartsAndStopsWithoutCrash` 通过（`AudioRecord` + Vosk `SpeechService` 启动 1.5 s 后停止，回调正常、无崩溃） |
| 单元测试 | `:app:testDebugUnitTest -x checkRuntimePayload`：52 项中 51 通过；`RuntimePayloadInstallerTest.releasePayloadMatchesPinnedDigestAndContainsRealHarness` 因缺少 payload 失败（预期）；同时修复了 `WorkspaceStoreTest` 在 Windows 上路径分隔符断言失败的跨平台缺陷 |

```sh
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.example.adsh android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am instrument -w -r com.example.adsh.test/androidx.test.runner.AndroidJUnitRunner
# 结果：OK (4 tests)
```

限制：模拟器是 x86_64，随附 payload 只含 arm64 Node ELF，引擎无法启动，因此没有执行“引擎就绪 + 点麦克风说话”的整条 UI 链路；该链路需真机或 arm64 环境补充验证。模型与选型细节见[离线语音识别](vosk-offline-speech.md)。

## 关键修复

1. **回到前台消息缺失**：`DshChatController.resumeSession()` 在 `ON_START` 时强制重新 follow 当前会话，用快照校正后台期间丢失的事件。
2. **空闲停止不生效**：`MainActivity.onStop()` 会先解绑服务，生命周期观察者随后收到 `ON_STOP` 时 binder 已为空，导致服务内 `foreground` 永远为真。改为在 `onStart`/`onStop` 中绑定前/解绑前直接设置前台标志。
3. **深色主题系统栏发白**：`AdshTheme` 将状态栏/导航栏设为透明，关闭对比度强制着色，并同步图标明暗。
4. **粤语译文串入**：`selected` 等通用资源键会与依赖库的 `zh-Hant-HK` 翻译冲突，改用应用专属键名。
5. **斜杠命令被当作普通提示词**：Compose 原先只调用 `session/prompt`；改为以 `/` 分流到 `commands/execute`，并把 `command/run|done` 折成命令气泡。
6. **Lint 检查器崩溃**：新版 Compose/lifecycle 检查器与 AGP 8.7.3 的 Kotlin 分析 API 二进制不兼容；精确禁用 4 个崩溃检查并豁免 `ExpiredTargetSdkVersion` 后，Lint 已纳入 `:app:check`。
7. **闹钟/计时器 Intent 被拒**：只声明 `android.permission.SET_ALARM` 时系统时钟仍报 `Permission Denial`；补上 Clock 实际校验的 `com.android.alarm.permission.SET_ALARM` 后，`set_alarm` 与 `set_timer` 均成功。
8. **远程引擎收到手机本地路径**：远程模式把 `/data/user/0/com.example.adsh/...` 作为 `cwd` 发给电脑端，导致 `mkdir '/data'` 失败。`DshChatController` 增加 `remoteMode`：远程会话不传 `cwd`，也不复用本地会话种子或写回本地会话 ID。
9. **繁体标签绕过审批**：策略只匹配简体“发送/删除”，系统语言为繁体时 Gmail 的“傳送”“刪除”被当作普通 TAP 直接放行。补齐繁体关键词后，“傳送”归为发送消息，“刪除”归为破坏性操作，实测拒绝会阻止点击、允许会执行。
10. **允许后点错窗口**：审批弹窗属于应用内 Dialog，会把目标应用挤出前台，允许后按文本重新查找控件会落到本应用窗口。点击路由现在记录请求发起时的前台应用，允许后用 launch intent 将其调回前台并等待最多 15 秒（ColorOS 需在“想要開啟 X”确认中点允许），再重新解析节点执行。
11. **繁简异体发布按钮绕过审批**：朋友圈按钮写作“發佈”（佈），关键词只有“發布”（布），首次发布未弹审批。补齐“發佈/发佈/发表/發文/发作品/限时日常”等异体与场景词后，发布类按钮统一归为发送消息。
12. **审批后点击被浮层吞掉**：审批浮层位于屏幕顶部，允许后立即重放的点击会落在尚未移除的浮层上。桥在允许后等待 350ms 再重新定位/点击，朋友圈与 QQ 空间的右上角发布按钮均一次成功。
13. **审批切前台导致布局漂移**：应用内审批对话框会把目标应用挤出前台并收起键盘。改为无障碍悬浮窗审批，目标应用保持前台，键盘与坐标稳定；仅当允许后前台确实改变时才重新拉起并定位。
14. **轨迹归档不完整**：原先只持久化当次进程内新结束的轮次，重启前已完成的轮次不会补写。`TraceLogStore.keys()` 读取磁盘上的 `sessionId#turn`，首次刷新时去重补写所有已完成轮次。
15. **原版 Web 界面整页空白**：DOM 完整且无 JS 异常，但 `html`、`body`、`#root` 的 `getBoundingClientRect().height` 均为 0；探测显示该 WebView 里 `100vh`、`100dvh`、`100%` 全部解析为 0，连 `about:blank` 也一样，而系统 Chrome 打开同一测试页为 708 CSS px。这是 Compose `AndroidView` 在 WebView 尚未获得高度时创建、Blink 视口被初始化为 0 所致。修复方式：先用 `onGloballyPositioned` 量出可用高度，再以显式像素高度创建 WebView；修复后 `100vh` 恢复为 640 CSS px。
16. **原生轨迹页顶部与状态栏重叠**：Trace 页面隐藏了通用顶栏，但自身没有处理系统栏 inset，返回、标题与分享图标压到状态栏上。给 `TraceScreen` 根布局加 `statusBarsPadding()`。

## 复现步骤

```sh
# 安装
adb -s fe350b43 install -r android/apks/adsh-debug.apk
adb -s fe350b43 shell am start -n com.example.adsh/.MainActivity

# 授予设备桥所需的系统权限（重装后需重新执行）
adb -s fe350b43 shell settings put secure enabled_accessibility_services \
  com.example.adsh/com.example.adsh.device.AdshAccessibilityService
adb -s fe350b43 shell settings put secure accessibility_enabled 1
adb -s fe350b43 shell cmd notification allow_listener \
  com.example.adsh/com.example.adsh.device.AdshNotificationListenerService
```

在应用设置页填写 API 密钥并开启设备能力，回到对话页启动引擎。可发送如下提示验证工具链路：

```text
Call mcp__adsh_device__device_info, then mcp__adsh_device__screen_snapshot,
then report the foreground package.
```

空闲停止复现：启动引擎并确认“已连接 dsh 引擎”，按 Home 回到桌面且不运行任务，等待 5 分钟；随后查看设置页日志或执行：

```sh
adb -s fe350b43 shell run-as com.example.adsh tail -n 5 files/logs/engine.log
adb -s fe350b43 shell dumpsys activity services com.example.adsh | grep -c DshEngineService
adb -s fe350b43 shell ps -A | grep -cE 'node|bin\.js'
```

预期：日志出现空闲停止记录，后两条命令输出 `0` 和 `0`。

## 已知限制与注意事项

- 重装 APK 后系统可能清空无障碍/通知监听授权，需要重新授予；应用设置页会显示当前授权状态。
- 引擎自带的 `android_*` 工具依赖官方 App 的本地 3181/3081 服务，在 adsh 中不可用；引擎启动时会自动禁用这些插件，所有设备操作统一走设备桥。
- 审批/提问卡片已在 `workspace-write` + ask 策略下验证允许路径；默认 `danger-full-access` + never 策略下工具仍直接执行，不会弹审批。
- 设备桥坐标系以应用可用内容区高度（真机 2479）为准，不是含系统栏的物理高度 2772。
- 空闲停止依赖 `MainActivity` 生命周期事件；若进程被系统直接杀死，服务随进程结束，同样不会残留 Node 进程。
