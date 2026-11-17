package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer

/**
 * «Призраки» команд игрока, которые ждут тика: на паузе посаженный организм, рассыпанное
 * вещество и убитая клетка видны сразу, хотя мир ещё не тронут. Наполняет их
 * UserCommandManager.previewQueued, рисует RenderSystem поверх кадра.
 *
 * Почему рисунок, а не настоящий шаг мира. Шаг на паузе (тик или применение очереди вне
 * тика) поменял бы мир не в том месте тика, где его применит повтор, — пришлось бы
 * менять формат журнала. Призрак в мир, снимок и журнал не попадает вообще.
 *
 * Пишет и читает только поток рендера (запись — под замком между тиками, потому что для
 * неё читается мир). Пока очередь пуста, [draw] — одно сравнение.
 */
class CommandGhosts {
    private var kinds = IntArray(INITIAL)
    private var colors = IntArray(INITIAL)
    /** x, y, радиус на призрак. */
    private var shapes = FloatArray(INITIAL * 3)
    private var count = 0

    /** Тик и эпоха мира, к которым относится предсказание; снимок следующего тика несёт итог сам. */
    private var tick = -1
    private var epoch = -1

    private val tmp = Color()

    fun begin(tick: Int, epoch: Int) {
        count = 0
        this.tick = tick
        this.epoch = epoch
    }

    fun cell(x: Float, y: Float, radius: Float, color: Int) = add(CELL, x, y, radius, color)
    fun substance(x: Float, y: Float, radius: Float, color: Int) = add(SUBSTANCE, x, y, radius, color)
    fun kill(x: Float, y: Float, radius: Float) = add(KILL, x, y, radius, 0)

    private fun add(kind: Int, x: Float, y: Float, radius: Float, color: Int) {
        if (count == kinds.size) {
            kinds = kinds.copyOf(count * 2)
            colors = colors.copyOf(count * 2)
            shapes = shapes.copyOf(count * 2 * 3)
        }
        kinds[count] = kind
        colors[count] = color
        shapes[count * 3] = x
        shapes[count * 3 + 1] = y
        shapes[count * 3 + 2] = radius
        count++
    }

    /** Проекция [shapes] уже мировая (экран ставит её перед RenderSystem.render). */
    fun draw(shapeRenderer: ShapeRenderer, snapshot: RenderSnapshot) {
        if (count == 0) return
        if (snapshot.epoch != epoch || snapshot.tick > tick) {
            count = 0
            return
        }
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (i in 0 until count) {
            when (kinds[i]) {
                CELL, SUBSTANCE -> {
                    Color.abgr8888ToColor(tmp, colors[i])
                    tmp.a = if (kinds[i] == CELL) CELL_ALPHA else SUBSTANCE_ALPHA
                    shapeRenderer.color = tmp
                }
                else -> shapeRenderer.color = KILL_SHADE
            }
            shapeRenderer.circle(shapes[i * 3], shapes[i * 3 + 1], shapes[i * 3 + 2], SEGMENTS)
        }
        shapeRenderer.end()

        // Контур: без него призрак клетки теряется на фоне того же цвета.
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        Gdx.gl.glLineWidth(2f)
        for (i in 0 until count) {
            val x = shapes[i * 3]
            val y = shapes[i * 3 + 1]
            val r = shapes[i * 3 + 2]
            when (kinds[i]) {
                CELL -> {
                    shapeRenderer.color = Color.WHITE
                    shapeRenderer.circle(x, y, r, SEGMENTS)
                }
                KILL -> {
                    val d = r * 0.7f
                    shapeRenderer.color = Color.RED
                    shapeRenderer.line(x - d, y - d, x + d, y + d)
                    shapeRenderer.line(x - d, y + d, x + d, y - d)
                }
            }
        }
        shapeRenderer.end()
    }

    private companion object {
        const val INITIAL = 16
        const val CELL = 0
        const val SUBSTANCE = 1
        const val KILL = 2
        const val SEGMENTS = 24
        const val CELL_ALPHA = 0.6f
        const val SUBSTANCE_ALPHA = 0.85f
        val KILL_SHADE = Color(0f, 0f, 0f, 0.55f)
    }
}
