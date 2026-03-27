package io.github.some_example_name.old.good_one

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI

interface ShaderRendererContract {
    fun render(time: Float)
    fun updateParticles(data: FloatArray)
    fun setProjectionMatrix(matrix: FloatArray)
    fun setNumParticles(num: Int)
    fun dispose()
}

class MainGame(private val rendererFactory: () -> ShaderRendererContract) : ApplicationAdapter() {

    private lateinit var renderer: ShaderRendererContract
    private lateinit var stage: Stage
    private lateinit var pauseButton: TextButton
    private lateinit var camera: OrthographicCamera
    private var paused = false
    private var elapsedTime = 0f
    private val particles = mutableListOf<Particle>()
    private var screenWidth = 0f//Gdx.graphics.width.toFloat()
    private var screenHeight = 0f//Gdx.graphics.height.toFloat()
    private var numParticles = 5000 // Изменяй здесь на любое значение (до MAX_PARTICLES в ShaderRenderer)
    private lateinit var particleData: FloatArray

    override fun create() {
        screenWidth = Gdx.graphics.width.toFloat()
        screenHeight = Gdx.graphics.height.toFloat()
        // GL context is already created, safe to create renderer
        renderer = rendererFactory()

        camera = OrthographicCamera(screenWidth, screenHeight)

        // Initialize particles dynamically
        particleData = FloatArray(numParticles * 7) // pos.x, pos.y, radius, color.r, g, b, a
        for (i in 0 until numParticles) {
            val p = Particle()
            p.position.set(MathUtils.random(0f, screenWidth), MathUtils.random(0f, screenHeight))
            p.velocity.set(MathUtils.random(-100f, 100f), MathUtils.random(-100f, 100f))
            p.radius = MathUtils.random(5f, 20f)
            p.color.set(MathUtils.random(), MathUtils.random(), MathUtils.random(), MathUtils.random())
            particles.add(p)
        }
        renderer.setNumParticles(numParticles)

        VisUI.load()
        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        // Pause button
        pauseButton = TextButton("Pause", VisUI.getSkin())
        pauseButton.setSize(200f, 80f)
        pauseButton.setPosition(30f, Gdx.graphics.height - 120f)
        pauseButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                paused = !paused
                pauseButton.setText(if (paused) "Play" else "Pause")
            }
        })
        stage.addActor(pauseButton)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        if (!paused) {
            elapsedTime += delta
            // Update particle physics
            for (p in particles) {
                p.position.add(p.velocity.x * delta, p.velocity.y * delta)
                if (p.position.x < p.radius || p.position.x > screenWidth - p.radius) p.velocity.x = -p.velocity.x
                if (p.position.y < p.radius || p.position.y > screenHeight - p.radius) p.velocity.y = -p.velocity.y
            }
        }
        // Pack particle data
        var index = 0
        for (p in particles) {
            particleData[index++] = p.position.x
            particleData[index++] = p.position.y
            particleData[index++] = p.radius
            particleData[index++] = p.color.r
            particleData[index++] = p.color.g
            particleData[index++] = p.color.b
            particleData[index++] = p.color.a
        }
        // Update instance buffer
        renderer.updateParticles(particleData)

        stage.act(delta)
        camera.update()
        renderer.setProjectionMatrix(camera.combined.values)
        renderer.render(elapsedTime)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        pauseButton.setPosition(30f, height - 120f)
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
    }

    override fun dispose() {
        renderer.dispose()
        stage.dispose()
        VisUI.dispose()
    }
}

class Particle {
    val position = Vector2()
    val velocity = Vector2()
    var radius = 0f
    val color = Color()
}
