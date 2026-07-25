package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.old.core.utils.drawTriangleMiddle
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class RenderSystem(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val shaderManager: ShaderManager,
    val renderBufferManager: RenderBufferManager,
    val pheromoneEntity: PheromoneEntity
) {

    var isRenderUi = true

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
//        pheromoneShaderManager.create()
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

    private var bufferCell = allocateBuffer(INITIAL_PARTICLE_CAPACITY, PARTICLE_STRUCT_SIZE)
    private var bufferPheromone = allocateBuffer(INITIAL_PHEROMONE_CAPACITY, PHEROMONE_STRUCT_SIZE)


    fun resize(width: Int, height: Int) {
        shaderManager.resize(width, height)
    }

    fun render() {
        val cellBuf = renderBufferManager.getCurrentCellBuffer()
        val pheromoneBuf = renderBufferManager.getCurrentPheromoneBuffer()
        val linkBuf = renderBufferManager.getCurrentLinkBuffer()
        val spec = renderBufferManager.getCurrentSpecificBufferData()
        if (zoom != camera.zoom || cameraX != camera.position.x || cameraY != camera.position.y) {
            if (!spec.isCellSelected) {
                blurLevel = 4.0f
                cameraX = camera.position.x
                cameraY = camera.position.y
                zoom = camera.zoom
            }
        }

        ensureCellBufferCapacityForWrite(particleEntity.aliveList.size)
        drawCellShader(cellBuf)

        ensurePheromoneBufferCapacityForWrite(pheromoneEntity.aliveList.size)
        if (doesUsePostProcess) {
            drawPheromoneShader(pheromoneBuf)
        }
        if (!doesUsePostProcess) {
            drawDebug(cellBuf, linkBuf, pheromoneBuf)
        }

        moveCameraAndDrawSelected(spec)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)


        if (isRenderUi) {
            drawTextSimInfo(spec)
        }

        if (blurLevel > 0) {
            blurLevel -= 0.09f
        }
    }

    private fun allocateBuffer(numInstances: Int, structSize: Int): ByteBuffer {
        return ByteBuffer
            .allocateDirect(numInstances * structSize)
            .order(ByteOrder.nativeOrder())
    }

    private fun ensureCellBufferCapacityForWrite(neededParticles: Int) {
        val currentCapacity = bufferCell.capacity() / PARTICLE_STRUCT_SIZE
        if (neededParticles <= currentCapacity) return

        var newCapacity = currentCapacity.toDouble()
        do { newCapacity *= 1.5 } while (newCapacity < neededParticles)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededParticles)
        bufferCell = allocateBuffer(finalCapacity, PARTICLE_STRUCT_SIZE)
    }


    private fun ensurePheromoneBufferCapacityForWrite(neededPheromones: Int) {
        val currentCapacity = bufferPheromone.capacity() / PHEROMONE_STRUCT_SIZE
        if (neededPheromones <= currentCapacity) return

        var newCapacity = currentCapacity.toDouble()
        do { newCapacity *= 1.5 } while (newCapacity < neededPheromones)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededPheromones)
        bufferPheromone = allocateBuffer(finalCapacity, PHEROMONE_STRUCT_SIZE)
    }


    private fun drawPheromoneShader(pheromoneBuffer: PheromoneBufferData) {
        val camX = camera.position.x
        val camY = camera.position.y
        (bufferPheromone as java.nio.Buffer).clear()
        with(pheromoneBuffer) {
            for (i in 0..<pheromoneBufferSize) {
                // Camera-relative positions — keeps float precision near the viewport
                // RGBA32F: 2 texels — (x,y,A,r) | (g,b,pad,pad)
                bufferPheromone.putFloat(x[i] - camX)
                bufferPheromone.putFloat(y[i] - camY)
                bufferPheromone.putFloat(a[i])
                val c = color[i]
                bufferPheromone.putFloat((c and 0xFF) / 255f)
                bufferPheromone.putFloat(((c ushr 8) and 0xFF) / 255f)
                bufferPheromone.putFloat(((c ushr 16) and 0xFF) / 255f)
                bufferPheromone.putFloat(0f)
                bufferPheromone.putFloat(0f)
            }
        }
        (bufferPheromone as java.nio.Buffer).flip()
    }


    private fun drawCellShader(cellBuf: RenderCellBufferData) {
        val camX = camera.position.x
        val camY = camera.position.y
        (bufferCell as java.nio.Buffer).clear()
        with(cellBuf) {
            for (i in 0..<renderCellBufferSize) {
                // Camera-relative positions — avoids stair-step motion at large world coords
                // RGBA32F: 3 texels — (x,y,r,g) | (b,radius,energy,cellType) | (cos,sin,pad,pad)
                bufferCell.putFloat(x[i] - camX)
                bufferCell.putFloat(y[i] - camY)
                bufferCell.putFloat(colorR[i])
                bufferCell.putFloat(colorG[i])
                bufferCell.putFloat(colorB[i])
                bufferCell.putFloat(radius[i])
                bufferCell.putFloat(energy[i])
                bufferCell.putFloat(cellType[i])
                bufferCell.putFloat(angleCos[i])
                bufferCell.putFloat(angleSin[i])
                bufferCell.putFloat(0f)
                bufferCell.putFloat(0f)
            }
        }
        (bufferCell as java.nio.Buffer).flip()

        shaderManager.render(
            currentRead = bufferCell,
            cameraProjection = camera.combined,
            cameraRelativeProjection = camera.projection,
            isNewFrame = true,
            isClear = false,
            worldX = camX,
            worldY = camY,
            blurAmount = blurLevel,
            zoom = camera.zoom,
            vignetteEnabled = 1f,
            pheromoneData = bufferPheromone
        )
    }


    fun drawDebug(cellBuf: RenderCellBufferData, linkBuf: RenderLinkBufferData, pheromoneBuffer: PheromoneBufferData) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        Gdx.gl.glLineWidth(2f)
        with(cellBuf) {
            shapeRenderer.color = Color.WHITE
            for (i in 0..<renderCellBufferSize) {
                if (directedAngleCos[i] != 0f || directedAngleSin[i] != 0f) {
                    shapeRenderer.line(
                        x[i],
                        y[i],
                        x[i] + directedAngleCos[i],
                        y[i] + directedAngleSin[i]
                    )
                }
            }
        }

        shapeRenderer.color = Color.GREEN

        with(linkBuf) {
            for (linkId in 0..<renderLinkAmount) {

                val cellAIndex = cellA[linkId]
                val cellBIndex = cellB[linkId]
                shapeRenderer.color = when (isNeuralDirected[linkId].toInt()) {
                    0, 1 -> Color.CYAN
                    -1 -> Color.GREEN
                    3 -> Color.PURPLE
                    else -> Color.RED
                }

                if ((isNeuralDirected[linkId].toInt() == 0)) {
                    shapeRenderer.drawTriangleMiddle(
                        cellBuf.x[cellAIndex],
                        cellBuf.y[cellAIndex],
                        cellBuf.x[cellBIndex],
                        cellBuf.y[cellBIndex],
                        arrowSize = 0.1f
                    )
                } else if ((isNeuralDirected[linkId].toInt() == 1)) {
                    shapeRenderer.drawTriangleMiddle(
                        cellBuf.x[cellBIndex],
                        cellBuf.y[cellBIndex],
                        cellBuf.x[cellAIndex],
                        cellBuf.y[cellAIndex],
                        arrowSize = 0.1f
                    )
                }

                shapeRenderer.line(
                    cellBuf.x[cellAIndex],
                    cellBuf.y[cellAIndex],
                    cellBuf.x[cellBIndex],
                    cellBuf.y[cellBIndex],
                )
            }
        }

