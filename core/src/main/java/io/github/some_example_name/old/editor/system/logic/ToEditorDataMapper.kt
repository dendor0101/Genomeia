package io.github.some_example_name.old.editor.system.logic

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.EyeData
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.editor.entities.NeuralReplay
import io.github.some_example_name.old.editor.entities.SpecialModDataReplay
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlin.math.atan2

class ToEditorDataMapper(
    val cellEntity: CellEntity,
    val cellReplay: CellReplay,
    val editorSimulationSystem: EditorSimulationSystem,
    val particleEntity: ParticleEntity,
    val neuralReplay: NeuralReplay,
    val specialModDataReplay: SpecialModDataReplay
) {

    fun mapToEditorData(index: Int): EditorCell {
        val id = cellEntity.cellGenomeId[index]
        val currentCellIndex = cellReplay.getCellIndex(currentTick, index)
        val isPhantom = currentCellIndex == null
        val parentIndex = if (index != 0) { cellEntity.parentIndex[index] } else -1
        val parentId = if (index != 0) { cellEntity.cellGenomeId[parentIndex] } else -1
        val action = if (currentTick != lastTick) {
            editorSimulationSystem.genomeStageInstruction[currentTick]
                .cellActions[if (isPhantom) parentId else id]
        } else null

        val angleToParent = if (index != 0) {
            val dx = particleEntity.x[index] - particleEntity.x[parentIndex]
            val dy = particleEntity.y[index] - particleEntity.y[parentIndex]

            val len = 1f / invSqrt(dx * dx + dy * dy)
            val toChildCos = dx / len
            val toChildSin = dy / len

            atan2(toChildSin, toChildCos)
        } else 0f

        val actual = if (!isPhantom) {
            val neuralIndex = cellReplay.getNeuralIndexes(currentTick, index)
            val specialIndex = cellReplay.getSpecialTypeIndexes(currentTick, index)
            val colorOfCellFrom = Color().also {
                val argb = cellReplay.getColor(currentTick, index)
                val rgba = ((argb shr 16) and 0xFF) or (argb and 0xFF00) or ((argb shl 16) and 0xFF0000) or (argb and -0x1000000)
                Color.argb8888ToColor(it, rgba)
            }
            val cell = cellEntity.cellList[cellReplay.getCellType(currentTick, index).toInt()]
            val special = specialModDataReplay.get(currentTick, specialIndex)
            val eye = special as? EyeData
            Action(
                color = colorOfCellFrom,
                cellType = cell.key,
                angleDirected = 0f,
                funActivation = neuralReplay.getActivationFuncType(currentTick, neuralIndex)?.let { Formulas.all()[it.toInt()].key },
                a = neuralReplay.getA(currentTick, neuralIndex),
                b = neuralReplay.getB(currentTick, neuralIndex),
                c = neuralReplay.getC(currentTick, neuralIndex),
                isSum = neuralReplay.getIsSum(currentTick, neuralIndex),
                colorRecognition = eye?.colorRecognition,
                lengthDirected = eye?.visibilityRange,
                pheromoneType = cellReplay.getPheromone(currentTick, index),
                // В Action.special — только то, что игрок задаёт в геноме (клавиша контроллера,
                // параметры задержки): состояние хвоста, продюсера или буфер задержки в геном
                // не попадают, см. Cell.genomeDataOf.
                special = cell.genomeDataOf(special)
            )
        } else {
            null
        }

        return EditorCell(
            id = id,
            index = index,
            parentIndex = parentIndex,
            parentId = parentId,
            x = particleEntity.x[index],
            y = particleEntity.y[index],
            radius = particleEntity.radius[index],
            isPhantom = isPhantom,
            angleToParent = angleToParent,
            angleDirected = if (!isPhantom) {
                val sin = cellReplay.getAngleSin(currentTick, index)
                val cos = cellReplay.getAngleCos(currentTick, index)
                atan2(sin, cos) - angleToParent
            } else null,
            divide = action?.divide,
            mutate = action?.mutate,
            actual = actual
        )
    }
}
