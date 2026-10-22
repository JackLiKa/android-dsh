package com.example.adsh

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
        return directory
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
