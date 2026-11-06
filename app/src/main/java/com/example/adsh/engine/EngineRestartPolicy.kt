package com.example.adsh.engine

/**
 * Decides whether an unexpected engine exit should be retried.
 *
 * Backoff follows 1 s, 2 s, 4 s; three failures inside the window open the
 * circuit instead of restarting again.
 */
class EngineRestartPolicy(
    private val maxFailures: Int = 3,
    private val windowMs: Long = 10 * 60_000L,
) {
    data class Decision(
        val restart: Boolean,
        val delayMs: Long = 0,
        val circuitOpen: Boolean = false,
        val failures: Int = 0,
    )

    private val failures = ArrayDeque<Long>()

    fun onExit(nowMs: Long, intentional: Boolean): Decision {
        if (intentional) {
            failures.clear()
            return Decision(restart = false)
        }
        prune(nowMs)
        failures.addLast(nowMs)
        val delay = retryDelay(failures.size)
        if (failures.size >= maxFailures) {
            return Decision(
                restart = false,
                delayMs = delay,
                circuitOpen = true,
                failures = failures.size,
            )
        }
        return Decision(restart = true, delayMs = delay, failures = failures.size)
    }

    fun reset() = failures.clear()

    private fun retryDelay(count: Int): Long = 1_000L shl (count - 1).coerceIn(0, 2)

    private fun prune(nowMs: Long) {
        while (failures.isNotEmpty() && nowMs - failures.first() >= windowMs) {
            failures.removeFirst()
        }
    }
}
