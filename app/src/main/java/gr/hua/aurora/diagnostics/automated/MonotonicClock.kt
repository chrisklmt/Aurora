package gr.hua.aurora.diagnostics.automated

import kotlinx.coroutines.delay

fun interface MonotonicClock {
    fun nowMillis(): Long
}

fun interface AutomatedDiagnosticsDelay {
    suspend fun delayMillis(millis: Long)
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long {
        return System.nanoTime() / 1_000_000L
    }
}

object RealAutomatedDiagnosticsDelay : AutomatedDiagnosticsDelay {
    override suspend fun delayMillis(millis: Long) {
        delay(millis)
    }
}
