package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import com.kotcrab.vis.ui.util.FloatDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.core.utils.skyBlueColors
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellPalette
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.features.editor.dialog.CellDialog
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Обучаемый синапс: передаёт входной сигнал дальше с весом и подстраивает вес по STDP
 * (spike-timing-dependent plasticity).
 *
 * ПОДКЛЮЧЕНИЕ
 * -----------
 * Ровно две нейросвязи: одна входит в синапс, вторая выходит в Neuron. Любое другое
 * подключение — синапс молчит и подсвечивается красным, пока его не починят.
 *
 * ОБУЧЕНИЕ
 * --------
 * Сигналы считаются бинарными, спайк — это рост сигнала за тик на ~1 (0 -> 1):
 *  - входной спайк: вырос сам вход синапса;
 *  - учительский спайк: вырос выход постсинаптического нейрона за вычетом вклада самого
 *    синапса, то есть нейрон сработал по другой причине (боль, другой вход).
 * Вход раньше учителя или одновременно — вес растёт на learningRate * trace, учитель
 * раньше входа — падает на depression * trace, где trace = exp(-|dt| * decayRate).
 * Вес живёт в [0, 1], выход синапса равен weight * input. Вес дошёл до 0 — синапс отмирает.
 *
 * ПАРАМЕТРЫ ГЕНОМА
 * ----------------
 * Своих полей в геноме у синапса нет, он занимает общие поля нейрона:
 * a = decayRate, b = learningRate, c = depression. Функция активации и способ сложения
 * входов ему не нужны: вход один, а активацию синапс не вызывает.
 *
 * СОСТОЯНИЕ
 * ---------
 * weight — вес; dTime — вход прошлого тика; remember — учительский сигнал прошлого тика;
 * tickRed / tickPain — тики последних входного и учительского спайков (NO_SPIKE — нет).
 * Пока синапс подключён неправильно, tickRed / tickPain заняты меткой, см. markMiswired.
 *
 * РЕДАКТОР
 * --------
 * Вместо обычных a, b, c и формулы нейрона диалог показывает параметры обучения —
 * [SynapseSection] в конце этого файла.
 */
