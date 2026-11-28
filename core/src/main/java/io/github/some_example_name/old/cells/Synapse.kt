package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import com.kotcrab.vis.ui.util.FloatDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.specialDataFor
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextField
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.utils.skyBlueColors
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.cellPalette
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.features.editor.dialog.CellDialog
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Обучаемый синапс: передаёт входной сигнал дальше с весом и подстраивает вес по STDP
 * (spike-timing-dependent plasticity) — «кто сработал раньше».
 *
 * ПОДКЛЮЧЕНИЕ
 * -----------
 * Ровно две нейросвязи: одна входит в синапс, вторая выходит в Neuron. Любое другое
 * подключение — синапс молчит и подсвечивается красным, пока его не починят.
 * Лучше всего за синапсом стоит Neuron с формулой lif: он складывает вклады синапсов и
 * срабатывает по порогу, а его спайк и есть то, по чему синапс учится.
 *
 * ОБУЧЕНИЕ
 * --------
 * Спайк — рост сигнала за тик на ~1 (0 -> 1):
 *  - входной спайк: вырос сам вход синапса;
 *  - спайк нейрона: вырос выход постсинаптического нейрона. У нейрона с lif это его спайк
 *    как есть. У остальных — за вычетом вклада самого синапса, то есть нейрон сработал по
 *    другой причине (учитель: боль, другой вход): иначе синапс учил бы сам себя.
 * У синапса два следа — «сколько недавно было спайков и насколько недавно». Каждый тик
 * они затухают в (1 − a) раз, спайк прибавляет к своему следу 1:
 *  - входной спайк: вес падает на depression · w · след нейрона (нейрон сработал раньше
 *    входа — вход ни при чём);
 *  - спайк нейрона: вес растёт на learningRate · (1 − w) · след входа (вход сработал раньше
 *    и, похоже, был причиной).
 * Если оба в один тик — усиление: ослабление считается по следу нейрона ДО его спайка,
 * усиление — по следу входа УЖЕ с его спайком.
 *
 * ЧЕМ ЭТО ЛУЧШЕ ПРЕЖНЕГО
 * ----------------------
 * До 05.11 синапс помнил только тики последнего входного и последнего учительского спайка,
 * менял вес по паре на rate · exp(−|dt| · a) и стирал оба тика. Теперь:
 *  - в счёт идут все недавние спайки, а не последняя пара: вход, который стабильно
 *    срабатывает перед нейроном, усиливается быстрее случайного;
 *  - после пары ничего не теряется: «вход → нейрон → вход» второй раз немного ослабляет;
 *  - мягкие границы: множители (1 − w) и w не дают весам слипнуться в 0 и 1;
 *  - нет exp и ln — они могут разойтись на 1 ulp между ПК и Android; нет «окна обучения»:
 *    след затухает сам.
 * Вес живёт в [0, 1]. Ослабленный ниже [DEATH_WEIGHT] синапс отмирает: с мягкой границей
 * вес до нуля не доходит никогда.
 *
 * ТОРМОЗЯЩИЙ СИНАПС
 * -----------------
 * [SynapseData.inhibitory]: выход −w · вход. Учится так же, но вес означает силу
 * торможения: вход, который стабильно предшествует спайку нейрона, тормозит его сильнее.
 *
 * ПАРАМЕТРЫ ГЕНОМА
 * ----------------
 * Обучение — в общих полях нейрона: a = затухание следов, b = learningRate,
 * c = depression (так они записаны во всех уже сохранённых геномах). Галочка торможения и
 * начальный вес — [SynapseData] в Action.special (вес игрок задаёт в редакторе; без него вес
 * выбирает первый тик, см. startWeight). Функция активации и способ сложения входов синапсу не
 * нужны: вход один, а активацию он не вызывает.
 *
 * СОСТОЯНИЕ
 * ---------
 * [SynapseState] (SpecialModData, объект у каждого синапса свой): вес, два следа, режим,
 * метка неправильного подключения. Из общих полей нейрона: dTime — вход прошлого тика,
 * remember — сигнал нейрона прошлого тика.
 *
 * ЦЕНА
 * ----
 * На синапс за тик: два умножения на затухание, несколько сравнений, одно разыменование
 * состояния. Синапсов в теле единицы–десятки, на время тика это не влияет.
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

    override val defaultGenomeData: SpecialModData get() = SynapseData.DEFAULT

    override fun editorSections(): List<CellEditorSection> = listOf(SynapseSection)

    /** Объект на синапс: состояние меняется каждый тик, общим оно быть не может. */
    override fun createSpecialData(action: Action): SpecialModData {
        val data = specialDataFor(action, SynapseData.DEFAULT) as SynapseData
        return SynapseState(weight = data.initialWeight ?: NOT_STARTED, inhibitory = data.inhibitory)
    }

    /** Мутация меняет только режим, выученный вес остаётся. */
    override fun mutateSpecialData(current: SpecialModData, action: Action): SpecialModData? {
        val data = action.special as? SynapseData ?: return null
        val state = current as? SynapseState ?: return createSpecialData(action)
        if (state.inhibitory == data.inhibitory) return null
        return state.copy(inhibitory = data.inhibitory)
    }

    override fun genomeDataOf(data: SpecialModData?): SpecialModData? =
        (data as? SynapseState)?.let { SynapseData(it.inhibitory, it.weight.takeIf { w -> w in 0f..1f }) }

    override fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int): Unit = with(cellEntity) {
        // Клетка могла мутировать из другого нейрона, у которого эти поля значат другое.
        // Хуже всего dTime: addNeural кладёт туда -1, и на первом же тике рост входа от -1
        // до 0 выглядел как входной спайк, которого не было.
        setDTime(cellIndex, 0f)
        setRemember(cellIndex, 0f)
        neuronImpulseOutput[cellIndex] = 0f
        // Вес здесь не ставится: при мутации в синапс onStart (MUTATE_ON_START) применяется
        // раньше, чем заводится SynapseState (ADD_SPECIAL_MOD_DATA), — объекта ещё нет.
        // createSpecialData берёт вес из генома. Без заданного веса его выбирает первый тик.
    }

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        // Нет состояния — тик самой мутации в синапс: команда, которая его заводит, ещё не
        // применилась. Синапс молчит один тик.
        val state = specialEntity.getSpecialData(cellIndex) as? SynapseState
        if (state == null) {
            neuronImpulseOutput[cellIndex] = 0f
            return
        }
        if (state.weight == NOT_STARTED) startWeight(cellIndex, state)

        val postCell = postsynapticCell(cellIndex)
        if (postCell == -1) {
            neuronImpulseOutput[cellIndex] = 0f
            markMiswired(cellIndex, state)
            return
        }
        unmarkMiswired(cellIndex, state)

        val rawInput = neuronImpulseInput[cellIndex]
        val input = if (rawInput.isNaN()) 0f else rawInput
        val sign = if (state.inhibitory) -1f else 1f
        var weight = state.weight

        val inputSpiked = input - getDTime(cellIndex) >= SPIKE_RISE
        setDTime(cellIndex, input)

        val postOutput = neuronImpulseOutput[postCell]
        val teacher = if (getActivationFuncType(postCell) == Formulas.lifIndex) postOutput
        else postOutput - sign * weight * input
        val postSpiked = teacher - getRemember(cellIndex) >= SPIKE_RISE
        setRemember(cellIndex, teacher)

        val keep = 1f - traceDecay(getA(cellIndex))
        var pre = state.preTrace * keep
        var post = state.postTrace * keep
        var depressed = false
        if (inputSpiked) {
            if (post > 0f) {
                weight -= depression(getC(cellIndex)) * weight * post
                depressed = true
            }
            pre += 1f
        }
        if (postSpiked) {
            post += 1f
            weight += learningRate(getB(cellIndex)) * (1f - weight) * pre
        }
        // Шаг больше 1 (быстрое обучение на длинном следе) перепрыгнул бы границу.
        weight = if (weight > 1f) 1f else if (weight < 0f) 0f else weight
        // Без обнуления след сотни тиков живёт денормализованным числом, а на некоторых
        // процессорах арифметика с такими числами в десятки раз медленнее.
        state.preTrace = if (pre < TRACE_CUTOFF) 0f else pre
        state.postTrace = if (post < TRACE_CUTOFF) 0f else post
        state.weight = weight

        if (depressed && weight < DEATH_WEIGHT) dieOfWeakness(cellIndex, threadId)

        neuronImpulseOutput[cellIndex] = sign * weight * input
    }

    /** Отдельным методом: ветка раз за жизнь синапса, ей не место в теле горячего doOnTick. */
    private fun CellEntity.dieOfWeakness(cellIndex: Int, threadId: Int) {
        worldCommandsManager.worldCommandBuffer[threadId].push(
            WorldCommandType.DELETE_CELL,
            cellIndex,
            getGeneration(cellIndex)
        )
    }

    /**
     * Если геном не задал вес, выбираем его воспроизводимо по индексу клетки.
     * Отдельным методом: ветка раз за жизнь синапса, а Random — аллокация, ей не место
     * в теле горячего doOnTick.
     */
    private fun startWeight(cellIndex: Int, state: SynapseState) {
        state.weight = Random(cellIndex).nextFloat() * INITIAL_WEIGHT_MAX
    }

    /**
     * Постсинаптическая клетка или -1, если синапс подключён неправильно: нужна ровно одна
     * входящая связь и ровно одна исходящая, причём исходящая — в Neuron.
     *
     * Сигнал нейрона берётся с выхода этой клетки, а у не-нейронов выход значит другое
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

    /**
     * Красная подсветка неправильно подключённого синапса. Цвет из генома запоминается в
     * [SynapseState.savedColor] и после починки возвращается он, а не цвет типа по умолчанию.
     * Следы забываются: учиться неподключённому синапсу нечем.
     */
    private fun CellEntity.markMiswired(cellIndex: Int, state: SynapseState) {
        if (state.miswired) return

        val color = getColor(cellIndex)
        // Красный без метки остаётся от сохранений старой версии — его возвращать незачем.
        state.savedColor = if (color == MISWIRED_COLOR) defaultColorBits else color
        state.miswired = true
        state.preTrace = 0f
        state.postTrace = 0f
        setColor(cellIndex, MISWIRED_COLOR)
    }

    private fun CellEntity.unmarkMiswired(cellIndex: Int, state: SynapseState) {
        if (!state.miswired) return

        setColor(cellIndex, state.savedColor)
        state.miswired = false
        state.preTrace = 0f
        state.postTrace = 0f
    }

    companion object {
        const val DEFAULT_DECAY_RATE = 0.025f
        const val DEFAULT_LEARNING_RATE = 0.01f
        const val DEFAULT_DEPRESSION = 0.008f

        /**
         * Самое медленное затухание следа: с ним след держится ~500 тиков, как спайк без
         * затухания у прежнего синапса. Совсем без затухания след рос бы от каждого спайка
         * без предела.
         */
        const val MIN_DECAY_RATE = 0.006f

        /** Начальный вес — случайный в [0, INITIAL_WEIGHT_MAX). */
        const val INITIAL_WEIGHT_MAX = 0.2f

        /** Ослабленный ниже этого синапс отмирает. */
        const val DEATH_WEIGHT = 0.01f

        /** Вес ещё не выставлен (см. startWeight). Настоящий вес живёт в [0, 1]. */
        const val NOT_STARTED = -1f
        private val MISWIRED_COLOR = Color.RED.toIntBits()

        /** Рост сигнала за тик, который считается спайком. Чуть меньше 1 — запас на float. */
        private const val SPIKE_RISE = 0.999f

        /**
         * След меньше этого — ноль. Вклад такого следа в вес меньше 1e-4 даже при
         * learningRate = 1: обучение он не меняет.
         */
        private const val TRACE_CUTOFF = 1e-4f

        /** Доля следа, которая остаётся, когда он «почти забыт», — для подписи в редакторе. */
        private const val FORGOTTEN = 0.05f

        /**
         * Скорость усиления из поля b. Ноль и меньше — значение по умолчанию: так ведут себя
         * все уже сохранённые геномы, где b остался нулём от обычного нейрона.
         */
        fun learningRate(b: Float) = if (b > 0f) b.coerceAtMost(1f) else DEFAULT_LEARNING_RATE

        /** Скорость ослабления из поля c, правила те же, что у learningRate. */
        fun depression(c: Float) = if (c > 0f) c.coerceAtMost(1f) else DEFAULT_DEPRESSION

        /**
         * Какая доля следа теряется за тик, из поля a, в [MIN_DECAY_RATE, 1]. Ноль — самое
         * медленное затухание. Сравнения, а не coerceIn: NaN проходит coerceIn насквозь.
         *
         * До 05.11 a было скоростью в exp(−dt · a); при малых a это почти то же самое
         * (1 − 0.025 = 0.975, exp(−0.025) ≈ 0.9753), поэтому старые геномы читаются как есть.
         */
        fun traceDecay(a: Float): Float = when {
            a >= 1f -> 1f
            a > MIN_DECAY_RATE -> a
            a.isNaN() -> DEFAULT_DECAY_RATE
            else -> MIN_DECAY_RATE
        }

        /**
         * Сколько тиков след спайка держится, пока не ослабнет до 5%: (1 − d)^n = 0.05,
         * n ≈ 3 / d. Только для подписи в редакторе — симуляция этим не пользуется.
         */
        fun memoryTicks(a: Float): Int = (3f / traceDecay(a)).roundToInt().coerceAtLeast(1)
    }
}

