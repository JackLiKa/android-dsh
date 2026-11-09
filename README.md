# adsh Android 工程

包名 `com.example.adsh`，最低 Android 10（API 29），arm64-v8a。应用把原版 dsh Web 引擎作为唯一会话与工具运行时，Compose 界面通过本机 HTTP + WebSocket 直接读写该引擎；不再维护第二套 Chat Completions 对话链路。

- [架构与实现](docs/architecture-android.md)
- [功能对齐状态](docs/parity-status.md)
- [Kotlin 插件 SDK 契约](docs/plugin-sdk-android.md)
- [运行时来源与校验](docs/runtime-provenance.md)
- [真机验证记录](docs/real-device-verification.md)

## 已实现能力

### 1. 统一对话运行时

- `DshEngineService` 以前台服务启动随附的 Node/dsh：`node --expose-internals bin.js --profile web --no-open`。启动前校验并解压 payload 到应用私有目录，必要时附加设备桥 patch。
- `DshWebClient` 完成带 token 的登录、健康探测、`POST /api/<endpoint>` 一元 RPC，以及 `/api/remote.mux` 多路 WebSocket。断线按 `500ms/1s/2s/4s/8s/10s` 退避重连，并重开全部流。
- `DshChatController` 负责 `session/list|create|prompt|follow|cancel|modelCatalog|selectModel`、`credentials/set`，消费 follow 流的 `snapshot`、`event`、`assistant-stream` 帧，投影出消息、工具调用、用量、运行态与错误。
- 审批与提问通过 mux 的 `waterfall` 事件送达 Compose，用户选择后以 `approval/request` 或 `user-questions/request` 的 result 回写；断线重连时按 `eventId` 去重。
- 以 `/` 开头的输入按引擎斜杠命令处理，经 `commands/execute` 下发；`command/run` 与 `command/done` 投影为独立的命令气泡，失败时标红并展示引擎返回文本。
- 模型目录与推理强度来自引擎 `session/modelCatalog`；选择结果写入引擎会话，而非本地偏好。
- Compose 对话页支持流式 Markdown、工具卡片、停止生成、错误重试、会话切换、空态建议项和模型标签截断。

### 2. 引擎生命周期、日志与恢复

- 设置页可启动、停止、刷新与重启引擎，并显示引擎、连接、模型和空闲状态。
- 前台引擎在应用退到后台且连续 5 分钟无任务时自动停止；`EngineIdlePolicy` 有单元测试。前台标志在 `MainActivity.onStart/onStop` 中直接写入服务，避免解绑后状态滞留。
- 应用回到前台时会重新订阅当前会话，用新快照重建消息，避免后台期间丢失最后一条回复。
- 异常退出按 1 秒、2 秒重启；连续 3 次失败后停止并给出中文提示，可在日志页一键进入安全模式。
- 安全模式执行 `--profile rescue --from-default-profile web`，从随附模板重建干净的 rescue 配置。
- 引擎日志持久化并按 1.5 秒轮询展示；支持刷新、清空、复制到剪贴板、错误/警告着色；日志中的 token 已脱敏。
- 退出代码、运行时长与日志末尾会记录在崩溃归因中。

### 3. 设备能力桥（可选）

设置页的“设备能力”开关是显式 opt-in，关闭后立即撤销：

- 应用在本机回环地址启动临时端口的 broker，使用 32 字节随机 token 做常量时间校验。
- 引擎通过 `--patch` 叠加 `@deepseek-ai/dsh-mcp-client`，以 stdio 启动随附的零依赖 MCP server；工具统一暴露为 `mcp__adsh_device__*`。
- 提供 15 个工具：设备信息、通知列表、界面树快照、点击、滑动、输入文本、打开应用、应用列表、设置闹钟、设置计时器、预填短信、直接发送短信、打开拨号、朗读文本、停止朗读。
- 界面树限制节点数与深度；点击按“精确文本 → 部分文本 → 索引 → 可点击祖先 → 坐标手势”降级。
- 无障碍与通知监听权限由系统单独授予；设置页展示授权状态、broker 端口和最近 6 条设备调用审计记录。
- 关闭开关会删除 patch、停止 broker 并重启引擎；引擎未运行时只保存设置。

安全边界：broker 仅监听 `127.0.0.1`；token 只写入应用私有 DataStore，并通过 MCP 配置的 `config.env` 注入（子进程环境会清除凭据型变量与 `DSH_*`）；界面树与通知可能包含敏感信息，默认关闭。

### 4. 语音、唤醒与自动化

