package io.github.some_example_name.render.microscope

import kotlin.math.*

/**
 * Движение линзы из HTML v12 (stepFocus). Несколько чисел на весь кадр, без чтения GPU.
 * Это имитация поиска линзы, а не измерение контраста изображения.
 */
class MicroscopeFocus {
    var position = Float.NaN
        private set
    var velocity = 0f
        private set
    var hunting = false
        private set
    private var idle = 9f
    private var direction = 1f
    private var zoomDirection = 1f
    private var randomState = 1

    fun reset() {
        position = Float.NaN
        velocity = 0f
        idle = 9f
        hunting = false
        direction = 1f
        zoomDirection = 1f
        randomState = 1
    }

    // Сид жеста приходит из команды стенда: повтор жеста не выбирает новое направление.
    fun beginGesture(seed: Int) {
        randomState = if (seed == 0) 1 else seed
        direction = if (nextHalf()) -1f else 1f
    }

    private fun nextHalf(): Boolean {
        var x = randomState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        randomState = x
        return x >= 0
    }

    fun update(dt: Float, speedPixels: Float, zoomDelta: Float, target: Float, enabled: Boolean): Float {
        if (!position.isFinite()) position = target
        if (!enabled) {
            position = target
            velocity = 0f
            hunting = false
            return position
        }
        val step = dt.coerceIn(1f / 240, .1f)
        val zoom = abs(zoomDelta)
        if (zoomDelta != 0f) zoomDirection = sign(zoomDelta)
        if (speedPixels > 40f || zoom > 1e-4f) {
            idle = 0f
            position += direction * min(speedPixels, 2500f) * .000045f * step + zoomDirection * zoom * .22f
        } else idle += step
        val error = (position - target).coerceIn(-.32f, .32f)
        position = target + error
        if (idle > .22f) {
            if (!hunting && abs(error) > .025f && nextHalf()) velocity += sign(error) * .22f
            hunting = true
            velocity += (-55f * error - 6.4f * velocity) * step
            position += velocity * step
            if (abs(error) < .003f && abs(velocity) < .01f) hunting = false
        } else {
            velocity *= exp(-step * 6f)
            position += velocity * step
            hunting = abs(error) > .01f
        }
        return position
    }
}
