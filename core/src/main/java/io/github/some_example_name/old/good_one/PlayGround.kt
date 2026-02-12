package io.github.some_example_name.old.good_one

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.screens.GlobalSettings.UI_SCALE
import io.github.some_example_name.old.good_one.utils.drawArrowWithRotationAngle
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.controllerIndexesLol
import io.github.some_example_name.old.world_logic.cells.keyStates
import io.github.some_example_name.old.world_logic.cells.previousKeyStates
import io.github.some_example_name.old.world_logic.ups
import kotlin.math.round

class PlayGround(
//    private val genomeEditor: GenomeEditorRefactored,
    private val spriteBatch: SpriteBatch,
    private val font: BitmapFont,
    private val cellManager: CellManager
) {

    var zoomOffsetX = 0f
    var zoomOffsetY = 0f
    var isDragged = false

    var drawRays = false

    fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val scale = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val worldX = (screenX / scale) + cellManager.zoomManager.screenOffsetX
        val worldY =
            ((Gdx.graphics.height - screenY) / scale) + cellManager.zoomManager.screenOffsetY // Инверсия Y
        return worldX to worldY
    }

    fun screenToWorldPC(screenX: Float, screenY: Float): Pair<Float, Float> {
        val worldX =
            (screenX / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)) + cellManager.zoomManager.screenOffsetX
        val worldY =
            (screenY / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)) + cellManager.zoomManager.screenOffsetY
        return worldX to worldY
    }

    fun handlePlay() {

        for (i in 0 until 9) {
            val key = Input.Keys.NUM_1 + i
            val isPressed = Gdx.input.isKeyPressed(key)

            if (isPressed && !previousKeyStates[i]) {
                val entry = controllerIndexesLol.entries.elementAtOrNull(i)
                if (entry != null) {
                    controllerIndexesLol[entry.key] = true
                }
                keyStates[i] = true // Устанавливаем в true, когда клавиша нажата
            }
            if (!isPressed && previousKeyStates[i]) {
                val entry = controllerIndexesLol.entries.elementAtOrNull(i)
                if (entry != null) {
                    controllerIndexesLol[entry.key] = false
                }
                keyStates[i] = false // Устанавливаем в false, когда клавиша отпущена
            }

            // Обновляем предыдущее состояние
            previousKeyStates[i] = isPressed
        }

//        if (editButtonClicked && isEditPossible) {
//            editButtonClicked = false
//            if (cellManager.cellLastId != -1) {
//                genomeEditor.startEditing()
//                isPlay = false
//            }
//        }
        val updateTimeRounded = round(1e5f/ups) /100f
        if (cellManager.grabbedCell != -1) {
            spriteBatch.begin()
            font.data.setScale(UI_SCALE)
            font.draw(
                spriteBatch,
                "FPS: ${Gdx.graphics.framesPerSecond}\n" + "UPS: ${ups}\n" + "Update Time: ${updateTimeRounded}ms\n" + "Cells: ${cellManager.cellLastId + 1 - 1535} Links ${cellManager.linksLastId + 1}\n" + "NeuronImpulseInput ${cellManager.neuronImpulseInput[cellManager.grabbedCell]}\n" + "NeuronImpulseOutput ${cellManager.neuronImpulseOutput[cellManager.grabbedCell]}\n",
                30f,
                120f
            )
            font.data.setScale(1f)
            spriteBatch.end()
        } else {
            spriteBatch.begin()
            font.data.setScale(UI_SCALE)
            font.draw(
                spriteBatch,
                "FPS: ${Gdx.graphics.framesPerSecond}\n"  + "UPS: ${ups}\n" + "Update Time: ${updateTimeRounded}ms\n" + "Cells: ${cellManager.cellLastId + 1 - 1535} Links ${cellManager.linksLastId + 1}\n",
                30f,
                120f
            )
            font.data.setScale(1f)
            spriteBatch.end()
        }
    }

    fun update(renderer: ShapeRenderer) {
        val xOffset = cellManager.zoomManager.screenOffsetX
        val yOffset = cellManager.zoomManager.screenOffsetY
        val zoom = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val cameraEndX = cellManager.zoomManager.screenOffsetX + Gdx.graphics.width / zoom
        val cameraEndY = cellManager.zoomManager.screenOffsetY + Gdx.graphics.height / zoom


        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(1.0f, 1f, 1f, 1f)

        for (i in 0..<cellManager.subManager.cellLastId + 1) {
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
        Gdx.gl.glLineWidth(2f)


        renderer.begin(ShapeRenderer.ShapeType.Line)

        if (drawRays) {
            for (i in 0..<cellManager.specialCellsId) {
                val cellId = cellManager.drawSpecialCells[i]
                when (cellManager.cellType[cellId]) {
                    6 -> {
                        renderer.color = Color.CYAN
                        renderer.circle(
                            (cellManager.x[cellId] - xOffset) * zoom,
                            (cellManager.y[cellId] - yOffset) * zoom,
                            150f * zoom
                        )
                    }

                    14 -> {
                        renderer.color = getColorFromBits(cellManager.colorDifferentiation[cellId])
                        renderer.drawArrowWithRotationAngle(
                            startX = (cellManager.x[cellId] - xOffset) * zoom,
                            startY = (cellManager.y[cellId] - yOffset) * zoom,
                            baseAngle = cellManager.angle[cellId] + cellManager.angleDiff[cellId],
                            length = cellManager.visibilityRange[cellId] * zoom,
                            isDrawWithoutTriangle = true,
                        )
                    }

                    3, 9, 15, 21 -> {
                        renderer.color = Color.CYAN
                        renderer.drawArrowWithRotationAngle(
                            startX = (cellManager.x[cellId] - xOffset) * zoom,
                            startY = (cellManager.y[cellId] - yOffset) * zoom,
                            baseAngle = cellManager.angle[cellId] + cellManager.angleDiff[cellId],
                            length = 30f * zoom
                        )
                    }
                    else -> renderer.color = Color.CYAN
                }
            }
        }
        renderer.end()
    }

}

fun getColorFromBits(bits: Int): Color {
    if (bits == 0) return Color.BLACK.cpy()

    var r = 0f
    var g = 0f
    var b = 0f
    var count = 0

    if (bits and 1 != 0) {
        r += 1f
        count++
    }
    if (bits and 2 != 0) {
        g += 1f
        count++
    }
    if (bits and 4 != 0) {
        b += 1f
        count++
    }

    return Color(r / count, g / count, b / count, 1f)
}

fun encodeColorToBits(r: Float, g: Float, b: Float): Int {
    var bits = 0
    if (r == 1f) bits = bits or 1
    if (g == 1f) bits = bits or 2
    if (b == 1f) bits = bits or 4
    return bits
}
