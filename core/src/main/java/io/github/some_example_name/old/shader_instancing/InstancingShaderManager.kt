package io.github.some_example_name.old.shader_instancing

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.GenomeEditorScreen
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import java.nio.Buffer
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.system.measureNanoTime

class InstancingShaderManager(val camera: OrthographicCamera, val editorScreen: GenomeEditorScreen) {

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private var circlesTex = 0
    private var neighborsTex = 0

    val maxCircles = 3500
    val maxNeighbors = 12
    val cellSize = 40f  // Из оригинала

    private val texWidth = 128  // Фиксированная ширина текстуры (должна быть <= GL_MAX_TEXTURE_SIZE)
    private val circlesTexelsPerCircle = 2  // 8 floats / 4 = 2 texel (RGBA)
    private val neighborsFloatsPerCircle = 12  // 10 neighbors + 2 pad, для выравнивания по 3 texel
    private val neighborsTexelsPerCircle = 3  // 12 / 4

    private val circlesMaxTexels = circlesTexelsPerCircle * maxCircles
    private val circlesHeight = ceil(circlesMaxTexels.toFloat() / texWidth).toInt()

    private val neighborsMaxTexels = neighborsTexelsPerCircle * maxCircles
    private val neighborsHeight = ceil(neighborsMaxTexels.toFloat() / texWidth).toInt()

    var neighborsBuffer: FloatBuffer = BufferUtils.newFloatBuffer(neighborsFloatsPerCircle * maxCircles)
    var circlesBuffer: FloatBuffer = BufferUtils.newFloatBuffer(8 * maxCircles)
    val neighborsArray = IntArray(neighborsFloatsPerCircle * maxCircles) { -1 }
    val neighborsAmountArray = IntArray(maxCircles) { -1 }

    var isGL30Available = false

