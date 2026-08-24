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
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderSettings
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import kotlin.math.sqrt

/**
 * Сторона симуляции в отрисовке кадра: взять снимок мира и перевести его на язык,
 * который понимает модуль рендера.
 *
 * Границу проводит просто: всё, что знает про клетки, связи и UPS, живёт здесь; всё,
 * что говорит с GPU, живёт в :render. Поэтому здесь нет ни одного вызова Gdx.gl,
 * кроме переключения состояний вокруг отладочных фигур, а там — ни одного упоминания
 * клетки.
 */
class RenderSystem(
    val worldRenderer: WorldRenderer,
    val renderBufferManager: RenderBufferManager
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

    /** Всё переиспользуется между кадрами: аллокаций на кадр быть не должно. */
    private val cellBuffer = CellInstanceBuffer()
    private val pheromoneBuffer = PheromoneInstanceBuffer()
    private val frame = RenderFrame()

    fun create(
        fontMatrix: Matrix4,
        spriteBatch: SpriteBatch,
        font: BitmapFont,
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        worldRenderer.checkResize()
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

    fun resize(width: Int, height: Int) {
        worldRenderer.resize(width, height)
    }

    fun render() {
        // Индекс кадра читается ОДИН раз, и оба буфера берутся по нему.
        //
        // Буфер связей хранит позиции внутри буфера клеток, поэтому они обязаны быть из
        // одной сборки. Два отдельных геттера этого не гарантировали: симуляция успевала
        // переключить буферы между вызовами, и связи рисовались по позициям от другого
        // кадра — на экране это линии между чужими организмами и уходящие в ноль.
        val frameIndex = renderBufferManager.frontFrameIndex()
        val cellBuf = renderBufferManager.cellBuffer(frameIndex)
        val linkBuf = renderBufferManager.linkBuffer(frameIndex)

        val pheromoneBuf = renderBufferManager.getCurrentPheromoneBuffer()
        val spec = renderBufferManager.getCurrentSpecificBufferData()

        val usePostProcess = RenderSettings.usePostProcess

        if (zoom != camera.zoom || cameraX != camera.position.x || cameraY != camera.position.y) {
            if (!spec.isCellSelected) {
                blurLevel = 4.0f
                cameraX = camera.position.x
                cameraY = camera.position.y
                zoom = camera.zoom
            }
        }

        packCells(cellBuf)
        packPheromones(pheromoneBuf, usePostProcess)

        frame.apply {
            cameraProjection = camera.combined
            cells = cellBuffer.end()
            uploadCells = true
            pheromones = pheromoneBuffer.end()
            pheromoneK = PheromonesManager.K
            pheromoneP = PheromonesManager.P
            blurAmount = blurLevel
            zoom = camera.zoom
            vignetteEnabled = 1f
            this.usePostProcess = usePostProcess
        }
        worldRenderer.render(frame)

        if (!usePostProcess) {
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

    /**
     * Ёмкость считается по СНИМКУ, который сейчас будет записан, а не по живому
     * aliveList: список принадлежит потоку симуляции и меняется прямо во время кадра.
     * Если в снимке частиц больше, чем в списке на момент чтения (а так бывает при
     * массовой гибели — снимок старше), запись уходила за границу буфера и кадр падал
     * с BufferOverflowException.
     *
     * packed1/packed2 уже посчитаны потоком симуляции — здесь только перекладывание
     * байт, без арифметики. См. пояснение в CellInstanceBuffer.
     */
    private fun packCells(cellBuf: RenderCellBufferData) {
        cellBuffer.begin(cellBuf.renderCellBufferSize)
        with(cellBuf) {
            for (i in 0..<renderCellBufferSize) {
                cellBuffer.put(
                    x = x[i],
                    y = y[i],
                    color = color[i],
                    packed1 = packed1[i],
                    packed2 = packed2[i]
                )
            }
        }
    }

    private fun packPheromones(pheromoneBuf: PheromoneBufferData, usePostProcess: Boolean) {
        pheromoneBuffer.begin(pheromoneBuf.pheromoneBufferSize)
        if (!usePostProcess) return
        with(pheromoneBuf) {
            for (i in 0..<pheromoneBufferSize) {
                pheromoneBuffer.put(
                    x = x[i],
                    y = y[i],
                    a = a[i],
                    color = color[i]
                )
            }
        }
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

    private fun moveCameraAndDrawSelected(spec: RenderSpecificBufferData) {
        if (!spec.isCellSelected) return

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

        shapeRenderer.color = Color.GOLD
        Gdx.gl.glLineWidth(5f)

        shapeRenderer.circle(
            spec.grabbedCellX ?: 0f,
            spec.grabbedCellY ?: 0f,
            0.55f,
            64
        )

        shapeRenderer.end()

        val targetX = spec.grabbedCellX ?: return
        val targetY = spec.grabbedCellY ?: return

        val lerpSpeed = 1f
        val delta = Gdx.graphics.deltaTime

        camera.position.x += (targetX - camera.position.x) * lerpSpeed * delta
        camera.position.y += (targetY - camera.position.y) * lerpSpeed * delta

        camera.update()
    }

    private fun drawTextSimInfo(spec: RenderSpecificBufferData) {
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
${spec.detailedPerformance}
                """.trimIndent(),
            30f,
            450f
        )
        font.data.setScale(1f)
        spriteBatch.end()
    }

    /**
     * GL-ресурсы принадлежат [worldRenderer], а он один на всю игру и переживает смену
     * экранов — поэтому здесь его НЕ трогаем. Диспозить его должен тот, кто им владеет
     * (DIGameGlobalContainer), при завершении игры.
     *
     * Собственных GL-ресурсов у этого класса нет: буферы инстансов — обычная память.
     */
    fun dispose() {
    }
}
