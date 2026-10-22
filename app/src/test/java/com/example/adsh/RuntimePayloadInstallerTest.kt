package com.example.adsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimePayloadInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun payload(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun publishesVerifiedRuntimeAndReusesImmutableGeneration() {
        val data = payload(mapOf(
            "runtime/bin/node" to "node",
            "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to "dsh",
            "dshhome/profiles/web/package.json" to "{}",
        ))
        val root = Files.createTempDirectory("adsh-runtime").toFile()
        val installer = RuntimePayloadInstaller(root)
        val first = installer.install(data.inputStream(), sha(data))
        assertEquals("node", first.resolve("runtime/bin/node").readText())
        assertTrue(first.resolve("runtime/bin/node").canExecute())
        assertEquals(first, installer.install(data.inputStream(), sha(data)))
    }

    @Test
    fun rejectsChangedPayloadBeforePublication() {
        val data = payload(mapOf("runtime/bin/node" to "changed"))
        val root = Files.createTempDirectory("adsh-runtime").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePayloadInstaller(root).install(data.inputStream(), "0".repeat(64))
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("runtime-") })
    }

    @Test
    fun rejectsTraversalEvenWithValidDigest() {
        val data = payload(mapOf(
            "../outside" to "bad",
            "runtime/bin/node" to "node",
            "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to "dsh",
            "dshhome/profiles/web/package.json" to "{}",
        ))
        val root = Files.createTempDirectory("adsh-runtime").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePayloadInstaller(root).install(data.inputStream(), sha(data))
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("runtime-") })
    }

    @Test
    fun releasePayloadMatchesPinnedDigestAndContainsRealHarness() {
        val asset = File("src/main/assets/dsh/payload.zip")
        val installed = RuntimePayloadInstaller(temp.newFolder("release"))
            .install(asset.inputStream(), "6f5003bd7729135683460d9d85cb0c1b2673dbfea6584046a0de8d6968345fb7")
        assertTrue(installed.resolve("runtime/bin/node").canExecute())
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/package.json")
            .readText().contains("\"version\": \"0.2.1-rc.2\""))
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-client-ui-trajectory/lib/client.js").isFile)
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-session-stats/lib/index.js").isFile)
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-token-meter/lib/index.js").isFile)
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "@deepseek-ai/dsh-client-ui-conversation/lib/client.js").isFile)
        assertTrue(installed.resolve("dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" +
            "restructure/src/EncodeStream.js").isFile)
    }
}
