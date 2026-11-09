package com.example.adsh.device

import com.example.adsh.engine.DshJson
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Single-call weather lookup through Open-Meteo (no API key, HTTPS), so the assistant can answer
 * "今天天气" without a long planning chain.
 */
object WeatherService {
    private const val TIMEOUT_MS = 8_000

    fun current(city: String): Map<String, Any?> {
        val name = city.trim()
        if (name.isEmpty()) throw DeviceCapabilities.BridgeFailure("需要城市名称")
        val encoded = URLEncoder.encode(name, "UTF-8")
        val geo = getJson(
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$encoded&count=1&language=zh&format=json",
        )
        val results = geo["results"] as? List<*>
        val place = results?.firstOrNull() as? Map<String, Any?>
            ?: throw DeviceCapabilities.BridgeFailure("没有找到城市：$name")
        val latitude = (place["latitude"] as? Number)?.toDouble()
            ?: throw DeviceCapabilities.BridgeFailure("城市坐标无效：$name")
        val longitude = (place["longitude"] as? Number)?.toDouble()
            ?: throw DeviceCapabilities.BridgeFailure("城市坐标无效：$name")
        val forecast = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=3",
        )
        val current = forecast["current"] as? Map<String, Any?>
            ?: throw DeviceCapabilities.BridgeFailure("天气服务没有返回当前天气")
        val daily = forecast["daily"] as? Map<String, Any?>
        val highs = daily?.get("temperature_2m_max") as? List<*>
        val lows = daily?.get("temperature_2m_min") as? List<*>
        val rain = daily?.get("precipitation_probability_max") as? List<*>
        return linkedMapOf(
            "city" to (place["name"] ?: name),
            "area" to place["admin1"],
            "time" to current["time"],
            "timezone" to forecast["timezone"],
            "condition" to describe((current["weather_code"] as? Number)?.toInt()),
            "temperature" to current["temperature_2m"],
            "apparent" to current["apparent_temperature"],
            "humidity" to current["relative_humidity_2m"],
            "wind_kmh" to current["wind_speed_10m"],
            "today_high" to highs?.getOrNull(0),
            "today_low" to lows?.getOrNull(0),
            "rain_probability" to rain?.getOrNull(0),
            "forecast" to forecastDays(daily),
        )
    }

    private fun forecastDays(daily: Map<String, Any?>?): List<Map<String, Any?>> {
        val dates = daily?.get("time") as? List<*> ?: return emptyList()
        val highs = daily["temperature_2m_max"] as? List<*>
        val lows = daily["temperature_2m_min"] as? List<*>
        val codes = daily["weather_code"] as? List<*>
        val rain = daily["precipitation_probability_max"] as? List<*>
        return dates.mapIndexed { index, date ->
            linkedMapOf(
                "date" to date,
                "condition" to describe((codes?.getOrNull(index) as? Number)?.toInt()),
                "high" to highs?.getOrNull(index),
                "low" to lows?.getOrNull(index),
                "rain_probability" to rain?.getOrNull(index),
            )
        }
    }

    private fun getJson(url: String): Map<String, Any?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "adsh-weather/1.0")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw DeviceCapabilities.BridgeFailure("天气服务暂时不可用（HTTP $status）")
            }
            DshJson.parse(text) as? Map<String, Any?>
                ?: throw DeviceCapabilities.BridgeFailure("天气服务返回格式异常")
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(code: Int?): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨"
        66, 67 -> "冻雨"
        71, 73, 75 -> "雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷雨"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知"
    }
}
