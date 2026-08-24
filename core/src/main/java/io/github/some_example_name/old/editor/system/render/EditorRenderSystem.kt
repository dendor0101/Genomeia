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
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderSettings
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.pack.CellInstanceBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Сторона редактора генома в отрисовке кадра.
 *
 * От RenderSystem отличается только источником данных: там снимок живого мира, здесь
 * запись роста организма по тикам (CellReplay). Дальше по конвейеру идут те же самые
 * упакованные байты в тот же самый [WorldRenderer] — ровно ради этого переиспользования
 * упаковка и вынесена в CellInstanceBuffer.
 */
class EditorRenderSystem(
    val worldRenderer: WorldRenderer,
    val cellReplay: CellReplay,
    val particleEntity: ParticleEntity,
    val editorSimulationSystem: EditorSimulationSystem,
    val drawingHelperElements: DrawingHelperElements
) {

    private lateinit var camera: OrthographicCamera

    /** Переиспользуются между кадрами. */
    private val cellBuffer = CellInstanceBuffer()
    private val frame = RenderFrame()

    var isUpdateBuffer = true

    fun create(
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        worldRenderer.checkResize()
        this.camera = camera
        drawingHelperElements.create(shapeRenderer, camera)
    }

    fun resize(width: Int, height: Int) {
        worldRenderer.resize(width, height)
    }

    fun render(touchedCellX: Float, touchedCellY: Float) {
        if (isUpdateBuffer) {
            fillBuffer()
        }

        frame.apply {
            cameraProjection = camera.combined
            cells = cellBuffer.end()
            // Всегда true, хотя буфер мог и не меняться.
            //
            // Соблазнительно поставить сюда isUpdateBuffer и сэкономить заливку текстуры,
            // но WorldRenderer один на всю игру, и текстура данных в нём общая с экраном
            // симуляции. Пропустив заливку на первом кадре после смены экрана, редактор
            // показал бы чужие частицы. Экономия того не стоит.
            uploadCells = true
            // Редактор феромоны не рисует: в записи роста их нет.
            pheromones = null
            blurAmount = -0.04f
            zoom = camera.zoom
            vignetteEnabled = 0f
            usePostProcess = RenderSettings.usePostProcess
        }
        worldRenderer.render(frame)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        drawingHelperElements.render(touchedCellX, touchedCellY)
    }

    private fun fillBuffer() {
        cellBuffer.begin()

        cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, color ->
            cellBuffer.putCell(
                x = particleEntity.x[index],
                y = particleEntity.y[index],
                color = color,
                angleCos = angleCos,
                angleSin = angleSin,
                radius = particleEntity.radius[index],
                // В редакторе энергии нет: чёрная точка внутри клетки не рисуется.
                energy = 0f,
                cellType = cellType.toInt(),
                // Ключ шума — индекс частицы, как и в живом мире: он закреплён за клеткой
                // и не зависит от порядка сборки буфера.
                noiseSeed = index
            )
        }

        // Клетка-призрак на месте будущего деления: показывает, куда и под каким углом
        // пойдёт дочерняя, пока игрок настраивает деление.
        val stage = currentTick
        val stageInstructions = editorSimulationSystem.genomeStageInstruction
        if (stage < stageInstructions.size) {
            val genomeStage = stageInstructions[stage]

            genomeStage.cellActions.forEach { (_, action) ->
                val divide = action.divide
                if (divide != null) {
                    val index = editorSimulationSystem.mapCellGenomeIdToIndex[divide.id]

                    val angle = divide.angle ?: 0f

                    cellBuffer.putCell(
                        x = particleEntity.x[index],
                        y = particleEntity.y[index],
                        color = (divide.color ?: Color.WHITE).toIntBits(),
                        angleCos = cos(angle),
                        angleSin = sin(angle),
                        radius = particleEntity.radius[index],
                        energy = 0f,
                        cellType = GHOST_CELL_TYPE,
                        noiseSeed = index
                    )
                }
            }
        }
    }

    private companion object {
        /** Слой в TextureArray, которым рисуется призрак будущей клетки. */
        const val GHOST_CELL_TYPE = 20
    }
}
