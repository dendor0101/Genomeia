package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.BodyDefinition
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * СИСТЕМА ОТСЧЁТА СЦЕНЫ — проверка симметрии (план стабильности, этап 1, пункт 3).
 *
 * Сцена пишется в своих координатах: «тело в точке (−4, 0), стена справа». Мир стенда (StandWorld)
 * ставит всё это в мир физики через систему отсчёта: сдвигает, поворачивает, отражает — и так же
 * обратно отдаёт сцене и правилам положения и скорости. Сама сцена о системе отсчёта не знает.
 *
 * Физике всё равно, где начало координат, куда смотрит ось и в каком порядке пронумерованы клетки.
 * Значит, та же сцена в другой системе отсчёта обязана дать тот же итог в координатах сцены — в
 * пределах допуска. Расхождение ловит:
 *   - сдвиг         — потерю точности вдали от начала координат и привязку к сетке контактов;
 *   - поворот       — оси, зашитые в решатель (обход по x, знак, округление синуса);
 *   - отражение     — перекос порядка обхода: «лево» и «право» считаются по-разному;
 *   - перенумерация — зависимость от номеров частиц: границы блоков решателя, чётность, порядок
 *                     клеток тела в памяти.
 *
 * Точка сцены (x, y) в мире: сначала отражение (y → −y), затем поворот на [angle], затем сдвиг.
 * Все числа — в единицах игры; в единицы мира физики переводит StandWorld.
 */
class Frame(
    val id: String,
    val title: String,
    val shiftX: Double = 0.0,
    val shiftY: Double = 0.0,
    val angle: Double = 0.0,
    val mirror: Boolean = false,
    /** Перенумерация: до сцены в мире заводится пустой блок слотов (все номера частиц сцены сдвигаются). */
    val pad: Boolean = false,
    /** Перенумерация: клетки каждого тела идут в памяти в обратном порядке. */
    val reverse: Boolean = false,
    /**
     * Разнобой: каждое тело и каждая частица сцены сдвинуты на свою малую долю этого расстояния (единицы
     * игры). В отличие от общего сдвига меняет зазоры между телами, а значит, и то, в какой точке подшага
     * они коснутся. Не система отсчёта, а контрольное возмущение: сцена, чей итог от него зависит,
     * чувствительна к фазе касания, и два разных решателя заведомо разойдутся в ней (Reference.kt).
     */
    val spread: Double = 0.0,
) {
    /** Сдвиг тела или частицы номер [index] при разнобое: от −2 до +2 долей, по номеру вразброс. */
    fun jiggle(index: Int): Double = if (spread == 0.0) 0.0 else spread * ((index * 7 + 3) % 5 - 2)
    private val cs = cos(angle)
    private val sn = sin(angle)
    private val my = if (mirror) -1.0 else 1.0

    /** Положения и направления не меняются вовсе (перенумерация их не трогает). */
    val still: Boolean get() = shiftX == 0.0 && shiftY == 0.0 && angle == 0.0 && !mirror
    val identity: Boolean get() = still && !pad && !reverse

    /** Угол кратен четверти оборота: только тогда прямоугольная рамка мира остаётся прямоугольной по осям. */
    val square: Boolean get() { val q = angle / (Math.PI / 2); return abs(q - Math.rint(q)) < 1e-12 }

    // Точка сцены → мир.
    fun x(sx: Double, sy: Double): Double = shiftX + sx * cs - my * sy * sn
    fun y(sx: Double, sy: Double): Double = shiftY + sx * sn + my * sy * cs
    // Вектор (скорость, сдвиг) сцены → мир.
    fun dx(vx: Double, vy: Double): Double = vx * cs - my * vy * sn
    fun dy(vx: Double, vy: Double): Double = vx * sn + my * vy * cs
    // Точка мира → сцена.
    fun sceneX(wx: Double, wy: Double): Double = (wx - shiftX) * cs + (wy - shiftY) * sn
    fun sceneY(wx: Double, wy: Double): Double = my * (-(wx - shiftX) * sn + (wy - shiftY) * cs)
    // Вектор мира → сцена.
    fun sceneDx(vx: Double, vy: Double): Double = vx * cs + vy * sn
    fun sceneDy(vx: Double, vy: Double): Double = my * (-vx * sn + vy * cs)
    /** Угол поворота тела сцены в мире. */
    fun turn(a: Double): Double = angle + my * a
    /** Знак вращения: отражение меняет направление «против часовой». */
    val spin: Double get() = my

    override fun toString(): String = id

    companion object {
        /** Сцена как написана. */
        val SCENE = Frame("scene", "как написана")

        /**
         * Малый сдвиг: и проверка (итог обязан совпасть), и мерило — насколько сцена сама разносит
         * ничтожное возмущение. Хаотичную сцену (толпа, таран) по одному прогону судить нельзя.
         */
        val NUDGE = Frame("nudge", "малый сдвиг (0.37, 0.19)", shiftX = 0.37, shiftY = 0.19)
        /** Контрольное возмущение для сверки с эталоном: тела сдвинуты вразнобой на сотые доли клетки (до 0.1 связи). */
        val PHASE = Frame("phase", "малый разнобой тел", spread = 0.03)
        val SHIFT = Frame("shift", "сдвиг на (1000, 700) клеток", shiftX = 1000.0, shiftY = 700.0)
        val TURN = Frame("turn", "поворот на 37°", angle = Math.toRadians(37.0))
        /** Для сцен с рамкой мира: прямоугольную рамку можно повернуть только на четверть оборота. */
        val QUARTER = Frame("turn", "поворот на 90°", angle = Math.PI / 2)
        val MIRROR = Frame("mirror", "отражение", mirror = true)
        val ORDER = Frame("order", "перенумерация частиц", pad = true, reverse = true)
        /** Две половины перенумерации по отдельности — для разбора, какая из них меняет итог (-Psymmetry=pad,reverse). */
        val PAD = Frame("pad", "сдвиг номеров частиц", pad = true)
        val REVERSE = Frame("reverse", "клетки тел в обратном порядке", reverse = true)

        /** Проверки симметрии по порядку отчёта. Поворот для сцены с рамкой мира — четверть оборота. */
        val CHECKS: List<Frame> = listOf(SHIFT, TURN, MIRROR, ORDER)

        /** Все системы отсчёта, которые можно назвать в -Psymmetry. */
        val NAMED: List<Frame> = CHECKS + listOf(PAD, REVERSE, NUDGE)

        fun byId(id: String): Frame? = (NAMED + SCENE).firstOrNull { it.id == id }
    }
}

