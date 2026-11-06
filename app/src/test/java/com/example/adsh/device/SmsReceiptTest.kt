package com.example.adsh.device

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsReceiptTest {
    @Test
    fun successfulPartsReportSent() {
        assertEquals(
            "短信发送成功：10086（2 条）",
            SmsReceipt.summary("10086", 2, listOf(SmsReceipt.RESULT_OK, SmsReceipt.RESULT_OK)),
        )
    }

    @Test
    fun carrierShortCodeRejectionIsExplained() {
        assertEquals(
            "短信发送失败：10001（1 条中 1 条失败：运营商不允许向该短号发送）",
            SmsReceipt.summary("10001", 1, listOf(7)),
        )
    }

    @Test
    fun partialFailureCountsOnlyFailedParts() {
        assertEquals(
            "短信发送失败：13800138000（3 条中 2 条失败：无服务；错误码 42）",
            SmsReceipt.summary("13800138000", 3, listOf(-1, 4, 42)),
        )
    }

    @Test
    fun missingReceiptsReportTimeout() {
        assertEquals(
            "已提交短信发送给 10086（2 条），但 15 秒内未收到完整发送回执",
            SmsReceipt.summary("10086", 2, listOf(SmsReceipt.RESULT_OK)),
        )
    }

    @Test
    fun auditKeepsFailedCodes() {
        assertEquals(
            "10001 chars=3 parts=1 result=fail(7)",
            SmsReceipt.audit("10001", 3, 1, listOf(7)),
        )
        assertEquals(
            "10086 chars=3 parts=1 result=timeout",
            SmsReceipt.audit("10086", 3, 1, emptyList()),
        )
    }
}
