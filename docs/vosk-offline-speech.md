# 离线语音识别（Vosk）集成与验证

状态：2026-11-08 完成 Vosk 离线链路验证，2026-11-09 在 arm64 模拟器（Android 14 / API 34）完成长按录音、语言/引擎选择与默认助手唤醒验证。默认引擎为“自动”：优先系统 `SpeechRecognizer`（通常更准，粤语只能走系统），系统不可用时回退随附 Vosk 模型。架构总览见[架构与实现](architecture-android.md)，功能对齐见[对齐状态](parity-status.md)。

## 1. 选型：为什么是 Vosk

候选对比（2026-11 复核，许可证与集成方式均为公开信息）：

| 框架 | 许可证 | Android 集成方式 | 中文模型 | 流式/部分结果 | 结论 |
|---|---|---|---|---|---|
| **Vosk**（[alphacep/vosk-api](https://github.com/alphacep/vosk-api)） | Apache-2.0 | Maven Central AAR `com.alphacephei:vosk-android:0.3.47`，含 arm64-v8a/armeabi-v7a/x86/x86_64 原生库，无需 NDK | `vosk-model-small-cn-0.22`，65 MiB | 支持（`onPartialResult`） | **采用**：零 native 构建、体积可控、离线、支持模拟器 x86_64 |
| [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/android/index.html) | Apache-2.0 | 官方 AAR 未发布到 Maven Central（[PR #1615](https://github.com/k2-fsa/sherpa-onnx/pull/1615) 仍在讨论），通常需 NDK/CMake 构建或用发布包手工集成 | Zipformer/Paraformer 中文模型，识别率更高 | 支持 | 备选升级路径：精度更高，但集成/构建成本与风险显著更高 |
| whisper.cpp | MIT | 需 NDK 交叉编译 + JNI 封装 | Whisper tiny/base 中文可用 | 分段、非实时友好 | 不采用：体积大、移动端延迟高、非流式 |
| FunASR | MIT | Python 优先，无官方 Android SDK；需 ONNX 导出与自建推理层 | Paraformer 中文很强 | 支持（自建） | 不采用：工程量大，超出本次集成范围 |
| PocketSphinx | BSD | 老牌 C 库 | 中文模型效果弱 | 支持 | 不采用：精度与维护状态不满足 |

Vosk 的关键工程优势：唯一需要新增的是 Gradle 依赖 + assets 模型 + 一层 Kotlin 封装，不引入 NDK、CMake、CMake 工具链或 ABI 维护成本；模型与音频全程本机处理，不依赖 Google 语音服务（真机 PJA110 上就没有可用的系统识别服务）。

## 2. 依赖与模型

```kotlin
// app/build.gradle.kts
implementation("com.alphacephei:vosk-android:0.3.47") // Apache-2.0，传递依赖 JNA 5.13.0 (aar)
```

模型不随仓库跟踪（65 MiB），首次克隆后执行：

```sh
python3 tools/fetch_vosk_model.py      # 下载 + 校验 SHA-256 + 解压到 assets/vosk/model-cn
```

| 项 | 值 |
|---|---|
| 模型 | `vosk-model-small-cn-0.22`（[下载页](https://alphacephei.com/vosk/models)） |
| 压缩包 SHA-256 | `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`（43,898,754 B） |
| 解压后 | 68,292,307 B（65.13 MiB），15 个文件：`am/final.mdl`、`conf/*`、`graph/{Gr,HCLr}.fst`、`graph/phones/*`、`ivector/*`、`uuid`、`README` |
| 打包路径 | `app/src/main/assets/vosk/model-cn/`（由 `.gitignore` 排除） |
| 运行期路径 | `filesDir/vosk/model-cn/`（首次启动从 assets 复制，staging 目录原子替换） |

`tools/fetch_vosk_model.py` 支持 `--force`、`--keep-zip`、`--archive`；下载后强校验 SHA-256，解压时去掉 zip 顶层目录，并在缺失时补 `uuid`。

## 3. 代码结构

| 文件 | 职责 |
|---|---|
| `OfflineSpeechRecognizer.kt` | Vosk 封装：assets 检测、模型复制与 `Model` 加载、`AudioRecord` 直录会话（16 kHz 单声道 PCM16、100 ms 分块、`NoiseSuppressor`/`AutomaticGainControl`）、RMS 音量回调、部分/最终结果回调、`decodePcm()` 供测试离线解码 |
| `VoiceController.kt` 的 `VoiceInputController` | 统一门面：按设置选择“自动/离线/系统”；自动模式优先系统 `SpeechRecognizer`（粤语仅系统可用），不可用时回退 Vosk；对 UI 暴露同一组回调与 0..1 音量 |
| `ChatScreen.kt` | 长按说话：按住约 350 ms 开始识别、松手结束；识别中显示动态音量条、实时部分结果与“松开结束”，最终文本可继续编辑 |
| `app/src/androidTest/.../OfflineSpeechRecognitionTest.kt` | 4 项设备级测试（模型打包、加载/幂等、中文 WAV 解码、麦克风会话启停） |

关键行为：

- 模型加载在单线程后台执行，不阻塞 UI；首次复制约 65 MiB，之后按 `am/final.mdl` 存在判断复用。
- 录音直接使用 `AudioRecord(VOICE_RECOGNITION)` 读取 PCM 并送入 `Recognizer.acceptWaveForm`，不再经过 Vosk 的 `SpeechService`，因此可自行控制增益、噪声抑制和音量回调。
- `VoiceInputController.available()` 在“assets 模型存在”或“系统识别可用”时为真，因此无系统语音服务的设备也能启用麦克风。
- 识别结果 JSON 由 `org.json` 解析：`partial` 帧映射部分结果，`text` 帧更新草稿；`stop()` 后以最终文本作为可编辑结果，不再自动发送。
- 录音会话结束（`stop()`）会等待 Vosk 的 `finalResult`；出错时映射为中文提示，不崩溃；`cancel()` 丢弃本次结果。

## 4. 构建与安装

```sh
cd android-dsh
python3 tools/fetch_vosk_model.py                 # 首次：准备模型
./gradlew :app:assembleDebug                      # 需要 assets/dsh/payload.zip（真实运行时）
# 若暂时没有 dsh payload（本仓库不跟踪），仅做 UI/语音验证可跳过其校验：
./gradlew :app:assembleDebug -x checkRuntimePayload
```

模型使 debug APK 增加约 65 MiB；不包含 dsh payload 的 APK 为 115,443,132 B（110.1 MiB，2026-11-08 构建）。

## 5. 设备验证（2026-11-08，模拟器）

环境：`Medium_Phone_API_37.0`（Android 17 / API 37、x86_64、1080×2400、WHPX 加速）；Gradle 8.9 + JDK 21 + compileSdk 35；App `com.example.adsh`。

```sh
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.example.adsh android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am instrument -w -r \
  com.example.adsh.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`OK (4 tests)`。

| 测试 | 结果 |
|---|---|
| `bundledModelIsPackagedInApk` | 通过（assets 模型存在，`VoiceInputController.available()` 为真） |
| `loadsModelIntoPrivateStorageAndIsIdempotent` | 通过（复制到 `filesDir/vosk/model-cn`，二次加载复用） |
| `decodesBundledChineseWavToText` | 通过；测试音频为 FunASR 官方示例（16 kHz/单声道/16 bit，5.547 s，SHA-256 `a1bd32dc…f4f20f`），识别输出 `欢迎 大家 来 体验 打磨 远 推出 的 语音识别 模型`（41 个部分结果） |
| `microphoneSessionStartsAndStopsWithoutCrash` | 通过（`AudioRecord` + Vosk 识别会话启动/停止，回调正常，无崩溃） |

说明：测试音频原句为“欢迎大家来体验达摩院推出的语音识别模型”，小模型把“达摩院”识别为“打磨远”，属模型精度限制而非链路问题。

应用壳层验证：安装后 `MainActivity` 正常启动，对话页显示引擎状态、底部 6 个 tab 与输入框；长按麦克风可进入聆听并显示动态音量条。

![模拟器上的 adsh 应用界面](images/emulator-app-home-2026-11-08.png)

## 5.1 语音交互与唤醒验证（2026-11-09，arm64 模拟器）

环境：`emulator-5554`（Pixel 6、Android 14 / API 34、arm64-v8a、1080×2400），payload 含 arm64 Node，引擎可完整启动。

- 长按麦克风约 350 ms 开始录音，松手结束；识别中显示 8 段音量条和实时部分结果，结束后文本写入输入框且可编辑。
- 设置页“语音识别”卡片可选普通话/粤语、自动/离线/系统引擎，并显示“快速响应”开关；切换语言与引擎后 `VoiceInputController` 重建。
- 授予默认助手角色后，`adb shell am start -a android.intent.action.ASSIST` 直接打开应用并自动开始聆听。
- 通过 `/dev/input/event12` 注入 3.6 秒 Home 长按，应用打开并进入聆听；注入 0.2 秒短按则停留在桌面；短按不会误唤醒。
- 53 项单元测试、lint（0 error）与 `assembleDebug` 全部通过。

## 6. 已知限制

1. 小模型精度有限（同音/近音词会错，如“达摩院”→“打磨远”）；对命令式短句与常用表达够用，长句听写建议换大模型或升级 sherpa-onnx。默认“自动”已优先系统识别，离线模型主要作为无系统服务时的兜底。
2. 随附离线模型只支持普通话（`zh-CN`）；粤语依赖系统语音服务，系统不提供时设置页会提示不可用。
3. 输出不带标点、不做逆文本规整；需要标点可再叠 Vosk 标点模型或上游处理。
4. APK 增加约 65 MiB；模型不纳入 Git，需要 `tools/fetch_vosk_model.py` 或 CI 缓存。
5. 离线识别与 dsh 引擎解耦：模型缺失且系统识别不可用时，UI 显示“当前设备没有可用的语音识别服务”。
6. 未在真机上用真实麦克风做长句回归（本次为模拟器 + WAV 注入 + 麦克风会话启停）。
7. Home 长按依赖系统把按键事件转发给无障碍服务；Android 12+ 上更可靠的是设为默认助手，两者都未满足时长按不会唤醒。

## 7. 后续方向

- 换 `vosk-model-cn-0.22`（约 1.3 GB）或迁移 sherpa-onnx 的流式 Zipformer 以提升精度。
- 加入 VAD/端点检测与标点模型，改善长句听写体验；松手后立即结束，VAD 可自动判断句尾。
- 增加“语音自检”入口（复用 `decodePcm`），便于设备排障。
- 在更多 ROM 上验证 Home 键过滤与默认助手长按入口。
