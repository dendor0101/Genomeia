package io.github.some_example_name.old.shader_instancing

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_TEXTURE_2D
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.GdxRuntimeException
import com.badlogic.gdx.utils.ScreenUtils
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.GenomeEditorScreen
import io.github.some_example_name.old.genome_editor.timeForProcessingActionResult
import io.github.some_example_name.old.genome_editor.timeForProcessingActionStart
import io.github.some_example_name.old.good_one.shader.FloatTexture2D
import io.github.some_example_name.old.good_one.shader.IntTexture2D_RG32I
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import java.nio.Buffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.system.measureNanoTime

class InstancingTextureShaderManager(val camera: OrthographicCamera, val editorScreen: GenomeEditorScreen) {

    private val shader: ShaderProgram
    private val mesh: Mesh

    val maxCircles = 5400
    val maxNeighbors = 12
    val cellSize = 40f

    private val textureSize = 256  // Фиксированная ширина текстуры (должна быть <= GL_MAX_TEXTURE_SIZE)
    private val neighborsFloatsPerCircle = 12

    val neighborsBuffer: IntBuffer = BufferUtils.newIntBuffer(textureSize * textureSize * 2)
    val circlesBuffer: FloatBuffer = BufferUtils.newFloatBuffer(textureSize * textureSize * 4)
    val neighborsArray = IntArray(neighborsFloatsPerCircle * maxCircles) { -1 }
    val neighborsAmountArray = IntArray(maxCircles) { -1 }
    val zeroChunkInt = IntArray(1024) { 0 }
    val circlesData = FloatArray(maxCircles * 8)

    private val circlesTexture = FloatTexture2D(textureSize, textureSize)
    private val neighborsTexture = IntTexture2D_RG32I(textureSize, textureSize)

    // Для оптимизации: Отслеживаем последнюю позицию камеры
    private var lastCamX = Float.MIN_VALUE
    private var lastCamY = Float.MIN_VALUE

    init {
        val vertFile = Gdx.files.internal("shaders/cells.vert").readString()
        val fragFile = Gdx.files.internal("shaders/cells.frag").readString()

        shader = ShaderProgram(vertFile, fragFile)

        if (!shader.isCompiled) {
            throw GdxRuntimeException("Shader compilation failed: ${shader.log}")
        }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE))
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }
    }

    fun render(replay: List<EditorCell>, isUpdate: Boolean, grabbedCellIndex: Int, isRestartSimulation: Boolean) {
        ScreenUtils.clear(26 / 255f, 27 / 255f, 46 / 255f, 1f)

        val count = replay.size
        if (count == 0) return

        val instanceCount = count.coerceAtMost(maxCircles)

        val camX = camera.position.x
        val camY = camera.position.y
        val cameraMoved = abs(camX - lastCamX) > 1e-6f || abs(camY - lastCamY) > 1e-6f

        val nanoTime = measureNanoTime {
            if (isUpdate) {  // Update соседей/энергии/цветов только когда клетки изменились
                computeNeighbors(grabbedCellIndex)
                updateNeighborsBuffer()
            }

            if (isUpdate || cameraMoved) {  // Update позиций, если камера двинулась или клетки изменились
                updateCirclesBuffer(replay, instanceCount, camX, camY)
                lastCamX = camX
                lastCamY = camY
            }

            if (isUpdate || cameraMoved) {
                circlesTexture.update(circlesBuffer)
            }
            if (isUpdate) {
                neighborsTexture.update(neighborsBuffer)
            }
        }

        if (isRestartSimulation) {
            timeForProcessingActionResult = (System.nanoTime() - timeForProcessingActionStart) / 1_000_000f
            println("Update nanoTime $timeForProcessingActionResult")
        }

        // Рендер (без изменений) // Render (unchanged)
        shader.bind()
        circlesTexture.texture.bind(0)
        shader.setUniformi("u_circlesTex", 0)

        neighborsTexture.texture.bind(1)
        shader.setUniformi("u_neighborsTex", 1)

        shader.setUniformMatrix("u_projTrans", camera.projection)
        shader.setUniformi("u_texWidth", textureSize)
        mesh.bind(shader)
        Gdx.gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, instanceCount)
        mesh.unbind(shader)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        Gdx.gl.glBindTexture(GL_TEXTURE_2D, 0)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        Gdx.gl.glBindTexture(GL_TEXTURE_2D, 0)

        Gdx.gl.glUseProgram(0)
    }

    private fun updateCirclesBuffer(replay: List<EditorCell>, instanceCount: Int, camX: Float, camY: Float) {
        circlesData.fill(0f)
        var offset = 0
        for (i in 0 until instanceCount) {
            circlesData[offset++] = replay[i].x - camX
            circlesData[offset++] = replay[i].y - camY
            circlesData[offset++] = replay[i].energy
            circlesData[offset++] = neighborsAmountArray[i].toFloat()
            circlesData[offset++] = replay[i].color.r
            circlesData[offset++] = replay[i].color.g
            circlesData[offset++] = replay[i].color.b
            circlesData[offset++] = if (replay[i].isJustAdded) 1f else 0f
        }

        (circlesBuffer as Buffer).clear()
        circlesBuffer.put(circlesData)
        val zeroChunkFloat = FloatArray(1024)
        while ((circlesBuffer as Buffer).hasRemaining()) {
            val toPut = minOf(1024, circlesBuffer.remaining())
            circlesBuffer.put(zeroChunkFloat, 0, toPut)
        }
        (circlesBuffer as Buffer).flip()
    }

    private fun updateNeighborsBuffer() {
        (neighborsBuffer as Buffer).clear()
        neighborsBuffer.put(neighborsArray)
        while ((neighborsBuffer as Buffer).hasRemaining()) {
            val toPut = minOf(1024, neighborsBuffer.remaining())
            neighborsBuffer.put(zeroChunkInt, 0, toPut)
        }
        (neighborsBuffer as Buffer).flip()
    }

    fun computeNeighbors(grabbedCellIndex: Int) {
        neighborsAmountArray.fill(0)
        neighborsArray.fill(-1)

        val replayNextStage = with(editorScreen.editor) {
            val nextStage = if (stages.size <= state.currentStage + 1) stages.size - 1 else state.currentStage + 1
            growthProcessor.simulationFullReplay[nextStage]
        }

        with(replayNextStage) {
            for (itIndex in 0..cellLastId) {
                if (grabbedCellIndex == itIndex) continue
                val cellId = maxNeighbors * itIndex
                var neighborCounter = 0

                val base = itIndex * MAX_LINK_AMOUNT
                val amount = linksAmount[itIndex]

                for (i in 0 until amount) {
                    val idx = base + i
                    val linkId = links[idx]

                    val otherCellId = if (links1[linkId] != itIndex) {
                        links1[linkId]
                    } else if (links2[linkId] != itIndex) links2[linkId]
                    else throw Exception("Not correct cell index links1: ${links1[linkId]} links2: ${links2[linkId]} itIndex: $itIndex linkId: $linkId")

                    val neighborsIndex = cellId + neighborCounter
                    neighborsArray[neighborsIndex] = otherCellId
                    neighborCounter++
                }
                neighborsAmountArray[itIndex] = neighborCounter.coerceAtMost(maxNeighbors)
                if (neighborCounter > maxNeighbors) {
                    Gdx.app.error("Neighbors", "Too many neighbors for cell ${itIndex}: $neighborCounter > $maxNeighbors")
                }
            }
        }
    }

    fun dispose() {
        shader.dispose()
        mesh.dispose()
        circlesTexture.dispose()
        neighborsTexture.dispose()
    }
}
