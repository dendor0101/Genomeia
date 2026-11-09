package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.entities.CellEntity
import java.util.MissingResourceException
import io.github.some_example_name.old.systems.genomics.genome.LegacyTypeIds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Функция активации нейрона: что клетка выдаёт по своему входу x.
 *
 * [key] — постоянное имя формулы в геноме и сохранениях (формат файла, не меняется и не
 * переиспользуется). Номер формулы — место в [Formulas], его считают при запуске, и им
 * симуляция индексирует NeuralEntity.activationFuncType.
 *
 * [compute] вызывается на каждый нейрон каждый тик из параллельной фазы клеток: писать
 * можно только в поля своей клетки (cellIndex), без аллокаций. Вход x уже очищен от NaN и
 * бесконечностей, выход очищает activation.
 */
abstract class Formula(val key: String, val label: String) {
    abstract fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float

    /**
     * Выход зависит не только от входа x, но и от времени, памяти нейрона или состояния
     * клетки (Time, Remember, Spike, Energy…). Такую формулу стенд гайда рисует как сигнал
     * во времени, а не как кривую y(x): одному x у неё соответствует разный y. Симуляция
     * этим не пользуется.
     */
    open val dependsOnTime: Boolean get() = false

    /** Раздел справочника формул в гайде. Симуляция этим не пользуется. */
    open val group: FormulaGroup get() = if (dependsOnTime) FormulaGroup.MEMORY else FormulaGroup.SIGNAL

    /**
     * Стенд гайда рисует третьей линией память нейрона (NeuralEntity.remember): у формулы
     * она значит что-то своё, что стоит видеть (накопленное у lif). Симуляция этим не пользуется.
     */
    open val showsMemory: Boolean get() = false

    /**
     * Входы такой формулы обязаны складываться: редактор ставит «Сложение» и не даёт его
     * сменить. У lif произведение входов бессмысленно — один молчащий вход обнулял бы всё.
     */
    open val requiresSum: Boolean get() = false

    /**
     * Что делает формула — для гайда. Ключ в I18NBundle: `formula.<key>.description`; у
     * формулы мода без описания — пустая строка. Лениво: тесты :core живут без Gdx.
     */
    val description: String by lazy {
        try {
            bundle.get("formula.$key.description")
        } catch (e: MissingResourceException) {
            ""
        }
    }
}

/**
 * Разделы справочника формул в гайде, в порядке показа. Заголовок раздела в I18NBundle —
 * `guide.formulas.group.<имя в нижнем регистре>`.
 */
enum class FormulaGroup {
    /** Без памяти: каждому входу x свой выход y. */
    SIGNAL,

    /** Вход не важен: выход — время, сытость клетки, клавиша, число связей. */
    SOURCE,

    /** Помнят прошлые тики. */
    MEMORY,

    /** Для спайковых сетей: выход — короткие импульсы 0/1 или их частота. */
    SPIKES,
}

/**
 * Реестр формул. Порядок встроенных совпадает со старыми номерами (LegacyTypeIds.FORMULAS),
 * но опираться на это нельзя: в файлах формула записана ключом.
 *
 * Мод добавляет формулу через [register] до первого обращения к [all].
 *
 * ЦЕНА
 * ----
 * Раньше формула выбиралась `when` по номеру (tableswitch), теперь — виртуальным вызовом
 * через массив объектов. На вызов это один косвенный переход, как и у tableswitch, плюс
 * передача аргументов: единицы наносекунд на нейрон за тик. Тела формул и раньше не
 * инлайнились в фазу клеток — activation больше порога инлайна HotSpot.
 */
object Formulas {

    private val builtIn = listOf<Formula>(
        Linear, Sine, Cosine, Sigmoid, StepLe, StepLt, Time, Impulse, InRange, Pow,
        Remember, RandomRange, Red, Green, Blue, Energy, ControllerKey, Decay, Spike, Abs,
        LinksAmount, Identity, Lif, Trace,
    )

    private val modded = mutableListOf<Formula>()

    private var frozen = false

