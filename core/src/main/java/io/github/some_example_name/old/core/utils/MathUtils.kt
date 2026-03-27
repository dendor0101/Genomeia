package io.github.some_example_name.old.core.utils

import kotlin.math.*

fun distanceTo(px: Float, py: Float, x: Float, y: Float): Float {
    val dx = px - x
    val dy = py - y
    val sqrt = dx * dx + dy * dy
    if (sqrt <= 0) return 0f
    val result = sqrt(sqrt)
    if (result.isNaN()) return 0f//throw Exception("TODO потом убрать")
    return result
}

fun invSqrt(x: Float): Float {
    val xhalf = 0.5f * x
    var i = java.lang.Float.floatToIntBits(x)
    i = 0x5f3759df - (i shr 1)
    var y = java.lang.Float.intBitsToFloat(i)
    y *= (1.5f - xhalf * y * y)
    return y
}
