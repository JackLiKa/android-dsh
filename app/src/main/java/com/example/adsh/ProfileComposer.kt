package com.example.adsh

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Represent
import org.yaml.snakeyaml.representer.Representer

data class JsExpression(val source: String)

data class ComposedProfile(
    val rows: List<Map<String, Any?>>,
    val warnings: List<String>,
)

private val jsTag = Tag("tag:yaml.org,2002:js")

private class PatchConstructor : SafeConstructor(
    LoaderOptions().apply {
        isAllowDuplicateKeys = false
        codePointLimit = 2_000_000
        nestingDepthLimit = 30
        setTagInspector { tag -> tag == jsTag }
    }
) {
    init {
        yamlConstructors[jsTag] = object : AbstractConstruct() {
            override fun construct(node: Node): Any = JsExpression((node as ScalarNode).value)
        }
    }
}

private class PatchRepresenter(options: DumperOptions) : Representer(options) {
    init {
        representers[JsExpression::class.java] = Represent { value ->
            representScalar(jsTag, (value as JsExpression).source)
        }
    }
}

class ProfileComposer {
    private val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    }
    private fun parser() = Yaml(PatchConstructor())

    fun dump(rows: List<Map<String, Any?>>): String = Yaml(PatchRepresenter(options), options).dump(rows)

    fun asInsertPatch(content: String): String {
        val parsed = parser().load<Any>(content) ?: return "[]\n"
        require(parsed is List<*>) { "cordis.yml must be a YAML list" }
        val entries = parsed.map(::copyMap)
        return dump(listOf(mapOf("insert" to entries)))
    }

    fun compose(layers: List<String>): ComposedProfile {
        val rows = mutableListOf<MutableMap<String, Any?>>()
        val index = mutableMapOf<String, MutableMap<String, Any?>>()
        val warnings = mutableListOf<String>()

        fun indexEntries(entries: List<MutableMap<String, Any?>>) {
            for (entry in entries) {
                (entry["id"] as? String)?.let { index[it] = entry }
                if (entry["group"] == true) {
                    @Suppress("UNCHECKED_CAST")
                    val children = entry["config"] as? List<MutableMap<String, Any?>>
                    children?.let { indexEntries(it) }
                }
            }
        }

        for (layer in layers) {
            if (layer.isBlank()) continue
            val parsed = parser().load<Any>(layer) ?: continue
            require(parsed is List<*>) { "Patch layer must be a YAML list" }
            for (raw in parsed) {
                val patch = copyMap(raw)
                val id = patch.remove("id") as? String
                val insert = patch.remove("insert")
                val expectedModule = patch.remove("module") ?: patch.remove("name")
                if (insert != null) {
                    require(insert is List<*>) { "insert must be a list" }
                    val inserted = insert.map(::copyMap)
                    if (id == null) rows.addAll(inserted)
                    else {
                        val parent = index[id]
                        when {
                            parent == null -> { warnings.add("找不到插入目标：$id"); continue }
                            parent["group"] != true -> { warnings.add("目标不是 group：$id"); continue }
                            else -> {
                                @Suppress("UNCHECKED_CAST")
                                val children = (parent["config"] as? List<MutableMap<String, Any?>>)
                                    ?.toMutableList() ?: mutableListOf()
                                children.addAll(inserted)
                                parent["config"] = children
                            }
                        }
                    }
                    indexEntries(inserted)
                    continue
                }
                if (id == null) { warnings.add("非 insert 补丁需要 id"); continue }
                val entry = index[id]
                if (entry == null) { warnings.add("找不到配置项：$id"); continue }
                val module = entry["module"] ?: entry["name"]
                if (expectedModule != null && expectedModule != module) {
                    warnings.add("模块名称与配置项 $id 不匹配")
                    continue
                }
                for ((key, value) in patch) entry[key] = value
            }
        }
        return ComposedProfile(rows, warnings)
    }

    private fun copyMap(value: Any?): MutableMap<String, Any?> {
        require(value is Map<*, *>) { "Patch entry must be a mapping" }
        return value.entries.associate { (key, item) ->
            require(key is String) { "Patch keys must be strings" }
            key to copyValue(item)
        }.toMutableMap()
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> copyMap(value)
        is List<*> -> value.map(::copyValue).toMutableList()
        else -> value
    }
}