    fun register(formula: Formula) = synchronized(this) {
        check(!frozen) { "формулы регистрируются до первого обращения к списку" }
        check(CellTypes.MOD_KEY.matches(formula.key)) { "ключ формулы мода '${formula.key}' обязан быть вида 'modid:name'" }
        modded += formula
    }

    private val list: Array<Formula> by lazy {
        val all = synchronized(this) {
            frozen = true
            (builtIn + modded).toTypedArray()
        }
        // Номер формулы живёт в NeuralEntity.activationFuncType — байте со знаком.
        check(all.size <= Byte.MAX_VALUE) { "формул ${all.size}, а в байт номера влезает ${Byte.MAX_VALUE}" }
        check(all.map { it.key }.toSet().size == all.size) { "две формулы с одним ключом" }
        all
    }

    private val indexByKey: Map<String, Int> by lazy { list.withIndex().associate { (i, f) -> f.key to i } }

    /** Все формулы по номеру. Массив общий — не менять. */
    fun all(): Array<Formula> = list

    /** Номер формулы по ключу, -1 — такой нет (геном от мода, которого нет). */
    fun indexOfKey(key: String): Int = indexByKey[key] ?: -1

    fun requireIndex(key: String): Int = indexByKey[key] ?: throw IllegalStateException(
        "неизвестная формула '$key': геном или мир от мода, которого нет"
    )

    fun byKey(key: String): Formula = list[requireIndex(key)]

    /** Формула по умолчанию: новый нейрон и нейрон, у которого в геноме формулы нет. */
    val DEFAULT: Formula get() = Linear

    val defaultIndex: Int by lazy { requireIndex(DEFAULT.key) }

    /** Номер lif: Synapse по нему узнаёт спайковый постсинаптический нейрон. */
    val lifIndex: Int by lazy { requireIndex(Lif.key) }
}

// ---------------------------------------------------------------- встроенные формулы
// Тела — один в один из прежнего `when` в activation: от них зависит повтор старых
// записей и хэши состояния, менять математику здесь нельзя без StateHash.VERSION++.

private object Linear : Formula("linear", "y = ax + b") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.getA(cellIndex) * x + cells.getB(cellIndex)
}

private object Sine : Formula("sin", "y = c * sin(ax + b)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.getC(cellIndex) * sin(cells.getA(cellIndex) * x + cells.getB(cellIndex))
}

private object Cosine : Formula("cos", "y = c * cos(ax + b)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.getC(cellIndex) * cos(cells.getA(cellIndex) * x + cells.getB(cellIndex))
}

private object Sigmoid : Formula("sigmoid", "y = sigmoid(ax + b) + c") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        1f / (1f + exp(-(cells.getA(cellIndex) * x + cells.getB(cellIndex)))) + cells.getC(cellIndex)
}

private object StepLe : Formula("step_le", "y = b, x <= a; y = c, x > a") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        if (x <= cells.getA(cellIndex)) cells.getB(cellIndex) else cells.getC(cellIndex)
}

private object StepLt : Formula("step_lt", "y = b, x < a; y = c, x >= a") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        if (x < cells.getA(cellIndex)) cells.getB(cellIndex) else cells.getC(cellIndex)
}

private object Time : Formula("time", "y = t") {
    override val group get() = FormulaGroup.SOURCE
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.simulationData.timeSimulation + cellIndex
}

private object Impulse : Formula("impulse", "y = impulse(a), x>=1") {
    override val group get() = FormulaGroup.SPIKES
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        if (x >= 1f && simulationData.timeSimulation > getDTime(cellIndex)) {
            setDTime(cellIndex, simulationData.timeSimulation + getA(cellIndex))
        }

        if (simulationData.timeSimulation < getDTime(cellIndex)) {
            1f
        } else {
            setDTime(cellIndex, -1f)
            0f
        }
    }
}

private object InRange : Formula("in_range", "y = x in (a, b) else y = c") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        if (x > cells.getA(cellIndex) && x < cells.getB(cellIndex)) x else cells.getC(cellIndex)
}

private object Pow : Formula("pow", "y = x^(a)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = x.pow(cells.getA(cellIndex))
}