/**
 * Параметры синапса в геноме, кроме a, b, c: [inhibitory] — тормозящий (выход −w · вход).
 * [initialWeight] задаёт стартовый вес для каждой новой клетки. При null вес выбирает первый тик.
 *
 * @SerialName не равен ключу клетки: "synapse" уже занят [SynapseState] в сохранённых мирах.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("synapse_data")
data class SynapseData(
    @ProtoNumber(1) val inhibitory: Boolean = false,
    /** Выученный стартовый вес. Старые геномы получают обычный детерминированный посев. */
    @ProtoNumber(2) val initialWeight: Float? = null
) : SpecialModData {
    init {
        require(initialWeight == null || initialWeight in 0f..1f)
    }
    companion object {
        val DEFAULT = SynapseData()
    }
}

/**
 * Состояние синапса. Изменяемое — поэтому объект свой у каждого синапса
 * (Synapse.createSpecialData), и пишет в него только воркер этой клетки.
 *
 * [weight] — вес в [0, 1] (NOT_STARTED — первый тик ещё не прошёл); [preTrace] /
 * [postTrace] — следы входа и нейрона; [inhibitory] — тормозящий, копия из [SynapseData];
 * [miswired] — синапс подключён неправильно и покрашен в красный, [savedColor] — цвет,
 * который вернётся после починки.
 *
 * Номера 2 и 3 заняты тиками последних спайков прежнего синапса (до 05.11): в старых
 * сохранениях они есть, protobuf их пропускает. Не переиспользовать.
 *
 * Цена: объект ~40 байт на синапс и одно разыменование за тик против 12 байт у каждой
 * нейронной клетки раньше. Синапсов в теле единицы–десятки, нейронных клеток — сотни.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("synapse")
data class SynapseState(
    @ProtoNumber(1) var weight: Float = Synapse.NOT_STARTED,
    @ProtoNumber(4) var miswired: Boolean = false,
    @ProtoNumber(5) var savedColor: Int = 0,
    @ProtoNumber(6) var preTrace: Float = 0f,
    @ProtoNumber(7) var postTrace: Float = 0f,
    @ProtoNumber(8) var inhibitory: Boolean = false,
) : SpecialModData

/** Состояние синапса [cellIndex] или null: клетка не синапс или её состояние ещё не заведено. */
fun SpecialEntity.synapseState(cellIndex: Int): SynapseState? = getSpecialData(cellIndex) as? SynapseState

