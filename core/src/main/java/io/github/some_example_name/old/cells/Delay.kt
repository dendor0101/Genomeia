package io.github.some_example_name.old.cells

import com.kotcrab.vis.ui.util.IntDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.specialDataFor
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.utils.purpleColors
import io.github.some_example_name.old.features.editor.dialog.CellDialog
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * Линия задержки: выход = вход [DelayData.ticks] тиков назад, y(t) = x(t - ticks).
 *
 * ЗАЧЕМ
 * -----
 * Сигнал проходит одну нейросвязь за тик, поэтому задержку на N тиков раньше собирали
 * цепочкой из N нейронов. Каждый из них — полноценная клетка: частица, связи, физика,
 * энергия. Детектор движения (два сенсора, сигнал одного задержан и перемножен с другим)
 * брал ~17 клеток, с Delay — 3–4.
 *
 * Отсчёт — как у нейрона с формулой y = x: замена такого нейрона на Delay добавляет к
 * пути сигнала ровно ticks тиков. ticks = 0 — сигнал проходит как через этот нейрон.
 *
 * ПАМЯТЬ
 * ------
 * Кольцевой буфер в [DelayState]: на тик одно чтение, одна запись и сдвиг головы, без
 * аллокаций и сдвига массива. Буфер заводится один раз при рождении клетки и полон с
 * начала — первые ticks тиков на выходе нули.
 *  - обычный режим: float на тик, 4 * ticks байт (15 тиков — 60 байт);
 *  - [DelayData.binary] («только 0 и 1»): бит на тик, ticks / 8 байт (15 тиков — один
 *    int). Вход считается 1, если он не меньше 0.5, выход ровно 0 или 1.
 * Двоичный режим — тот же буфер, а не очередь «тиков переключения»: очереди нужна ёмкость,
 * и частое переключение (каждый тик) её переполняло бы — события терялись бы. У битового
 * буфера размер фиксирован и точен при любом сигнале.
 * Плюс заголовки объекта и массива, ~40 байт. Против N клеток цепочки — в разы меньше.
 *
 * ПАРАМЕТРЫ ГЕНОМА
 * ----------------
 * [DelayData] в Action.special: число тиков и галочка двоичного режима. Поля нейрона a, b,
 * c и формула не используются: активацию клетка не вызывает (isNeuronTransportable = false),
 * выход пишет сама, как Synapse. Входы складываются (isSum = true).
 */
class Delay(cellTypeId: Int) : Cell(
    key = "delay",
    defaultColor = purpleColors[6],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    override val defaultGenomeData: SpecialModData get() = DelayData.DEFAULT

    override fun editorSections(): List<CellEditorSection> = listOf(DelaySection)

    /** Свой буфер у каждой клетки — данные генома ([DelayData]) общие, а буфер меняется каждый тик. */
    override fun createSpecialData(action: Action): SpecialModData =
        DelayState.of(specialDataFor(action, DelayData.DEFAULT) as DelayData)

    /** Новые параметры из генома — новый пустой буфер; те же — буфер остаётся. */
    override fun mutateSpecialData(current: SpecialModData, action: Action): SpecialModData? {
        val data = action.special as? DelayData ?: return null
        val state = current as? DelayState
        if (state != null && state.ticks == data.clampedTicks && state.binary == data.binary) return null
        return DelayState.of(data)
    }

    override fun genomeDataOf(data: SpecialModData?): SpecialModData? =
        (data as? DelayState)?.let { DelayData(it.ticks, it.binary) }

    override fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int) {
        // Как у Neuron: после мутации из другой клетки на выходе мог остаться её сигнал.
        cellEntity.neuronImpulseOutput[cellIndex] = 0f
    }

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        // Активацию клетка не вызывает, а вход чистит от NaN и бесконечностей именно она —
        // поэтому здесь: бесконечность в буфере вышла бы наружу через ticks тиков.
        val raw = neuronImpulseInput[cellIndex]
        val input = if (raw.isFinite()) raw else 0f
        // Нет буфера — тик самой мутации в Delay: команда, которая его заводит, ещё не
        // применилась. Клетка молчит один тик.
        val state = specialEntity.getSpecialData(cellIndex) as? DelayState
        neuronImpulseOutput[cellIndex] = state?.push(input) ?: 0f
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }
}

/**
 * Параметры задержки в геноме: [ticks] — на сколько тиков, [binary] — «только 0 и 1»
 * (буфер из битов вместо float, см. Delay). Неизменяемые — общие для генома и всех его клеток.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("delay")
data class DelayData(
    @ProtoNumber(1) val ticks: Int = DEFAULT_TICKS,
    @ProtoNumber(2) val binary: Boolean = false
) : SpecialModData {

    /** Задержка в допустимых пределах: геном из файла может нести что угодно. */
    val clampedTicks: Int get() = ticks.coerceIn(0, MAX_TICKS)

    companion object {
        const val DEFAULT_TICKS = 10

        /**
         * Предел задержки. Обычный буфер на нём — 2 КБ на клетку, двоичный — 64 байта.
         * Больше — уже не «понять движение», а память на сотни клеток мира.
         */
        const val MAX_TICKS = 512

        val DEFAULT = DelayData()
    }
}