/** Сцена просит то, чего в этой системе отсчёта сделать нельзя (рамку мира под углом 37°). */
class FrameUnfit(message: String) : RuntimeException(message)

/**
 * То же тело с клетками в обратном порядке: клетка k описания становится клеткой slot[k].
 * Связи, треугольники и куски костей и мышц — те же, в новых номерах; связи и треугольники тоже
 * идут в обратном порядке. Ориентация треугольников сохраняется.
 */
internal fun BodyDefinition.renumbered(slot: IntArray): BodyDefinition {
    val n = count
    fun cells(a: FloatArray) = FloatArray(n).also { out -> for (k in 0 until n) out[slot[k]] = a[k] }
    fun cells(a: BooleanArray) = BooleanArray(n).also { out -> for (k in 0 until n) out[slot[k]] = a[k] }
    fun backwards(a: IntArray) = IntArray(a.size) { slot[a[a.size - 1 - it]] }
    val triN = triCount
    // Временные связи роста идут вместе со своими связями (в обратном порядке), их клетки — в новых номерах.
    val retireStart = if (hasTempLinks) IntArray(linkCount + 1) else IntArray(0)
    val retireCells = IntArray(linkRetireCells.size)
    if (hasTempLinks) {
        var m = 0
        for (j in 0 until linkCount) {
            val l = linkCount - 1 - j
            for (s in linkRetireStart[l] until linkRetireStart[l + 1]) {
                val p = linkRetireCells[2 * s + 1]
                retireCells[m++] = slot[linkRetireCells[2 * s]]; retireCells[m++] = if (p < 0) p else slot[p]
            }
            retireStart[j + 1] = m / 2
        }
    }
    return BodyDefinition(
        x = cells(x), y = cells(y), radius = cells(radius), isBone = cells(isBone), isMuscle = cells(isMuscle),
        linkA = backwards(linkA), linkB = backwards(linkB),
        triA = backwards(triA), triB = backwards(triB), triC = backwards(triC),
        triRestArea2 = FloatArray(triN) { triRestArea2[triN - 1 - it] },
        boneClusters = Array(boneClusters.size) { c -> IntArray(boneClusters[c].size) { slot[boneClusters[c][it]] }.also { it.sort() } },
        muscleClusters = Array(muscleClusters.size) { c -> IntArray(muscleClusters[c].size) { slot[muscleClusters[c][it]] }.also { it.sort() } },
        scale = scale,
        linkRetireStart = retireStart, linkRetireCells = retireCells,
    )
}