/**
 * Параметры синапса в диалоге редактора вместо a, b, c обычного нейрона. Хранятся они в тех
 * же полях (a = затухание следов, b = learningRate, c = depression), галочка торможения и
 * начальный вес — в [SynapseData], см. Synapse.
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
        fun data() = (actionSystem.action.special ?: actual?.special) as? SynapseData ?: SynapseData.DEFAULT

        fun memoryText() = bundle.format("synapse.memory", Synapse.memoryTicks(decayRate))

        // Метка памяти стоит под полями, а обновляется из них — поэтому ссылка заводится заранее.
        var memoryLabel: VisLabel? = null

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
                memoryLabel?.setText(memoryText())
            }

            // Начальный вес живёт в SynapseData, а не в a, b, c: пустое поле — null, и вес
            // выбирает первый тик (startWeight), как у всех геномов до этого поля.
            visLabel("${bundle.get("synapse.initialWeight")}:", font = game.extraLargeFont) { left().padRight(16.dp()) }
                .hint(bundle.get("synapse.initialWeight.hint"))
            visTextField(
                text = data().initialWeight?.toString() ?: "",
                hint = "0..1",
                textFieldFilter = FloatDigitsOnlyFilter(false),
                onTextChange = { text ->
                    val weight = if (text.isBlank()) null else text.toFloatOrNull()?.takeIf { it in 0f..1f }
                    if (text.isBlank() || weight != null) {
                        actionSystem.action = actionSystem.action.copy(special = data().copy(initialWeight = weight))
                    }
                }
            ) { left() }
                .hint(bundle.get("synapse.initialWeight.hint"))
            row()
        }
        row()

        memoryLabel = visLabel(memoryText()) { left() }
        row()

        visToggleButton(
            text = bundle.get("synapse.inhibitory"),
            checked = data().inhibitory,
            onCheckedChange = { on ->
                actionSystem.action = actionSystem.action.copy(special = data().copy(inhibitory = on))
            }
        ) { left().padTop(8.dp()).padBottom(32.dp()) }.hint(bundle.get("synapse.inhibitory.hint"))
        row()
    }

    override fun describeMutation(actual: Action, mutate: Action, out: StringBuilder) = with(mutate) {
        if (a != null && a != actual.a) out.append("${bundle.get("synapse.decayRate")}: ${actual.a} -> $a\n")
        if (b != null && b != actual.b) out.append("${bundle.get("synapse.learningRate")}: ${actual.b} -> $b\n")
        if (c != null && c != actual.c) out.append("${bundle.get("synapse.depression")}: ${actual.c} -> $c\n")
        val to = special as? SynapseData
        val from = actual.special as? SynapseData
        if (to != null && to.inhibitory != from?.inhibitory) {
            out.append("${bundle.get("synapse.inhibitory")}: ${from?.inhibitory} -> ${to.inhibitory}\n")
        }
        if (to != null && to.initialWeight != from?.initialWeight) {
            out.append("${bundle.get("synapse.initialWeight")}: ${from?.initialWeight} -> ${to.initialWeight}\n")
        }
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
