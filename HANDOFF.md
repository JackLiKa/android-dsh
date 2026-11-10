# 进度交接（2026-11-10）

## 已完成

- 设备策略层：`DENY > ASK > ALLOW`、阻断式审批（应用内/通知/无障碍浮层）、审计日志、设置页。
- 设备桥与多应用能力：QQ 邮箱、QQ、微信、Telegram、Gmail 发消息；朋友圈、QQ 空间、抖音图文发布；本地 OCR 兜底。
- 轨迹日志：`files/logs/trace.jsonl` 记录轮次/步骤/工具/token/TTFT/输出速度，原生「轨迹」页可查看与导出。
- 原版 Web 界面白屏已修复：
  - 根因是 Compose `AndroidView` 在 WebView 获得高度前创建，Blink 视口初始化为 0；该 WebView 中 `100vh`、`100dvh`、`100%` 全为 0，连 `about:blank` 也一样，而系统 Chrome 正常。
  - `NativeDshWebView` 先用 `onGloballyPositioned` 量出可用高度，再以显式像素高度创建 WebView；修复后真机 `100vh` = 640 CSS px，对话与轨迹页正常渲染。
- Web 轨迹页用量条（上游 `ui-trajectory`，补丁 `docs/patches/0001-trajectory-usage-summary.patch`）：
  - `TrajectorySummary` 显示缓存命中率、上下文占用、会话 Token、请求数；明细表每行也显示缓存命中率。
  - 缓存命中率 = `cacheRead / (uncachedInput + cacheRead + cacheWrite)`，无计费输入时显示 `—`，部分命中不会四舍五入成 100%。
  - 上下文占用 = `projectedTokens ?? pressureTokens` / `contextWindow`，只算 prompt 侧；窗口缺失时只显示已用 token。
  - 真机实测：`缓存命中 98.6% · 上下文 10% · 104k / 1M · 会话 Token 11.3M · 请求 58 次`。
- 原生 `TraceScreen` 同步：
  - token 行「缓存命中」改为「缓存读取」，新增「缓存命中 %」「上下文 % · used / window」「会话 Token」。
  - 数据通过 `SessionMeterStore` 读取 `dsh-native-home/storages/session_projcache/sessions/session-<id>.json` 的 `tokenUsage` 与 `contextPressure`，每轮结束重新读取。
  - 真机实测：`缓存读取 8.9M · 缓存命中 97.6% · 上下文 26% · 260.2k / 1M · 会话 Token 9.2M`。
  - 修复 Trace 页顶部返回/标题/分享与状态栏重叠（`statusBarsPadding()`）。
- 上游验证：`tsc -b` 通过，`ui-trajectory` 13 个测试文件 210 项全部通过；Android 单测 91 项通过（1 项设备相关跳过）。
- 已重建 bundle、更新 payload、构建安装 APK，并更新 README 与 docs。

## 当前状态

- 除可选的抖音视频发布外，上一轮交接的任务已全部完成。
- 抖音只验证到图文发布，视频发布尚未实测。2026-11-10 尝试过一次：模型走“文件管理器长按视频 → 分享 → 发布到抖音”，在 ColorOS 分享面板、抖音拍摄页与相册之间来回；两次 `SEND_MESSAGE` 审批都在 60 秒内未处理，按超时拒绝，随后停止本轮。下次建议直接走抖音内“+ → 相册 → 视频 → 下一步 → 发布”，并在审批浮层出现时立即点“允许一次”。
- 重装 APK 会清空无障碍/通知授权；本次已用 `settings put secure enabled_accessibility_services …` 重新开启。
- 上游 `/Users/user/androids/dsh/upstream` 的 `ui-trajectory` 改动仍未提交；补丁已导出，是否单独向上游提交待定。

## 下次从这里开始（可选）

1. 抖音视频发布：从抖音内“+ → 相册 → 视频”选择 `Movies/studio_video_*.mp4`，填写文案并设为私密，审批出现时点“允许一次”。
2. 若要把 Web 用量条贡献回上游：在 `upstream` 仓库单独提交 `ui-trajectory` 改动与测试，保留 `docs/patches/0001-trajectory-usage-summary.patch` 作为 Android 侧记录。
3. 长会话压缩后的上下文口径、缺少 `contextWindow` 的供应商回归。

## 构建与安装

```bash
# 上游 bundle
cd /Users/user/androids/dsh/upstream
pnpm --filter @deepseek-ai/dsh-client-ui-trajectory bundle

# 更新 payload 内 client.js 后重算 SHA-256，再构建 APK
cd /Users/user/androids/dsh/android
JAVA_HOME="/Users/user/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home" \
  ./gradlew :app:assembleDebug -PpayloadSha=<sha256> --console=plain
adb -s fe350b43 install -r -g app/build/outputs/apk/debug/app-debug.apk

# 单元测试
JAVA_HOME="/Users/user/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home" \
  ./gradlew :app:testDebugUnitTest --console=plain
```

## 设备与环境

- 设备：OnePlus `PJA110`，`fe350b43`，Android 16 / ColorOS 16。
- 引擎：`http://127.0.0.1:3080/`；设备桥端口每次启动随机。
- 屏幕：1240×2772；WebView CSS 视口 354×640（DPR 3.5）。
- payload 当前 SHA-256：`25799b34715f8ad28ffb3dc3c7cf1404805c52bf794c2b9c8ea25766bb87c3c8`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `f93044812a7092218b980d30c3910d01a61a21924acc1df00214c7d1225ad1bc`。

## WebView 调试

```bash
adb -s fe350b43 shell cat /proc/net/unix | grep webview_devtools
adb -s fe350b43 forward tcp:9222 localabstract:webview_devtools_remote_<pid>
curl -s http://127.0.0.1:9222/json
# 用 ws://127.0.0.1:9222/devtools/page/<id> 连接 CDP
```

## 关键文件

- 上游改动：`packages/client/ui-trajectory/src/client/{TrajectorySummary.tsx,TrajectorySummary.module.css,usage-metrics.ts,TrajectoryView.tsx,TrajectoryTable.tsx,locales.ts}`
- 补丁：`docs/patches/0001-trajectory-usage-summary.patch`
- 原生轨迹：`app/src/main/java/com/example/adsh/TraceScreen.kt`、`engine/TraceLog.kt`、`engine/SessionMeterStore.kt`
- WebView：`app/src/main/java/com/example/adsh/NativeDshWebView.kt`
- 设备策略/审计：`app/src/main/java/com/example/adsh/device/`、`DevicePolicyCard.kt`
