package io.github.some_example_name.old.good_one

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.editor.GenomeEditorRefactored
import io.github.some_example_name.old.good_one.utils.drawArrowWithRotation
import io.github.some_example_name.old.good_one.utils.getMouseCoord
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_WIDTH
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_WIDTH
import io.github.some_example_name.old.logic.ThreadManager.Companion.CHUNK_SIZE_Y
import io.github.some_example_name.old.logic.ThreadManager.Companion.TOTAL_CHUNKS
import io.github.some_example_name.old.logic.isPlay
import kotlin.math.roundToInt

class PlayGround(
    private val stage: Stage,
    private val camera: OrthographicCamera,
    private val skin: Skin,
    private val genomeEditor: GenomeEditorRefactored,
    private val spriteBatch: SpriteBatch,
    private val font: BitmapFont,
    private val cellManager: CellManager
) {

    var zoomOffsetX = 0f
    var zoomOffsetY = 0f
    var isDragged = false

    fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val scale = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val worldX = (screenX / scale) + cellManager.zoomManager.screenOffsetX
        val worldY = ((Gdx.graphics.height - screenY) / scale) + cellManager.zoomManager.screenOffsetY // Инверсия Y
        return worldX to worldY
    }

    fun handlePlay() {

//        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
//            val (mouseX, mouseY) = getMouseCoord()
//            val (worldX, worldY) = screenToWorld(mouseX, mouseY)
//            isDragged = !cellManager.grabbed(worldX, worldY)
//            if (isDragged) {
//                zoomOffsetX = mouseX
//                zoomOffsetY = mouseY
//            }
//        }
//
//        if (cellManager.grabbedCell != -1) {
//            isDragged = false
//            if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && cellManager.grabbedCell != -1) {
//                val (mouseX, mouseY) = getMouseCoord()
//                val (worldX, worldY) = screenToWorld(mouseX, mouseY)
//                cellManager.moveTo(worldX, worldY)
//            } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
//                cellManager.grabbedCell = -1
//            }
//        } else if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isDragged) {
//            val (mouseX, mouseY) = getMouseCoord()
//            cellManager.zoomManager.screenOffsetX += (zoomOffsetX - mouseX) / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
//            cellManager.zoomManager.screenOffsetY += (zoomOffsetY - mouseY) / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
//            zoomOffsetX = mouseX
//            zoomOffsetY = mouseY
//        }


        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            val (mouseX, mouseY) = getMouseCoord()
            val (worldX, worldY) = screenToWorld(mouseX, mouseY)
            cellManager.deleteCell(worldX, worldY)
        }

        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            val (mouseX, mouseY) = getMouseCoord()
            val (worldX, worldY) = screenToWorld(mouseX, mouseY)
            cellManager.deleteCell(worldX, worldY)
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (cellManager.cellLastId != -1) {
                genomeEditor.startEditing()
                isPlay = false
            }
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.I)) {
            cellManager.showInfo()
        }

        if (cellManager.grabbedCell != -1) {
            spriteBatch.begin()
            font.draw(
                spriteBatch, "FPS: ${Gdx.graphics.framesPerSecond}\n" +
                    "NeuronImpulse ${cellManager.neuronImpulseImport[cellManager.grabbedCell]}\n", 30f, Gdx.graphics.height - 30f
            )
            spriteBatch.end()
        } else {
//            println("lol ${Gdx.graphics.framesPerSecond}")
//            val (mouseX, mouseY) = getMouseCoord()
//            val (worldX, worldY) = screenToWorld(mouseX, mouseY)
            spriteBatch.begin()
            font.draw(spriteBatch, "FPS: ${Gdx.graphics.framesPerSecond}\n", 30f, Gdx.graphics.height - 30f)
//                "mouseX $mouseX; mouseY $mouseY\n" +
//                "worldMouseX $worldX; worldMouseY $worldY", 30f, Gdx.graphics.height - 30f)
            spriteBatch.end()
        }


    }

    class ChangeUnits(
        val deleteCells: MutableList<Cell> = mutableListOf(),
        val addCells: MutableList<Cell> = mutableListOf(),
        val deleteLinks: MutableList<Link> = mutableListOf(),
        val addLinks: MutableList<Link> = mutableListOf(),
        val deleteNeronLinks: MutableList<Link> = mutableListOf(),
        val addNeronLinks: MutableList<Link> = mutableListOf()
    )

    private val chunkSize: Float = CHUNK_SIZE_Y * CELL_SIZE


    fun update(renderer: ShapeRenderer) {
        val xOffset = cellManager.zoomManager.screenOffsetX
        val yOffset = cellManager.zoomManager.screenOffsetY
        val zoom = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val cameraEndX = cellManager.zoomManager.screenOffsetX + Gdx.graphics.width / zoom
        val cameraEndY = cellManager.zoomManager.screenOffsetY + Gdx.graphics.height / zoom


        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(1.0f, 1f, 1f, 1f)
//        println("--------------------")
        for (i in 0..<cellManager.subManager.cellLastId + 1) {
//            println("${cellManager.subManager.x[i]} ${cellManager.subManager.y[i]}")
            if (xOffset > cellManager.subManager.x[i]) continue
            if (yOffset > cellManager.subManager.y[i]) continue
            if (cameraEndX < cellManager.subManager.x[i]) continue
            if (cameraEndY < cellManager.subManager.y[i]) continue
            if (cellManager.subManager.x[i] > 0 && cellManager.subManager.y[i] > 0) {
                renderer.circle(
                    (cellManager.subManager.x[i] - xOffset) * zoom,
                    (cellManager.subManager.y[i] - yOffset) * zoom,
                    cellManager.subManager.radius[i] * zoom
                )
            }
        }
        renderer.end()


        renderer.begin(ShapeRenderer.ShapeType.Line)



//        renderer.color = Color(0.0546f, 0.101f, 0.0664f, 1f)
//
//        for (i in 0..WORLD_CELL_WIDTH) {
//            renderer.line((i * CELL_SIZE - xOffset) * zoom, (0f - yOffset) * zoom, (i * CELL_SIZE - xOffset) * zoom, (WORLD_HEIGHT - yOffset) * zoom)
//        }
//        for (i in 0..WORLD_CELL_HEIGHT) {
//            renderer.line((0f - xOffset) * zoom, (i * CELL_SIZE - yOffset) * zoom, (WORLD_WIDTH - xOffset) * zoom, (i * CELL_SIZE - yOffset) * zoom)
//        }

//        renderer.color = Color(0.15f, 0f, 0f, 1f)
//        for (i in 1 until TOTAL_CHUNKS) {
//            val y = i * chunkSize - yOffset
//            renderer.line((0f - xOffset) * zoom, y * zoom, (WORLD_WIDTH - xOffset) * zoom, y * zoom)
//        }

        renderer.color = Color.CYAN
        renderer.line((0f - xOffset) * zoom, (0 - yOffset) * zoom, (WORLD_WIDTH - xOffset) * zoom, (0 - yOffset) * zoom)
        renderer.line((0f - xOffset) * zoom, (0 - yOffset) * zoom, (0f - xOffset) * zoom, (WORLD_HEIGHT - yOffset) * zoom)
        renderer.line((WORLD_WIDTH - xOffset) * zoom, (0 - yOffset) * zoom, (WORLD_WIDTH - xOffset) * zoom, (WORLD_HEIGHT - yOffset) * zoom)
        renderer.line((0f - xOffset) * zoom, (WORLD_HEIGHT - yOffset) * zoom, (WORLD_WIDTH - xOffset) * zoom, (WORLD_HEIGHT - yOffset) * zoom)

        renderer.end()



        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = Color.CYAN
        for (i in 0..<cellManager.specialCellsId) {
            val cellId = cellManager.drawSpecialCells[i]
            when (cellManager.cellType[cellId]) {
                6 -> {
                    renderer.circle((cellManager.x[cellId] - xOffset) * zoom, (cellManager.y[cellId] - yOffset) * zoom, 75f * zoom)
                }

                14 -> {
                    val index = cellManager.startAngleId[cellId]
                    if (index != -1) {
                        renderer.drawArrowWithRotation(
                            startX = (cellManager.x[cellId] - xOffset) * zoom,
                            startY = (cellManager.y[cellId] - yOffset) * zoom,
                            targetX = (cellManager.x[index] - xOffset) * zoom,
                            targetY = (cellManager.y[index] - yOffset) * zoom,
                            angle = cellManager.angle[cellId],
                            length = 170f * zoom,
                            isDrawWithoutTriangle = true
                        )
                    }
                }

                3 -> {
                    val index = cellManager.startAngleId[cellId]
                    if (index != -1) {
                        renderer.drawArrowWithRotation(
                            startX = (cellManager.x[cellId] - xOffset) * zoom,
                            startY = (cellManager.y[cellId] - yOffset) * zoom,
                            targetX = (cellManager.x[index] - xOffset) * zoom,
                            targetY = (cellManager.y[index] - yOffset) * zoom,
                            angle = cellManager.angle[cellId],
                            length = 30f * zoom
                        )
                    }
                }
            }
        }
        renderer.end()
    }
}
