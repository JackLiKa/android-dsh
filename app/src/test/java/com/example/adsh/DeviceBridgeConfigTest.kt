package com.example.adsh

import com.example.adsh.device.DeviceBridgeConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBridgeConfigTest {
    @Test
    fun patchKeepsInsertRowAndQuotesValues() {
        val yaml = DeviceBridgeConfig.patchYaml(
            nodePath = "/data/user/0/com.example.adsh/dsh-runtime/runtime/bin/node",
            scriptPath = "/data/user/0/com.example.adsh/android-bridge/mcp-server.mjs",
            workingDirectory = "/data/user/0/com.example.adsh/android-bridge",
            url = "http://127.0.0.1:4567",
            token = "abc123",
        )
        assertTrue(yaml.startsWith("#"))
        assertTrue(yaml.contains("- insert:"))
        assertTrue(yaml.contains("id: adsh-device-bridge"))
        assertTrue(yaml.contains("name: '@deepseek-ai/dsh-mcp-client'"))
        assertTrue(yaml.contains("transport: stdio"))
        assertTrue(yaml.contains("serverName: 'adsh_device'"))
        assertTrue(yaml.contains("ADSH_BRIDGE_URL: 'http://127.0.0.1:4567'"))
        assertTrue(yaml.contains("ADSH_BRIDGE_TOKEN: 'abc123'"))
        assertTrue(yaml.contains("toolCallTimeoutMs: 30000"))
        assertTrue(yaml.contains("failOnStartupError: false"))
    }

    @Test
    fun patchEscapesSingleQuotes() {
        val yaml = DeviceBridgeConfig.patchYaml(
            nodePath = "/tmp/it's a node",
            scriptPath = "/tmp/script.mjs",
            workingDirectory = "/tmp",
            url = "http://127.0.0.1:1",
            token = "t",
        )
        assertTrue(yaml.contains("command: '/tmp/it''s a node'"))
    }
}