- 对话页麦克风支持语音输入：短按把识别结果填入输入框，长按识别完成后直接发送；首次使用请求 `RECORD_AUDIO`，不可用时提示并保留系统语音输入回退。
- 助手回复可通过标题栏的朗读开关交给系统 TTS 播报；只朗读已完成的回复，按消息去重，停止生成或出错时不朗读。
- 无障碍悬浮球默认关闭。开启后常驻屏幕边缘并可拖动：轻点打开对话并开始语音，长按只打开对话，不引入 `SYSTEM_ALERT_WINDOW` 权限。
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

- `./gradlew --offline :app:testDebugUnitTest`：52 项 JVM 测试通过。
- `./gradlew --offline :app:lintDebug`：Android Lint 0 error、41 warning；Compose 检查器的二进制崩溃已通过把 Compose BOM 固定到 `2024.12.01`、lifecycle 固定到 `2.8.7`（与 Kotlin 2.0.21 / AGP 8.7.3 同代）根除，不再禁用会崩溃的检查器，仅保留 `ExpiredTargetSdkVersion` 豁免。
- `./gradlew --offline :app:assembleDebug`：调试 APK 构建通过（约 174 MB，含 Node/dsh payload）。
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
  - MCP `tools/list` 返回 15 个工具；通过 adb forward 以真实 MCP stdio 协议逐一调用全部 15 个工具：设备信息、应用列表、通知、界面树、点击、滑动、输入、打开应用、闹钟、计时器、短信预填、短信直发、拨号、朗读与停止朗读均返回成功；模拟器与真机的 `send_sms` 均返回“短信发送成功”，模拟器发出的短信可在系统 `content://sms/sent` 中查到，真机无线电日志记录 `Status = Success`；空收件人返回中文校验错误，失败回执按运营商错误码解释，15 秒未收到完整回执返回超时提示；真机 ColorOS 在系统确认浮层点“发送”后返回成功；不确认时约 10 秒后系统拒绝发送，工具在 15 秒预算内返回“未收到完整发送回执”；无 token 返回 401。
  - 麦克风运行时授权弹窗正常；真机 PJA110 未安装语音识别服务，界面显示“当前设备没有可用的语音识别服务”。悬浮球轻点可打开对话并触发语音请求，长按只打开对话。
  - 自动化页可创建/删除任务并显示模板；设置页“远程控制电脑端”与手势唤醒开关渲染正常。
  - 切到系统设置再回到应用后，最终回复会实时补齐（前台重新订阅快照的修复已生效）。
  - 深色主题下状态栏与导航栏颜色与界面一致。
  - 应用退到后台并保持无任务 5 分钟后，引擎日志记录“闲置 5 分钟，引擎已停止以节省电量”，服务与 Node 进程均退出。
  - 系统语言为繁体中文（zh-Hant-HK）时，启动配置显示“web (已选)”，不再被依赖库翻译覆盖。
- 最终调试 APK：`apks/adsh-debug.apk`（181,820,325 字节），SHA-256 `9e4da58e1d5e5ef671308cf3fc96319a6bfa92aafd0db343f54caa35f7aa1e21`。

## 构建与安装

### 准备运行时

本仓库只跟踪代码和文档。APK、Node/dsh payload 及参考 payload 备份超过 GitHub 普通文件限制，均由 `.gitignore` 排除。首次克隆后先准备运行时；本地生成的 APK 包含完整 payload，运行时不依赖再次下载。参考发布包的来源、许可证与 SHA-256 见[运行时来源](docs/runtime-provenance.md)。

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

`:app:check` 同时执行单元测试与 `lintDebug`。AGP 8.7.3 自带的 Kotlin 分析 API 与新 Compose/lifecycle lint 检查器存在二进制不兼容（`IncompatibleClassChangeError`），因此精确禁用会崩溃的 4 个检查：`NullSafeMutableLiveData`、`FlowOperatorInvokedInComposition`、`UnrememberedMutableState`、`RememberInComposition`；`targetSdk = 28` 是运行时硬性要求，故豁免 `ExpiredTargetSdkVersion`。其余检查保持开启。

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
- 随附运行时自带的 `android_a11y_status` 工具可能报告 `running=false`，即使本应用的无障碍桥已正常工作；判定可用性以设备桥调用和设置页状态为准。
- 设备桥依赖无障碍与通知监听权限，不同厂商 ROM 可能限制后台前台服务或无障碍连接（真机 PJA110 上已通过）。
- payload 来自第三方发布 APK 并由本地脚本修复，升级与再分发前需重新核对许可证和校验值；详见[运行时来源](docs/runtime-provenance.md)。
- 历史会话与工作区由 dsh 引擎持久化；应用内“工作区”页只负责选择新会话的初始目录，不迁移引擎已有会话。
