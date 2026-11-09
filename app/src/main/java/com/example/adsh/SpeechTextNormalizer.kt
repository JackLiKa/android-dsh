package com.example.adsh

/**
 * Conservative inverse text normalization (ITN) for Mandarin ASR output.
 *
 * Chinese acoustic models emit numerals as characters ("二零二六年十一月九日") while chat
 * input, commands and phone numbers read better as digits. Conversion only happens when the
 * neighbouring words make the reading unambiguous; idioms such as "一起", "十分", "一点意见"
 * and "三点意见" are left untouched.
 */
object SpeechTextNormalizer {
    private val DIGITS = mapOf(
        '零' to 0,
        '〇' to 0,
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )
    private const val NUM_CHARS = "零〇一二三四五六七八九十百千万亿两"
    private val NUMBER_RUN = Regex("[$NUM_CHARS]+")
    private val PERCENT = Regex("百分之([$NUM_CHARS]+)")
    private val UNITS = listOf(
        "分钟", "小时", "秒钟", "公里", "千米", "厘米", "毫米", "公斤", "千克", "毫升",
        "秒", "米", "克", "斤", "升", "岁", "个", "位", "次", "人", "天", "周",
        "层", "楼", "元", "块钱", "块", "毛", "角",
    )
    private val TIME_PREFIXES = listOf(
        "上午", "下午", "中午", "晚上", "早上", "凌晨", "半夜", "今天", "明天", "昨天",
        "现在", "时间", "大约", "大概", "约",
    )

    /** Convert the unambiguous Chinese numeral runs in [text] to Arabic digits. */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        var result = PERCENT.replace(text) { match ->
            val value = parseNumber(match.groupValues[1]) ?: return@replace match.value
            "$value%"
        }
        result = NUMBER_RUN.replace(result) { match ->
            val raw = match.value
            val prefix = result.substring(maxOf(0, match.range.first - 4), match.range.first)
            val suffix = result.substring(
                match.range.last + 1,
                minOf(result.length, match.range.last + 1 + 6),
            )
            normalizeRun(raw, prefix, suffix) ?: raw
        }
        return result
    }

    private fun normalizeRun(raw: String, prefix: String, suffix: String): String? {
        if (raw.length > 12) return null
        val value = parseNumber(raw) ?: return null

        if (suffix.startsWith("年")) {
            if (raw.length in 2..4 && raw.all { it in DIGITS }) {
                return raw.map { DIGITS.getValue(it) }.joinToString("")
            }
            return value.takeIf { it in 1..9999 }?.toString()
        }
        if (suffix.startsWith("月")) {
            return value.takeIf { it in 1..12 }?.toString()
        }
        if (suffix.startsWith("日") || suffix.startsWith("号")) {
            if (suffix.startsWith("号码")) return null
            return value.takeIf { it in 1..31 }?.toString()
        }
        if (suffix.startsWith("点")) {
            if (value !in 0..24) return null
            val rest = suffix.removePrefix("点")
            val minuteFollows = rest.isNotEmpty() && (
                rest[0] in NUM_CHARS || rest.startsWith("半") || rest.startsWith("整")
                )
            val timeContext = minuteFollows ||
                rest.startsWith("钟") ||
                (rest.isEmpty() && value >= 10) ||
                TIME_PREFIXES.any { prefix.endsWith(it) }
            if (!timeContext) return null
            return value.toString()
        }
        if (prefix.endsWith("点") && value in 0..59) {
            val followedByMinute = suffix.startsWith("分") ||
                suffix.isEmpty() ||
                suffix.first() in "，。！？、"
            if (followedByMinute) return value.toString()
        }
        if (prefix.endsWith("第")) {
            // "第一时间" is an idiom, not an ordinal.
            if (raw == "一" && suffix.startsWith("时间")) return null
            return value.takeIf { it in 1..9999 }?.toString()
        }
        if (suffix.startsWith("度") && value >= 2) {
            return value.takeIf { it in 2..99_999_999 }?.toString()
        }
        if (hasUnit(suffix)) {
            return value.takeIf { it in 0..99_999_999 }?.toString()
        }
        // "三块五": the trailing unit digit of a spoken price.
        if (raw.length == 1 && (prefix.endsWith("块") || prefix.endsWith("元"))) {
            return value.toString()
        }
        return null
    }

    private fun hasUnit(suffix: String): Boolean {
        if (suffix.startsWith("块儿")) return false
        return UNITS.any { suffix.startsWith(it) }
    }

    /** Parse a canonical Chinese number; pure digit strings are read digit by digit. */
    internal fun parseNumber(raw: String): Long? {
        if (raw.isEmpty()) return null
        if (raw.all { it in DIGITS }) {
            var value = 0L
            for (char in raw) {
                value = value * 10 + DIGITS.getValue(char)
                if (value > 999_999_999L) return null
            }
            return value
        }
        var total = 0L
        var section = 0L
        var number = 0L
        var seen = false
        for (char in raw) {
            when (char) {
                in DIGITS -> {
                    number = DIGITS.getValue(char).toLong()
                    seen = true
                }
                '十' -> {
                    section += (if (number == 0L) 1 else number) * 10
                    number = 0
                    seen = true
                }
                '百' -> {
                    section += (if (number == 0L) 1 else number) * 100
                    number = 0
                    seen = true
                }
                '千' -> {
                    section += (if (number == 0L) 1 else number) * 1000
                    number = 0
                    seen = true
                }
                '万' -> {
                    total += (section + number) * 10_000
                    section = 0
                    number = 0
                    seen = true
                }
                '亿' -> {
                    total = (total + section + number) * 100_000_000
                    section = 0
                    number = 0
                    seen = true
                }
                else -> return null
            }
            if (total > 999_999_999L) return null
        }
        if (!seen) return null
        return (total + section + number).takeIf { it <= 999_999_999L }
    }
}
