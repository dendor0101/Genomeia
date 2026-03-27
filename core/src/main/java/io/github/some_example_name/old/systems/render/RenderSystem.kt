package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SimEntity
import kotlin.math.round

class RenderSystem(
    val tripleBufferManager: TripleBufferManager,
    val simEntity: SimEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val shaderManager: ShaderManager
) {

    val fontMatrix = Matrix4()
    private lateinit var shapeRenderer: ShapeRenderer

    fun create() {
        shaderManager.create()
        shapeRenderer = ShapeRenderer()
    }

    fun drawShader(camera: Camera) {
        val (currentRead, isNewFrame) = tripleBufferManager.getAndSwapConsumer()
        shaderManager.render(
            currentRead = currentRead,
            cameraProjection = camera.combined,
            isNewFrame = isNewFrame
        )

//        // начинаем рисование линий
//        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
//
//        // цвет линии
//        shapeRenderer.color = Color.WHITE
//
//
//        shapeRenderer.projectionMatrix = camera.combined
//
//        for (linkId in 0..linkEntity.lastId) {
//            if (linkEntity.isAlive[linkId]) {
//
//                if (linkEntity.isNeuronLink[linkId]) {
//                    shapeRenderer.color = Color.CYAN
//                } else {
//                    shapeRenderer.color = Color.WHITE
//                }
//
//                val c1 = linkEntity.links1[linkId]
//                val c2 = linkEntity.links2[linkId]
//                shapeRenderer.line(cellEntity.getX(c1), cellEntity.getY(c1), cellEntity.getX(c2), cellEntity.getY(c2))
//            }
//        }
//
//        shapeRenderer.end()
    }
    fun drawTextSimInfo(spriteBatch: SpriteBatch, font: BitmapFont) {
        //TODO тут кстати тоже нужна синхронизация, хоть и не так критично

        val uiProjection = fontMatrix.setToOrtho2D(
            0f,
            0f,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat()
        )
        spriteBatch.projectionMatrix = uiProjection

        spriteBatch.begin()
        font.draw(
            spriteBatch,
            """
                    FPS: ${Gdx.graphics.framesPerSecond}
                    UPS: ${simEntity.ups}
                    Update Time: ${round(1e5f / simEntity.ups) / 100f} ms
                    Cells: ${cellEntity.lastId - cellEntity.deadStack.size + 1}
                    Particles: ${particleEntity.lastId - particleEntity.deadStack.size + 1}
                    Links ${linkEntity.lastId - linkEntity.deadStack.size + 1}
                    NeuronImpulseInput ${if (simEntity.grabbedCell != -1) cellEntity.neuronImpulseInput[simEntity.grabbedCell] else "0.0"}
                    NeuronImpulseOutput ${if (simEntity.grabbedCell != -1) cellEntity.neuronImpulseOutput[simEntity.grabbedCell] else "0.0"}
                """.trimIndent(),
            30f,
            140f
        )
        font.data.setScale(1f)
        spriteBatch.end()
    }

    fun dispose() {

    }
}
