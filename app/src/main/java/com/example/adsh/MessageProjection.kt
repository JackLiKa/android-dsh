package com.example.adsh

data class ModelMessage(val role: String, val content: String)

fun deriveMessages(events: List<SessionEventRow>): List<ModelMessage> =
    events.mapNotNull { event ->
        if (event.data.isEmpty()) return@mapNotNull null
        when (event.type) {
            "user/message" -> ModelMessage("user", event.data)
            "assistant/message" -> ModelMessage("assistant", event.data)
            else -> null
        }
    }
