package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.systems.logic.LevelHud

/**
 * Метка цели и круг допуска уровня — поверх мира, под интерфейсом.
 *
 * Зачем в мировых координатах, а не на экране: цель уровня задана координатой мира
 * (destination в LevelInfo или zone(x, y, r) из Lua), и при движении камеры метка
 * должна остаться на месте. Отдельный рисунок, а не часть RenderSystem: тот про уровни
 * ничего не знает и живёт в :render, а метка — это данные уровня.
 *
 * Состояние GL: RenderSystem рисует до нас и может оставить свой блендинг, поэтому
 * включаем и выключаем его сами, а projectionMatrix выставляем явно.
 */
object LevelOverlay {
    private val ZONE_COLOR = Color(0.92f, 0.55f, 0.22f, 0.9f)
    private val SPAWN_COLOR = Color(0.35f, 0.85f, 0.45f, 0.9f)
    private val MARKER_COLOR = Color(0.98f, 0.35f, 0.25f, 1f)

    /** Размер метки в единицах мира: мир — сотни единиц, камера смотрит на десятки. */
    private const val MARKER_RADIUS = 1.5f
    private const val MARKER_ARM = 3.5f
    private const val ZONE_SEGMENTS = 64

    fun draw(shapes: ShapeRenderer, camera: Camera, hud: LevelHud) {
        if (!hud.hasSpawnZone && !hud.hasZone && !hud.hasMarker) return

        shapes.projectionMatrix = camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.begin(ShapeRenderer.ShapeType.Line)
        // Зона старта зелёная, цель — оранжевая: два разных круга не должны сливаться.
        if (hud.hasSpawnZone) {
            shapes.color = SPAWN_COLOR
            shapes.circle(hud.spawnX, hud.spawnY, hud.spawnRadius, ZONE_SEGMENTS)
        }
        if (hud.hasZone) {
            shapes.color = ZONE_COLOR
            shapes.circle(hud.zoneX, hud.zoneY, hud.zoneRadius, ZONE_SEGMENTS)
        }
        if (hud.hasMarker) {
            shapes.color = MARKER_COLOR
            shapes.circle(hud.markerX, hud.markerY, MARKER_RADIUS, 16)
            // Перекрестье: точку видно и когда камера ушла далеко и кружок стал точкой.
            shapes.line(hud.markerX - MARKER_ARM, hud.markerY, hud.markerX + MARKER_ARM, hud.markerY)
            shapes.line(hud.markerX, hud.markerY - MARKER_ARM, hud.markerX, hud.markerY + MARKER_ARM)
        }
        shapes.end()

        Gdx.gl.glDisable(GL20.GL_BLEND)
    }
}