/**
 * Буфер задержки одной клетки. Изменяемый — поэтому объект свой у каждой клетки
 * (Delay.createSpecialData), и пишет в него только воркер этой клетки.
 *
 * [values] — значения по тикам (обычный режим), [bits] — биты по тикам (двоичный), второй
 * массив пустой. [head] — слот, куда ляжет текущий вход и откуда выходит вход ticks тиков
 * назад.
 *
 * Обычный класс, а не data class: равенство data class сравнивало бы массивы по ссылке.
 * В сохранение и хэш состояние идёт protobuf-байтами (SpecialModDataTypes.encode).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("delay_state")
class DelayState(
    @ProtoNumber(1) val ticks: Int = 0,
    @ProtoNumber(2) val binary: Boolean = false,
    @ProtoNumber(3) @ProtoPacked val values: FloatArray = FloatArray(0),
    @ProtoNumber(4) @ProtoPacked val bits: IntArray = IntArray(0),
    @ProtoNumber(5) var head: Int = 0
) : SpecialModData {

    /** Положить вход этого тика и вернуть вход ticks тиков назад. */
    fun push(input: Float): Float {
        val n = ticks
        if (n == 0) return if (binary) bit(input) else input
        val h = head
        head = if (h + 1 == n) 0 else h + 1
        if (!binary) {
            val out = values[h]
            values[h] = input
            return out
        }
        val word = h ushr 5
        val mask = 1 shl (h and 31)
        val old = bits[word]
        bits[word] = if (input >= BINARY_THRESHOLD) old or mask else old and mask.inv()
        return if (old and mask != 0) 1f else 0f
    }

    override fun equals(other: Any?): Boolean =
        other is DelayState && ticks == other.ticks && binary == other.binary && head == other.head &&
            values.contentEquals(other.values) && bits.contentEquals(other.bits)

    override fun hashCode(): Int =
        ((ticks * 31 + binary.hashCode()) * 31 + head) * 31 + values.contentHashCode() * 17 + bits.contentHashCode()

    companion object {
        /** Порог двоичного режима: вход от 0.5 — единица. */
        const val BINARY_THRESHOLD = 0.5f

        private fun bit(input: Float) = if (input >= BINARY_THRESHOLD) 1f else 0f

        /** Пустой буфер под параметры генома. */
        fun of(data: DelayData): DelayState {
            val ticks = data.clampedTicks
            return if (data.binary) DelayState(ticks, true, bits = IntArray((ticks + 31) ushr 5))
            else DelayState(ticks, false, values = FloatArray(ticks))
        }
    }
}

/**
 * Параметры задержки в диалоге клетки: число тиков и галочка «только 0 и 1». Формулы и
 * коэффициентов нейрона у Delay нет, поэтому секция стоит вместо NeuralSection.
 */
object DelaySection : CellEditorSection {

    // Поля нейрона общие, и в геноме они должны быть заполнены: ставятся те же значения,
    // что у обычного нейрона. Входы складываются.
    override fun applyDefaults(action: Action) = NeuralSection.applyDefaults(action)

    override fun clear(action: Action) = NeuralSection.clear(action)

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        fun current() = (actionSystem.action.special ?: actionSystem.clickedCell.actual?.special) as? DelayData
            ?: DelayData.DEFAULT
        val start = current()

        visTable({ left() }) {
            visLabel("${bundle.get("delay.ticks")}:", font = game.extraLargeFont) { left().padRight(16.dp()) }
                .hint(bundle.get("delay.ticks.hint"))
            visTextField(
                text = start.ticks.toString(),
                hint = DelayData.DEFAULT_TICKS.toString(),
                textFieldFilter = IntDigitsOnlyFilter(false),
                onTextChange = { text ->
                    // Пустое поле — значение по умолчанию; не число (слишком длинное) — без изменений.
                    val ticks = if (text.isBlank()) DelayData.DEFAULT_TICKS else text.toIntOrNull()
                    if (ticks != null) actionSystem.action = actionSystem.action.copy(
                        special = current().copy(ticks = ticks.coerceIn(0, DelayData.MAX_TICKS))
                    )
                }
            ) { left() }.hint(bundle.get("delay.ticks.hint"))
        }
        row()

        visToggleButton(
            text = bundle.get("delay.binary"),
            checked = start.binary,
            onCheckedChange = { on ->
                actionSystem.action = actionSystem.action.copy(special = current().copy(binary = on))
            }
        ) { left().padTop(8.dp()).padBottom(32.dp()) }.hint(bundle.get("delay.binary.hint"))
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) {
        val to = mutate.special as? DelayData ?: return
        val from = actual.special as? DelayData
        if (to.ticks != from?.ticks) out.append("${bundle.get("delay.ticks")}: ${from?.ticks} -> ${to.ticks}\n")
        if (to.binary != from?.binary) out.append("${bundle.get("delay.binary")}: ${from?.binary} -> ${to.binary}\n")
    }
}
