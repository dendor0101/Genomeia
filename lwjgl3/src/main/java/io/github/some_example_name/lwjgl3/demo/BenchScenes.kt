package io.github.some_example_name.lwjgl3.demo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * СЦЕНЫ СТОЛКНОВЕНИЙ, ОБЩИЕ ДЛЯ СТЕНДА И ДЛЯ ДЕМО.
 *
 * ПОЧЕМУ ОБЩИЕ, А НЕ ДВЕ КОПИИ. Стенд считает числа без окна, демо показывает то же
 * самое глазами. Это разные потребители одной постановки, и если расставлять тела в
 * двух местах, копии молча разъедутся — в этом проекте так уже трижды случалось с
 * копией решателя, и каждый раз это стоило часа поисков. Здесь расстановка одна, и
 * совпадение стенда с картинкой структурное, а не удача.
 *
 * ЗАЧЕМ СМОТРЕТЬ ГЛАЗАМИ, если есть числа. Стенд гоняет топологию, собранную им же:
 * рассыпал тело, разложил клетки. В живой симуляции та же куча получается ИНАЧЕ — из
 * разрушения, с пересборкой контура, смежности и списка исключений. Если стенд
 * спокоен, а в демо та же сцена дребезжит, значит ломается не решатель контактов, а
 * что-то в пересборке после разрушения. Ровно так уже находилась ошибка, из-за которой
 * бывшие соседи переставали сталкиваться.
 *
 * КОНТЕКСТ передаётся снаружи, потому что демо владеет массивами напрямую, а стенд
 * добирается до них рефлексией. Сцене всё равно, кто их дал.
 */
class SceneCtx(
    val n: Int,
    val px: DoubleArray, val py: DoubleArray,
    val prevX: DoubleArray, val prevY: DoubleArray,
    val vx: DoubleArray, val vy: DoubleArray,
    /**
     * Тело клетки берётся ФУНКЦИЕЙ, а не массивом. После shatter топология
     * пересобирается и organismOf становится НОВЫМ массивом — ссылка, снятая заранее,
     * молча указывала бы на старую разбивку, и сцена расставляла бы клетки не тех тел.
     */
    val organismOfCell: (Int) -> Int,
    val organismSizeOf: (Int) -> Int,
    /**
     * Число тел — тоже ФУНКЦИЕЙ, и по той же причине, что organismOfCell. После
     * разрыва компоненты пересобираются, и снятое заранее число новых кусков не
     * видит вовсе: связи умирают, кусок отваливается, а сцена его не находит.
     */
    val organismCount: () -> Int,
    val isFreeOf: (Int) -> Boolean,
    /** Обратная масса: ноль делает клетку неподвижной стенкой. */
    val invMass: DoubleArray,
    /** Контактный радиус клетки — по нему считается шаг стенки. */
    val contactRadiusOf: (Int) -> Double,
    val meanLink: Double,
    val dt: Double,
    /** Потолок скорости в клетках за тик — сцены задают скорости в его долях. */
    val vmax: Double,
    /** Объявить все мягкие связи организма мёртвыми и пересобрать топологию. */
    val shatter: (Int) -> Unit,
    /** Прогнать один тик симуляции. */
    val step: () -> Unit,
    /** Прогнать один тик С СОКРАЩЕНИЕМ МЫШЦ — то же, что клавиша G. */
    val stepContract: () -> Unit,
    /** Входит ли клетка в жёсткий костный кластер. */
    val inBoneOf: (Int) -> Boolean,
    /**
     * Навести камеру на точку. Сцена обязана звать это, если уводит всё лишнее прочь:
     * иначе в окне остаётся пустота, а числа при этом считаются как ни в чём не бывало.
     * В стенде окна нет, там это пустышка.
     */
    val focus: (Double, Double) -> Unit,
)

/** Одна сцена: имя и расстановка. Угол и поперечный сдвиг перебирает вызывающий. */
class BenchScene(val name: String, val vary: Boolean, val setup: (SceneCtx, Double, Double) -> Unit)

object BenchScenes {

    /** Окно гребка для сцены с куском, в тиках. См. охоту в CollisionBench. */
    const val GAIT_FROM = 280
    const val GAIT_TO = 730

