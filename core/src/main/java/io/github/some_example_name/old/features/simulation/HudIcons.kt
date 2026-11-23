package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Значки кнопок экрана симуляции, нарисованные кодом — как жук BugOverlay: ради пары
 * значков ассеты не нужны, а нарисованный крупно и уменьшенный с мипмапами значок одинаково
 * чёткий на любой плотности экрана.
 *
 * Значки белые на прозрачном: цвет им даёт кнопка (makeStyledIconButton красит в цвет
 * надписей). Живут, пока жив экран; освобождает [dispose].
 */
class HudIcons : Disposable {

    private val textures = ArrayList<Texture>()

    val menu = icon(::drawMenu)
    val edit = icon(::drawEdit)
    val pause = icon(::drawPause)
    val play = icon(::drawPlay)
    val organism = icon(::drawOrganism)
    val optics by lazy { icon { p -> ring(p,54,54,32,7); white(p); thickLine(p,75,75,107,107,6) } }
    val options by lazy { icon { p -> white(p); thickLine(p,24,40,104,40,5); thickLine(p,24,88,104,88,5); p.fillCircle(46,40,13); p.fillCircle(82,88,13) } }
    /** Крестик — закрыть панель (настройки мира). */
    val close by lazy { icon { p -> white(p); thickLine(p, 36, 36, 92, 92, 8); thickLine(p, 92, 36, 36, 92, 8) } }
    /** Стрелка по кругу — вернуть к стандарту (настройки мира). */
    val reset by lazy { icon(::drawReset) }
    val spawnCell = icon(::drawSpawnCell)
    val spawnFood = icon(::drawFood)
    val killCell = icon(::drawKill)

    /** Значок инструмента — на кнопке инструмента и в его окне. */
    fun of(tool: SimulationTool): TextureRegion = when (tool) {
        SimulationTool.SPAWN_CELL -> spawnCell
        SimulationTool.SPAWN_FOOD -> spawnFood
        SimulationTool.KILL_CELL -> killCell
    }

