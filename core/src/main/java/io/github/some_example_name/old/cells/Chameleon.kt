package io.github.some_example_name.old.cells

import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.utils.whiteColors
import io.github.some_example_name.old.features.editor.dialog.CellDialog
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Клетка-хамелеон: красится по сигналу от чёрного до выбранного цвета.
 *
 * Сигнал 0..1 (вне диапазона обрезается) — это яркость выбранных каналов [ChameleonData]:
 * 0 — чёрный, 1 — цвет из включённых R, G, B (один R — красный, все три — белый, R+G — жёлтый).
 * Каналы хранятся в данных клетки, а не в a, b, c формулы, поэтому формула остаётся свободной.
 *
 * Цвет собирается целыми числами без Color: хамелеон считается на каждый тик, а
 * аллокация объекта на клетку в параллельной фазе — лишний мусор для GC.
 */
class Chameleon(cellTypeId: Int) : Cell(
    key = "chameleon",
    defaultColor = whiteColors[0],
    cellTypeId = cellTypeId,
    textureName = "Chameleon.png",
    isNeural = true
) {

    override val defaultGenomeData: SpecialModData get() = DEFAULT_DATA

    override fun editorSections() = super.editorSections() + ChameleonSection

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        with(cellEntity) {
            val channels = (specialEntity.getSpecialData(cellIndex) as? ChameleonData)?.channels
                ?: ChameleonData.ALL
            val level = (neuronImpulseOutput[cellIndex].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val r = if (channels and ChameleonData.RED != 0) level else 0
            val g = if (channels and ChameleonData.GREEN != 0) level else 0
            val b = if (channels and ChameleonData.BLUE != 0) level else 0

            // Тот же порядок байт, что у Color.toIntBits: ABGR, альфа 255.
            setColor(cellIndex, (255 shl 24) or (b shl 16) or (g shl 8) or r)

            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
    }

    private companion object {
        val DEFAULT_DATA = ChameleonData()
    }
}

/**
 * Свои данные клетки Chameleon: какие цветовые каналы она красит.
 *
 * [channels] — маска из [RED], [GREEN], [BLUE]; по умолчанию все три (белый градиент).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("chameleon")
data class ChameleonData(
    @ProtoNumber(1) val channels: Int = ALL
) : SpecialModData {

    companion object {
        const val RED = 1
        const val GREEN = 2
        const val BLUE = 4
        const val ALL = RED or GREEN or BLUE
    }
}

/** Выбор каналов R, G, B в диалоге клетки. */
object ChameleonSection : CellEditorSection {

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        val current = (actionSystem.action.special ?: actionSystem.clickedCell.actual?.special) as? ChameleonData
            ?: ChameleonData()

        visTable({ left().padBottom(32.dp()) }) {
            visLabel(bundle.get("chameleon.channels"), font = game.extraLargeFont) { padRight(16.dp()) }
                .hint(bundle.get("editor.hint.chameleonChannels"))
            var channels = current.channels
            listOf("R" to ChameleonData.RED, "G" to ChameleonData.GREEN, "B" to ChameleonData.BLUE).forEach { (name, bit) ->
                visToggleButton(
                    text = name,
                    checked = channels and bit != 0,
                    onCheckedChange = { on ->
                        channels = if (on) channels or bit else channels and bit.inv()
                        actionSystem.action = actionSystem.action.copy(special = ChameleonData(channels))
                    }
                ) { padRight(8.dp()) }.hint(bundle.get("editor.hint.chameleonChannels"))
            }
        }
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) {
        val to = mutate.special as? ChameleonData ?: return
        val from = actual.special as? ChameleonData
        if (to != from) out.append("${bundle.get("chameleon.channels")} ${from?.channels} -> ${to.channels}\n")
    }
}
