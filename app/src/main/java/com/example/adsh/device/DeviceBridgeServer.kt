package com.example.adsh.device

import android.content.Context
import android.util.Log
import com.example.adsh.engine.DshJson
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal loopback HTTP bridge consumed by the stdio MCP adapter. It binds
 * 127.0.0.1 on an ephemeral port and requires the per-install bearer token, so
 * only the engine child process receives it (through the generated profile
 * patch). The server exists only while the user keeps the bridge enabled.
 */
class DeviceBridgeServer(
    private val context: Context,
    private val token: String,
) {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4)
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        if (running.get()) return port
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "adsh-device-bridge").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                if (running.get()) Log.w(TAG, "accept failed", error)
                return
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching { socket.soTimeout = 10_000 }
            try {
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) {
                    respond(socket, 400, failure("请求格式无效"))
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                if (!authorized(headers["authorization"].orEmpty())) {
                    respond(socket, 401, failure("令牌无效"))
                    return
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 1_000_000) {
                    respond(socket, 413, failure("请求过大"))
                    return
                }
                val body = if (length > 0) readBody(input, length) else ""
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val result = try {
                    success(route(method, path, query, body))
                } catch (error: DeviceCapabilities.BridgeFailure) {
                    failure(error.message.orEmpty())
                } catch (error: Exception) {
                    Log.w(TAG, "bridge route failed", error)
                    failure(error.message ?: error.javaClass.simpleName)
                }
                respond(socket, 200, result)
            } catch (error: Exception) {
                Log.w(TAG, "bridge request failed", error)
                runCatching { respond(socket, 500, failure("设备桥内部错误")) }
            }
        }
    }

    private fun route(method: String, path: String, query: Map<String, String>, body: String): Any? {
        return when {
            method == "GET" && path == "/health" -> mapOf("running" to true)
            method == "GET" && path == "/device" -> DeviceCapabilities.deviceInfo(context)
            method == "GET" && path == "/notifications" ->
                DeviceCapabilities.notifications(int(query["limit"], 20, 1, 50))
            method == "GET" && path == "/ui" ->
                DeviceCapabilities.uiSnapshot(context, int(query["maxNodes"], 120, 20, 300))
            method == "GET" && path == "/ui/ocr" -> {
                guard(
                    DeviceRequest(
                        action = DeviceAction.OBSERVE,
                        packageName = DeviceCapabilities.foregroundPackage(),
                        detail = "截屏并离线识别屏幕文字",
                    ),
                )
                DeviceCapabilities.ocrSnapshot(context, int(query["maxBlocks"], 120, 20, 300))
            }
            method == "POST" && path == "/ui/tap" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val label = args["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                var x = number(args["x"])
                var y = number(args["y"])
                val targetApp = DeviceCapabilities.foregroundPackage()
                val request = DeviceRequest(
                    action = DevicePolicy.inferTapAction(text)
                        ?: DevicePolicy.inferTapAction(label)
                        ?: DeviceAction.TAP,
                    packageName = targetApp,
                    label = text ?: label ?: if (x != null && y != null) {
                        "坐标 ${x.toInt()},${y.toInt()}"
                    } else {
                        null
                    },
                    detail = when {
                        text != null -> "点击“${text.take(40)}”"
                        label != null -> "点击“${label.take(40)}”（坐标 ${x?.toInt()}, ${y?.toInt()}）"
                        else -> "坐标点击 (${x?.toInt()}, ${y?.toInt()})"
                    },
                )
                val needsApproval = DevicePolicy.evaluate(
                    DeviceGovernor.policy(),
                    request,
                ).decision == DeviceDecision.ASK
                guard(request)
                // The overlay window sits above the target UI; give the main
                // thread time to remove it before replaying taps in that area.
                if (needsApproval) Thread.sleep(350)
                if (needsApproval && targetApp != null &&
                    DeviceCapabilities.foregroundPackage() != targetApp
                ) {
                    if (!DeviceCapabilities.raiseApp(context, targetApp)) {
                        throw DeviceCapabilities.BridgeFailure(
                            "已允许，但无法把 $targetApp 切回前台，请重试",
                        )
                    }
                    // Approval switched to adsh, which usually closes the target
                    // keyboard and shifts the layout. Never reuse stale pixels.
                    if (text == null) {
                        val locate = label ?: throw DeviceCapabilities.BridgeFailure(
                            "审批后需要 label 才能重新定位；坐标点击请提供 label",
                        )
                        Thread.sleep(600)
                        val point = DeviceCapabilities.ocrFind(
                            locate,
                            int(args["index"], 0, 0, 10_000),
                        ) ?: throw DeviceCapabilities.BridgeFailure(
                            "审批后界面已变化，没有找到“$locate”，已取消点击",
                        )
                        x = point.first
                        y = point.second
                    }
                }
                DeviceCapabilities.tap(
                    text = text,
                    index = int(args["index"], 0, 0, 10_000),
                    x = x,
                    y = y,
                )
            }
            method == "POST" && path == "/ui/longpress" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val label = args["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                var x = number(args["x"])
                var y = number(args["y"])
                val targetApp = DeviceCapabilities.foregroundPackage()
                val request = DeviceRequest(
                    action = DevicePolicy.inferTapAction(text)
                        ?: DevicePolicy.inferTapAction(label)
                        ?: DeviceAction.TAP,
                    packageName = targetApp,
                    label = text ?: label ?: if (x != null && y != null) {
                        "坐标 ${x.toInt()},${y.toInt()}"
                    } else {
                        null
                    },
                    detail = when {
                        text != null -> "长按“${text.take(40)}”"
                        label != null -> "长按“${label.take(40)}”（坐标 ${x?.toInt()}, ${y?.toInt()}）"
                        else -> "长按坐标 (${x?.toInt()}, ${y?.toInt()})"
                    },
                )
                val needsApproval = DevicePolicy.evaluate(
                    DeviceGovernor.policy(),
                    request,
                ).decision == DeviceDecision.ASK
                guard(request)
                // The overlay window sits above the target UI; give the main
                // thread time to remove it before replaying taps in that area.
                if (needsApproval) Thread.sleep(350)
                if (needsApproval && targetApp != null &&
                    DeviceCapabilities.foregroundPackage() != targetApp
                ) {
                    if (!DeviceCapabilities.raiseApp(context, targetApp)) {
                        throw DeviceCapabilities.BridgeFailure(
                            "已允许，但无法把 $targetApp 切回前台，请重试",
                        )
                    }
                    if (text == null) {
                        val locate = label ?: throw DeviceCapabilities.BridgeFailure(
                            "审批后需要 label 才能重新定位；坐标长按请提供 label",
                        )
                        Thread.sleep(600)
                        val point = DeviceCapabilities.ocrFind(
                            locate,
                            int(args["index"], 0, 0, 10_000),
                        ) ?: throw DeviceCapabilities.BridgeFailure(
                            "审批后界面已变化，没有找到“$locate”，已取消长按",
                        )
                        x = point.first
                        y = point.second
                    }
                }
                DeviceCapabilities.longPress(
                    text = text,
                    index = int(args["index"], 0, 0, 10_000),
                    x = x,
                    y = y,
                    durationMs = int(args["durationMs"], 800, 500, 3_000),
                )
            }
            method == "POST" && path == "/ui/swipe" -> {
                val args = bodyObject(body)
                val x1 = number(args["x1"]) ?: throw DeviceCapabilities.BridgeFailure("需要 x1")
                val y1 = number(args["y1"]) ?: throw DeviceCapabilities.BridgeFailure("需要 y1")
                val x2 = number(args["x2"]) ?: throw DeviceCapabilities.BridgeFailure("需要 x2")
                val y2 = number(args["y2"]) ?: throw DeviceCapabilities.BridgeFailure("需要 y2")
                guard(
                    DeviceRequest(
                        action = DeviceAction.SWIPE,
                        packageName = DeviceCapabilities.foregroundPackage(),
                        detail = "从 (${x1.toInt()},${y1.toInt()}) 滑到 (${x2.toInt()},${y2.toInt()})",
                    ),
                )
                DeviceCapabilities.swipe(
                    x1, y1, x2, y2,
                    int(args["durationMs"], 300, 50, 3_000),
                )
            }
            method == "POST" && path == "/ui/text" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString()
                    ?: throw DeviceCapabilities.BridgeFailure("需要 text")
                val selector = args["selector"]?.toString()
                guard(
                    DeviceRequest(
                        action = DeviceAction.TYPE,
                        packageName = DeviceCapabilities.foregroundPackage(),
                        label = selector?.takeIf { it.isNotBlank() } ?: "输入框",
                        detail = "写入 ${text.length} 个字符",
                        credential = DeviceCapabilities.inputFieldIsCredential(selector),
                    ),
                )
                DeviceCapabilities.inputText(text, selector)
            }
            method == "POST" && path == "/app/open" -> {
                val args = bodyObject(body)
                val packageName = DeviceCapabilities.resolveAppPackage(
                    context,
                    packageName = args["package"]?.toString(),
                    appName = args["name"]?.toString(),
                )
                guard(
                    DeviceRequest(
                        action = DeviceAction.LAUNCH,
                        packageName = packageName,
                        label = args["name"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: packageName,
                        detail = "打开 $packageName",
                    ),
                )
                DeviceCapabilities.openApp(context, packageName, null)
            }
            method == "GET" && path == "/apps" ->
                DeviceCapabilities.listApps(
                    context,
                    int(query["limit"], 80, 1, 200),
                )
            method == "POST" && path == "/actions/alarm" -> {
                val args = bodyObject(body)
                val hour = number(args["hour"])?.toInt()
                val minute = number(args["minute"])?.toInt()
                guard(
                    DeviceRequest(
                        action = DeviceAction.ALARM,
                        label = args["label"]?.toString(),
                        detail = "设置 ${hour ?: "?"}:${minute?.toString()?.padStart(2, '0') ?: "?"} 的闹钟",
                    ),
                )
                DeviceCapabilities.setAlarm(
                    context,
                    hour = hour,
                    minute = minute,
                    label = args["label"]?.toString(),
                )
            }
            method == "POST" && path == "/actions/timer" -> {
                val args = bodyObject(body)
                val seconds = number(args["seconds"])?.toInt()
                    ?: throw DeviceCapabilities.BridgeFailure("需要 seconds")
                guard(
                    DeviceRequest(
                        action = DeviceAction.TIMER,
                        label = args["label"]?.toString(),
                        detail = "创建 $seconds 秒计时器",
                    ),
                )
                DeviceCapabilities.setTimer(context, seconds, args["label"]?.toString())
            }
            method == "GET" && path == "/actions/weather" -> {
                val city = query["city"]?.takeIf { it.isNotBlank() }
                    ?: throw DeviceCapabilities.BridgeFailure("需要 city")
                WeatherService.current(city)
            }
            method == "POST" && path == "/actions/weather" -> {
                val args = bodyObject(body)
                val city = args["city"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: throw DeviceCapabilities.BridgeFailure("需要 city")
                WeatherService.current(city)
            }
            method == "POST" && path == "/actions/sms" -> {
                val args = bodyObject(body)
                val text = args["body"]?.toString()
                    ?: throw DeviceCapabilities.BridgeFailure("需要 body")
                val recipient = DevicePolicy.maskNumber(args["to"]?.toString())
                guard(
                    DeviceRequest(
                        action = DeviceAction.LAUNCH,
                        label = "预填短信",
                        detail = "给 $recipient 预填 ${text.length} 字短信，仍需你手动发送",
                    ),
                )
                DeviceCapabilities.composeSms(context, args["to"]?.toString(), text)
            }
            method == "POST" && path == "/actions/sms/send" -> {
                val args = bodyObject(body)
                val text = args["body"]?.toString().orEmpty()
                guard(
                    DeviceRequest(
                        action = DeviceAction.SEND_MESSAGE,
                        label = "发送短信",
                        detail = "收件人 ${DevicePolicy.maskNumber(args["to"]?.toString())}，" +
                            "${text.length} 个字符，发送后不可撤回",
                    ),
                )
                DeviceCapabilities.sendSms(
                    context,
                    args["to"]?.toString(),
                    text,
                )
            }
            method == "POST" && path == "/actions/dial" -> {
                val args = bodyObject(body)
                val number = args["number"]?.toString()
                    ?: throw DeviceCapabilities.BridgeFailure("需要 number")
                guard(
                    DeviceRequest(
                        action = DeviceAction.CALL,
                        label = "拨打电话",
                        detail = "号码 ${DevicePolicy.maskNumber(number)}，仍需你手动拨出",
                    ),
                )
                DeviceCapabilities.dial(context, number)
            }
            method == "POST" && path == "/actions/clipboard" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString() ?: throw DeviceCapabilities.BridgeFailure("需要 text")
                guard(
                    DeviceRequest(
                        action = DeviceAction.TYPE,
                        packageName = DeviceCapabilities.foregroundPackage(),
                        label = "剪贴板",
                        detail = "写入 ${text.length} 个字符到剪贴板",
                    ),
                )
                DeviceCapabilities.setClipboard(context, text)
            }
            method == "POST" && path == "/actions/share" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString() ?: throw DeviceCapabilities.BridgeFailure("需要 text")
                val packageName = args["package"]?.toString()?.takeIf { it.isNotBlank() }
                guard(
                    DeviceRequest(
                        action = DeviceAction.LAUNCH,
                        packageName = packageName,
                        label = "分享文本",
                        detail = "把 ${text.length} 个字符交给${packageName ?: "系统分享面板"}，仍需选择联系人并发送",
                    ),
                )
                DeviceCapabilities.shareText(
                    context,
                    packageName = packageName,
                    text = text,
                    subject = args["subject"]?.toString(),
                )
            }
            method == "POST" && path == "/actions/mailto" -> {
                val args = bodyObject(body)
                val to = args["to"]?.toString() ?: throw DeviceCapabilities.BridgeFailure("需要收件人 to")
                val body = args["body"]?.toString().orEmpty()
                val packageName = args["package"]?.toString()?.takeIf { it.isNotBlank() }
                guard(
                    DeviceRequest(
                        action = DeviceAction.LAUNCH,
                        packageName = packageName,
                        label = "预填邮件",
                        detail = "收件人 ${DevicePolicy.maskNumber(to)}，正文 ${body.length} 个字符，仍需手动发送",
                    ),
                )
                DeviceCapabilities.composeMail(
                    context,
                    packageName = packageName,
                    to = to,
                    subject = args["subject"]?.toString(),
                    body = body,
                )
            }
            method == "POST" && path == "/actions/speak" -> {
                val args = bodyObject(body)
                val text = args["text"]?.toString()
                    ?: throw DeviceCapabilities.BridgeFailure("需要 text")
                guard(
                    DeviceRequest(
                        action = DeviceAction.SPEAK,
                        detail = "朗读 ${text.length} 个字符",
                    ),
                )
                DeviceCapabilities.speak(context, text)
            }
            method == "POST" && path == "/actions/speak/stop" -> {
                DeviceCapabilities.stopSpeaking()
                "已停止朗读"
            }
            else -> throw DeviceCapabilities.BridgeFailure("未知接口：$method $path")
        }
    }

    /**
     * Runs the on-device policy and, when needed, blocks this worker until the
     * user answers in-app or expires. The agent can never resolve its own ask.
     */
    private fun guard(request: DeviceRequest) {
        val authorization = DeviceGovernor.authorize(context, request)
        if (authorization.decision != DeviceDecision.ALLOW) {
            throw DeviceCapabilities.BridgeFailure(authorization.message)
        }
    }

    private fun bodyObject(body: String): Map<String, Any?> {
        if (body.isBlank()) return emptyMap()
        val parsed = DshJson.parse(body)
        return parsed as? Map<String, Any?>
            ?: throw DeviceCapabilities.BridgeFailure("请求体必须是 JSON 对象")
    }

    private fun authorized(header: String): Boolean {
        val expected = "Bearer $token"
        return MessageDigest.isEqual(expected.toByteArray(), header.toByteArray())
    }

    private fun int(value: Any?, fallback: Int, minimum: Int, maximum: Int): Int {
        val parsed = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: fallback
            else -> fallback
        }
        return parsed.coerceIn(minimum, maximum)
    }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }.toMap()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream()
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1 && byte != '\n'.code) {
            if (byte != '\r'.code) buffer.write(byte)
            byte = input.read()
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun success(value: Any?): String =
        DshJson.stringify(mapOf("ok" to true, "value" to value))

    private fun failure(message: String): String =
        DshJson.stringify(mapOf("ok" to false, "error" to message))

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private companion object {
        const val TAG = "AdshBridge"
    }
}
