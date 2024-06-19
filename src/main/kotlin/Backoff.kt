package io.github.jvmusin

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class Backoff(val startPeriod: Duration, val maxPeriod: Duration)

fun <T> Any.withBackoff(
    backoff: Backoff = Backoff(startPeriod = 5.seconds, maxPeriod = 30.minutes),
    operation: () -> T
): T {
    val start = System.currentTimeMillis()
    var sleepPeriod = backoff.startPeriod
    while (true) {
        try {
            return operation()
        } catch (e: Exception) {
            val timeSpentMillis = System.currentTimeMillis() + sleepPeriod.inWholeMilliseconds - start
            if (timeSpentMillis.milliseconds > backoff.maxPeriod) throw e
            getLogger().warning("Operation failed, sleeping for $sleepPeriod. Reason: ${e.message}")
            Thread.sleep(sleepPeriod.toJavaDuration())
            sleepPeriod = minOf(sleepPeriod * 2, backoff.maxPeriod)
        }
    }
}