//      Дебаг отрисовка феромона
        with(pheromoneBuffer) {
            for (i in 0..<pheromoneBufferSize) {
                shapeRenderer.circle(x[i], y[i], sqrt(radiusSquared[i]), 64)
            }
        }
        shapeRenderer.end()
    }

    private fun moveCameraAndDrawSelected(spec: RenderSpecificBufferData) = with(renderBufferManager) {
        if (spec.isCellSelected) {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

            shapeRenderer.color = Color.GOLD
            Gdx.gl.glLineWidth(5f)

            with(renderBufferManager) {
                if (spec.isCellSelected) {
                    shapeRenderer.circle(
                        spec.grabbedCellX ?: 0f,
                        spec.grabbedCellY ?: 0f,
                        0.55f,
                        64
                    )
                }
            }

            shapeRenderer.end()

            val targetX = spec.grabbedCellX ?: return
            val targetY = spec.grabbedCellY ?: return

            val lerpSpeed = 1f
            val delta = Gdx.graphics.deltaTime

            camera.position.x += (targetX - camera.position.x) * lerpSpeed * delta
            camera.position.y += (targetY - camera.position.y) * lerpSpeed * delta

            camera.update()
        }
    }

    private fun drawTextSimInfo(spec: RenderSpecificBufferData) = with(renderBufferManager) {
        spriteBatch.begin()
        font.draw(
            spriteBatch,
            """
                    FPS: ${Gdx.graphics.framesPerSecond}
                    UPS: ${spec.ups}
                    Update Time: ${spec.updateTime} ms
                    Cells: ${spec.cellsAmount}
                    Particles: ${spec.particleAmount}
                    Links ${spec.linksAmount}
                    NeuronImpulseInput ${spec.neuronImpulseInput}
                    NeuronImpulseOutput ${spec.neuronImpulseOutput}
                    Cell type ${spec.cellName}
                    Selected cell index ${spec.selectedCellIndex}
                """.trimIndent(),
            30f,
            200f
        )
        font.data.setScale(1f)
        spriteBatch.end()
    }

    fun dispose() {
        //TODO
    }

    companion object {
        const val INITIAL_PARTICLE_CAPACITY = 30_000
        const val INITIAL_PHEROMONE_CAPACITY = 1_000
        /** 48 bytes = 3× RGBA32F texels (x,y,r,g | b,radius,energy,cellType | cos,sin,pad,pad). */
        const val PARTICLE_STRUCT_SIZE = 48

        /** 32 bytes = 2× RGBA32F texels (x,y,A,r | g,b,pad,pad). */
        const val PHEROMONE_STRUCT_SIZE = 32
    }

}
