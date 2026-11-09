# 进度交接（2026-11-09）

## 已完成

- 设备策略层：`DENY > ASK > ALLOW`、阻断式审批（应用内/通知/无障碍浮层）、审计日志、设置页。
- 设备桥与多应用能力：QQ 邮箱、QQ、微信、Telegram、Gmail 发消息；朋友圈、QQ 空间、抖音图文发布；本地 OCR 兜底。
- 轨迹日志：`files/logs/trace.jsonl` 记录轮次/步骤/工具/token/TTFT/输出速度，原生「轨迹」页可查看与导出。
- Web 轨迹页用量条（上游 `ui-trajectory`）：
  - 新增 `TrajectorySummary`，显示缓存命中率、上下文占用、会话 token、请求数。
  - 缓存命中率 = `cacheRead / (uncachedInput + cacheRead + cacheWrite)`，无计费输入时显示 `—`。
  - 上下文占用 = `projectedTokens ?? pressureTokens` / `contextWindow`，只算 prompt 侧。
  - 明细表新增「缓存命中率」行；中英文文案已补齐。
  - 上游源码改动已导出为 `docs/patches/0001-trajectory-usage-summary.patch`。
- 上游验证：`tsc -b` 通过，`ui-trajectory` 13 个测试文件 210 项全部通过。
- 已重建 bundle、更新 payload，并构建安装 APK。

## 当前卡点

真机 WebView 中轨迹/对话页出现异常：无障碍树里文本节点高度全为 0，页面疑似空白或未完成布局。
需要先确认是新 bundle 运行时异常，还是 WebView 渲染/缓存问题。

## 下次从这里开始

1. 用 WebView DevTools 直接看控制台和布局：
   ```bash
   adb -s fe350b43 forward tcp:9222 localabstract:webview_devtools_remote_2928
   curl -s http://127.0.0.1:9222/json
   ```
   连接 CDP 后检查 `Runtime.exceptionThrown`、`document.body` 尺寸和 `getBoundingClientRect()`。
2. 对比新旧 bundle 隔离问题：
   - 旧条目大小 398,766 B，新条目 413,931 B。
   - 如怀疑新 bundle，可临时把旧 `client.js` 写回 payload 验证。
3. Web 页验证通过后，把同样的缓存命中率/上下文占用补到原生 `TraceScreen.kt`，并把「缓存命中」改名为「缓存读取」。
4. 更新 `README.md`、`docs/architecture-android.md`、`docs/parity-status.md`、`docs/real-device-verification.md`。
5. 可选：补验抖音视频发布（目前只验证了图文）。

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
- 屏幕：1240×2772；WebView CSS 视口约 412 px。
- payload 当前 SHA-256：`25799b34715f8ad28ffb3dc3c7cf1404805c52bf794c2b9c8ea25766bb87c3c8`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，`apks/adsh-debug.apk` 已被 gitignore。

## 关键文件

- 上游改动：`packages/client/ui-trajectory/src/client/{TrajectorySummary.tsx,TrajectorySummary.module.css,usage-metrics.ts,TrajectoryView.tsx,TrajectoryTable.tsx,locales.ts}`
- 补丁：`docs/patches/0001-trajectory-usage-summary.patch`
- 原生轨迹：`app/src/main/java/com/example/adsh/TraceScreen.kt`、`engine/TraceLog.kt`
- 设备策略/审计：`app/src/main/java/com/example/adsh/device/`、`DevicePolicyCard.kt`
