package com.example.adsh

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

class RuntimePayloadInstaller(private val root: File) {
    private val required = listOf(
        "runtime/bin/node",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "dshhome/profiles/web/package.json",
    )

    fun install(asset: InputStream, expectedSha256: String): File {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "无效的运行时校验值" }
        check(root.mkdirs() || root.isDirectory)
        val destination = root.resolve("runtime-$expectedSha256")
        if (destination.exists()) return verifyPublished(destination, expectedSha256)
        val unique = UUID.randomUUID().toString()
        val archive = root.resolve(".download-$unique.zip")
        val staging = root.resolve(".staging-$unique")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(archive).use { output ->
                asset.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "运行时压缩包过大" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expectedSha256) { "运行时 SHA-256 校验失败" }
            check(staging.mkdir())
            extract(archive, staging)
            check(required.all { staging.resolve(it).isFile }) { "运行时缺少必需文件" }
            for (directory in listOf("runtime/bin", "bin")) {
                staging.resolve(directory).listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    check(file.setExecutable(true, true)) { "无法启用运行时命令：${file.name}" }
                }
            }
            for (relative in listOf(
                "git/libexec/git-core/git-remote-http",
                "git/libexec/git-core/git-remote-https",
                "python/bin/python3.14",
            )) {
                val command = staging.resolve(relative)
                if (command.isFile) check(command.setExecutable(true, true))
            }
            FileOutputStream(staging.resolve("payload.sha256")).use { stream ->
                stream.write((expectedSha256 + "\n").toByteArray())
                stream.fd.sync()
            }
            if (!destination.exists()) check(staging.renameTo(destination)) { "运行时代际发布失败" }
            return verifyPublished(destination, expectedSha256)
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun verifyPublished(directory: File, sha256: String): File {
        check(directory.isDirectory &&
            directory.resolve("payload.sha256").readText().trim() == sha256 &&
            required.all { directory.resolve(it).isFile }) { "已发布的运行时损坏" }
        applyNativeLinks(directory)
        patchTermuxRunpaths(directory)
        return directory
    }

    /**
     * The runtime was built in a Termux prefix: every ELF carries
     * `DT_RUNPATH=/data/data/com.termux/files/usr/lib`, which does not exist inside this app.
     * Rewrite it in place (same byte budget, NUL padded) to `$ORIGIN` for libraries and
     * `$ORIGIN/../lib` for executables so the dynamic linker resolves the bundled libraries
     * without relying on `LD_LIBRARY_PATH` (not honored on every Android build or native
     * translation setup, and DT_RUNPATH is not transitive for indirect dependencies).
     *
     * A marker file makes this one-time work per published generation.
     */
    private fun patchTermuxRunpaths(directory: File) {
        val marker = directory.resolve(".termux-rpath-patched")
        if (marker.isFile) return
        val lib = directory.resolve("runtime/lib")
        lib.listFiles().orEmpty().filter { it.isFile }.forEach { patchElfRunpath(it, "\$ORIGIN") }
        directory.resolve("runtime/bin").listFiles().orEmpty().filter { it.isFile }
            .forEach { patchElfRunpath(it, "\$ORIGIN/../lib") }
        runCatching { marker.writeText("1") }
    }

    /**
     * Rewrites the ELF `DT_RUNPATH`/`DT_RPATH` string in place (same byte budget, NUL padded).
     * Only the exact dynamic-string-table slot is touched: Node also embeds unrelated build
     * metadata that contains the Termux prefix, and patching arbitrary byte matches corrupts it.
     */
    private fun patchElfRunpath(file: File, replacement: String) {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[4] != 2.toByte()) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = buffer.getLong(32)
        val phentsize = buffer.getShort(54).toInt()
        val phnum = buffer.getShort(56).toInt()
        if (phoff <= 0 || phentsize <= 0 || phnum <= 0) return
        var dynOffset = -1L
        var dynSize = 0L
        val loads = mutableListOf<LongArray>()
        for (index in 0 until phnum) {
            val base = phoff + index.toLong() * phentsize
            if (base < 0 || base + 56 > bytes.size) return
            val at = base.toInt()
            when (buffer.getInt(at)) {
                1 -> loads += longArrayOf(
                    buffer.getLong(at + 8),   // p_offset
                    buffer.getLong(at + 16),  // p_vaddr
                    buffer.getLong(at + 32),  // p_filesz
                )
                2 -> {
                    dynOffset = buffer.getLong(at + 8)
                    dynSize = buffer.getLong(at + 32)
                }
            }
        }
        if (dynOffset < 0 || dynSize <= 0) return
        var strtabAddress = -1L
        var runpathIndex = -1L
        var cursor = dynOffset.toInt()
        val dynEnd = (dynOffset + dynSize).toInt()
        while (cursor + 16 <= dynEnd) {
            val tag = buffer.getLong(cursor)
            val value = buffer.getLong(cursor + 8)
            if (tag == 0L) break
            when (tag) {
                5L -> strtabAddress = value
                15L, 29L -> runpathIndex = value
            }
            cursor += 16
        }
        if (strtabAddress < 0 || runpathIndex < 0) return
        var strtabOffset = -1L
        for (load in loads) {
            // load = [fileOffset, virtualAddress, fileSize]
            if (strtabAddress >= load[1] && strtabAddress < load[1] + load[2]) {
                strtabOffset = load[0] + (strtabAddress - load[1])
                break
            }
        }
        if (strtabOffset < 0) return
        val position = (strtabOffset + runpathIndex).toInt()
        val current = "/data/data/com.termux/files/usr/lib".toByteArray(Charsets.UTF_8)
        if (position < 0 || position + current.size > bytes.size) return
        if (!bytes.copyOfRange(position, position + current.size).contentEquals(current)) return
        replacement.toByteArray(Charsets.UTF_8).copyInto(bytes, position)
        for (offset in replacement.length until current.size) bytes[position + offset] = 0
        runCatching {
            FileOutputStream(file).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
        }
    }

    /**
     * Restores the soname aliases declared in `runtime/lib/LINKS.txt` (for example
     * `libz.so.1` -> `libz.so.1.3.2`). Zip archives cannot carry symbolic links, and the bundled
     * Node ELF resolves its NEEDED entries through those alias names, so without this step the
     * engine fails with `CANNOT LINK EXECUTABLE ... libz.so.1 not found`. Hard link first, then a
     * relative symbolic link, finally a copy, matching what the runtime's own packager does.
     */
    private fun applyNativeLinks(directory: File) {
        val lib = directory.resolve("runtime/lib")
        val manifest = lib.resolve("LINKS.txt")
        if (!manifest.isFile) return
        manifest.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(Regex("\\t+"), limit = 2)
            if (parts.size != 2) return@forEachLine
            val name = parts[0].trim()
            val target = parts[1].trim()
            if (name.isEmpty() || target.isEmpty() || '/' in name || '/' in target) return@forEachLine
            val link = File(lib, name)
            val source = File(lib, target)
            if (link.exists() || !source.isFile) return@forEachLine
            runCatching { Files.createLink(link.toPath(), source.toPath()) }
                .recoverCatching { Files.createSymbolicLink(link.toPath(), Paths.get(target)) }
                .recoverCatching { source.copyTo(link, overwrite = true) }
        }
    }

    private fun extract(archive: File, destination: File) {
        val prefix = destination.canonicalPath + File.separator
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(seen.size < 50_000 && seen.add(entry.name)) { "运行时条目过多或重复" }
                require(entry.name.isNotBlank() && !entry.name.startsWith('/') &&
                    '\\' !in entry.name && entry.name.split('/').none { it == ".." }) {
                    "运行时包含无效路径"
                }
                val output = destination.resolve(entry.name)
                require(output.canonicalPath.startsWith(prefix)) { "运行时路径超出安装根目录" }
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory)
                } else {
                    check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
                    check(output.createNewFile()) { "运行时条目重复" }
                    FileOutputStream(output).use { file ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(size <= 256L * 1024 * 1024 && total <= 1024L * 1024 * 1024) {
                                "运行时解压后超过限制"
                            }
                            file.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
