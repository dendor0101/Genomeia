package io.github.some_example_name.old.editor.system.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.linkColor
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.previousCtrlClicked
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.SymmetryManager
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.ParticleEntity
import kotlin.math.PI

class DrawingHelperElements(
    val cellReplay: CellReplay,
    val particleEntity: ParticleEntity,
    val symmetryManager: SymmetryManager,
    val cellSearchManager: CellSearchManager,
    val editorSimulationSystem: EditorSimulationSystem
) {

    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera

    fun create(
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        this.shapeRenderer = shapeRenderer
        this.camera = camera
    }

    /**
     * Метки поверх клеток: рамка поля, симметрия, деление и мутация, связь, которую тянут.
     * Нейросвязи, стрелки и лучи рисует шейдер отладочного режима (EditorRenderSystem.fillOverlay).
     */
    fun render(touchedCellX: Float, touchedCellY: Float) {
        shapeRenderer.color = Color.WHITE
        shapeRenderer.projectionMatrix = camera.combined

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.rect(
            0f,
            0f,
            DIGenomeEditorContainer.gridWidth.toFloat(),
            DIGenomeEditorContainer.gridHeight.toFloat()
        )

        symmetryManager.drawSymmetry(shapeRenderer)

        Gdx.gl.glLineWidth(2f)

        if (DIGenomeEditorContainer.grabbedCellIndex != -1) {
            cellSearchManager.getAllCloseNeighboursEditor(
                particleEntity.x(DIGenomeEditorContainer.grabbedCellIndex),
                particleEntity.y(DIGenomeEditorContainer.grabbedCellIndex),
                grabbedRadius = particleEntity.radius[DIGenomeEditorContainer.grabbedCellIndex],
                DIGenomeEditorContainer.grabbedCellIndex
            ).forEach {
                shapeRenderer.color = Color.RED
                shapeRenderer.line(
                    particleEntity.x(DIGenomeEditorContainer.grabbedCellIndex),
                    particleEntity.y(DIGenomeEditorContainer.grabbedCellIndex),
                    particleEntity.x(it),
                    particleEntity.y(it)
                )
            }
        }

        cellReplay.forEachInTick(currentTick) { _, index, cellGenomeId, _, _, color ->
            if (DIGenomeEditorContainer.grabbedCellIndex != index) {
                if (currentTick != lastTick) {
                    val action =
                        editorSimulationSystem.genomeStageInstruction[currentTick].cellActions[cellGenomeId]
                    if (action?.mutate != null) {
                        val colorOfCellFrom = Color().also {
                            val argb = color
                            val rgba = ((argb shr 16) and 0xFF) or (argb and 0xFF00) or ((argb shl 16) and 0xFF0000) or (argb and -0x1000000)
                            Color.argb8888ToColor(it,  rgba)
                        }

                        shapeRenderer.color = Color(1f - colorOfCellFrom.a, 1f - colorOfCellFrom.g, 1f - colorOfCellFrom.b, 1f)
                        val x = particleEntity.x(index)
                        val y = particleEntity.y(index)
                        val radius = 0.125f

                        shapeRenderer.circle(x, y, radius, 32)

                        shapeRenderer.line(
                            x - radius, y,
                            x + radius, y
                        )

                        shapeRenderer.line(
                            x, y - radius,
                            x, y + radius
                        )
                    }

                    if (action?.divide != null) {
                        val colorOfCellFrom = Color().also {
                            val argb = color
                            val rgba = ((argb shr 16) and 0xFF) or (argb and 0xFF00) or ((argb shl 16) and 0xFF0000) or (argb and -0x1000000)
                            Color.argb8888ToColor(it,  rgba)
                        }

                        shapeRenderer.color = Color(1f - colorOfCellFrom.a, 1f - colorOfCellFrom.g, 1f - colorOfCellFrom.b, 1f)

                        val radius = 0.15f

                        val side = radius * PI.toFloat()
                        val halfSide = side / 2f

                        shapeRenderer.rect(
                            particleEntity.x(index) - halfSide,
                            particleEntity.y(index) - halfSide,
                            side,
                            side
                        )
                    }
                }
            }
        }

        if (previousCtrlClicked != -1 && cellReplay.getCellIndex(
                DIGenomeEditorContainer.nextStageTick, previousCtrlClicked) != null) {
            val x = particleEntity.x(previousCtrlClicked)
            val y = particleEntity.y(previousCtrlClicked)

            shapeRenderer.color = Color.CYAN
            shapeRenderer.circle(x, y,  0.125f, 32)

            shapeRenderer.color = linkColor
            shapeRenderer.line(x, y, touchedCellX, touchedCellY)

            val clickedCell = cellSearchManager.getClickedCellIndex(
                clickX = touchedCellX,
                clickY = touchedCellY
            )

            if (clickedCell != null) {
                shapeRenderer.color = Color.CYAN
                val x = particleEntity.x(clickedCell.first)
                val y = particleEntity.y(clickedCell.first)
                shapeRenderer.circle(x, y,  0.125f, 32)
            }
        }
        // Если клетки, от которой тянули связь, на стадии уже нет, — здесь только не
        // рисуем линию. Забывает её EditorLogicSystem после команды: рендер состояние
        // редактора не меняет.

        shapeRenderer.end()
    }

    /**
     * Видна ли связь: у клетки, которую сейчас тащат, связи пропадают, пока она дальше
     * касания от соседа. Общее с отладочным оверлеем редактора (EditorRenderSystem).
     */
    fun isLinkVisible(cellA: Int, cellB: Int): Boolean {
        val grabbed = DIGenomeEditorContainer.grabbedCellIndex
        if (grabbed == -1 || (grabbed != cellA && grabbed != cellB)) return true

        val dx = particleEntity.x(cellB) - particleEntity.x(cellA)
        val dy = particleEntity.y(cellB) - particleEntity.y(cellA)
        val r = particleEntity.radius[cellA] + particleEntity.radius[cellB]
        return dx * dx + dy * dy <= r * r
    }
}
