package io.github.some_example_name.old.editor.system.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.core.utils.drawArrowWithRotationAngle
import io.github.some_example_name.old.core.utils.drawTriangleMiddle
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.linkColor
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.previousCtrlClicked
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.showPhysicalLink
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.entities.LinkReplay
import io.github.some_example_name.old.editor.entities.NeuralLinkReplay
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.SymmetryManager
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import kotlin.math.PI

class DrawingHelperElements(
    val cellReplay: CellReplay,
//    val linkReplay: LinkReplay,
    val neuralLinkReplay: NeuralLinkReplay,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val symmetryManager: SymmetryManager,
    val cellList: List<Cell>,
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
                particleEntity.x[DIGenomeEditorContainer.grabbedCellIndex],
                particleEntity.y[DIGenomeEditorContainer.grabbedCellIndex],
                grabbedRadius = particleEntity.radius[DIGenomeEditorContainer.grabbedCellIndex],
                DIGenomeEditorContainer.grabbedCellIndex
            ).forEach {
                shapeRenderer.color = Color.RED
                shapeRenderer.line(
                    particleEntity.x[DIGenomeEditorContainer.grabbedCellIndex],
                    particleEntity.y[DIGenomeEditorContainer.grabbedCellIndex],
                    particleEntity.x[it],
                    particleEntity.y[it]
                )
            }
        }

//        linkReplay.forEachInTick(DIGenomeEditorContainer.nextStageTick) { links1, links2 ->
//            drawLinks(false, false, 0, links1, links2)
//        }

        neuralLinkReplay.forEachInTick(DIGenomeEditorContainer.nextStageTick) { isLink1NeuralDirected, color, links1, links2 ->
            drawLinks(true, isLink1NeuralDirected, color, links1, links2)
        }

        cellReplay.forEachInTick(currentTick) { cellType, index, cellGenomeId, angleCos, angleSin, color ->
            if (DIGenomeEditorContainer.grabbedCellIndex != index) {
                val cell = cellList[cellType.toInt()]

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
                        val x = particleEntity.x[index]
                        val y = particleEntity.y[index]
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
                            particleEntity.x[index] - halfSide,
                            particleEntity.y[index] - halfSide,
                            side,
                            side
                        )
                    }
                }

                when {
                    cell is Eye -> {
                        shapeRenderer.color = Color.CYAN
                        shapeRenderer.drawArrowWithRotationAngle(
                            startX = particleEntity.x[index],
                            startY = particleEntity.y[index],
                            angleCos = angleCos,
                            angleSin = angleSin,
                            length = cellEntity.specialEntity.getVisibilityRange(index),
                            isDrawWithoutTriangle = true,
                        )
                    }

                    cell.isDirected -> {
                        shapeRenderer.color = Color.CYAN
                        shapeRenderer.drawArrowWithRotationAngle(
                            startX = particleEntity.x[index],
                            startY = particleEntity.y[index],
                            angleCos = angleCos,
                            angleSin = angleSin,
                            length = 0.375f
                        )
                    }
                }
            }
        }

        if (previousCtrlClicked != -1 && cellReplay.getCellIndex(
                DIGenomeEditorContainer.nextStageTick, previousCtrlClicked) != null) {
            val x = particleEntity.x[previousCtrlClicked]
            val y = particleEntity.y[previousCtrlClicked]

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
                val x = particleEntity.x[clickedCell.first]
                val y = particleEntity.y[clickedCell.first]
                shapeRenderer.circle(x, y,  0.125f, 32)
            }
        } else {
            previousCtrlClicked = -1
        }

        shapeRenderer.end()
    }

    fun drawLinks(
        isNeural: Boolean,
        isLink1NeuralDirected: Boolean,
        color: Int,
        cellA: Int,
        cellB: Int
    ) {

        var isDrawLinkByDistance = true
        if (DIGenomeEditorContainer.grabbedCellIndex != -1) {
            if (DIGenomeEditorContainer.grabbedCellIndex == cellA || DIGenomeEditorContainer.grabbedCellIndex == cellB) {
                val dx = particleEntity.x[cellB] - particleEntity.x[cellA]
                val dy = particleEntity.y[cellB] - particleEntity.y[cellA]

                val radiusA = particleEntity.radius[cellA]
                val radiusB = particleEntity.radius[cellB]

                val r = radiusA + radiusB

                if (dx * dx + dy * dy > r * r) {
                    isDrawLinkByDistance = false
                }
            }
        }

        if (isDrawLinkByDistance) {
            if (isNeural) {
                val colorOfLink = Color().also {
                    val argb = color
                    val rgba = ((argb shr 16) and 0xFF) or (argb and 0xFF00) or ((argb shl 16) and 0xFF0000) or (argb and -0x1000000)
                    Color.argb8888ToColor(it,  rgba)
                }
                shapeRenderer.color = colorOfLink
                if (isLink1NeuralDirected) {
                    shapeRenderer.drawTriangleMiddle(
                        particleEntity.x[cellB],
                        particleEntity.y[cellB],
                        particleEntity.x[cellA],
                        particleEntity.y[cellA],
                        0.1f
                    )
                } else {
                    shapeRenderer.drawTriangleMiddle(
                        particleEntity.x[cellA],
                        particleEntity.y[cellA],
                        particleEntity.x[cellB],
                        particleEntity.y[cellB],
                        0.1f
                    )
                }
            } else {
                shapeRenderer.color = Color.RED
            }

            if (showPhysicalLink || isNeural) {
                shapeRenderer.line(
                    particleEntity.x[cellB],
                    particleEntity.y[cellB],
                    particleEntity.x[cellA],
                    particleEntity.y[cellA]
                )
            }
        }
    }
}