class Synapse(cellTypeId: Int): Cell(
    key = "synapse",
    defaultColor = skyBlueColors.last(),
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    private val defaultColorBits = defaultColor.toIntBits()

    override fun editorSections(): List<CellEditorSection> = listOf(SynapseSection)

    override fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int): Unit = with(cellEntity) {
        // Случайный, но воспроизводимый: зависит только от индекса клетки.
        setWeight(cellIndex, Random(cellIndex).nextFloat() * INITIAL_WEIGHT_MAX)

        // Клетка могла мутировать из другого нейрона, у которого эти поля значат другое.
        // Хуже всего dTime: addNeural кладёт туда -1, и на первом же тике рост входа от -1
        // до 0 выглядел как входной спайк, которого не было.
        setDTime(cellIndex, 0f)
        setRemember(cellIndex, 0f)
        setTickRed(cellIndex, NO_SPIKE)
        setTickPain(cellIndex, NO_SPIKE)
        neuronImpulseOutput[cellIndex] = 0f
    }

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        val postCell = postsynapticCell(cellIndex)
        if (postCell == -1) {
            neuronImpulseOutput[cellIndex] = 0f
            markMiswired(cellIndex)
            return
        }
        unmarkMiswired(cellIndex)

        val learningRate = learningRate(getB(cellIndex))
        val depression = depression(getC(cellIndex))
        val decayRate = decayRate(getA(cellIndex))
        val window = learningWindowTicks(decayRate, learningRate, depression)
        val now = simulationData.tickCounter

        val rawInput = neuronImpulseInput[cellIndex]
        val input = if (rawInput.isNaN()) 0f else rawInput
        var weight = getWeight(cellIndex)

        val inputSpiked = detectInputSpike(cellIndex, input, now, window)
        val teacherSpiked = detectTeacherSpike(
            cellIndex,
            teacher = neuronImpulseOutput[postCell] - input * weight,
            now = now,
            window = window
        )

        val inputTick = getTickRed(cellIndex)
        val teacherTick = getTickPain(cellIndex)
        if ((inputSpiked || teacherSpiked) && inputTick != NO_SPIKE && teacherTick != NO_SPIKE) {
            weight = plasticity(weight, teacherTick - inputTick, learningRate, depression, decayRate)
            setWeight(cellIndex, weight)
            setTickRed(cellIndex, NO_SPIKE)
            setTickPain(cellIndex, NO_SPIKE)

            if (weight <= 0f) {
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    WorldCommandType.DELETE_CELL,
                    cellIndex,
                    getGeneration(cellIndex)
                )
            }
        }

        neuronImpulseOutput[cellIndex] = weight * input
    }

    /**
     * Постсинаптическая клетка или -1, если синапс подключён неправильно: нужна ровно одна
     * входящая связь и ровно одна исходящая, причём исходящая — в Neuron.
     *
     * Учительский сигнал берётся с выхода этой клетки, а у не-нейронов выход значит другое
     * (у мышцы, например, это сила сокращения), поэтому годится только Neuron.
     */
    private fun CellEntity.postsynapticCell(cellIndex: Int): Int {
        val links = neuralConnections.get(cellIndex) ?: return -1
        if (links.size != 2) return -1

        var incoming = 0
        var postCell = -1
        for (i in 0 until 2) {
            val linkIndex = links.getInt(i)
            val toCell1 = neuralLinkEntity.isLink1NeuralDirected[linkIndex]
            val from = if (toCell1) neuralLinkEntity.links2[linkIndex] else neuralLinkEntity.links1[linkIndex]
            val to = if (toCell1) neuralLinkEntity.links1[linkIndex] else neuralLinkEntity.links2[linkIndex]
            if (from == cellIndex) postCell = to else incoming++
        }

        if (incoming != 1 || postCell == -1) return -1
        return if (cellList[cellType[postCell].toInt()] is Neuron) postCell else -1
    }

    /** Входной спайк: вход вырос за тик на ~1. Заодно забывает слишком старый спайк. */
    private fun CellEntity.detectInputSpike(cellIndex: Int, input: Float, now: Int, window: Int): Boolean {
        val rise = input - getDTime(cellIndex)
        setDTime(cellIndex, input)

        if (rise >= SPIKE_RISE) {
            setTickRed(cellIndex, now)
            return true
        }
        if (isForgotten(getTickRed(cellIndex), now, window)) setTickRed(cellIndex, NO_SPIKE)
        return false
    }

    /** Учительский спайк: то же самое для выхода постсинаптического нейрона. */
    private fun CellEntity.detectTeacherSpike(cellIndex: Int, teacher: Float, now: Int, window: Int): Boolean {
        val rise = teacher - getRemember(cellIndex)
        setRemember(cellIndex, teacher)

        if (rise >= SPIKE_RISE) {
            setTickPain(cellIndex, now)
            return true
        }
        if (isForgotten(getTickPain(cellIndex), now, window)) setTickPain(cellIndex, NO_SPIKE)
        return false
    }

    /**
     * Красная подсветка неправильно подключённого синапса.
     *
     * Пока синапс не подключён, учиться ему нечем, поэтому поля спайков свободны: tickPain
     * хранит метку MISWIRED, а tickRed — цвет, который вернётся после починки. Так после
     * починки возвращается цвет из генома, а не цвет типа по умолчанию.
     */
    private fun CellEntity.markMiswired(cellIndex: Int) {
        if (getTickPain(cellIndex) == MISWIRED) return

        val color = getColor(cellIndex)
        // Красный без метки остаётся от сохранений старой версии — его возвращать незачем.
        setTickRed(cellIndex, if (color == MISWIRED_COLOR) defaultColorBits else color)
        setTickPain(cellIndex, MISWIRED)
        setColor(cellIndex, MISWIRED_COLOR)
    }

    private fun CellEntity.unmarkMiswired(cellIndex: Int) {
        if (getTickPain(cellIndex) != MISWIRED) return

        setColor(cellIndex, getTickRed(cellIndex))
        setTickRed(cellIndex, NO_SPIKE)
        setTickPain(cellIndex, NO_SPIKE)
    }

    companion object {
        const val DEFAULT_DECAY_RATE = 0.025f
        const val DEFAULT_LEARNING_RATE = 0.01f
        const val DEFAULT_DEPRESSION = 0.008f

        /** Без затухания (decayRate около нуля) спайк помнится столько тиков. */
        const val NO_DECAY_WINDOW_TICKS = 500

        /** Начальный вес — случайный в [0, INITIAL_WEIGHT_MAX). */
        const val INITIAL_WEIGHT_MAX = 0.2f

        private const val NO_SPIKE = -1
        private const val MISWIRED = Int.MIN_VALUE
        private val MISWIRED_COLOR = Color.RED.toIntBits()

        /** Рост сигнала за тик, который считается спайком. Чуть меньше 1 — запас на float. */
        private const val SPIKE_RISE = 0.999f

        /** Изменение веса меньше этого уже не в счёт — по нему считается окно обучения. */
        private const val NEGLIGIBLE_WEIGHT_STEP = 0.001f

        private const val MIN_DECAY_RATE = 0.0001f
        private const val MAX_DECAY_RATE = 10f

        /**
         * Скорость усиления из поля b. Ноль и меньше — значение по умолчанию: так ведут себя
         * все уже сохранённые геномы, где b остался нулём от обычного нейрона.
         */
        fun learningRate(b: Float) = if (b > 0f) b.coerceAtMost(1f) else DEFAULT_LEARNING_RATE

        /** Скорость ослабления из поля c, правила те же, что у learningRate. */
        fun depression(c: Float) = if (c > 0f) c.coerceAtMost(1f) else DEFAULT_DEPRESSION

        /**
         * Затухание следа из поля a. Ноль — без затухания: спайк помнится
         * NO_DECAY_WINDOW_TICKS тиков в полную силу. Отрицательное значение раздувало бы
         * след вместо затухания, бесконечность давала бы NaN в весе — поэтому зажато.
         */
        fun decayRate(a: Float) = if (a.isNaN()) DEFAULT_DECAY_RATE else a.coerceIn(0f, MAX_DECAY_RATE)

        /**
         * Сколько тиков спайк ждёт пару. Дальше изменение веса rate * exp(-dt * decayRate)
         * меньше NEGLIGIBLE_WEIGHT_STEP, то есть ждать его уже бессмысленно:
         * dt = ln(rate / NEGLIGIBLE_WEIGHT_STEP) / decayRate. Берётся большая из двух
         * скоростей, чтобы окно не обрезало ни усиление, ни ослабление.
         */
        fun learningWindowTicks(decayRate: Float, learningRate: Float, depression: Float): Int {
            if (decayRate <= MIN_DECAY_RATE) return NO_DECAY_WINDOW_TICKS
            val strongest = max(learningRate, depression)
            return (ln(strongest / NEGLIGIBLE_WEIGHT_STEP) / decayRate).roundToInt().coerceAtLeast(1)
        }

        /** Вес после пары спайков, dt = тик учителя - тик входа. */
        fun plasticity(weight: Float, dt: Int, learningRate: Float, depression: Float, decayRate: Float): Float {
            val trace = exp(-abs(dt) * decayRate)
            val changed = if (dt >= 0) weight + learningRate * trace else weight - depression * trace
            return changed.coerceIn(0f, 1f)
        }

        /** Спайк старше окна обучения или из будущего (счётчик тиков отмотан) забывается. */
        private fun isForgotten(tick: Int, now: Int, window: Int) =
            tick != NO_SPIKE && (now - tick > window || tick > now)
    }
}

