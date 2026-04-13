package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RenderSystem(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val shaderManager: ShaderManager,
    val renderBufferManager: RenderBufferManager,
    val diContext: DIContext
) {

    private lateinit var fontMatrix: Matrix4
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera

    private var zoom = 0f
    private var cameraX = 0f
    private var cameraY = 0f
    private var blurLevel = 0f

    fun create(
        fontMatrix: Matrix4,
        spriteBatch: SpriteBatch,
        font: BitmapFont,
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        shaderManager.create()
        this.fontMatrix = fontMatrix
        this.spriteBatch = spriteBatch
        this.font = font
        this.shapeRenderer = shapeRenderer
        this.camera = camera
    }

    fun moveCamera(dx: Float, dy: Float) {
        camera.position.x += dx
        camera.position.y += dy
        camera.update()
    }

    @Volatile
    var isClear = 3
    private var buffer = allocateBuffer(INITIAL_PARTICLE_CAPACITY)

    fun resize(width: Int, height: Int) {
        shaderManager.resize(width, height)
    }

    fun render() {
        if (zoom != camera.zoom || cameraX != camera.position.x || cameraY != camera.position.y) {
            blurLevel = 4.0f
            cameraX = camera.position.x
            cameraY = camera.position.y
            zoom = camera.zoom
        }
        ensureCapacityForWrite(particleEntity.aliveList.size)
        drawShader()
        synchronized(renderBufferManager.renderSpecificBufferData) {
            moveCameraAndDrawSelected()
            drawTextSimInfo()
        }
        if (blurLevel > 0) {
            blurLevel -= 0.09f
        }
    }

    private fun allocateBuffer(numParticles: Int): ByteBuffer {
        return ByteBuffer
            .allocateDirect(numParticles * PARTICLE_STRUCT_SIZE)
            .order(ByteOrder.nativeOrder())
    }

    private fun ensureCapacityForWrite(neededParticles: Int) {
        val currentCapacity = buffer.capacity() / PARTICLE_STRUCT_SIZE
        if (neededParticles + 10 <= currentCapacity) return

        var newCapacity = currentCapacity.toDouble()
        do { newCapacity *= 1.5 } while (newCapacity < neededParticles)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededParticles)
        buffer = allocateBuffer(finalCapacity)
    }

    private fun drawShader() {
        buffer.clear()
        synchronized(renderBufferManager.renderCellBufferData) {
            with(renderBufferManager.renderCellBufferData) {
                for (i in 0..<renderCellBufferSize) {
                    buffer.putFloat(x[i])
                    buffer.putFloat(y[i])
                    buffer.putInt(color[i])
                    buffer.putInt(packed1[i])
                    buffer.putInt(packed2[i])
                    buffer.putInt(0)
                }
                repeat(10) {
                    buffer.putFloat(-100f)
                    buffer.putFloat(-100f)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                }
            }
        }
        buffer.flip()

        val screenCoords = Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
        camera.unproject(screenCoords)

        val worldX = camera.position.x
        val worldY = camera.position.y
        shaderManager.render(
            currentRead = buffer,
            cameraProjection = camera.combined,
            isNewFrame = true,
            isClear = false,
            worldX = worldX,
            worldY = worldY,
            blurAmount = blurLevel,
            zoom = camera.zoom
        )

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
//
        Gdx.gl.glLineWidth(1f)
        shapeRenderer.color = Color.WHITE
        shapeRenderer.rect(
            0f,
            0f,
            diContext.gridWith.toFloat(),
            diContext.gridHeight.toFloat()
        )
//        shapeRenderer.color = Color.GREEN
//
//        synchronized(renderBufferManager.renderCellBufferData) {
//            synchronized(renderBufferManager.renderLinkBufferData) {
//                with(renderBufferManager.renderLinkBufferData) {
//                    for (linkId in 0..<renderLinkAmount) {
//                        val cellAIndex = cellA[linkId]
//                        val cellBIndex = cellB[linkId]
//
//                        shapeRenderer.line(
//                            renderBufferManager.renderCellBufferData.x[cellAIndex],
//                            renderBufferManager.renderCellBufferData.y[cellAIndex],
//                            renderBufferManager.renderCellBufferData.x[cellBIndex],
//                            renderBufferManager.renderCellBufferData.y[cellBIndex],
//                        )
//                    }
//                }
//            }
//        }
        shapeRenderer.end()
    }

    private fun moveCameraAndDrawSelected() = with(renderBufferManager) {
        if (renderSpecificBufferData.isCellSelected) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

            shapeRenderer.color = Color.GOLD
            Gdx.gl.glLineWidth(5f)

            with(renderBufferManager) {
                if (renderSpecificBufferData.isCellSelected) {
                    shapeRenderer.circle(
                        renderSpecificBufferData.grabbedCellX ?: 0f,
                        renderSpecificBufferData.grabbedCellY ?: 0f,
                        0.55f
                    )
                }
            }

            shapeRenderer.end()

            val targetX = renderSpecificBufferData.grabbedCellX ?: return
            val targetY = renderSpecificBufferData.grabbedCellY ?: return

            val lerpSpeed = 1f
            val delta = Gdx.graphics.deltaTime

            camera.position.x += (targetX - camera.position.x) * lerpSpeed * delta
            camera.position.y += (targetY - camera.position.y) * lerpSpeed * delta

            camera.update()
        }
    }

    private fun drawTextSimInfo() = with(renderBufferManager) {
        spriteBatch.begin()
        font.draw(
            spriteBatch,
            """
                    FPS: ${Gdx.graphics.framesPerSecond}
                    UPS: ${renderSpecificBufferData.ups}
                    Update Time: ${renderSpecificBufferData.updateTime} ms
                    Cells: ${renderSpecificBufferData.cellsAmount}
                    Particles: ${renderSpecificBufferData.particleAmount}
                    Links ${renderSpecificBufferData.linksAmount}
                    NeuronImpulseInput ${renderSpecificBufferData.neuronImpulseInput}
                    NeuronImpulseOutput ${renderSpecificBufferData.neuronImpulseOutput}
                    Cell type ${renderSpecificBufferData.cellName}
                """.trimIndent(),
            30f,
            180f
        )
        font.data.setScale(1f)
        spriteBatch.end()
    }

    fun dispose() {

    }

    companion object {
        const val INITIAL_PARTICLE_CAPACITY = 30_000
        const val BYTE_SIZE = 4
        const val PARTICLE_PROPERTIES_AMOUNT = 5
        const val PARTICLE_STRUCT_SIZE = 24
    }
}
