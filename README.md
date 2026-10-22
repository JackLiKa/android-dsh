# adsh Android 工程

原始 harness 仓库位于 `../upstream/`；Android 工程及 [架构](docs/architecture-android.md)、[SDK 目标接口](docs/plugin-sdk-android.md)位于当前目录。目标包名为 `com.example.adsh`，最低 Android 10。

[对齐状态](docs/parity-status.md)列出与原仓库逐域验收所需的运行时和测试证据。
[运行时来源](docs/runtime-provenance.md)记录内置 Node/dsh payload 的校验值、签名与真机探针。

## 本次 APK 已实现

- 中文 Compose 对话、插件和设置页；系统状态栏、三键导航栏与输入法分别让位。MainActivity 接受 adb extras `profile`、`task` 和 `dump-config`（展示启动树）。
- Compose“对话”页使用 DeepSeek 文本 SSE，请求模型可在 `deepseek-flash` / `deepseek-v4-pro` 间手动切换；选定路由在请求前写入当前会话事件，切换后产生模型可见提示，重开会话恢复原选择。文本历史由 `deriveMessages()` 从已写入的 user/assistant 事件投影；API key 由 Android Keystore AES-GCM 加密后存入 DataStore；无 key 时输入只写本地。原版 dsh Web 使用自己的模型选择器。
- `LlmRegistry` 按供应商 id 注册可卸载模型适配器；内置 DeepSeek 文本适配器经 Kotlin Service 挂载，卸载后移除路由。设置页显示当前供应商与模型，在线请求通过服务仓库解析适配器。
- 插件页可显式启动内置 dsh Web：前台服务校验 SHA-256、解压隔离运行时并启动 Node；页面通过本机 WebView 打开原版 DSH 界面。原生 Web 的插件页、模型供应商及工具链使用独立的 dsh runtime 与数据目录；当前 Compose 中保存的 DeepSeek 密钥仅在启动时通过进程环境传给 dsh。
- Android WebView 的附件入口使用系统文件选择器；用户选取的 `content:` 文件（含多选）交给原版 dsh 前端处理，保留文件读取权限边界。前台引擎启动时把 Compose 当前选择的应用私有工作区作为 `DSH_WORKSPACE` 传入，新会话按参考项目的 patch 使用该初始目录。
- Compose 对话输入区提供“完整 dsh · 统计与工具轨迹”入口：首次点击启动原版前台引擎，之后打开原版界面。原版 Web 内置 `sessionStats`、`tokenUsage`、`contextPressure` 和 `ui-trajectory`，从原生 Session 事件显示时长、轮次、Token、缓存命中、上下文占用及工具调用轨迹；数值仅在有对应事件或供应商容量时出现。
- 私有 `local-events.jsonl` 顺序写入并 `fsync`，Room 保存同样的事件；重开会话时按日志补齐 Room 镜像。
- Kotlin `Service`、注入检查、`PluginContext`、五种事件分发及 fiber 逆序卸载；单元测试覆盖依赖、waterfall 短路、bail 与卸载。
- Profile 目录初始化 `package.json`、`cordis.yml`、`cordis.patch.yml`、`compatibility.json`；读取内置 bundle、用户行及 profile/home/命令覆盖层，按 id 应用插入、整项 config 替换与 disabled 覆盖。插件页展示解析结果和原始配置树；会话列表显示摘要和事件条数，事件卡片可展开。
- 历史页可搜索全部本地会话、按当前工作区筛选和恢复；重启后恢复最近选中的会话。工作区页可创建、选择应用私有项目目录，创建会话时固定工作区身份；切换工作区开启新会话，旧会话保留原始关联。既有未关联的会话归入默认工作区。

## 后续实现

此 APK 有两套隔离数据：已有 Compose 对话使用 `local-events.jsonl` 与 Chat Completions，原版 dsh Web 使用独立 `filesDir/dsh-native-home` 与原生 Session 格式。两套会话、设置及工作区尚未迁移或统一；Compose 密钥与当前私有工作区在引擎启动时传入原版进程，dsh 自身保存的设置与凭证仍独立。Compose 配置树中的 bundle 行仍是预览；原版 Web 的实际运行时由前台服务启动。外部 SAF 工作区、Kotlin/TS 插件安装与加载、在线 registry/git/tarball 安装、构建脚本审批、Android 权限及多模态模型识别结果仍需在本 APK 上验收。选文件只证明本地附件入口可调用；识别图片需要在原版界面选择支持图片的模型及完成真实请求。内置运行时携带 Android 适配补丁，修复了参考包遗漏的 `restructure` 模块源码；来源及 `targetSdk 28` 的平台取舍见[运行时来源](docs/runtime-provenance.md)。请勿将当前版本用于要求完整跨界面数据同步、支付或隐私自动化的任务。

当前验证：37 项 JVM 单元测试、2 项运行时打包脚本测试与调试版 APK 构建通过；Android Lint 在迁移第三方检测器字节码时遇到 `NegativeArraySizeException`，该检查待修复后重跑。此前设备 `fe350b43` 上实测状态栏下方显示标题、三键导航上方显示输入框；输入法显示时，输入框底沿距键盘顶端约 28 像素。旧运行时 Web 服务监听正常，但插件日志反复出现 `restructure/src/EncodeStream.js` 缺失，因而影响页面与功能激活；修复版已在隔离 Node 探针中成功导入该模块，原版对话仍需真机完整验收。引擎启动等候新增 75 秒超时与持久的失败/降级提示，避免页面无限显示“正在启动”。Compose 文本适配器已防止 SSE `content: null` 被串成字面 `null`，也能跳过空 choices 的 usage 帧；旧版日志仍按 append-only 规则保留。隔离测试 profile 使用内置 pnpm `--offline` 成功安装上游的本地测试 bundle，并在配置树里看到三个插件行。

统计 UI 的源代码回归：upstream 的 `chat-stats.client.spec.tsx` 与 `ui-trajectory/tests/layout.client.spec.tsx` 共 76 项测试通过。Android 构建测试检查这些客户端组件及原生投影包随 payload 分发；真实设备上的统计数值与工具时间线仍需要有记录的原版 dsh 会话进行验收。

## 构建和安装

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

工程使用 Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Compose、Room 2.8.5、DataStore 1.1.1、SnakeYAML 2.2，JDK 21。在工程目录执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest`；生成的调试 APK 复制到 `apks/adsh-debug.apk`。连接设备后用 `adb -s <serial> install apks/adsh-debug.apk` 安装；通过 `adb -s <serial> shell am start -n com.example.adsh/.MainActivity --es profile web --es task "hello"` 启动并预填任务。设置页面可输入自己的 DeepSeek API 密钥；每次模型请求需要网络。通过 `adb -s <serial> shell am start -n com.example.adsh/.MainActivity --es profile web --ez dump-config true` 打开插件页预览配置。
