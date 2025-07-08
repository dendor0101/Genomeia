package io.github.some_example_name.attempts.game.physics


fun invSqrt(x: Float): Float {
    val xhalf = 0.5f * x
    var i = java.lang.Float.floatToIntBits(x)
    i = 0x5f3759df - (i shr 1)
    var y = java.lang.Float.intBitsToFloat(i)
    y *= (1.5f - xhalf * y * y)
    return y
}