/**
 * Память: положительный вход запоминается, ноль её не трогает, отрицательный стирает.
 *
 * До 17.10 ноль тоже стирал (`x <= 0`), и формула работала как y = max(x, 0) — память не
 * держалась ни тика. Поменялась математика, поэтому StateHash.VERSION 5: хэши старых .bug
 * с этой формулой разошлись бы на первом же нуле на входе.
 */
private object Remember : Formula("remember", "y = remember(x), x > 0") {
    override val dependsOnTime get() = true

    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        if (x > 0f) {
            setRemember(cellIndex, x)
        } else if (x < 0f) {
            setRemember(cellIndex, 0f)
        }
        getRemember(cellIndex)
    }
}

private object RandomRange : Formula("random", "y = random(a, b)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        randomFromFloat(x, cells.getA(cellIndex), cells.getB(cellIndex))
}

private object Red : Formula("red", "y = r(x)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = (x * 255f).toInt().toFloat()
}

private object Green : Formula("green", "y = g(x)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = ((x * 255f).toInt() * 256).toFloat()
}

private object Blue : Formula("blue", "y = b(x)") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = ((x * 255f).toInt() * 65536).toFloat()
}

private object Energy : Formula("energy", "y = energy") {
    override val group get() = FormulaGroup.SOURCE
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.energy[cellIndex] / cells.maxEnergy[cellIndex]
}

/**
 * Клавиша контроллера с номером a (см. ControllerKeys). Осталась ради старых геномов;
 * в новых для этого есть клетка Controller, у которой клавиша хранится по имени.
 */
private object ControllerKey : Formula("controller", "y = controller(a)") {
    override val group get() = FormulaGroup.SOURCE
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        if (cells.simulationData.controllerKeys[cells.getA(cellIndex).toInt()]) 1f else 0f + x
}

private object Decay : Formula("decay", "y = decay(x - a), (b, c)") {
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        val decay = getA(cellIndex)
        val min = getB(cellIndex)
        val max = getC(cellIndex)

        if (x > min && x > getRemember(cellIndex)) {
            setRemember(cellIndex, x.coerceIn(min, max))
        }
        var impulse = getRemember(cellIndex)

        if (impulse > min) {
            impulse -= decay
        }
        impulse = impulse.coerceIn(min, max)

        setRemember(cellIndex, impulse)

        impulse
    }
}

private object Spike : Formula("spike", "y = spike(x) = 1, dx >= a") {
    override val group get() = FormulaGroup.SPIKES
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        val dx = x - getRemember(cellIndex)
        setRemember(cellIndex, x)

        if (dx > 0 && dx >= getA(cellIndex)) {
            setDTime(cellIndex, simulationData.tickCounter.toFloat())
            1f
        } else 0f
    }
}

private object Abs : Formula("abs", "y = |x|") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = abs(x)
}

private object LinksAmount : Formula("links_amount", "y = links amount") {
    override val group get() = FormulaGroup.SOURCE
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = cells.linkAmount[cellIndex].toFloat()
}

/**
 * Тождество. В редакторе его не было: так старая симуляция считала номера формул вне
 * списка (ветка else). Нужна, чтобы такие старые геномы и миры вели себя как раньше.
 */
private object Identity : Formula(LegacyTypeIds.IDENTITY_FORMULA, "y = x") {
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) = x
}

