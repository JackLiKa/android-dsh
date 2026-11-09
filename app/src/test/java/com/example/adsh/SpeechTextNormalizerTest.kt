package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {
    @Test
    fun convertsDatesAndClockTimes() {
        assertEquals("2026年11月9日", SpeechTextNormalizer.normalize("二零二六年十一月九日"))
        assertEquals("下午3点15分", SpeechTextNormalizer.normalize("下午三点十五分"))
        assertEquals("上午10点半", SpeechTextNormalizer.normalize("上午十点半"))
        assertEquals("11点", SpeechTextNormalizer.normalize("十一点"))
        assertEquals("3点整", SpeechTextNormalizer.normalize("三点整"))
    }

    @Test
    fun convertsPercentOrdinalsAndQuantities() {
        assertEquals("50%", SpeechTextNormalizer.normalize("百分之五十"))
        assertEquals("100%", SpeechTextNormalizer.normalize("百分之百"))
        assertEquals("第3名", SpeechTextNormalizer.normalize("第三名"))
        assertEquals("12岁", SpeechTextNormalizer.normalize("十二岁"))
        assertEquals("105个", SpeechTextNormalizer.normalize("一百零五个"))
        assertEquals("20000元", SpeechTextNormalizer.normalize("两万元"))
        assertEquals("3块5", SpeechTextNormalizer.normalize("三块五"))
    }

    @Test
    fun leavesAmbiguousIdiomsUntouched() {
        assertEquals("一起走", SpeechTextNormalizer.normalize("一起走"))
        assertEquals("十分高兴", SpeechTextNormalizer.normalize("十分高兴"))
        assertEquals("我有一点意见", SpeechTextNormalizer.normalize("我有一点意见"))
        assertEquals("快一点", SpeechTextNormalizer.normalize("快一点"))
        assertEquals("三点意见", SpeechTextNormalizer.normalize("三点意见"))
        assertEquals("第一时间", SpeechTextNormalizer.normalize("第一时间"))
        assertEquals("千万不要", SpeechTextNormalizer.normalize("千万不要"))
    }

    @Test
    fun keepsAsciiAndPunctuation() {
        assertEquals(
            "2026年11月9日，气温5度。",
            SpeechTextNormalizer.normalize("二零二六年十一月九日，气温五度。"),
        )
    }
}
