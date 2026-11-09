package com.example.adsh.engine

import com.example.adsh.EngineStatus

/** Starts the engine when the app opens unless the user disabled it or stopped it manually. */
object EngineStartPolicy {
    fun shouldAutoStart(
        phase: EngineStatus.Phase,
        enabled: Boolean,
        manuallyStopped: Boolean,
    ): Boolean = enabled && !manuallyStopped &&
        (phase == EngineStatus.Phase.IDLE || phase == EngineStatus.Phase.STOPPED)
}
