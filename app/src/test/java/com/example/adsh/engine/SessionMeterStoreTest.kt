package com.example.adsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionMeterStoreTest {
    @Test
    fun `cache hit is null without billed input`() {
        assertNull(SessionMeter().cacheHitPercent)
        assertNull(SessionMeter(cacheReadTokens = 0, uncachedInputTokens = 0).cacheHitPercent)
    }

    @Test
    fun `cache hit never rounds a partial hit to 100`() {
        assertEquals("92.3", SessionMeter(cacheReadTokens = 120, uncachedInputTokens = 10).cacheHitPercent)
    }

    @Test
    fun `full and exact cache hits`() {
        assertEquals("100", SessionMeter(cacheReadTokens = 100, uncachedInputTokens = 0).cacheHitPercent)
        assertEquals("50", SessionMeter(cacheReadTokens = 50, uncachedInputTokens = 50).cacheHitPercent)
    }

    @Test
    fun `cache writes count toward the prompt total`() {
        assertEquals("80", SessionMeter(cacheReadTokens = 80, cacheWriteTokens = 20).cacheHitPercent)
    }

    @Test
    fun `context percent uses prompt-side pressure`() {
        val meter = SessionMeter(pressureTokens = 104_028, contextWindow = 1_000_000)
        assertEquals(10, meter.contextPercent)
        assertEquals(104_028, meter.usedContextTokens)
        assertNull(SessionMeter(pressureTokens = 104_028).contextPercent)
        assertEquals(100, SessionMeter(pressureTokens = 2_000_000, contextWindow = 1_000_000).contextPercent)
    }

    @Test
    fun `projected tokens win over pressure tokens`() {
        val meter = SessionMeter(pressureTokens = 10, projectedTokens = 20, contextWindow = 200)
        assertEquals(20, meter.usedContextTokens)
        assertEquals(10, meter.contextPercent)
    }

    @Test
    fun `reads the host projection cache`() {
        val home = Files.createTempDirectory("meter-home").toFile()
        try {
            val dir = File(home, "storages/session_projcache/sessions").apply { mkdirs() }
            File(dir, "session-abc.json").writeText(
                """
                {
                  "record": {
                    "rows": {
                      "tokenUsage": {
                        "val": {
                          "totals": {
                            "uncachedInputTokens": 153143,
                            "outputTokens": 32333,
                            "cacheReadTokens": 11162624,
                            "cacheWriteTokens": 0
                          }
                        }
                      },
                      "contextPressure": {
                        "val": {
                          "surfaceTokens": 57541,
                          "contextWindow": 1000000,
                          "pressureTokens": 104028
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
            val meter = SessionMeterStore(home).read("abc")!!
            assertEquals(11_162_624, meter.cacheReadTokens)
            assertEquals(153_143, meter.uncachedInputTokens)
            assertEquals(32_333, meter.outputTokens)
            assertEquals(104_028, meter.usedContextTokens)
            assertEquals(1_000_000, meter.contextWindow)
            assertEquals(10, meter.contextPercent)
            assertNull(SessionMeterStore(home).read("missing"))
            assertNull(SessionMeterStore(home).read(null))
        } finally {
            home.deleteRecursively()
        }
    }
}
