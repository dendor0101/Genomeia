package io.github.some_example_name.render.microscope

import kotlin.math.min

/** Несколько чисел на камеру, не на частицу. Взвесь уже содержит разные Z;
 * ей не хватало смещения столика из игры. Сдвиг от зума вокруг курсора не
 * накапливаем: иначе при приближении слои разъезжаются. Double нужен только
 * для накопленного пути, GPU по-прежнему получает два float. */
internal class MicroscopeParallax {
    private var targetX = 0.0
    private var targetY = 0.0
    private var previousX = Double.NaN
    private var previousY = Double.NaN
    private var previousScale = Double.NaN
    var x = 0f; private set
    var y = 0f; private set

    fun reset() {
        targetX = 0.0; targetY = 0.0; x = 0f; y = 0f
        previousX = Double.NaN; previousY = Double.NaN; previousScale = Double.NaN
    }

    fun pan(dx: Double, dy: Double) { targetX += dx; targetY += dy }

    fun followCamera(cx: Double, cy: Double, scale: Double) {
        if (previousX.isFinite() && scale == previousScale) pan(cx - previousX, cy - previousY)
        previousX = cx; previousY = cy; previousScale = scale
        // Здесь уже фактическая поза игровой камеры, а не цель жеста HTML.
        // Второе сглаживание заставляло взвесь скользить после остановки камеры.
        // Прямой pan/update стенда сохраняет исходное поведение HTML.
        x = targetX.toFloat(); y = targetY.toFloat()
    }

    fun update(dt: Float) {
        val k = min(1f, dt * 7f)
        x += (targetX.toFloat() - x) * k
        y += (targetY.toFloat() - y) * k
    }
}
