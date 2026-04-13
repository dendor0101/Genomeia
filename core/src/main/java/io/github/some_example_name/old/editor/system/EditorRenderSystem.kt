package io.github.some_example_name.old.editor.system

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.core.DIGenomeEditorContainer.gridHeight
import io.github.some_example_name.old.core.DIGenomeEditorContainer.gridWith
import io.github.some_example_name.old.editor.entities.ReplayEntity
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PARTICLE_CAPACITY
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.PARTICLE_STRUCT_SIZE
import io.github.some_example_name.old.systems.render.ShaderManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EditorRenderSystem(
    val shaderManager: ShaderManager,
    val replayEntity: ReplayEntity,
    val editorLogicSystem: EditorLogicSystem
) {

    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    var showPhysicalLink = false

    fun create(
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        shaderManager.create()
        this.shapeRenderer = shapeRenderer
        this.camera = camera
    }

    private var buffer = allocateBuffer(INITIAL_PARTICLE_CAPACITY)
    var isUpdateBuffer = true

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

    fun render() {
        if (isUpdateBuffer) {
            buffer.clear()
            replayEntity.forEachInTick(editorLogicSystem.currentTick) { x, y, energy, color, cellType ->
                val bAngle = ((0 / 360f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bRadius = (((0.5f - 0.1f) / 0.4f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bEnergy = ((energy / 10f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bCell = cellType.toInt().coerceIn(0, 255)
                val packed1 = bAngle or (0) or (0) or (bRadius shl 24)
                val packed2 = bEnergy or (bCell shl 8)

                buffer.putFloat(x)
                buffer.putFloat(y)
                buffer.putInt(color)
                buffer.putInt(packed1)
                buffer.putInt(packed2)
                buffer.putInt(0)
            }
            buffer.flip()
        }

        shaderManager.render(
            currentRead = buffer,
            cameraProjection = camera.combined,
            isNewFrame = true,
            isClear = false,
            worldX = 0f,
            worldY = 0f,
            blurAmount = 0f,
            zoom = 0f
        )

        shapeRenderer.color = Color.WHITE
        shapeRenderer.projectionMatrix = camera.combined


        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.rect(
            0f,
            0f,
            gridWith.toFloat(),
            gridHeight.toFloat()
        )

        Gdx.gl.glLineWidth(2f)

//        editor.editorLinks.forEach {
//            when (it.isNeuralTo2) {
//                true -> {
//                    shape.color = Color.CYAN
//                    shape.drawTriangleMiddle(
//                        it.x1,
//                        it.y1,
//                        it.x2,
//                        it.y2
//                    )
//                }
//                false -> {
//                    shape.color = Color.CYAN
//                    shape.drawTriangleMiddle(
//                        it.x2,
//                        it.y2,
//                        it.x1,
//                        it.y1
//                    )
//                }
//                null -> {
//                    shape.color = Color.RED
//                }
//            }
//            if (showPhysicalLink || it.isNeuralTo2 != null) {
//                shape.line(
//                    it.x1,
//                    it.y1,
//                    it.x2,
//                    it.y2
//                )
//            }
//        }
//
//        editor.specialCells.forEach {
//            when (it.cellType) {
//                6 -> {
//                    shape.color = Color.CYAN
//                    shape.circle(
//                        it.x,
//                        it.y,
//                        150f
//                    )
//                }
//
//                14 -> {
//                    shape.color = Color.CYAN
//                    shape.drawArrowWithRotationAngle(
//                        startX = it.x,
//                        startY = it.y,
//                        baseAngle = it.angle,
//                        length = it.length,
//                        isDrawWithoutTriangle = true,
//                    )
//                }
//
//                3, 9, 15, 19, 21 -> {
//                    shape.color = Color.CYAN
//                    shape.drawArrowWithRotationAngle(
//                        startX = it.x,
//                        startY = it.y,
//                        baseAngle = it.angle,
//                        length = 15f
//                    )
//                }
//            }
//        }
//
//        if (previousCtrlClicked != -1 && previousCtrlClicked < editor.editorCells.size) {
//            val cell = editor.editorCells[previousCtrlClicked]
//            shape.color = Color.CYAN
//            shape.circle(cell.x, cell.y, 5f)
//        } else {
//            previousCtrlClicked = -1
//        }

        shapeRenderer.end()
    }
}