    private fun icon(draw: (Pixmap) -> Unit): TextureRegion {
        val p = Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888)
        p.blending = Pixmap.Blending.None
        p.setColor(0f, 0f, 0f, 0f)
        p.fill()
        draw(p)
        val texture = Texture(p, true).apply {
            setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        }
        p.dispose()
        textures += texture
        return TextureRegion(texture)
    }

    override fun dispose() {
        textures.forEach { it.dispose() }
        textures.clear()
    }

    private companion object {
        const val SIZE = 128

        fun white(p: Pixmap) = p.setColor(1f, 1f, 1f, 1f)

        /** Вырезать: при Blending.None прозрачный цвет стирает уже нарисованное. */
        fun clear(p: Pixmap) = p.setColor(0f, 0f, 0f, 0f)

        fun ring(p: Pixmap, x: Int, y: Int, r: Int, width: Int) {
            white(p); p.fillCircle(x, y, r)
            clear(p); p.fillCircle(x, y, r - width)
        }

        fun thickLine(p: Pixmap, x1: Int, y1: Int, x2: Int, y2: Int, r: Int) {
            val dx = (x2 - x1).toFloat()
            val dy = (y2 - y1).toFloat()
            val steps = sqrt(dx * dx + dy * dy).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                p.fillCircle(x1 + (dx * i / steps).toInt(), y1 + (dy * i / steps).toInt(), r)
            }
        }

        /** Кольцо с вырезом справа сверху и стрелкой на конце выреза — «вернуть». */
        fun drawReset(p: Pixmap) {
            ring(p, 64, 64, 40, 11)
            clear(p); p.fillTriangle(64, 64, 127, 64, 100, 2)
            white(p); p.fillTriangle(78, 68, 120, 68, 99, 42)
        }

        /** Три полосы — «меню». */
        fun drawMenu(p: Pixmap) {
            white(p)
            for (y in intArrayOf(34, 64, 94)) thickLine(p, 26, y, 102, y, 7)
        }

        /** Карандаш с отдельным наконечником — редактирование генома. */
        fun drawEdit(p: Pixmap) {
            // Покрытие 4×4 сглаживает диагонали. Считается один раз при создании экрана.
            val scale = 4
            val hi = Pixmap(SIZE * scale, SIZE * scale, Pixmap.Format.RGBA8888)
            hi.blending = Pixmap.Blending.None
            white(hi)
            fun triangle(x1: Int, y1: Int, x2: Int, y2: Int, x3: Int, y3: Int) =
                hi.fillTriangle(x1 * scale, y1 * scale, x2 * scale, y2 * scale, x3 * scale, y3 * scale)
            triangle(36, 78, 82, 32, 100, 50)
            triangle(36, 78, 100, 50, 54, 96)
            triangle(31, 84, 48, 101, 23, 109)
            triangle(88, 26, 96, 18, 114, 36)
            triangle(88, 26, 114, 36, 106, 44)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                var alpha = 0
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    alpha += hi.getPixel(x * scale + dx, y * scale + dy) and 255
                }
                // Белый RGB даже у прозрачных пикселей исключает тёмный ореол при фильтрации.
                p.drawPixel(x, y, -256 or (alpha / (scale * scale)))
            }
            hi.dispose()
        }

        fun drawPause(p: Pixmap) {
            white(p)
            p.fillRectangle(32, 24, 22, 80)
            p.fillRectangle(74, 24, 22, 80)
        }

        fun drawPlay(p: Pixmap) {
            white(p)
            p.fillTriangle(36, 22, 36, 106, 106, 64)
        }

        /** Организм: клетки-кольца, слипшиеся в комок. */
        fun drawOrganism(p: Pixmap) {
            val cells = intArrayOf(64, 64, 22, 30, 50, 15, 98, 50, 15, 40, 98, 15, 88, 96, 15, 64, 22, 13)
            // Сначала все круги целиком, потом нутро каждого: стенки соседей сливаются в одну.
            white(p)
            for (i in cells.indices step 3) p.fillCircle(cells[i], cells[i + 1], cells[i + 2])
            clear(p)
            for (i in cells.indices step 3) p.fillCircle(cells[i], cells[i + 1], cells[i + 2] - 6)
            white(p)
            p.fillCircle(64, 64, 7)
        }

        /** Клетка с плюсом — «поставить организм». */
        fun drawSpawnCell(p: Pixmap) {
            ring(p, 54, 74, 40, 8)
            white(p)
            p.fillCircle(54, 74, 9)
            p.fillRectangle(84, 10, 12, 40)
            p.fillRectangle(70, 24, 40, 12)
        }

        /** Россыпь крупинок — «еда» (субстанция ложится так же, горстью). */
        fun drawFood(p: Pixmap) {
            white(p)
            val grains = intArrayOf(64, 64, 14, 34, 44, 10, 92, 38, 11, 30, 90, 11, 96, 92, 12, 64, 24, 8, 64, 104, 8, 18, 64, 7, 110, 64, 7)
            for (i in grains.indices step 3) p.fillCircle(grains[i], grains[i + 1], grains[i + 2])
        }

        /** Перечёркнутая клетка — «убить клетку». */
        fun drawKill(p: Pixmap) {
            ring(p, 64, 64, 44, 8)
            white(p)
            val r = 34f
            val a = (Math.PI / 4).toFloat()
            thickLine(p, (64 - cos(a) * r).toInt(), (64 - sin(a) * r).toInt(), (64 + cos(a) * r).toInt(), (64 + sin(a) * r).toInt(), 6)
            thickLine(p, (64 - cos(a) * r).toInt(), (64 + sin(a) * r).toInt(), (64 + cos(a) * r).toInt(), (64 - sin(a) * r).toInt(), 6)
        }
    }
}
