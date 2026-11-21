package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * ВАРИАЦИЯ СЦЕНЫ: те же клетки и те же связи, но другие длины связей и радиусы клеток.
 *
 * Зачем. Фигуры каталога стоят на ровной решётке редактора: каждая связь 0.6, каждая клетка
 * радиуса 0.5. В игре так бывает редко: редактор ставит дочь на расстоянии от 0.6 до 0.9 суммы
 * радиусов родителя и дочери (CellPlacement.CENTER_MIN..CENTER_MAX в :core), радиус клетки —
 * от 0.2 до 0.5 (CHILD_RADIUS_MIN..MAX), а связь появляется у каждой пары клеток, которые
 * касаются (расстояние меньше суммы радиусов). Решатель, отлаженный на ровной решётке, на
 * таком теле ведёт себя иначе: радиус касания клетки — половина её самой длинной связи по
 * контуру, масса — квадрат радиуса. Поэтому каждая сцена каталога идёт во всех вариациях.
 *
 * Что вариация обязана сохранить — правила редактора, иначе тело нельзя было бы собрать в игре:
 *  - связанные клетки стоят не ближе 0.6 суммы своих радиусов и ближе этой суммы;
 *  - несвязанные клетки не перекрываются (инвариант «перекрытие = связь»).
 * Обе проверки — в VariationTest, по всем фигурам каталога.
 *
 * Как это устроено. Раскладка костей и мышц, выбор клеток «крайняя правая», «ближайшая к
 * центру» считаются на ровной решётке и одинаковы у всех вариаций; меняется только поза покоя и
 * радиусы (ShapeSpec.x, y, r). Какая вариация действует, знает поток: сцена собирается и
 * шагает внутри [use], и фигуры в это время отдают позу своей вариации. Так тысяча готовых
 * сцен получила вариации без правки каждой.
 */