    fun create() {
        println("size $circlesHeight $neighborsHeight")

        // Лог максимального размера текстуры для отладки
//        Gdx.app.log("GL", "Max texture size: ${Gdx.gl.glGetInteger(GL20.GL_MAX_TEXTURE_SIZE)}")

        isGL30Available = Gdx.graphics.isGL30Available()
        if (!isGL30Available) {
            Gdx.app.error("Shader", "OpenGL ES 3.0 not supported on this device. Falling back to alternative rendering if available.")
            // Здесь можно инициализировать fallback, например, SpriteBatch для простого рендера
            return
        }

        // Quad mesh
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE))
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }

        val vertFile = Gdx.files.internal("shaders/cells.vert").readString()
        val fragFile = Gdx.files.internal("shaders/cells.frag").readString()
        shader = ShaderProgram(vertFile, fragFile)
        if (!shader.isCompiled) {
            Gdx.app.error("Shader", shader.log)
            Gdx.app.exit()
        }

        val texIds = BufferUtils.newIntBuffer(2)
        Gdx.gl.glGenTextures(2, texIds)
        circlesTex = texIds.get(0)
        neighborsTex = texIds.get(1)

        // circlesTex
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, circlesTex)
        Gdx.gl30.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, texWidth, circlesHeight, 0, GL20.GL_RGBA, GL20.GL_FLOAT, null)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)

        // neighborsTex
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, neighborsTex)
        Gdx.gl30.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, texWidth, neighborsHeight, 0, GL20.GL_RGBA, GL20.GL_FLOAT, null)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)

        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
    }

    fun render(replay: List<EditorCell>, isUpdate: Boolean, grabbedCellIndex: Int) {
        ScreenUtils.clear(26 / 255f, 27 / 255f, 46 / 255f, 1f)

        if (!isGL30Available) {
            // Fallback рендер, например, используя SpriteBatch или простые круги без инстансинга
            Gdx.app.log("Shader", "Using fallback rendering due to lack of GLES 3.0 support.")
            return
        }

        val count = replay.size
        if (count == 0) return

        // Вычисление соседей
        if (isUpdate) {
            // Позиции и размеры для соседей

            val nanoTime = measureNanoTime {
                computeNeighbors(replay, grabbedCellIndex)
            }
//            println("computeNeighbors ${nanoTime / 1_000_000} FPS ${Gdx.graphics.framesPerSecond}")

            circlesBuffer = BufferUtils.newFloatBuffer(8 * maxCircles)
            val nanoTime1 = measureNanoTime {
                // Буфер для circles (8 floats per circle, full max с pad)
                for (i in 0 until count) {
                    circlesBuffer.put(replay[i].x)
                    circlesBuffer.put(replay[i].y)
                    circlesBuffer.put(replay[i].energy)  // c.size в шейдере
                    circlesBuffer.put(neighborsAmountArray[i].toFloat())
                    circlesBuffer.put(replay[i].color.r)
                    circlesBuffer.put(replay[i].color.g)
                    circlesBuffer.put(replay[i].color.b)
                    circlesBuffer.put(if (replay[i].isJustAdded) 1f else 0f)
                }
                // Pad остальное 0
                for (i in count until maxCircles) {
                    repeat(8) { circlesBuffer.put(0f) }
                }
                (circlesBuffer as Buffer).flip()
            }

            // Буфер для neighbors (12 floats per circle: 10 neigh + 2 pad -1)
            neighborsBuffer = BufferUtils.newFloatBuffer(neighborsFloatsPerCircle * maxCircles)
            val nanoTime2 = measureNanoTime {
                for (i in 0 until neighborsArray.size) {
                    neighborsBuffer.put(neighborsArray[i].toFloat())
                }
                (neighborsBuffer as Buffer).flip()
            }
        }

        val nanoTime3 = measureNanoTime {
            // Загрузка текстур
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, circlesTex)
            Gdx.gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0,
                0,
                texWidth,
                circlesHeight,
                GL20.GL_RGBA,
                GL20.GL_FLOAT,
                circlesBuffer
            )
            checkGlError("circlesTex upload")

            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, neighborsTex)
            Gdx.gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0,
                0,
                texWidth,
                neighborsHeight,
                GL20.GL_RGBA,
                GL20.GL_FLOAT,
                neighborsBuffer
            )
            checkGlError("neighborsTex upload")
        }

        // Рендер
        shader.bind()
        shader.setUniformMatrix("u_projTrans", camera.combined)
        shader.setUniformi("u_circlesTex", 0)
        shader.setUniformi("u_neighborsTex", 1)
        shader.setUniformi("u_texWidth", texWidth)

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        mesh.bind(shader)
        Gdx.gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, count)
        mesh.unbind(shader)

        shader.end()  // Важно для VisUI!

        Gdx.gl.glDisable(GL20.GL_BLEND)

        // Unbind текстур для VisUI
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)  // Сброс на 0
    }

    private fun checkGlError(operation: String) {
        var error: Int
        while (Gdx.gl.glGetError().also { error = it } != GL20.GL_NO_ERROR) {
            Gdx.app.error("GL Error", "$operation: glError $error")
        }
    }

    fun computeNeighbors(replay: List<EditorCell>, grabbedCellIndex: Int) {
        neighborsAmountArray.fill(0)
        neighborsArray.fill(-1)

        val replayNextStage = with(editorScreen.editor) {
            val nextStage =
                if (stages.size <= state.currentStage + 1) stages.size - 1 else state.currentStage + 1
            growthProcessor.simulationFullReplay[nextStage]
        }

        with(replayNextStage) {
            for (itIndex in 0 .. cellLastId) {
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
                    else throw Exception("Not correct cell index")

                    val neighborsIndex = cellId + neighborCounter
                    neighborsArray[neighborsIndex] = otherCellId
                    neighborCounter++
                }
                // Clamp для безопасности
                neighborsAmountArray[itIndex] = neighborCounter.coerceAtMost(maxNeighbors)
                if (neighborCounter > maxNeighbors) {
                    Gdx.app.error("Neighbors", "Too many neighbors for cell ${itIndex}: $neighborCounter > $maxNeighbors")
                }
            }
        }
    }

    fun dispose() {
        if (isGL30Available) {
            mesh.dispose()
            shader.dispose()
            val ids = BufferUtils.newIntBuffer(2)
            ids.put(circlesTex).put(neighborsTex).flip()
            Gdx.gl.glDeleteTextures(2, ids)
        }
    }
}
