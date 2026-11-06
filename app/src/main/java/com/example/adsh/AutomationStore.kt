package com.example.adsh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AutomationRoutine(val id: String, val title: String, val prompt: String)

/** Local, private storage for one-tap task routines. */
class AutomationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("adsh_automation", Context.MODE_PRIVATE)

    fun list(): List<AutomationRoutine> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val prompt = item.optString("prompt")
                if (id.isBlank() || prompt.isBlank()) return@mapNotNull null
                AutomationRoutine(
                    id = id,
                    title = item.optString("title").ifBlank { prompt.take(24) },
                    prompt = prompt,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(title: String, prompt: String): List<AutomationRoutine> {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return list()
        val routine = AutomationRoutine(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { cleanPrompt.take(24) },
            prompt = cleanPrompt,
        )
        return save(list() + routine)
    }

    fun remove(id: String): List<AutomationRoutine> =
        save(list().filterNot { it.id == id })

    private fun save(items: List<AutomationRoutine>): List<AutomationRoutine> {
        val array = JSONArray()
        items.forEach { routine ->
            array.put(
                JSONObject()
                    .put("id", routine.id)
                    .put("title", routine.title)
                    .put("prompt", routine.prompt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        return items
    }

    private companion object {
        const val KEY = "routines"
    }
}
