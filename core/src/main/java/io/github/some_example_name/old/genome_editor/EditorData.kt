package io.github.some_example_name.old.genome_editor

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.good_one.utils.primitive_hash_map.UnorderedIntPairMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap


data class EditorCell(
    val id: Int,
    val index: Int,
    val parentIndex: Int,
    val parentId: Int,
    var x: Float,
    var y: Float,
    val radius: Float,
    val energy: Float,
    val angle: Float,
    val color: Color,
    val isJustAdded: Boolean,
    val gridId: Int,
    val divide: Action?,
    val mutate: Action?,
    val cellProperty: Action?
)

data class SpecialCell(
    val x: Float,
    val y: Float,
    val cellType: Int,
    val angle: Float,
    val length: Float
)

fun toEditorSpecialCell(replayNextStage: FullReplayStructure): List<SpecialCell> {
    val specialCells = mutableListOf<SpecialCell>()

    for (index in 0 .. replayNextStage.cellLastId) {
        when (replayNextStage.cellType[index]) {
            3, 6, 9, 14, 15, 19, 21 -> {
                specialCells.add(
                    SpecialCell(
                        x = replayNextStage.x[index],
                        y = replayNextStage.y[index],
                        cellType = replayNextStage.cellType[index],
                        angle = replayNextStage.angle[index] + replayNextStage.angleDiff[index],
                        length = if (replayNextStage.cellType[index] == 14) replayNextStage.visibilityRange[index] else 30f
                    )
                )
            }
        }
    }
    return specialCells
}

fun toEditorCells(
    replayTick: GenomeStageReplayStructure,
    genomeStage: GenomeStage?,
    gridCellWidthSize: Int,
    gridCellHeightSize: Int,
    isFast: Boolean, //TODO подумать нужон ли isFast и как его лучше сделать
    replayStage: FullReplayStructure,
    replayNextStage: FullReplayStructure
): List<EditorCell> {
    val editorCells = mutableListOf<EditorCell>()
    val parentMap = Int2IntOpenHashMap(10)

    for (index in 0 .. replayNextStage.cellLastId) {
        var isJustAdded = false
        var parentIndex = -1
        var parentId = -1
        val action = if (index < replayTick.cellAmount) {
            genomeStage?.cellActions?.get(replayNextStage.id[index]).also {
                it?.divide?.id?.let { actionId -> parentMap.put(actionId, index) }
            }
        } else {
            parentIndex = parentMap.get(replayNextStage.id[index])
            parentId = replayNextStage.parentId[index]
            isJustAdded = true
            genomeStage?.cellActions?.get(replayNextStage.parentId[index])
        }

        val editorCell = EditorCell(
            id = replayNextStage.id[index],
            index = index,
            parentIndex = parentIndex,
            parentId = parentId,
            x = replayNextStage.x[index],
            y = replayNextStage.y[index],
            radius = 20f,
            energy = if (index < replayTick.cellAmount) replayTick.energy[index] else 0f,
            angle = replayNextStage.angle[index],
            color = Color(replayNextStage.colorR[index], replayNextStage.colorG[index], replayNextStage.colorB[index], 1f),
            isJustAdded = isJustAdded,
            gridId = replayNextStage.gridId[index],
            divide = action?.divide,
            mutate = action?.mutate,
            cellProperty = if (index < replayTick.cellAmount && index < replayStage.cellLastId + 1/*TODO не помню для чего сделал index приходил == replayStage.cellLastId + 1*/) {
                Action(
                    cellType = replayStage.cellType[index],
                    funActivation = replayStage.activationFuncType[index],
                    color = Color(
                        replayStage.colorR[index],
                        replayStage.colorG[index],
                        replayStage.colorB[index],
                        1f
                    ),
                    a = replayStage.a[index],
                    b = replayStage.b[index],
                    c = replayStage.c[index],
                    isSum = replayStage.isSum[index],
                    colorRecognition = replayStage.colorDifferentiation[index],
                    lengthDirected = replayStage.visibilityRange[index],
                    angleDirected = replayStage.angleDiff[index]
                )
            } else null
        )
/*

        if (action?.divide != null && action.divide?.angle != null) {
            val divide = action.divide!!
            val genomeAngle = divide.angle!!
            val divideAngle = genomeAngle + angle[index]
            val parentLinkLength = divide.physicalLink[id[index]]?.length ?: 30f
            val x = x[index] + MathUtils.cos(divideAngle) * parentLinkLength
            val y = y[index] + MathUtils.sin(divideAngle) * parentLinkLength

            val xGrid = (x / CELL_SIZE).toInt()
            val yGrid = (y / CELL_SIZE).toInt()
            if (xGrid < 0 || xGrid >= gridCellWidthSize || yGrid < 0 || yGrid >= gridCellHeightSize) throw Exception()
            val gridId = yGrid * gridCellWidthSize + xGrid

            val justAddedEditorCell = EditorCell(
                id = divide.id,
                index = cellAmount + justAddedEditorCells.size,
                parentIndex = index,
                parentId = id[index],
                x = x,
                y = y,
                radius = 20f,
                energy = 0f,
                angle = 0f,
                color = divide.color ?: Color.WHITE,
                isJustAdded = true,
                gridId = gridId,
                divide = action.divide,
                mutate = null,
                cellProperty = null
            )
            justAddedEditorCells.add(
                justAddedEditorCell
            )
        }
*/

        editorCells.add(editorCell)
    }
//    editorCells.addAll(justAddedEditorCells)

    return editorCells
}

data class EditorLinks(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val isJustAdded: Boolean,
    val isNeuralTo2: Boolean?,
    val parentId: Int
//    val neuralDirected: Int?
)

fun toEditorLinks(
    replayTick: FullReplayStructure,
    linksPairId: UnorderedIntPairMap
): List<EditorLinks> {
    val editorLinks = mutableListOf<EditorLinks>()
    for (index in 0 .. replayTick.linksLastId) {
        val isNeuralTo2 = if (replayTick.isNeuronLink[index]) {
            val indexDirected = replayTick.directedNeuronLink[index]
            when (indexDirected) {
                replayTick.id[replayTick.links1[index]] -> false
                replayTick.id[replayTick.links2[index]] -> true
                else -> null
            }
        } else null

        linksPairId.put(replayTick.id[replayTick.links1[index]], replayTick.id[replayTick.links2[index]], index)

        editorLinks.add(
            EditorLinks(
                x1 = replayTick.x[replayTick.links1[index]],
                y1 = replayTick.y[replayTick.links1[index]],
                x2 = replayTick.x[replayTick.links2[index]],
                y2 = replayTick.y[replayTick.links2[index]],
                isJustAdded = false,
                isNeuralTo2 = isNeuralTo2,
                parentId = replayTick.id[replayTick.links2[index]]
//                neuralDirected = if (isNeuralTo2 != null) replayTick.id[replayTick.directedNeuronLink[index]] else null
            )
        )
    }
    return editorLinks
}
