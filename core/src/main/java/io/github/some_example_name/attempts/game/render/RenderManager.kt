package io.github.some_example_name.attempts.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.attempts.game.logutils.fpsStringBuilder
import io.github.some_example_name.attempts.game.logutils.logAveragingValues
import io.github.some_example_name.attempts.game.logutils.measureTime
import io.github.some_example_name.attempts.game.main.*
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.FRICTION
import io.github.some_example_name.attempts.game.physics.Link
import io.github.some_example_name.attempts.game.physics.Particle

class RenderManager(
    private val shapeRenderer: ShapeRenderer,
    private val batch: SpriteBatch,
    private val font: BitmapFont
) {

    fun drawRender(particles: List<Particle>, links: MutableList<Link>) {
        if (generationCounter != null) {
            if (generationCounter == 0) {
                friction = FRICTION
                isWallMovable = false
                generationCounter = null
            } else {
                generationCounter = generationCounter!! - 1
            }
        }

        "render".measureTime {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)


            // Получаем текущий FPS
            val fps = Gdx.graphics.framesPerSecond
            fpsStringBuilder.setLength(0) // Очищаем StringBuilder
            fpsStringBuilder.append("FPS: ").append(fps).append("\n")
            logAveragingValues()

            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
            shapeRenderer.color = Color.WHITE

            fpsStringBuilder.append("Particles: ").append(particles.size).append("\n")
            "draw no sync".measureTime {
                particles.forEach {
                    it.drawShell(shapeRenderer)
                }
                shapeRenderer.color = Color.GREEN
//                println(links.size)
                links.forEach {
                    if (particles.size > it.id1 && particles.size > it.id2) {
                        // Получаем центр экрана
                        val centerX = Gdx.graphics.width / 2f
                        val centerY = Gdx.graphics.height / 2f

                        // Смещаем координаты относительно центра экрана и применяем масштабирование
//                        val scaledX = centerX + (particles[it.id1].x - centerX + cameraOffset.x) * zoomScale
//                        val scaledY = centerY + (particles[it.id1].y - centerY + cameraOffset.y) * zoomScale
//                        val scaledX = centerX + (particles[it.id2].x - centerX + cameraOffset.x) * zoomScale
//                        val scaledY = centerY + (particles[it.id2].y - centerY + cameraOffset.y) * zoomScale
                        shapeRenderer.line(
                            centerX + (particles[it.id1].x - centerX + cameraOffset.x) * zoomScale,
                            centerY + (particles[it.id1].y - centerY + cameraOffset.y) * zoomScale,
                            centerX + (particles[it.id2].x - centerX + cameraOffset.x) * zoomScale,
                            centerY + (particles[it.id2].y - centerY + cameraOffset.y) * zoomScale,
                        )
                    }
                }
            }

            batch.begin()
            font.draw(
                batch,
                fpsStringBuilder.toString(),
                30f,
                Gdx.graphics.height - 30f
            ) // Выводим FPS в верхний левый угол
            batch.end()
            shapeRenderer.end()

            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color.GREEN
            shapeRenderer.circle(Gdx.graphics.width / 2f, Gdx.graphics.height / 2f, 4f)
            shapeRenderer.end()
        }
    }

    private fun Particle.drawShell(shapeRenderer: ShapeRenderer) {
        // Получаем центр экрана
        val centerX = Gdx.graphics.width / 2f
        val centerY = Gdx.graphics.height / 2f

        // Смещаем координаты относительно центра экрана и применяем масштабирование
        val scaledX = centerX + (x - centerX + cameraOffset.x) * zoomScale
        val scaledY = centerY + (y - centerY + cameraOffset.y) * zoomScale
        val scaledRadius = repulsionRadius * zoomScale

        // Проверяем, находится ли объект за пределами экрана
        if (scaledX + scaledRadius < 0 || scaledY + scaledRadius < 0 ||
            scaledX - scaledRadius > Gdx.graphics.width || scaledY - scaledRadius > Gdx.graphics.height
        ) {
            return // Не рисуем, если объект вне экрана
        }

        shapeRenderer.color = color
        // Рисуем круг
        shapeRenderer.circle(scaledX, scaledY, scaledRadius)


        shapeRenderer.color = colorCore
        // Рисуем круг
        shapeRenderer.circle(scaledX, scaledY, scaledRadius * 0.3f)
    }

}

