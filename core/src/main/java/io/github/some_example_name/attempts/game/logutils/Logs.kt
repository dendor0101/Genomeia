package io.github.some_example_name.attempts.game.logutils

import kotlin.system.measureTimeMillis

val logMapAverageTime = mutableMapOf<String, LogStructure>()
var logCounter = 0
lateinit var fpsStringBuilder: StringBuilder
private const val AVERAGING_LOGS = 30


inline fun String.measureTime(block: () -> Unit) {
    val time = measureTimeMillis {
        block.invoke()
    }
    synchronized(logMapAverageTime) {
        logMapAverageTime[this] = logMapAverageTime[this]?.copy(real = time) ?: LogStructure(real = time)
    }
}

fun logAveragingValues() {
    synchronized(logMapAverageTime) {
        logMapAverageTime.forEach { (t, u) ->
            fpsStringBuilder.append("$t: ").append(u.show).append("\n")
            u.sum += u.real
        }

        logCounter++
        if (logCounter == AVERAGING_LOGS) {
            logMapAverageTime.forEach { (t, u) ->
                u.show = u.sum / AVERAGING_LOGS
                u.sum = 0
            }
            logCounter = 0
        }
    }
}
