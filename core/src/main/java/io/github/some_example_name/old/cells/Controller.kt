package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.utils.purpleColors
import io.github.some_example_name.old.features.editor.dialog.CellDialog
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.simulation.ControllerKeys
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Клетка управления: нейросенсор клавиши игрока.
 *
 * Пока клавиша [ControllerData.key] зажата, сырой сигнал 1, иначе 0, и дальше он идёт через
 * формулу клетки — как у Compass или Eye. С формулой по умолчанию (y = ax + b, a = 1, b = 0)
 * выход равен 1, пока клавиша зажата; один импульс на нажатие даёт формула spike.
 *
 * Клавиша — одна из клавиш контроллера (ControllerKeys): только они попадают в журнал .bug
 * и есть на экранной клавиатуре телефона. Симуляция читает не саму клавиатуру, а снимок
 * клавиш на тик (SimulationData.controllerKeys) — поэтому повтор бага нажимает их в те же
 * тики, и результат не зависит от того, какой воркер взял клетку.
 *
 * Клавиша хранится в своих данных клетки ([ControllerData], см. SpecialModData), а не в
 * a, b, c: они остаются свободны для формулы.
 */
class Controller(cellTypeId: Int) : Cell(
    key = "controller",
    defaultColor = purpleColors[3],
    cellTypeId = cellTypeId,
    textureName = "sensor.png",
    isNeural = true,
    isNeuronTransportable = false
) {

    override val defaultGenomeData: SpecialModData get() = DEFAULT_DATA

    override fun editorSections() = super.editorSections() + ControllerSection

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val keyIndex = (specialEntity.getSpecialData(cellIndex) as? ControllerData)?.keyIndex ?: -1
        val pressed = keyIndex != -1 && simulationData.controllerKeys[keyIndex]
        neuronImpulseOutput[cellIndex] = activation(cellIndex, if (pressed) 1f else 0f)
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

    private companion object {
        val DEFAULT_DATA = ControllerData()
    }
}

/**
 * Свои данные клетки Controller: какую клавишу она слушает.
 *
 * [key] — ИМЯ клавиши из ControllerKeys.NAMES ("W", "1", "SPACE"…), а не её номер: номер —
 * это бит в маске журнала и место кнопки на экране, и он может поменяться, а геном обязан
 * читаться всегда. Номер считается один раз при создании объекта ([keyIndex]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("controller")
data class ControllerData(
    @ProtoNumber(1) val key: String = "W"
) : SpecialModData {

    /** Номер клавиши в SimulationData.controllerKeys, -1 — такой клавиши нет. */
    @Transient
    val keyIndex: Int = ControllerKeys.indexOf(key)
}

/** Выбор клавиши в диалоге клетки. */
object ControllerSection : CellEditorSection {

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        val current = (actionSystem.action.special ?: actionSystem.clickedCell.actual?.special) as? ControllerData
            ?: ControllerData()

        visTable({ left().padBottom(32.dp()) }) {
            visLabel(bundle.get("controller.key"), font = game.extraLargeFont) { padRight(16.dp()) }.hint(bundle.get("editor.hint.controllerKey"))
            visSelectBox(
                items = ControllerKeys.NAMES,
                selectedIndex = current.keyIndex.coerceAtLeast(0),
                onChange = { _, keyIndex ->
                    actionSystem.action = actionSystem.action.copy(special = ControllerData(ControllerKeys.NAMES[keyIndex]))
                }
            ).hint(bundle.get("editor.hint.controllerKey"))
        }
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) {
        val to = mutate.special as? ControllerData ?: return
        val from = actual.special as? ControllerData
        if (to != from) out.append("${bundle.get("controller.key")} ${from?.key} -> ${to.key}\n")
    }
}