    /** Центр масс по геометрии — сценам достаточно среднего по клеткам. */
    fun com(c: SceneCtx, o: Int): DoubleArray {
        var x = 0.0; var y = 0.0; var k = 0
        for (i in 0 until c.n) if (c.organismOfCell(i) == o) { x += c.px[i]; y += c.py[i]; k++ }
        return if (k == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(x / k, y / k)
    }

    /**
     * Клетки тела, снятые СПИСКОМ. Брать надо ДО рассыпания: shatter пересобирает
     * связные компоненты, каждая одиночная клетка становится своим организмом, и номер
     * тела после этого указывает уже на одну случайную клетку. На этом сцены и не
     * срабатывали — тело рассыпалось, а расстановка отбирала пустоту.
     */
    fun cellsOf(c: SceneCtx, o: Int): List<Int> =
        (0 until c.n).filter { c.organismOfCell(it) == o }

    /** Два самых крупных тела: на них и ставятся все сцены. */
    fun twoLargest(c: SceneCtx): IntArray {
        val order = (0 until c.organismCount()).sortedByDescending { c.organismSizeOf(it) }
        val a = order[0]
        val b = if (order.size > 1) order[1] else order[0]
        return intArrayOf(a, b)
    }

    /** Ось между телами и перпендикуляр к ней. */
    fun axis(c: SceneCtx, oa: Int, ob: Int): DoubleArray {
        val ca = com(c, oa); val cb = com(c, ob)
        var ax = cb[0] - ca[0]; var ay = cb[1] - ca[1]
        val len = sqrt(ax * ax + ay * ay)
        if (len < 1e-9) return doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        ax /= len; ay /= len
        return doubleArrayOf(ax, ay, -ay, ax)
    }

    fun rotateAndShift(c: SceneCtx, o: Int, deg: Double, offCells: Double,
                       perpX: Double, perpY: Double) {
        val ctr = com(c, o)
        val a = Math.toRadians(deg); val cs = cos(a); val sn = sin(a)
        for (i in 0 until c.n) {
            if (c.organismOfCell(i) != o) continue
            val x = c.px[i] - ctr[0]; val y = c.py[i] - ctr[1]
            c.px[i] = ctr[0] + x * cs - y * sn + perpX * offCells * c.meanLink
            c.py[i] = ctr[1] + x * sn + y * cs + perpY * offCells * c.meanLink
            c.prevX[i] = c.px[i]; c.prevY[i] = c.py[i]
        }
    }

    fun launch(c: SceneCtx, o: Int, dx: Double, dy: Double, cellsPerTick: Double) {
        val sp = cellsPerTick * c.meanLink / c.dt
        for (i in 0 until c.n) if (c.organismOfCell(i) == o) {
            c.vx[i] = dx * sp; c.vy[i] = dy * sp
        }
    }

    /**
     * Уложить СВОБОДНЫЕ клетки тела в плотную решётку, а костные убрать со сцены.
     *
     * Костные обязательно исключаются: shatter убивает лишь мягкие связи, внутрикостные
     * остаются, и уложив кость на решётку, мы ломаем её жёсткую позу. projectBone тут же
     * возвращает её обратно на BONE_MAX_STEP за подшаг — это восемь клеток за тик, и
     * замер показывает не кучу, а раздавленную кость.
     */
    fun packFree(c: SceneCtx, cells: List<Int>, cx: Double, cy: Double, stepCells: Double) {
        val ids = cells.filter { c.isFreeOf(it) }
        for (k in cells) {
            if (c.isFreeOf(k)) continue
            c.px[k] += 900.0 * c.meanLink
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
        }
        if (ids.isEmpty()) return
        val side = Math.ceil(sqrt(ids.size.toDouble())).toInt().coerceAtLeast(1)
        val st = stepCells * c.meanLink
        for ((k, i) in ids.withIndex()) {
            c.px[i] = cx + (k % side - side / 2.0) * st
            c.py[i] = cy + (k / side - side / 2.0) * st
            c.prevX[i] = c.px[i]; c.prevY[i] = c.py[i]
            c.vx[i] = 0.0; c.vy[i] = 0.0
        }
    }

    /**
     * МАЯТНИК: клетка, висящая на ОДНОЙ связи, и лёгкое касание по ней.
     *
     * Ровно тот случай, который виден руками: от одного маленького прикосновения такая
     * клетка отскакивает несоразмерно сильно. У неё нет соседей, которые разделили бы
     * поправку, и вся она уходит в одну клетку.
     */
    fun pendulumCell(c: SceneCtx, o: Int, degree: (Int) -> Int): Int {
        var best = -1
        for (i in 0 until c.n) {
            if (c.organismOfCell(i) != o) continue
            if (degree(i) != 1) continue
            best = i
            break
        }
        return best
    }

    /**
     * ЗАМКНУТАЯ ЁМКОСТЬ, НАБИТАЯ ЧАСТИЦАМИ. Самая злая сцена из всех.
     *
     * ЧЕМ ОНА ОТЛИЧАЕТСЯ ОТ ПРОСТОЙ КУЧИ, и почему без неё замер обманывает. Свободная
     * куча РАЗЛЕТАЕТСЯ: перекрытие уходит в разлёт, давление падает, и через пару секунд
     * там тихо. Замер на ней показывал «починено», хотя руками дребезг никуда не девался.
     * В ёмкости уйти некуда — частицы остаются в плотном контакте столько, сколько идёт
     * симуляция, и каждая держит по шесть соседей одновременно. Это и есть тот режим, в
     * котором дребезг живёт.
     *
     * СТЕНКА — закреплённые клетки (обратная масса ноль). Они участвуют в контактах
     * наравне с прочими (одиночная клетка попадает в contact-множество как изолированная),
     * но не двигаются: вся поправка уходит второй стороне пары. Шаг по кольцу берётся от
     * их же контактного радиуса, чтобы круги соседей ПЕРЕКРЫВАЛИСЬ — иначе частицы
     * начнут просачиваться наружу и сцена перестанет быть замкнутой.
     */
    fun container(c: SceneCtx, cells: List<Int>, cx: Double, cy: Double,
                  fillCells: Int, packStep: Double) {
        val free = cells.filter { c.isFreeOf(it) }
        if (free.isEmpty()) return

        // Клетки, не ставшие свободными (кости), со сцены убираем: уложив кость на
        // решётку, мы сломали бы её жёсткую позу, и projectBone разнёс бы сцену.
        for (k in cells) {
            if (c.isFreeOf(k)) continue
            c.px[k] += 900.0 * c.meanLink
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
        }

        // Под стенку резервируется столько, сколько ей реально нужно, а не половина всех
        // клеток: кольцу хватает пары сотен, а остальное должно уйти в засыпку — иначе
        // ёмкость стоит полупустой при полном запасе частиц.
        val wallBudget = 240
        val fill = minOf(fillCells, maxOf(1, free.size - wallBudget))
        val inside = free.take(fill)
        val rest = free.drop(fill)

        // ЗАСЫПКА ЗАПОЛНЯЕТ КРУГ, А НЕ ВПИСАННЫЙ В НЕГО КВАДРАТ.
        //
        // Раньше клетки клались квадратной решёткой row-major, а кольцо описывалось
        // вокруг квадрата. Квадрат занимает лишь около половины площади круга, четыре
        // сегмента по краям оставались пустыми — ёмкость выглядела заполненной наполовину.
        //
        // Теперь наоборот: радиус считается ИЗ ЧИСЛА ЧАСТИЦ, а они раскладываются по
        // всем узлам решётки внутри круга. Укладка шестиугольная — ряды смещены на
        // полшага, расстояние между рядами st*sqrt(3)/2. Это плотнейшая упаковка кругов
        // на плоскости, то есть то самое «битком», которое и хотелось видеть.
        val st = packStep * c.meanLink
        val rowH = st * sqrt(3.0) / 2.0
        // Площадь на одну частицу в шестиугольной укладке равна st * rowH.
        val fillRadius = sqrt(fill * st * rowH / Math.PI)

        // Шаг стенки: половина контактного диаметра, тогда круги заведомо перекрыты.
        val wallR = if (rest.isNotEmpty()) c.contactRadiusOf(rest[0]) else 0.3 * c.meanLink
        // Кольцо отодвинуто от засыпки на диаметр частицы, чтобы она не стартовала
        // прижатой к стенке — иначе сцена начинается с контакта, которого не задумывали.
        val radius = fillRadius + 2.0 * wallR
        val stepLen = wallR
        val wallCount = minOf(rest.size, Math.ceil(2.0 * Math.PI * radius / stepLen).toInt())
        for (w in 0 until wallCount) {
            val k = rest[w]
            val a = 2.0 * Math.PI * w / wallCount
            c.px[k] = cx + radius * cos(a)
            c.py[k] = cy + radius * sin(a)
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
            c.invMass[k] = 0.0                 // стенка неподвижна
        }
        // ЛИШНИЕ КЛЕТКИ РАСКЛАДЫВАЮТСЯ РЕДКОЙ РЕШЁТКОЙ, а не сдвигаются на месте.
        // Сдвиг сохранял исходную форму тела, где соседи стоят ближе порога контакта:
        // за кадром оставалось плотное взаимно перекрытое облако, оно шумело наравне
        // со сценой и попадало во все замеры. Шаг берётся заведомо больше порога пары.
        //
        // ШАГ СЧИТАЕТСЯ ПО САМОЙ КРУПНОЙ КЛЕТКЕ, А НЕ ПО ПЕРВОЙ ПОПАВШЕЙСЯ. Радиусы у
        // клеток разные, порог пары равен СУММЕ двух радиусов. Пока шаг брался от
        // радиуса rest[0], пара крупных клеток в парке оставалась перекрытой навсегда,
        // и парк шумел сильнее самой сцены: замер показал 8-9 клеток за тик у клетки
        // ВНЕ засыпки при 0.8 внутри неё.
        var parkR = 0.0
        for (w in wallCount until rest.size) {
            val r = c.contactRadiusOf(rest[w]); if (r > parkR) parkR = r
        }
        val parkStep = 2.0 * parkR * 1.25
        for (w in wallCount until rest.size) {
            val k = rest[w]
            val idx = w - wallCount
            val row = idx / 40
            c.px[k] = cx + 60.0 * c.meanLink + (idx % 40) * parkStep
            c.py[k] = cy + row * parkStep
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
        }

        // Обход узлов шестиугольной решётки внутри круга, от центра наружу по рядам.
        // Клетки кончаются раньше узлов или наоборот — что кончится первым, тем и
        // ограничимся: лишние узлы просто останутся пустыми, лишние клетки уедут в парк.
        val sites = ArrayList<DoubleArray>(fill * 2)
        val rows = Math.ceil(fillRadius / rowH).toInt() + 1
        for (r in -rows..rows) {
            val y = r * rowH
            val half = fillRadius * fillRadius - y * y
            if (half <= 0.0) continue
            val xSpan = sqrt(half)
            val shift = if (r % 2 == 0) 0.0 else st / 2.0
            var q = -Math.ceil((xSpan + shift) / st).toInt()
            while (q * st + shift <= xSpan) {
                val x = q * st + shift
                if (x * x + y * y <= fillRadius * fillRadius) sites.add(doubleArrayOf(x, y))
                q++
            }
        }
        // Ближе к центру — первыми, чтобы при нехватке клеток ёмкость заполнялась
        // изнутри, а не оставалась дырявой в середине.
        sites.sortBy { it[0] * it[0] + it[1] * it[1] }

        val placed = minOf(inside.size, sites.size)
        for (idx in 0 until placed) {
            val k = inside[idx]
            c.px[k] = cx + sites[idx][0]
            c.py[k] = cy + sites[idx][1]
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
        }
        // Клетки, которым узлов не хватило, — в парк, к прочим лишним.
        for (idx in placed until inside.size) {
            val k = inside[idx]
            c.px[k] = cx + 60.0 * c.meanLink + (idx % 40) * parkStep
            c.py[k] = cy - 20.0 * parkStep - (idx / 40) * parkStep
            c.prevX[k] = c.px[k]; c.prevY[k] = c.py[k]
            c.vx[k] = 0.0; c.vy[k] = 0.0
        }
    }

    /**
     * Набор сцен. Порядок фиксирован: демо и стенд ходят по нему одним индексом, и
     * номер сцены в отчёте стенда совпадает с тем, что показывает демо.
     */
    fun all(): List<BenchScene> = listOf(
        BenchScene("лобовой", true) { c, ang, off ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val ax = axis(c, oa, ob)
            rotateAndShift(c, ob, ang, off, ax[2], ax[3])
            launch(c, oa, ax[0], ax[1], 0.8 * c.vmax)
            launch(c, ob, -ax[0], -ax[1], 0.8 * c.vmax)
        },
        BenchScene("вскользь", true) { c, ang, off ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val ax = axis(c, oa, ob)
            rotateAndShift(c, ob, ang, off + 10.0, ax[2], ax[3])
            launch(c, oa, ax[0], ax[1], 0.8 * c.vmax)
            launch(c, ob, -ax[0], -ax[1], 0.8 * c.vmax)
        },
        BenchScene("мягкое касание", true) { c, ang, off ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val ax = axis(c, oa, ob)
            rotateAndShift(c, ob, ang, off, ax[2], ax[3])
            launch(c, oa, ax[0], ax[1], 0.5)
            launch(c, ob, -ax[0], -ax[1], 0.5)
        },
        BenchScene("два толчка", true) { c, ang, off ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val ax = axis(c, oa, ob)
            rotateAndShift(c, ob, ang, off, ax[2], ax[3])
            launch(c, oa, ax[0], ax[1], 0.8 * c.vmax)
            launch(c, ob, -ax[0], -ax[1], 0.8 * c.vmax)
            for (fr in 1..(1.0 / c.dt).toInt()) c.step()
            launch(c, oa, ax[0], ax[1], 0.8 * c.vmax)
            launch(c, ob, -ax[0], -ax[1], 0.8 * c.vmax)
        },
        BenchScene("бассейн одиночных", false) { c, _, _ ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val cells = cellsOf(c, ob)
            val ctr = com(c, oa)
            c.shatter(ob)
            packFree(c, cells, ctr[0] + 14.0 * c.meanLink, ctr[1], 0.6)
        },
        BenchScene("куча плотнее", false) { c, _, _ ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val cells = cellsOf(c, ob)
            val ctr = com(c, oa)
            c.shatter(ob)
            packFree(c, cells, ctr[0] + 14.0 * c.meanLink, ctr[1], 0.4)
        },
        BenchScene("куча в теле", false) { c, _, _ ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val cells = cellsOf(c, ob)
            val ctr = com(c, oa)
            c.shatter(ob)
            packFree(c, cells, ctr[0], ctr[1], 0.6)
        },
        // Замкнутая ёмкость: давлению уйти некуда, дребезг виден сразу. См. container.
        BenchScene("ёмкость, плотно", false) { c, _, _ ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val cells = cellsOf(c, ob)
            val ctr = com(c, oa)
            c.shatter(ob)
            // Шаг 1.15 связи — чуть БОЛЬШЕ порога пары (он около 1.01 связи), то есть
            // частицы стоят вплотную, но без начального перекрытия.
            container(c, cells, ctr[0] + 26.0 * c.meanLink, ctr[1], 550, 1.15)
        },
        BenchScene("ёмкость, битком", false) { c, _, _ ->
            val (oa, ob) = twoLargest(c).let { it[0] to it[1] }
            val cells = cellsOf(c, ob)
            val ctr = com(c, oa)
            c.shatter(ob)
            // Шаг 0.85 связи — заметно ниже порога, засыпка стартует сжатой.
            container(c, cells, ctr[0] + 26.0 * c.meanLink, ctr[1], 550, 0.85)
        },
        /**
         * ОТОРВАВШИЙСЯ КУСОК, ПОЛУЧЕННЫЙ НАСТОЯЩИМ РАЗРУШЕНИЕМ.
         *
         * ПОЧЕМУ ИМЕННО ТАК, А НЕ НАРИСОВАННЫЙ РУКАМИ. Всё, что мы про кусок думаем,
         * держится на том, КАК он оторвался: какие связи умерли, какие клетки стали
         * граничными, кто кому перестал быть соседом. Кусок, выложенный по картинке,
         * этой истории не имеет, и стенд на нём проверял бы не ту физику. Поэтому
         * здесь гоняется тот же гребок, что по клавише G, пока кусок не отвалится сам.
         *
         * Дальше всё лишнее уезжает со сцены, а куску обнуляются скорости. Дальше он
         * обязан просто стоять: внешних сил нет, гравитации нет. Любое движение после
         * этого — энергия из ниоткуда, и видно, откуда именно она берётся.
         */
        BenchScene("оторванный кусок", false) { c, _, _ ->
            val whole = twoLargest(c)[0]
            val origin = IntArray(c.n) { c.organismOfCell(it) }
            // ОКНО ГРЕБКА ЗАФИКСИРОВАНО, и это не произвол. Момент включения гребка
            // решает, что именно оторвётся, поэтому окно перебрали (-Phunt=1) и взяли
            // то, где кусок мечется сильнее всех: путь вчетверо больше сноса.
            // Пока окно плавало, сцена ловила первый попавшийся здоровый кусок и
            // отвечала «всё хорошо», хотя руками дребезг виден каждый запуск.
            var t = 0
            while (t < GAIT_TO) {
                if (t >= GAIT_FROM) c.stepContract() else c.step()
                t++
            }
            val piece = looseFragment(c, whole, origin)
            if (piece < 0) return@BenchScene
            val mine = BooleanArray(c.n)
            for (i in cellsOf(c, piece)) mine[i] = true
            var fx = 0.0; var fy = 0.0; var fc = 0
            for (i in 0 until c.n) {
                if (mine[i]) {
                    c.vx[i] = 0.0; c.vy[i] = 0.0; c.prevX[i] = c.px[i]; c.prevY[i] = c.py[i]
                    fx += c.px[i]; fy += c.py[i]; fc++
                    continue
                }
                c.px[i] += 600.0 * c.meanLink
                c.prevX[i] = c.px[i]; c.prevY[i] = c.py[i]
                c.vx[i] = 0.0; c.vy[i] = 0.0
            }
            if (fc > 0) c.focus(fx / fc, fy / fc)
        },
    )

    /**
     * Самый крупный кусок, ОТДЕЛИВШИЙСЯ от тела: не само тело и не одиночная клетка.
     *
     * Нижняя граница в три клетки не случайна: у пары и у одиночки нет ни контура, ни
     * внутренних связей, и разговор про «дрожат пограничные частицы» к ним неприменим.
     */
    fun looseFragment(c: SceneCtx, whole: Int, origin: IntArray): Int {
        for (f in fragments(c, whole, origin)) if (!f.bony) return f.id
        return fragments(c, whole, origin).firstOrNull()?.id ?: -1
    }

    class Frag(val id: Int, val size: Int, val bony: Boolean, val topY: Double)

    /**
     * Все куски, отделившиеся ОТ ТЕЛА, сверху вниз.
     *
     * Порядок по высоте, а не по размеру: разговор идёт про кусок, который
     * отваливается сверху. Костяные помечены отдельно — жёсткий кластер живёт по
     * своим правилам, и мерить на нём мягкую ткань бессмысленно.
     */
    fun fragments(c: SceneCtx, whole: Int, origin: IntArray): List<Frag> {
        val cnt = c.organismCount()
        val size = IntArray(cnt); val fromWhole = IntArray(cnt)
        val inBone = IntArray(cnt); val sumY = DoubleArray(cnt)
        for (i in 0 until c.n) {
            val o = c.organismOfCell(i)
            if (o < 0 || o >= cnt) continue
            size[o]++
            sumY[o] += c.py[i]
            if (origin[i] == whole) fromWhole[o]++
            if (c.inBoneOf(i)) inBone[o]++
        }
        val out = ArrayList<Frag>()
        for (o in 0 until cnt) {
            if (o == whole || size[o] < 3) continue
            if (fromWhole[o] * 2 < size[o]) continue
            out.add(Frag(o, size[o], inBone[o] * 2 >= size[o], sumY[o] / size[o]))
        }
        out.sortByDescending { it.topY }
        return out
    }
}
