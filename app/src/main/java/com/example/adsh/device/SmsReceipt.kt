package com.example.adsh.device

/**
 * Turns SmsManager sent-broadcast result codes into a user-facing outcome.
 * Kept free of Android types so it stays unit-testable.
 */
internal object SmsReceipt {
    const val RESULT_OK = -1
    const val TIMEOUT_SECONDS = 15

    fun label(code: Int): String = when (code) {
        RESULT_OK -> "成功"
        1 -> "通用错误"
        2 -> "无线电关闭"
        3 -> "空 PDU"
        4 -> "无服务"
        5 -> "超出发送限额"
        6 -> "FDN 限制"
        7 -> "运营商不允许向该短号发送"
        8 -> "运营商永久禁止向该短号发送"
        else -> "错误码 $code"
    }

    fun summary(address: String, parts: Int, codes: List<Int>): String {
        if (codes.size < parts) {
            return "已提交短信发送给 $address（$parts 条），但 $TIMEOUT_SECONDS 秒内未收到完整发送回执"
        }
        val failures = codes.filter { it != RESULT_OK }
        if (failures.isEmpty()) {
            return "短信发送成功：$address（$parts 条）"
        }
        val reasons = failures.distinct().joinToString("；") { label(it) }
        return "短信发送失败：$address（$parts 条中 ${failures.size} 条失败：$reasons）"
    }

    fun audit(address: String, chars: Int, parts: Int, codes: List<Int>): String {
        val status = when {
            codes.size < parts -> "timeout"
            codes.all { it == RESULT_OK } -> "ok"
            else -> "fail(${codes.filter { it != RESULT_OK }.distinct().joinToString(",")})"
        }
        return "$address chars=$chars parts=$parts result=$status"
    }
}
