# 内置 dsh 运行时来源

Android APK 内置的 `app/src/main/assets/dsh/payload.zip` 从用户指定的 [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) `v1.20.1` 发布包中提取。该仓库检出的参考源码为 `98162a2`；发布包中的 `@deepseek-ai/dsh/package.json` 声明 `0.2.1-rc.2`。本工作区原仓库为 `upstream/` 的 `687f282e`；生产发布仍需要把 Android 适配补丁与该原仓库的源码构建产物逐项核对。

| 产物 | SHA-256 |
|---|---|
| 上游发布的 `DeepSeekHarness.apk` | `c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa` |
| 参考 APK 原始的 `assets/payload.zip`，保留在 `runtime/previous/` | `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab` |
| 本工程修复后打包的 `app/src/main/assets/dsh/payload.zip` | `6f5003bd7729135683460d9d85cb0c1b2673dbfea6584046a0de8d6968345fb7` |

已用 Android SDK `apksigner verify --print-certs` 验证参考 APK 的签名；证书 SHA-256 为 `6a421f47610e2e3393d4181d66bb656e8bfe30a1c4f018b36f29bf80299127b5`，与参考仓库公开的签名指纹一致。该核验确认发布物与项目公布的指纹相符，代码审查及构建可复现性仍是独立验收项。

参考 payload 的 `restructure@3.0.2` 有 `index.js`、`package.json`、`LICENSE`，但遗漏 `src/*.js`；插件加载时报 `ERR_MODULE_NOT_FOUND: restructure/src/EncodeStream.js`。本工程 `tools/repair_runtime_dependency.py` 校验旧 payload SHA-256，并要求这三个已打包文件与本地 upstream 依赖的内容逐字节相同，之后加入该版本的 17 个 `src/*.js` 文件，产生新的 SHA-256。隔离设备探针已验证 Node 能导入修复后的模块并取得 51 个导出；完整 Web 会话回归尚待用新 APK 验证。脚本单元测试覆盖原始包不变、完整后继与校验失败路径。

参考仓库代码采用 MIT；虚拟屏原生模块引用的 Operit 代码采用 LGPL-3.0。参考仓库的 [MIT 文本](REFERENCE-LICENSE.txt)、[第三方声明](REFERENCE-THIRD-PARTY-NOTICES.md)、[LGPL 文本](REFERENCE-LGPL-3.0.txt)及[GPL 文本](REFERENCE-GPL-3.0.txt)保留在 Android 工程中。本 APK 只纳入已核验发布包的运行时 payload；发布前仍需逐项核对其中全部依赖的许可与源代码归属。

在 Android 16、`com.example.adsh` 的真机隔离探针中，`run-as` 环境下内置 Node `v26.4.0` 可执行，`dsh --profile web --dump-config` 输出 1264 行，Web 端点返回预期的未认证 `401`。`targetSdk 35` 时，应用进程直接执行该文件得到 `error=13 Permission denied`；参考项目采用的 `targetSdk 28` 在当前设备允许应用进程运行，真实 dsh Web 服务在 `127.0.0.1:3080` 就绪，WebView 显示上游登录界面。最低系统版本仍为 API 29。`targetSdk 28` 会改变平台的存储与后台行为，发布及跨 Android 10+ 设备验收前需单独审查这一安全取舍和替代执行路径。

启动时校验 payload SHA-256，解压到私有目录的独立 generation 并排他发布；旧 generation 留在设备上，新的运行时沿用 `filesDir/dsh-native-home`。现有原型会话、Room、设置和凭证仍留在原目录；Android Keystore 中已有的 DeepSeek 密钥会在引擎启动时注入子进程环境，不写入 payload 或日志。用户在插件页明确启动时，`DshEngineService` 才启动前台引擎；WebView 只允许导航至 `127.0.0.1:3080`，引擎的一次性登录 URL 仅保存在进程内存中。默认未接通参考项目的特权、无障碍、虚拟屏与浏览器桥接，手机端对应插件行为需要逐项验证。

在设备的隔离测试 profile `hello-test` 中，已通过参考 payload 的 `dsh plugin --profile hello-test add file:<本机测试 bundle> --offline` 安装 upstream 仓库自带的惰性 fixture，随后 `--dump-config` 中出现其三个插入行。首次探针缺少 `TMPDIR` 时内置 pnpm 尝试读取 Termux 私有路径而失败；设置应用私有 cache 目录后重试成功。前台服务配置了该目录、OpenSSL/Git 路径与证书文件，并从用户选择的私有工作区设置 `DSH_WORKSPACE`。registry/git/tarball 安装、构建脚本审批、HMR 卸载、原版 Web 工作区操作以及用户的 Kotlin/TS 插件仍缺真实设备验收。

同版本的原仓库在 macOS arm64 上执行 `pnpm install --frozen-lockfile --ignore-scripts`、`pnpm run build:artifacts`（7/7 gates）与 `pnpm run build:check`（2/2 gates）通过。APK 当前仍使用参考仓库发布物的 Android 专用运行时与补丁；这些产物的可重建性及与本地源码构建产物的逐文件差异待完成审计。

## 替代运行时（2026-11-09 本地完整包）

参考发布仓库（`woaiys3/deepseek-harness-android-app`）已不可访问（HTTP 404），无法取得上表固定的 `6f5003bd…` payload。为完成本机可安装的完整包，改用同布局的公开运行时：

| 项 | 值 |
|---|---|
| 来源 | `github.com/guzhou079-arch/deepseek-harness-android` v1.49 发布 APK 的 `assets/payload.zip` |
| payload SHA-256 | `88bcda193d094837d1639476c58bfaeded34c6addc1717519602bfa88f25e813`（159,737,680 B） |
| 引擎版本 | `@deepseek-ai/dsh` 0.2.2-rc.1；Node v26.4.0（arm64）；布局 `hoisted-3+sb131` |
| 构建参数 | `-PpayloadSha=88bcda…`；模拟器验证时另加 `-PstartupTimeoutMs=300000` |

与固定参考版的差异：dsh 0.2.1-rc.2 → 0.2.2-rc.1；来源、校验值与构建可重建性均不同，**不能视为通过本文件第 1 节的固定指纹审计**。

安装器为适配该运行时新增两处兼容处理（`RuntimePayloadInstaller`）：

1. 恢复 `runtime/lib/LINKS.txt` 声明的 soname 别名（hardlink → symlink → copy）。Zip 不携带符号链接；缺少别名时 Node 报 `CANNOT LINK EXECUTABLE … libz.so.1 not found`。
2. 把 ELF 的 Termux `DT_RUNPATH`（`/data/data/com.termux/files/usr/lib`）原位改写为 `$ORIGIN`（库）/`$ORIGIN/../lib`（可执行文件），使动态链接不依赖 `LD_LIBRARY_PATH`，并修复非直接依赖的查找。

验证：构建后 APK 为 264,658,154 B；模拟器（Android 17 x86_64，ARM 翻译层）日志出现 `dsh web: http://127.0.0.1:3080/?token=***` 与 `dsh Web 已就绪`，界面显示“已连接 dsh 引擎”。该验证不覆盖真机原生 arm64 回归；发布前仍需按固定指纹流程重新审计来源与许可。
