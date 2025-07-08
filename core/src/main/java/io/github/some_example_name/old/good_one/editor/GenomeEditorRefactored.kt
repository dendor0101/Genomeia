package io.github.some_example_name.old.good_one.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import io.github.some_example_name.old.good_one.*
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.ui.*
import io.github.some_example_name.old.good_one.utils.*
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.WALL_AMOUNT
import io.github.some_example_name.old.logic.genomeUpdate

class GenomeEditorRefactored(
    private val stage: Stage,
    private val skin: Skin,
    private val uiProcessor: UiProcessor,
    private val cellManager: CellManager
) {
    private var selectedCell: Pair<String, CellCopy>? = null
    private var isLinking = false
    private var isLinkingIsPhysical = true
    private var grabbedCell: CellCopy? = null
    private var deltaGrabbed = Vector2(0f, 0f)

    var zoomOffsetX = 0f
    var zoomOffsetY = 0f
    var isDragged = false

    val cellsCopy = hashMapOf<String, CellCopy>()
    private val cellsCopyOriginal = hashMapOf<String, CellCopy>()
    var indexCounter = -1

    private fun updateUiByState() {
        if (uiProcessor.uiState is Play) {
            stage.clear()
        } else {
            drawDialog(
                stage = stage,
                skin = skin,
                uiState = uiProcessor.uiState,
                indexCounter = indexCounter - WALL_AMOUNT,
                clickDivide = { divide ->
                    divide(divide)
                },
                clickMutate = { mutate ->
                    selectedCell?.let {
                        mutate(mutate, it.first)
                    }
                },
                addedChanges = { change ->
                    selectedCell?.let {
                        changeAdded(change, it.first)
                    }
                },
                onRotate = { isDivide, value ->
                    if (isDivide) {
                        selectedCell?.second?.addedCellSettings?.angle = value
                    } else {
                        selectedCell?.second?.apply {
                            angleDirected = value
                        }
                    }
                }
            )
        }
    }

    private fun divide(result: UiResult) {
        if (result.id == null || result.cellType == null) return
        selectedCell?.let { selectedCell ->
            val offset = findLocationForNewCell(cellsCopy, selectedCell.second)
            val key = cellsCopy.generateUniqueId(result.id!!)
            indexCounter++
            val addedCell = CellCopy(
                x = offset.first,
                y = offset.second,
                index = indexCounter - WALL_AMOUNT,
                cellTypeId = result.cellType!!,
                angleDirected = result.angle,
                startDirectionId = selectedCell.first,
                lengthDirected = if (result.cellType == 14) 170f else null,
                activationFuncType = result.funActivation,
                a = result.a,
                b = result.b,
                c = result.c,
                colorCore = addCell(result.cellType!!).colorCore,
                isAdded = true,
                isSelected = true,
                parentCellId = selectedCell.first,
                physicalLink = hashMapOf(
                    selectedCell.first to LinkDataCopy(
                        connectId = selectedCell.second.index,
                        isNeuronal = false,
                        directedNeuronLink = null
                    )
                ),
                isAlreadyDividedInGenomeStage = true,
                addedCellSettings = null
            )
            cellsCopy[key] = addedCell
            selectedCell.second.isAlreadyDividedInGenomeStage = true
            selectedCell.second.childCellId = key
            selectedCell.second.isSelected = false
            val newCellPair = key to addedCell
            this.selectedCell = newCellPair
            uiProcessor.uiState = Pause.Selected(newCellPair)
            updateUiByState()
        }
    }

    private fun mutate(change: UiResult, id: String) {
        cellsCopy[id]?.also { selected ->
            change.a?.let { selected.a = it }
            change.b?.let { selected.b = it }
            change.c?.let { selected.c = it }
            change.funActivation?.let { selected.activationFuncType = it }
            if (selected.angleDirected == null && addCell(selected.cellTypeId) is Directed) {
                selected.angleDirected = 0f
            }
            change.angle?.let {
                selected.angleDirected = it
            }
            change.cellType?.let {
                when (it) {
                    3, 9, 14, 15 -> {
                        selected.startDirectionId = selected.physicalLink.keys.firstOrNull()
                    }

                    else -> selected.angleDirected = null
                }
                selected.lengthDirected = if (it == 14) 170f else null
                selected.cellTypeId = it
                selected.colorCore = addCell(it).colorCore
                updateUiByState()
            }
        }
    }

    private fun changeAdded(change: UiResult, id: String) {
        cellsCopy[id]?.addedCellSettings?.also { selected ->
            change.a?.let { selected.a = it }
            change.b?.let { selected.b = it }
            change.c?.let { selected.c = it }
            change.funActivation?.let { selected.funActivation = it }
            change.angle?.let { selected.angle = it }
            change.id?.let { selected.id = it }
            change.cellType?.let {
                selected.cellType = it
                updateUiByState()
            }
        }
    }

    fun startEditing() {
        indexCounter = cellManager.cellLastId
        toCopyCell(cellsCopy)
        cellsCopyOriginal.clear()
        toCopyCell(cellsCopyOriginal)
        uiProcessor.uiState = Pause.Unselected
        updateUiByState()
    }

    private fun toCopyCell(cellsCopy: HashMap<String, CellCopy>) {
        for (i in WALL_AMOUNT..<cellManager.cellLastId+1) {
//            if (cellManager.cellType[i] == 16) continue
            val physicalLink = hashMapOf<String, LinkDataCopy>()
            //TODO брутфорсом очень долго, нужно брать клетки
            for (l in 0..cellManager.linksLastId) {
                if (cellManager.links1[l] == i) {
                    physicalLink[cellManager.id[cellManager.links2[l]]] = LinkDataCopy(
                        connectId = cellManager.links2[l],
                        isNeuronal = cellManager.isNeuronLink[l] && cellManager.directedNeuronLink[l] != -1,
                        directedNeuronLink = cellManager.directedNeuronLink[l] - WALL_AMOUNT
                    )
                }
            }
            val cellType = cellManager.cellType[i]
            val isDirected = when (cellType) {
                3, 9, 14, 15 -> true
                else -> false
            }
            val isNeural = when (cellType) {
                3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15 -> true
                else -> false
            }
            cellsCopy[cellManager.id[i]] = CellCopy(
                x = cellManager.x[i],
                y = cellManager.y[i],
                index = i - WALL_AMOUNT,
                cellTypeId = cellManager.cellType[i],
                colorCore = Color(cellManager.colorR[i], cellManager.colorG[i], cellManager.colorB[i], 1f),
                isSelected = false,
                isAdded = false,
                isAlreadyDividedInGenomeStage = false,
                parentCellId = null,
                physicalLink = physicalLink,
                angleDirected = if (isDirected) cellManager.angle[i] else null,
                startDirectionId = if (isDirected && cellManager.startAngleId[i] != -1)
                    cellManager.id[cellManager.startAngleId[i]] else null,
                lengthDirected = if (cellManager.cellType[i] == 14) 170f else null,
                activationFuncType = if (isNeural) cellManager.activationFuncType[i] else null,
                a = if (isNeural) cellManager.a[i] else null,
                b = if (isNeural) cellManager.b[i] else null,
                c = if (isNeural) cellManager.c[i] else null,
                linkToOriginalCell = null,
                debugCurrentNeuronImpulseImport = cellManager.neuronImpulseImport[i],
                addedCellSettings = UiResult(
                    cellType = 0,
                    angle = 0f,
                    funActivation = 0,
                    a = 1f,
                    b = 0f,
                    c = 0f
                )
            )
        }
    }

    fun finishEditing() {
        val changes = findChanges(cellsCopyOriginal, cellsCopy)
        if (changes.cellActions.isNotEmpty()) {
            for (i in 0..cellManager.cellLastId) {
                cellManager.isDividedInThisStage[i] = false
                cellManager.isMutateInThisStage[i] = false
            }
            genomeStage++
            genomeStageInstruction.add(GenomeStage())
            genomeStageInstruction[genomeStage - 1] = changes
            cellManager.genomeUpdate()
        }
        cellsCopy.clear()
        cellsCopyOriginal.clear()
        indexCounter = -1
        uiProcessor.uiState = Play
        updateUiByState()
    }

    private fun findChanges(
        cellsCopyInit: HashMap<String, CellCopy>,
        cellsCopy: HashMap<String, CellCopy>
    ): GenomeStage {
        val genomeStage = GenomeStage()

        for ((key, oldCell) in cellsCopyInit) {
            val cellAction = CellAction()

            cellsCopy[key]?.let { parentCell ->
                if (parentCell.isAlreadyDividedInGenomeStage && parentCell.childCellId != null) {
                    cellsCopy[parentCell.childCellId]?.let { newCell ->
                        val physicalLink: Map<Int, LinkData?> = newCell.physicalLink.map { (k, v) ->
                            val dist = cellsCopy[k]?.distanceTo(newCell.x, newCell.y)
                            if (dist != null && dist < 60f) {

                                v.connectId to LinkData(
                                    length = dist,
                                    isNeuronal = v.isNeuronal,
                                    weight = if (v.isNeuronal) 1f else null,
                                    directedNeuronLink = v.directedNeuronLink
                                )
                            } else {
                                v.connectId to null
                            }
                        }.toMap()
                        cellAction.divide = Action(
                            id = parentCell.childCellId,
                            angle = calculateAngle(parentCell, newCell),
                            cellType = newCell.cellTypeId,
                            indexOfNew = newCell.index,
                            physicalLink = HashMap(physicalLink),
                            color = newCell.colorCore,
                            angleDirected = newCell.angleDirected,
                            startDirectionId = cellsCopy[newCell.startDirectionId]?.index,
                            funActivation = newCell.activationFuncType,
                            a = newCell.a,
                            b = newCell.b,
                            c = newCell.c
                        )
                    }
                }
            }

            cellsCopy[key]?.let { newCell ->
                val removedKeys = oldCell.physicalLink.keys - newCell.physicalLink.keys
                val removedLinks: Map<Int, LinkData?> =
                    removedKeys.associate { oldCell.physicalLink[it]!!.connectId - WALL_AMOUNT to null }

                val addedKeys = newCell.physicalLink.keys - oldCell.physicalLink.keys
                val addedLinks: Map<Int, LinkData?> =
                    addedKeys.associate {
                        val value = newCell.physicalLink[it]!!
                        val dist = cellsCopy[it]?.distanceTo(newCell.x, newCell.y)
                        if (dist != null) {
                            value.connectId - WALL_AMOUNT to LinkData(
                                length = dist,
                                isNeuronal = value.isNeuronal,
                                directedNeuronLink = value.directedNeuronLink,
                                weight = 1f
                            )
                        } else {
                            value.connectId - WALL_AMOUNT to null
                        }
                    }

                // Общие ключи
                val commonKeys = oldCell.physicalLink.keys.intersect(newCell.physicalLink.keys)

                // Изменённые значения
                val modifiedLinks: Map<Int, LinkData?> = commonKeys.mapNotNull { key ->
                    val oldValue = oldCell.physicalLink[key]
                    val newValue = newCell.physicalLink[key]

                    if (oldValue != newValue) {
                        val dist = cellsCopy[key]?.distanceTo(newCell.x, newCell.y)
                        if (dist != null) {
                            newValue!!.connectId - WALL_AMOUNT to LinkData(
                                length = dist,
                                isNeuronal = newValue.isNeuronal,
                                directedNeuronLink = newValue.directedNeuronLink,
                                weight = 1f
                            )
                        } else {
                            newValue!!.connectId - WALL_AMOUNT to null
                        }
                    } else null
                }.toMap()

                val changedPhysicalLinks = HashMap(removedLinks + addedLinks + modifiedLinks)

                val hasChanges = newCell.cellTypeId != oldCell.cellTypeId ||
                    newCell.colorCore != oldCell.colorCore ||
                    newCell.a != oldCell.a ||
                    newCell.b != oldCell.b ||
                    newCell.c != oldCell.c ||
                    newCell.angleDirected != oldCell.angleDirected ||
                    newCell.activationFuncType != oldCell.activationFuncType ||
                    newCell.startDirectionId != oldCell.startDirectionId ||
                    changedPhysicalLinks.isNotEmpty()

                if (hasChanges) {
                    cellAction.mutate = Action(
                        cellType = if (newCell.cellTypeId != oldCell.cellTypeId) newCell.cellTypeId else null,
                        color = if (newCell.colorCore != oldCell.colorCore) newCell.colorCore else null,
                        physicalLink = changedPhysicalLinks,
                        a = if (newCell.a != oldCell.a) newCell.a else null,
                        b = if (newCell.b != oldCell.b) newCell.b else null,
                        c = if (newCell.c != oldCell.c) newCell.c else null,
                        funActivation = if (newCell.activationFuncType != oldCell.activationFuncType) newCell.activationFuncType else null,
                        angleDirected = if (newCell.angleDirected != oldCell.angleDirected) newCell.angleDirected else null,
                        startDirectionId = if (newCell.startDirectionId != oldCell.startDirectionId)
                            cellsCopy[newCell.startDirectionId]?.index else null
                    )
                }
            }

            if (cellAction.divide != null || cellAction.mutate != null) {
                genomeStage.cellActions[key] = cellAction
            }
        }

        return genomeStage
    }


    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val worldX = (screenX / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)) + cellManager.zoomManager.screenOffsetX
        val worldY = (screenY / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)) + cellManager.zoomManager.screenOffsetY
        return worldX to worldY
    }

    fun handlePause() {
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            val (mouseX, mouseY) = getMouseCoord()
            if (mouseX > Gdx.graphics.width - 170f) return
            val (worldX, worldY) = screenToWorld(mouseX, mouseY)
            val clickedPair = cellsCopy.cellClicked(worldX, worldY) //Поиск нажатой клетки
            if (clickedPair == null) {
                unselect() //Снятие выделения
                isDragged = true
                zoomOffsetX = mouseX
                zoomOffsetY = mouseY
                return
            }
            if (!isLinking) {
                isDragged = false
                if (clickedPair.second.isAdded) {
                    clickedPair.second.grabCell(worldX, worldY) // Логика захвата клетки для перетаскивания
                }
                clickedPair.select() //Выделение клетки
            } else {
                isDragged = false
                selectedCell?.let { selectedCell ->
                    if (selectedCell.first != clickedPair.first) {
                        selectedCell.addLink(clickedPair)
                    }
                } ?: return
            }
        }

        if (grabbedCell != null) {
            //Логика для перетаскивания клетки
            if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && grabbedCell != null) {
                val (mouseX, mouseY) = getMouseCoord()
                val (worldX, worldY) = screenToWorld(mouseX, mouseY)
                grabbedCell?.apply {
                    x = worldX - deltaGrabbed.x
                    y = worldY - deltaGrabbed.y
                }
            } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                grabbedCell = null
            }
        } else if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isDragged) {
            val (mouseX, mouseY) = getMouseCoord()
            cellManager.zoomManager.screenOffsetX += (zoomOffsetX - mouseX) / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
            cellManager.zoomManager.screenOffsetY += (zoomOffsetY - mouseY) / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
            zoomOffsetX = mouseX
            zoomOffsetY = mouseY
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.SHIFT_LEFT)) {
            isLinking = true
            isLinkingIsPhysical = true
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_LEFT)) {
            isLinking = true
            isLinkingIsPhysical = false
        } else if (isLinking && !Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) && !Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            isLinking = false
        }
    }

    private fun Pair<String, CellCopy>.addLink(clickedPair: Pair<String, CellCopy>) {
        val link1 = this.second.physicalLink
        val link2 = clickedPair.second.physicalLink
        if (link1.contains(clickedPair.first)) {
            if (isLinkingIsPhysical) {
                link1.remove(clickedPair.first)
            } else {
                val link = link1[clickedPair.first] ?: return
                link.isNeuronal = !link.isNeuronal
                link.directedNeuronLink = if (link.isNeuronal) cellsCopy[clickedPair.first]?.index else null
            }
        } else if (link2.contains(this.first)) {
            if (isLinkingIsPhysical) {
                link2.remove(this.first)
            } else {
                val link = link2[this.first] ?: return
                link.isNeuronal = !link.isNeuronal
                link.directedNeuronLink = if (link.isNeuronal) cellsCopy[clickedPair.first]?.index else null
            }
        } else {
            //TODO clickedPair.second.isAdded || this.second.isAdded как-то продумать эту историю,
            // чтобы нельзя было соединять через геном оторванные клетки
            if (isLinkingIsPhysical/* && clickedPair.second.isAdded || this.second.isAdded*/) {

                link1[clickedPair.first] = LinkDataCopy(
                    connectId = clickedPair.second.index,
                    isNeuronal = false,
                    directedNeuronLink = null
                )
            }
        }
        updateUiByState()
    }

    private fun Pair<String, CellCopy>.select() {
        selectedCell?.second?.isSelected = false
        selectedCell = this.also { it.second.isSelected = true }
        uiProcessor.uiState = Pause.Selected(this)
        updateUiByState()
    }

    private fun unselect() {
        selectedCell?.second?.isSelected = false
        selectedCell = null
        uiProcessor.uiState = Pause.Unselected
        updateUiByState()
    }

    private fun CellCopy.grabCell(mouseX: Float, mouseY: Float) {
        grabbedCell = this
        deltaGrabbed.x = mouseX - this.x
        deltaGrabbed.y = mouseY - this.y
    }
}