/**
 * Спайковый нейрон «копит, теряет, срабатывает» (leaky integrate-and-fire):
 * v = v·(1 − a) + x; v ≥ b — выход 1 ровно на один тик, v = 0 и пауза на c тиков; иначе 0.
 *
 * Так ровный вход становится частотой спайков: чем сильнее x, тем чаще на выходе 1. Этим
 * сенсор кодирует силу сигнала для Synapse, который понимает только спайки, а нейрон
 * за синапсами складывает их взвешенные вклады и срабатывает, когда их набралось на порог.
 *
 *  - a — какая доля накопленного теряется за тик, в [0, 1]: 1 — не копит совсем (просто
 *    порог), 0 — не теряет ничего;
 *  - b — порог; 0 и меньше — порог 1: у нового нейрона b = 0, а с порогом 0 он срабатывал
 *    бы от тишины;
 *  - c — пауза после спайка в тиках, не меньше 1: два спайка подряд — это ровная 1, и
 *    Synapse увидел бы в них один (он ловит рост сигнала).
 * Тормозящий вход опускает v не ниже −порога: иначе долгое торможение копилось бы без
 * предела, и нейрон потом молчал бы сотни тиков.
 *
 * Состояние: remember — накопленное v, dTime — сколько тиков паузы осталось. Обратный
 * отсчёт, а не номер тика конца паузы: dTime — float, и номер тика в нём точен только до
 * 2^24 (~6 суток мира при 30 UPS). У нового нейрона dTime = -1 — паузы нет.
 *
 * Только умножение, сложение и сравнения: на ПК и Android результат побитово одинаковый.
 * По цене — как linear.
 */
private object Lif : Formula("lif", "y = lif(x): v(1 - a) + x >= b") {
    override val dependsOnTime get() = true
    override val group get() = FormulaGroup.SPIKES
    override val showsMemory get() = true
    override val requiresSum get() = true

    /** Предел паузы: дальше это уже не пауза, а выключенный нейрон. */
    private const val MAX_PAUSE = 100_000

    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        val pause = getDTime(cellIndex)
        if (pause > 0f) {
            setDTime(cellIndex, pause - 1f)
            return 0f
        }

        // Сравнения, а не coerceIn: NaN из генома проходит coerceIn насквозь.
        val a = getA(cellIndex)
        val leak = if (a >= 1f) 1f else if (a > 0f) a else 0f
        val b = getB(cellIndex)
        val threshold = if (b > 0f) b else 1f

        val v = getRemember(cellIndex) * (1f - leak) + x
        if (v >= threshold) {
            setRemember(cellIndex, 0f)
            setDTime(cellIndex, getC(cellIndex).toInt().coerceIn(1, MAX_PAUSE).toFloat())
            return 1f
        }
        setRemember(cellIndex, if (v < -threshold) -threshold else v)
        0f
    }
}

/**
 * След: y = y·(1 − a) + b·x. Обратное к lif: превращает частоту спайков в плавный сигнал
 * для мышц, хвоста, хамелеона. Формулу ставят прямо исполнителю — отдельный нейрон не нужен.
 *
 * При спайках с частотой r (доля тиков со спайком) выход в среднем b·r/a, а при b = a —
 * сама частота: спайк каждый тик — 1, каждый пятый — 0.2.
 *  - a — какая доля выхода теряется за тик, в [0, 1]: больше — быстрее реакция (~1/a тиков
 *    на смену частоты), но выход дрожит между спайками; меньше — ровнее, но медленнее;
 *  - b — на сколько поднимает выход один спайк; 0 — как a. С a = 1, b = 0 нового нейрона
 *    формула просто передаёт вход: y = x.
 *
 * |y| < [CUTOFF] — ноль. Без этого затухающий хвост сотни тиков держал бы выход чуть выше
 * нуля (Excreta выделяет при любом положительном выходе), а потом стал бы денормализованным
 * числом — на некоторых процессорах арифметика с ними в десятки раз медленнее.
 *
 * Состояние: remember — сам y.
 */
private object Trace : Formula("trace", "y = y(1 - a) + bx") {
    override val dependsOnTime get() = true
    override val group get() = FormulaGroup.SPIKES

    private const val CUTOFF = 0.001f

    override fun compute(cells: CellEntity, cellIndex: Int, x: Float): Float = with(cells) {
        val a = getA(cellIndex)
        val leak = if (a >= 1f) 1f else if (a > 0f) a else 0f
        val b = getB(cellIndex)
        val gain = if (b == 0f || b.isNaN()) leak else b

        var y = getRemember(cellIndex) * (1f - leak) + gain * x
        if (y < CUTOFF && y > -CUTOFF) y = 0f
        setRemember(cellIndex, y)
        y
    }
}
