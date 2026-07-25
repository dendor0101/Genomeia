package io.github.some_example_name.old.editor.system.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.render.ShaderManager
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

//TODO рендер слишком большой
class EditorRenderSystem(
    val shaderManager: ShaderManager,
    val cellReplay: CellReplay,
    val particleEntity: ParticleEntity,
    val editorSimulationSystem: EditorSimulationSystem,
    val drawingHelperElements: DrawingHelperElements
) {

    private lateinit var camera: OrthographicCamera

    fun create(
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        shaderManager.create()
        this.camera = camera
        drawingHelperElements.create(shapeRenderer, camera)
    }

    private var buffer = allocateBuffer(RenderSystem.Companion.INITIAL_PARTICLE_CAPACITY)
    var isUpdateBuffer = true

    private fun allocateBuffer(numParticles: Int): ByteBuffer {
        return ByteBuffer
            .allocateDirect(numParticles * RenderSystem.Companion.PARTICLE_STRUCT_SIZE)
            .order(ByteOrder.nativeOrder())
    }

    private fun ensureCapacityForWrite(neededParticles: Int) {
        val currentCapacity = buffer.capacity() / RenderSystem.Companion.PARTICLE_STRUCT_SIZE
        if (neededParticles + 10 <= currentCapacity) return

        var newCapacity = currentCapacity.toDouble()
        do { newCapacity *= 1.5 } while (newCapacity < neededParticles)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededParticles)
        buffer = allocateBuffer(finalCapacity)
    }

    fun resize(width: Int, height: Int) {
        shaderManager.resize(width, height)
    }

    fun putBuffer(
        cos: Float,
        sin: Float,
        x: Float,
        y: Float,
        color: Int,
        radius: Float,
        cellType: Byte
    ) {
        // LibGDX Color.toIntBits() = ABGR8888 (R in low byte)
        val colorR = (color and 0xFF) / 255f
        val colorG = ((color ushr 8) and 0xFF) / 255f
        val colorB = ((color ushr 16) and 0xFF) / 255f

        // Camera-relative positions (same as RenderSystem) for float precision
        val camX = camera.position.x
        val camY = camera.position.y

        // RGBA32F: 3 texels — (x,y,r,g) | (b,radius,energy,cellType) | (cos,sin,pad,pad)
        buffer.putFloat(x - camX)
        buffer.putFloat(y - camY)
        buffer.putFloat(colorR)
        buffer.putFloat(colorG)
        buffer.putFloat(colorB)
        buffer.putFloat(radius)
        buffer.putFloat(0f) // energy
        buffer.putFloat(cellType.toFloat())
        buffer.putFloat(cos)
        buffer.putFloat(sin)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
    }




    fun render(touchedCellX: Float, touchedCellY: Float) {
        if (isUpdateBuffer) {
            (buffer as Buffer).clear()
            cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, color ->
                putBuffer(
                    cos = angleCos,
                    sin = angleSin,
                    x = particleEntity.x[index],
                    y = particleEntity.y[index],
                    color = color,
                    radius = particleEntity.radius[index],
                    cellType = cellType
                )
            }

            val stage = currentTick
            val stageInstructions = editorSimulationSystem.genomeStageInstruction
            if (stage < stageInstructions.size) {
                val genomeStage = stageInstructions[stage]

                genomeStage.cellActions.forEach { cellActionId, action ->
                    val divide = action.divide
                    if (divide != null) {
                        val index = editorSimulationSystem.mapCellGenomeIdToIndex[divide.id]

                        val angle = divide.angle ?: 0f

                        putBuffer(
                            cos = cos(angle),
                            sin = sin(angle),
                            x = particleEntity.x[index],
                            y = particleEntity.y[index],
                            color = (divide.color ?: Color.WHITE).toIntBits(),
                            radius = particleEntity.radius[index],
                            cellType = 20
                        )
                    }
                }
            }
            (buffer as Buffer).flip()
        }

        shaderManager.render(
            currentRead = buffer,
            cameraProjection = camera.combined,
            cameraRelativeProjection = camera.projection,
            isNewFrame = true,
            isClear = false,
            worldX = camera.position.x,
            worldY = camera.position.y,
            blurAmount = -0.04f,
            zoom = camera.zoom,
            vignetteEnabled = 0f
        )


        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        drawingHelperElements.render(touchedCellX, touchedCellY)
    }

}
