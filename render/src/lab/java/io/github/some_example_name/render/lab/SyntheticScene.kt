package io.github.some_example_name.render.lab

import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Сцена, собранная из ничего — чтобы стенд был полезен сразу, без дампа из игры.
 *
 * Показывает то, что в живом кадре ещё поди поймай:
 *  - ВСЕ слои TextureArray разом, по сетке — видно, какой тип чем рисуется;
 *  - развёртку радиуса по всему диапазону, который влезает в байт (0.05…0.75);
 *  - развёртку энергии — это радиус чёрной точки внутри клетки;
 *  - развёртку угла на полный оборот — видно, как шейдер крутит текстуру;
 *  - несколько пятен феромона.
 *
 * Числа тут подобраны так, чтобы всё было в кадре при [ZOOM], и ничего больше не значат.
 */
object SyntheticScene {

    class Scene(val cells: ByteBuffer, val pheromones: ByteBuffer)

    /** Совпадает с PheromonesManager.K/P в :core. Дублируется намеренно: см. RenderFrame. */
    const val PHEROMONE_K = 0.4f
    const val PHEROMONE_P = 0.01f

    private const val SPACING = 1.6f
    private const val SWEEP_COUNT = 12

    var CENTER_X = 0f
        private set
    var CENTER_Y = 0f
        private set

    /** Видно примерно 24 мира по ширине окна 1280 px. */
    const val ZOOM = 0.019f

    fun build(layerCount: Int = 27): Scene {
        val cells = CellInstanceBuffer()
        cells.begin()

        val columns = ceil(sqrt(layerCount.toFloat())).toInt().coerceAtLeast(1)
        val rows = ceil(layerCount / columns.toFloat()).toInt()

        // ===== сетка типов: каждый слой TextureArray один раз =====
        for (layer in 0 until layerCount) {
            val column = layer % columns
            val row = layer / columns
            val angle = layer * 0.37f
            cells.putCell(
                x = column * SPACING,
                y = -row * SPACING,
                color = rgba(220, 200, 170),
                angleCos = cos(angle),
                angleSin = sin(angle),
                radius = 0.55f,
                energy = 0f,
                cellType = layer,
                noiseSeed = layer * 977
            )
        }

        val sweepY = -rows * SPACING - SPACING

        // ===== развёртка радиуса: от минимума до максимума, что влезает в байт =====
        repeat(SWEEP_COUNT) { i ->
            val t = i / (SWEEP_COUNT - 1f)
            cells.putCell(
                x = i * SPACING,
                y = sweepY,
                color = rgba(200, 120, 120),
                angleCos = 1f,
                angleSin = 0f,
                radius = CellInstanceBuffer.RADIUS_MIN + t * CellInstanceBuffer.RADIUS_SPAN,
                energy = 0f,
                cellType = 0,
                noiseSeed = 1000 + i
            )
        }

        // ===== развёртка энергии: радиус чёрной точки внутри клетки =====
        repeat(SWEEP_COUNT) { i ->
            val t = i / (SWEEP_COUNT - 1f)
            cells.putCell(
                x = i * SPACING,
                y = sweepY - SPACING,
                color = rgba(140, 190, 140),
                angleCos = 1f,
                angleSin = 0f,
                radius = 0.6f,
                energy = t * CellInstanceBuffer.ENERGY_SPAN,
                cellType = 0,
                noiseSeed = 2000 + i
            )
        }

        // ===== развёртка угла: полный оборот =====
        repeat(SWEEP_COUNT) { i ->
            val angle = (i / SWEEP_COUNT.toFloat()) * 2f * Math.PI.toFloat()
            cells.putCell(
                x = i * SPACING,
                y = sweepY - 2 * SPACING,
                color = rgba(150, 160, 220),
                angleCos = cos(angle),
                angleSin = sin(angle),
                radius = 0.6f,
                energy = 0f,
                cellType = 1.coerceAtMost(layerCount - 1),
                // Ключ шума один на всю строку: доворот от шума одинаковый, поэтому видно
                // именно вращение от угла, а не мешанину из двух источников сразу.
                noiseSeed = 4242
            )
        }

        // Ширину берём по САМОЙ широкой части, а не по сетке типов: развёртки длиннее её,
        // и если центрироваться по сетке, они уезжают за правый край экрана.
        val width = maxOf((columns - 1) * SPACING, (SWEEP_COUNT - 1) * SPACING)
        val height = sweepY - 2 * SPACING
        CENTER_X = width / 2f
        CENTER_Y = height / 2f

        // ===== феромоны =====
        //
        // Пятна получаются большими и бледными, и это не подбор чисел, а свойство самой
        // формулы: радиус растёт как (a/P - 1)/K, а прозрачность в центре равна a. При
        // P = 0.01 радиус в пару миров означает a ≈ 0.026, то есть альфу 2% — на экране
        // ничего. Заметное пятно неизбежно широкое. Здесь взяты значения, при которых
        // видно и само пятно, и его спад.
        val pheromones = PheromoneInstanceBuffer()
        pheromones.begin()
        pheromones.put(x = CENTER_X, y = CENTER_Y, a = 0.25f, color = rgba(90, 200, 255))
        pheromones.put(x = CENTER_X + 7f, y = CENTER_Y - 3f, a = 0.5f, color = rgba(255, 140, 90))

        return Scene(cells = cells.end(), pheromones = pheromones.end())
    }

    /**
     * Цвет в том виде, в каком его ждёт unpackRGBA8: младший байт — красный.
     * Это ровно раскладка libGDX Color.toIntBits(), которой пользуется игра.
     */
    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (b shl 16) or (g shl 8) or r
}
