package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** Счётчики проходимости мембраны — общие на все экземпляры стенда. См. dbgSeal. */
object SealStats {
    var minR = 0.0
    var checks = 0L
    var over = 0L
    var worst = 0.0
    var worstI = -1
    var worstJ = -1
    var worstStretch = 0.0
    var over2 = 0L
    var over5 = 0L
    var liveChecks = 0L
    var liveOver = 0L
    var liveWorst = 0.0
    var liveI = -1
    var liveJ = -1
    var liveStretch = 0.0
    var torn = 0L
    var tearingOnTicks = 0L
    var tearingOffTicks = 0L
    var overTearing = 0L
    var overNoTear = 0L
}
