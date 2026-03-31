package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import kotlin.math.round

class RenderSystem(
    val tripleBufferManager: TripleBufferManager,
    val simulationData: SimulationData,
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

    @Volatile
    var isClear = 3
    val circlesBuffer = BufferUtils.newByteBuffer(250_000 * 16)
    var maxCircleCount = 0

    fun drawShader(camera: OrthographicCamera) {
//        val (currentRead, isNewFrame) = tripleBufferManager.getAndSwapConsumer()
        circlesBuffer.clear()
        synchronized(particleEntity) {
            with(particleEntity) {
                if (isClear == 0) {
                    if (maxCircleCount < aliveList.size) maxCircleCount = aliveList.size
                    for (i in 0..<aliveList.size) {
                        val idx = aliveList.getInt(i)
                        circlesBuffer.putFloat(x[idx])
                        circlesBuffer.putFloat(y[idx])
                        circlesBuffer.putFloat(radius[idx])
                        circlesBuffer.putInt(color[idx])
                    }
                } else {
                    isClear--
                    for (i in 0..<maxCircleCount) {
                        circlesBuffer.putFloat(0f)
                        circlesBuffer.putFloat(0f)
                        circlesBuffer.putFloat(0f)
                        circlesBuffer.putInt(0)
                    }
                    println("clear")
                }
            }
        }
        circlesBuffer.flip()

        shaderManager.render(
            currentRead = circlesBuffer,
            cameraProjection = camera.combined,
            isNewFrame = true,
            isClear = false
        )

        //TODO сделать буфер
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

        shapeRenderer.color = Color.GOLD

        shapeRenderer.projectionMatrix = camera.combined

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
//                if (cellEntity.isAlive[c1] && cellEntity.isAlive[c2]) {
//                    shapeRenderer.line(
//                        cellEntity.getX(c1),
//                        cellEntity.getY(c1),
//                        cellEntity.getX(c2),
//                        cellEntity.getY(c2)
//                    )
//                }
//            }
//        }

        if (simulationData.grabbedCellIndex != -1) {
            shapeRenderer.circle(
                cellEntity.getX(simulationData.grabbedCellIndex),
                cellEntity.getY(simulationData.grabbedCellIndex),
                0.55f
            )

            val targetX = cellEntity.getX(simulationData.grabbedCellIndex)
            val targetY = cellEntity.getY(simulationData.grabbedCellIndex)

            val lerpSpeed = 1f // скорость
            val delta = Gdx.graphics.deltaTime

            camera.position.x += (targetX - camera.position.x) * lerpSpeed * delta
            camera.position.y += (targetY - camera.position.y) * lerpSpeed * delta

            camera.update()
        }

        shapeRenderer.end()
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
                    UPS: ${simulationData.ups}
                    Update Time: ${round(1e5f / simulationData.ups) / 100f} ms
                    Cells: ${cellEntity.lastId - cellEntity.deadStack.size + 1}
                    Particles: ${particleEntity.lastId - particleEntity.deadStack.size + 1}
                    Links ${linkEntity.lastId - linkEntity.deadStack.size + 1}
                    NeuronImpulseInput ${if (simulationData.grabbedCellIndex != -1) cellEntity.neuronImpulseInput[simulationData.grabbedCellIndex] else "0.0"}
                    NeuronImpulseOutput ${if (simulationData.grabbedCellIndex != -1) cellEntity.neuronImpulseOutput[simulationData.grabbedCellIndex] else "0.0"}
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

data class RenderSpecificDataBuffer(
    val ups: Int,
    val cellsAmount: Int,
    val particleAmount: Int,
    val linksAmount: Int,
    val neuronImpulseInput: Int?,
    val neuronImpulseOutput: Int?,
    val grabbedCellX: Float?,
    val grabbedCellY: Float?
)