/**
 * Параметры синапса в диалоге редактора вместо a, b, c обычного нейрона. Хранятся они в тех
 * же полях (a = decayRate, b = learningRate, c = depression), см. Synapse.
 *
 * Пустое поле — значение по умолчанию. У learningRate и depression ноль тоже означает
 * «по умолчанию» (так их видит симуляция), поэтому ноль показывается пустым полем.
 *
 * Пояснения к полям не занимают место в диалоге, а всплывают при наведении мыши
 * или при зажатии пальцем — см. hoverPopup.
 */
object SynapseSection : CellEditorSection {

    // Формула и сложение входов синапсу не нужны (вход один, активацию он не вызывает),
    // но поля нейрона общие — ставятся те же значения, что у обычного нейрона.
    override fun applyDefaults(action: Action) = action.copy(
        funActivation = cellPalette.defaultFormula().key,
        a = Synapse.DEFAULT_DECAY_RATE,
        b = Synapse.DEFAULT_LEARNING_RATE,
        c = Synapse.DEFAULT_DEPRESSION,
        isSum = true
    )

    // В a, b, c у синапса параметры обучения, а не коэффициенты формулы: при переходе
    // в обычный нейрон они сбрасываются, и тот получает свои значения по умолчанию.
    override fun clear(action: Action) = NeuralSection.clear(action)

