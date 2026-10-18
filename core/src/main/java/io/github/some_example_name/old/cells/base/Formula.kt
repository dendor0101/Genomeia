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
        LinksAmount, Identity,
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
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.simulationData.timeSimulation + cellIndex
}

private object Impulse : Formula("impulse", "y = impulse(a), x>=1") {
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
    override val dependsOnTime get() = true
    override fun compute(cells: CellEntity, cellIndex: Int, x: Float) =
        cells.energy[cellIndex] / cells.maxEnergy[cellIndex]
}

/**
 * Клавиша контроллера с номером a (см. ControllerKeys). Осталась ради старых геномов;
 * в новых для этого есть клетка Controller, у которой клавиша хранится по имени.
 */
private object ControllerKey : Formula("controller", "y = controller(a)") {
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
