package io.github.some_example_name.attempts.game.main

import com.badlogic.gdx.math.Vector2
import io.github.some_example_name.attempts.game.physics.PhysicsManager
import io.github.some_example_name.attempts.game.physics.WorldGenerator
import io.github.some_example_name.attempts.game.render.RenderManager

class GameController(
    private val renderManager: RenderManager,
    private val worldGenerator: WorldGenerator,
    private val worldGridManager: WorldGridManager,
    private val physicsManager: PhysicsManager
) {

    fun init() {
        physicsManager.particles = worldGenerator.generateWorld()

        worldGridManager.initGrid(physicsManager.particles)
        physicsManager.init()

    }

    fun renderTick() {
        renderManager.drawRender(physicsManager.particles, physicsManager.links)
    }

    fun moveCamera(cameraOffset: Vector2) {

    }

    fun click(x: Float, y: Float) {
        physicsManager.addParticleByClick(x, y)
    }

    fun zoom(zoomScale: Float) {

    }
}
