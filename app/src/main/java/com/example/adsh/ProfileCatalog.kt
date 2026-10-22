package com.example.adsh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProfileCatalog(private val context: Context) {
    private val root = context.filesDir.resolve("dsh-home")
    private val templates = mapOf(
        "web" to listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"),
        "headless" to listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-headless"),
        "sdk" to listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-sdk-app"),
        "acp" to listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"),
        "sdk-minimal" to listOf("@deepseek-ai/dsh-sdk-minimal"),
    )

    suspend fun compose(name: String, overlays: List<String> = emptyList()): ComposedProfile =
        withContext(Dispatchers.IO) {
            require(name.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "无效的 profile 名称" }
            val directory = root.resolve("profiles/$name")
            if (!directory.exists()) {
                val bundles = templates[name] ?: error("找不到 profile：$name")
                check(directory.mkdirs())
                val profile = JSONObject().put("name", "adsh-profile-$name").put(
                    "dsh", JSONObject().put("profile", JSONObject().put("bundles", JSONArray(bundles)))
                )
                directory.resolve("package.json").writeText(profile.toString(2) + "\n")
                directory.resolve("cordis.yml").writeText("[]\n")
                directory.resolve("cordis.patch.yml").writeText("[]\n")
                directory.resolve("compatibility.json").writeText("{}\n")
            }
            val customRows = directory.resolve("cordis.yml")
            if (!customRows.exists()) customRows.writeText("[]\n")
            val profile = JSONObject(directory.resolve("package.json").readText())
            val bundleNames = profile.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
            val layers = mutableListOf<String>()
            for (index in 0 until bundleNames.length()) {
                val bundleName = bundleNames.getString(index)
                require(bundleName.matches(Regex("@deepseek-ai/dsh-[a-z0-9-]+"))) {
                    "尚未安装的 bundle：$bundleName"
                }
                val packageDirectory = "bundles/${bundleName.removePrefix("@deepseek-ai/dsh-")}"
                val manifest = JSONObject(context.assets.open("$packageDirectory/package.json")
                    .bufferedReader().use { it.readText() })
                val declared = manifest.getJSONObject("dsh").getJSONObject("bundle").get("patch")
                val paths = when (declared) {
                    is String -> listOf(declared)
                    is JSONArray -> List(declared.length()) { declared.getString(it) }
                    else -> error("无效的 bundle.patch：$bundleName")
                }
                for (path in paths) {
                    require(path.matches(Regex("\\./[a-z0-9.-]+\\.yml")))
                    layers += context.assets.open("$packageDirectory/${path.removePrefix("./")}")
                        .bufferedReader().use { it.readText() }
                }
            }
            val composer = ProfileComposer()
            layers += composer.asInsertPatch(customRows.readText())
            layers += directory.resolve("cordis.patch.yml").readText()
            val homePatch = root.resolve("cordis.patch.yml")
            if (homePatch.isFile) layers += homePatch.readText()
            layers += overlays
            composer.compose(layers)
        }
}