enum class Variation(val id: String, val title: String, val about: String,
                     /** Во сколько раз растянута решётка: связь 0.6 превращается в 0.6 · stretch. */
                     val stretch: Double,
                     /** Наибольший случайный сдвиг клетки от узла решётки, единиц игры. */
                     private val jitter: Double) {
    BASE("base", "ровная решётка", "Все связи 0.6, все клетки радиуса 0.5 — как у тела, собранного в редакторе вплотную.", 1.0, 0.0),
    LONG("long", "длинные связи", "Все связи 0.9 — самое дальнее деление, которое разрешает редактор; клетки радиуса 0.5.", 1.5, 0.0),
    MIXLEN("len", "случайные длины связей", "Связи случайной длины от 0.61 до 0.95; клетки радиуса 0.5.", 1.3, 0.085),
    RADII("rad", "разные радиусы клеток", "Клетки случайного радиуса от 0.34 до 0.5 и отдельные мелкие 0.2–0.28; связи 0.6.", 1.0, 0.0),
    SMALL("small", "мелкие клетки", "Все клетки радиуса 0.25, все связи 0.3: тело вдвое меньше и вчетверо легче.", 0.5, 0.0),
    MIXED("mix", "длины и радиусы вместе", "Связи случайной длины от 0.62 до 0.82, клетки случайного радиуса от 0.42 до 0.5.", 1.2, 0.05);

    /** Окончание номера сцены: у ровной решётки его нет, чтобы прежние номера не поменялись. */
    val suffix: String get() = if (this == BASE) "" else "@$id"

    /**
     * Во сколько раз раздвигать тела, расставленные сценой по готовым координатам (толпа по
     * кругу, стая решёткой): тело на растянутой решётке больше. Мелкие клетки расстановку не
     * сжимают — телам просто дальше лететь.
     */
    val spread: Double get() = maxOf(1.0, stretch)

    /**
     * Случайна ли геометрия: клетки сдвинуты кто куда или радиусы (а с ними и массы) разные. У такой
     * вариации зеркальная фигура перестаёт быть зеркальной, и требовать от неё «среда не уводит в
     * сторону» нельзя (15.11: круг и квадрат со случайными радиусами уходили вбок на 1–6 связей).
     */
    val uneven: Boolean get() = this == MIXLEN || this == RADII || this == MIXED

    /** Меняет ли вариация радиусы: такие вариации есть и у сцен из одних свободных частиц. */
    val changesRadius: Boolean get() = this == RADII || this == SMALL || this == MIXED

    /** Поза покоя и радиусы клеток фигуры на решётке ([lx], [ly]) со связями [linkA]–[linkB]. */
    internal fun geometry(lx: DoubleArray, ly: DoubleArray, linkA: IntArray, linkB: IntArray, seed: Long): Geometry {
        val n = lx.size
        if (this == BASE) return Geometry(lx, ly, DoubleArray(n) { GAME_RADIUS })
        val rnd = Random(seed * 31 + ordinal)
        val x = DoubleArray(n); val y = DoubleArray(n)
        for (k in 0 until n) {
            // Сдвиг — не меньше половины наибольшего: иначе почти все связи вышли бы средней длины.
            val angle = 2 * Math.PI * rnd.nextDouble()
            val reach = jitter * (0.5 + 0.5 * rnd.nextDouble())
            x[k] = lx[k] * stretch + reach * cos(angle)
            y[k] = ly[k] * stretch + reach * sin(angle)
        }
        val r = DoubleArray(n)
        when (this) {
            SMALL -> r.fill(0.25)
            MIXED -> for (k in 0 until n) r[k] = 0.42 + 0.08 * rnd.nextDouble()
            RADII -> {
                for (k in 0 until n) r[k] = 0.34 + 0.16 * rnd.nextDouble()
                // Мелкая клетка — только среди крупных: её связь 0.6 обязана быть короче суммы
                // радиусов, поэтому соседи мелкой — не меньше 0.45, а две мелкие рядом не стоят.
                val small = BooleanArray(n)
                val nearSmall = BooleanArray(n)
                val start = IntArray(n + 1)
                for (e in linkA.indices) { start[linkA[e] + 1]++; start[linkB[e] + 1]++ }
                for (k in 0 until n) start[k + 1] += start[k]
                val fill = start.copyOf(); val other = IntArray(start[n])
                for (e in linkA.indices) { other[fill[linkA[e]]++] = linkB[e]; other[fill[linkB[e]]++] = linkA[e] }
                for (k in 0 until n) {
                    val pick = rnd.nextDouble() < 0.14
                    val size = rnd.nextDouble()
                    if (!pick || nearSmall[k]) continue
                    small[k] = true
                    r[k] = 0.2 + 0.08 * size
                    for (s in start[k] until start[k + 1]) nearSmall[other[s]] = true
                }
                for (k in 0 until n) if (nearSmall[k] && !small[k] && r[k] < 0.45) r[k] = 0.45 + 0.05 * (r[k] - 0.34) / 0.16
            }
            else -> r.fill(GAME_RADIUS)
        }
        return Geometry(x, y, r)
    }

    /** Радиус свободной частицы номер [index] сцены: у вариаций с радиусами частицы тоже разные. */
    fun ballRadius(index: Int): Double = when (this) {
        RADII -> 0.2 + 0.3 * unit(index)
        SMALL -> 0.25
        MIXED -> 0.3 + 0.2 * unit(index)
        else -> GAME_RADIUS
    }

    /** Средний радиус свободной частицы к обычному 0.5: во столько раз сжимается шаг набивки полости. */
    val ballScale: Double get() = when (this) { RADII -> 0.7; SMALL -> 0.5; MIXED -> 0.8; else -> 1.0 }

    /** Число из [0, 1) по номеру — без общего генератора: порядок вызовов на радиус не влияет. */
    private fun unit(index: Int): Double {
        var h = index * -0x61c88647 + ordinal * 0x2545F491
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
        return (h and 0xFFFFFF) / 16777216.0
    }

    companion object {
        val ALL: List<Variation> = values().toList()
        /** Сцена без тел: длины связей менять не у чего, остаются только вариации с радиусами. */
        val BALLS: List<Variation> = listOf(BASE, RADII, SMALL, MIXED)
        /** Тело из генома: длины и радиусы у него свои. */
        val ONLY_BASE: List<Variation> = listOf(BASE)

        private val active = ThreadLocal.withInitial { BASE }

        /** Вариация, в которой сейчас собирается или шагает сцена на этом потоке. */
        val current: Variation get() = active.get()

        /** Выполнить [block] в вариации [v]; прежняя возвращается и при исключении. */
        inline fun <T> use(v: Variation, block: () -> T): T {
            val before = swap(v)
            try { return block() } finally { swap(before) }
        }

        @PublishedApi internal fun swap(v: Variation): Variation { val before = active.get(); active.set(v); return before }

        fun byId(id: String): Variation? = values().firstOrNull { it.id == id }
    }
}

/** Поза покоя и радиусы клеток фигуры в одной вариации, единицы игры. */
class Geometry(val x: DoubleArray, val y: DoubleArray, val r: DoubleArray) {
    // Фигура стоит серединой своей рамки в нуле (Lattice.fromCells), поэтому это настоящие полуразмеры.
    val halfWidth: Double = if (x.isEmpty()) 0.0 else x.maxOf { kotlin.math.abs(it) }
    val halfHeight: Double = if (y.isEmpty()) 0.0 else y.maxOf { kotlin.math.abs(it) }
    /** Радиус круга вокруг центра фигуры, в который она помещается вместе с радиусами клеток. */
    val reach: Double = x.indices.maxOfOrNull { kotlin.math.sqrt(x[it] * x[it] + y[it] * y[it]) + r[it] } ?: 0.0
    /** Радиусы касания клеток — считает фигура, когда они впервые понадобятся (ShapeSpec.contact). */
    @Volatile internal var contact: DoubleArray? = null
}

/** Геометрии одной решётки по вариациям: общие у фигуры и всех её раскладок костей и мышц. */
internal class GeometryCache {
    private val byVariation = arrayOfNulls<Geometry>(Variation.values().size)
    fun get(v: Variation, build: () -> Geometry): Geometry {
        byVariation[v.ordinal]?.let { return it }
        synchronized(this) {
            byVariation[v.ordinal]?.let { return it }
            return build().also { byVariation[v.ordinal] = it }
        }
    }
}