    override fun VisTable.compose(dialog: CellDialog) {
        val actionSystem = dialog.actionSystem
        val actual = actionSystem.clickedCell.actual
        var decayRate = actionSystem.action.a ?: actual?.a ?: Synapse.DEFAULT_DECAY_RATE
        var learningRate = actionSystem.action.b ?: actual?.b ?: Synapse.DEFAULT_LEARNING_RATE
        var depression = actionSystem.action.c ?: actual?.c ?: Synapse.DEFAULT_DEPRESSION

        fun windowText() = bundle.format(
            "synapse.window",
            Synapse.learningWindowTicks(
                Synapse.decayRate(decayRate),
                Synapse.learningRate(learningRate),
                Synapse.depression(depression)
            )
        )

        // Метка окна стоит под полями, а обновляется из них — поэтому ссылка заводится заранее.
        var windowLabel: VisLabel? = null
        fun updateWindow() = windowLabel?.setText(windowText())

        visTable({ left() }) {
            synapseParam(
                name = bundle.get("synapse.learningRate"),
                hint = bundle.get("synapse.learningRate.hint"),
                value = learningRate,
                default = Synapse.DEFAULT_LEARNING_RATE,
                zeroMeansDefault = true
            ) {
                learningRate = it
                actionSystem.action = actionSystem.action.copy(b = it)
                updateWindow()
            }

            synapseParam(
                name = bundle.get("synapse.depression"),
                hint = bundle.get("synapse.depression.hint"),
                value = depression,
                default = Synapse.DEFAULT_DEPRESSION,
                zeroMeansDefault = true
            ) {
                depression = it
                actionSystem.action = actionSystem.action.copy(c = it)
                updateWindow()
            }

            synapseParam(
                name = bundle.get("synapse.decayRate"),
                hint = bundle.get("synapse.decayRate.hint"),
                value = decayRate,
                default = Synapse.DEFAULT_DECAY_RATE,
                zeroMeansDefault = false
            ) {
                decayRate = it
                actionSystem.action = actionSystem.action.copy(a = it)
                updateWindow()
            }
        }
        row()

        windowLabel = visLabel(windowText()) { left().padBottom(32.dp()) }
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) = with(mutate) {
        if (a != null && a != actual.a) out.append("${bundle.get("synapse.decayRate")}: ${actual.a} -> $a\n")
        if (b != null && b != actual.b) out.append("${bundle.get("synapse.learningRate")}: ${actual.b} -> $b\n")
        if (c != null && c != actual.c) out.append("${bundle.get("synapse.depression")}: ${actual.c} -> $c\n")
    }

    /** Строка «название + поле» с всплывающим пояснением. Отрицательные значения синапсу не нужны. */
    private fun VisTable.synapseParam(
        name: String,
        hint: String,
        value: Float,
        default: Float,
        zeroMeansDefault: Boolean,
        onChange: (Float) -> Unit
    ) {
        // Пояснение висит и на названии, и на поле: на телефоне зажатие названия показывает
        // его, не открывая клавиатуру.
        visLabel("$name:", font = game.extraLargeFont) { left().padRight(16.dp()) }
            .hint(hint)
        visTextField(
            text = if (zeroMeansDefault && value <= 0f) "" else value.toString(),
            hint = default.toString(),
            textFieldFilter = FloatDigitsOnlyFilter(false),
            onTextChange = { text ->
                if (text.isBlank()) onChange(default) else text.toFloatOrNull()?.let(onChange)
            }
        ) { left() }
            .hint(hint)
        row()
    }
}
