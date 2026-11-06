package com.example.adsh.engine

/** Stops a ready engine only when nobody is looking and nothing is running. */
object EngineIdlePolicy {
    fun shouldStop(
        ready: Boolean,
        busy: Boolean,
        foreground: Boolean,
        idleMs: Long,
        limitMs: Long,
    ): Boolean = ready && !busy && !foreground && idleMs >= limitMs
}
