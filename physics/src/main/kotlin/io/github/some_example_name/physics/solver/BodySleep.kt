package io.github.some_example_name.physics

/**
 * СОН ТЕЛ: организм, который долго стоит, перестаёт считаться.
 *
 * Нужен прежде всего рельефу карты: сотни тел лежат годами и ничего не делают, а
 * стоят столько же, сколько плывущие. Единица сна — организм (связная компонента),
 * а не частица и не блок: половина тела спать не может, иначе связи между спящей и
 * бодрствующей половиной тянули бы только одну сторону.
 *
 * ЗАСЫПАЕТ организм, у которого SLEEP_TICKS тиков подряд самая быстрая живая клетка
 * медленнее SLEEP_SPEED_CELLS_PER_TICK, мышцы не работают и его не держит мышь.
 * Касания засыпать НЕ мешают: куски рельефа лежат вплотную всегда, и запрет на
 * касание не дал бы им уснуть никогда. Покой и так проверяет скорость — тело, которое
 * толкают, не медленное.
 *
 * СПЯЩИЙ ОРГАНИЗМ ЗАМОРОЖЕН через обратную массу: invMass = 0, скорость ноль,
 * prevX = px. Решатель уже умеет такие частицы — так заморожены мёртвые клетки, —
 * поэтому почти ни одна стадия не меняется: интегрирование и скорости их пропускают,
 * ограничения с нулевым весом не двигают, контакт отдаёт всю поправку бодрствующей
 * стороне, то есть спящее тело для остальных — неподвижная стена. Настоящая масса
 * лежит в restInvMass, при пробуждении она возвращается (кроме мёртвых клеток).
 * Блоки решателя, где не осталось ни одной бодрствующей частицы, пропускаются
 * целиком (ConstraintBlocks.awake): это и есть основная экономия.
 *
 * БУДИТ:
 *  - касание клетки другого тела быстрее WAKE_SPEED_CELLS_PER_TICK (см. там, почему
 *    не любое касание);
 *  - мышь, схватившая клетку организма;
 *  - мышца организма, которой дали команду;
 *  - разрыв связи или гибель клетки организма (контакт может убить клетку спящего).
 * Будит запрос по номеру КЛЕТКИ, а не организма: между запросом и исполнением
 * организм может разделиться, и номер организма к тому времени устареет.
 * Исполняется запрос в начале следующего подшага, однопоточно и по порядку очереди.
 *
 * ДЕТЕРМИНИЗМ: решения принимаются в конце тика по номерам организмов, пробуждения —
 * в начале подшага по очереди запросов. Всё, что влияет на будущие тики (флаг сна,
 * счётчик покоя), лежит здесь и попадает в проверку скрытого состояния стенда.
 *
 * ЦЕНА: проверка покоя — один проход по частицам бодрствующих организмов за тик
 * (vx, vy, organismOf: 20 байт на частицу). Засыпание и пробуждение проходят
 * диапазон индексов своего организма, а не весь мир.
 */
class BodySleep(var on: Boolean) {
    /** Организм спит. Ёмкость — по числу клеток, как у organismSize. */
    var asleep = BooleanArray(0); private set

    /** Сколько тиков подряд организм спокоен. */
    var calmTicks = IntArray(0); private set

    /**
     * Диапазон индексов частиц организма [first, last]. Может быть шире настоящего:
     * после отделения куска старый организм свой диапазон не сужает. Внутри всё равно
     * сверяется organismOf, лишние индексы только стоят времени.
     */
    var first = IntArray(0); private set
    var last = IntArray(0); private set

    /** Сколько организмов спит. Ноль — все проверки сна выходят на первой строке. */
    var asleepCount = 0; private set

    /** Организм занят в этом тике (мышца, мышь, повреждение): спокойным не считается. */
    private var busy = BooleanArray(0)

    private var wakeQueue = IntArray(16)
    private var wakeQueueN = 0

    fun isAsleep(organism: Int): Boolean = asleepCount > 0 && asleep[organism]

    /** Разбудить организм клетки в начале следующего подшага; бодрствующему — сбросить покой. */
    fun requestWake(cell: Int) {
        if (!on) return
        if (wakeQueueN == wakeQueue.size) wakeQueue = wakeQueue.copyOf(wakeQueue.size * 2)
        wakeQueue[wakeQueueN++] = cell
    }

    internal fun reset(n: Int, organismOf: IntArray, organismCount: Int) {
        if (asleep.size != n) {
            asleep = BooleanArray(n); calmTicks = IntArray(n); busy = BooleanArray(n)
            first = IntArray(n); last = IntArray(n)
        } else {
            asleep.fill(false); calmTicks.fill(0); busy.fill(false)
        }
        asleepCount = 0; wakeQueueN = 0
        java.util.Arrays.fill(first, 0, organismCount, Int.MAX_VALUE)
        java.util.Arrays.fill(last, 0, organismCount, -1)
        for (i in 0 until n) {
            val o = organismOf[i]
            if (i < first[o]) first[o] = i
            last[o] = i
        }
    }

    /**
     * Кусок [fresh] отделился от [old]: состояние сна наследуется. Частицы отделившегося
     * куска уже заморожены, если спал родитель, — флаг обязан совпадать с ними.
     */
    internal fun inheritSplit(fresh: Int, old: Int, cells: IntArray, count: Int) {
        if (asleep.isEmpty()) return
        asleep[fresh] = asleep[old]
        if (asleep[fresh]) asleepCount++
        calmTicks[fresh] = calmTicks[old]
        busy[fresh] = busy[old]
        var lo = Int.MAX_VALUE; var hi = -1
        for (k in 0 until count) { val c = cells[k]; if (c < lo) lo = c; if (c > hi) hi = c }
        first[fresh] = lo; last[fresh] = hi
    }

    internal fun hasWakeRequests() = wakeQueueN > 0
    internal fun wakeRequestCount() = wakeQueueN
    internal fun wakeRequest(k: Int) = wakeQueue[k]
    internal fun clearWakeRequests() { wakeQueueN = 0 }

    internal fun markBusy(organism: Int) { busy[organism] = true }

    /** Забирает флаг занятости: true — организм был занят в этом тике. */
    internal fun takeBusy(organism: Int): Boolean {
        val b = busy[organism]
        busy[organism] = false
        return b
    }

    internal fun setAsleep(organism: Int, value: Boolean) {
        if (asleep[organism] == value) return
        asleep[organism] = value
        if (value) asleepCount++ else asleepCount--
        calmTicks[organism] = 0
    }

    internal fun countCalm(organism: Int): Int = ++calmTicks[organism]
    internal fun resetCalm(organism: Int) { calmTicks[organism] = 0 }
}

/** После полной разметки организмов (загрузка, сброс): все бодрствуют. */
internal fun BodyState.resetSleep() {
    sleep.reset(n, organismOf, organismCount)
}

/**
 * Начало тика: мышь и мышцы будят свои организмы и отмечают их занятыми.
 * Исполняется сразу, до учёта импульса за тик, чтобы обратная масса была одной на весь тик.
 */
internal fun BodyState.wakeByInput() {
    val s = sleep
    if (!s.on) return
    if (dragId >= 0) s.requestWake(dragId)
    val clusters = body.muscleClusters
    for (m in muscleActivation.indices) {
        val a = muscleActivation[m]
        val prev = if (m < muscleActivationPrev.size) muscleActivationPrev[m] else a
        if (kotlin.math.abs(muscleTarget[m] - a) <= SLEEP_MUSCLE_EPS &&
            kotlin.math.abs(a - prev) <= SLEEP_MUSCLE_EPS) continue
        for (c in clusters[m]) s.requestWake(c)
    }
    applyWakeRequests()
}

/** Исполнить запросы пробуждения. Пустая очередь — одна проверка. */
internal fun BodyState.applyWakeRequests() {
    val s = sleep
    if (!s.hasWakeRequests()) return
    for (k in 0 until s.wakeRequestCount()) {
        val o = organismOf[s.wakeRequest(k)]
        s.markBusy(o)
        if (s.asleep[o]) wakeOrganism(o)
    }
    s.clearWakeRequests()
}

/**
 * Касание бодрствующей клетки, движущейся быстрее порога, будит спящего соседа.
 * Зовётся после каждой подготовки контактов; пока никто не спит — одна проверка.
 */
internal fun BodyState.noteSleepContacts(ct: BoundaryContacts) {
    val s = sleep
    if (s.asleepCount == 0) return
    val wake = WAKE_SPEED_CELLS_PER_TICK * body.meanLinkLength / DT
    val wake2 = wake * wake
    for (k in 0 until ct.contactCount) {
        val i = ct.contactI(k); val j = ct.contactJ(k)
        val si = s.asleep[organismOf[i]]; val sj = s.asleep[organismOf[j]]
        if (si == sj) continue
        val mover = if (si) j else i
        val v2 = vx[mover] * vx[mover] + vy[mover] * vy[mover]
        if (v2 > wake2) s.requestWake(if (si) i else j)
    }
}

/**
 * Конец тика: счёт покоя и засыпание. Проходит только бодрствующие организмы и
 * только их диапазоны индексов.
 */
internal fun BodyState.updateSleep() {
    val s = sleep
    if (!s.on) return
    val calm = SLEEP_SPEED_CELLS_PER_TICK * body.meanLinkLength / DT
    val calm2 = calm * calm
    for (o in 0 until organismCount) {
        if (s.asleep[o]) continue
        if (s.takeBusy(o) || organismSize[o] == 0) { s.resetCalm(o); continue }
        var still = true
        for (i in s.first[o]..s.last[o]) {
            if (organismOf[i] != o || cellDead[i]) continue
            if (vx[i] * vx[i] + vy[i] * vy[i] > calm2) { still = false; break }
        }
        if (!still) { s.resetCalm(o); continue }
        if (s.countCalm(o) >= SLEEP_TICKS) sleepOrganism(o)
    }
}

private fun BodyState.sleepOrganism(o: Int) {
    val awake = constraintBlocks?.awake
    val block = constraintBlocks?.particleBlock
    for (i in sleep.first[o]..sleep.last[o]) {
        if (organismOf[i] != o) continue
        invMass[i] = 0.0
        vx[i] = 0.0; vy[i] = 0.0
        prevX[i] = px[i]; prevY[i] = py[i]
        if (awake != null) awake[block!![i]]--
    }
    // Запас среды — скорость увлечённой воды; у стоящего тела ей не с чего быть.
    flowVX[o] = 0.0; flowVY[o] = 0.0
    sleep.setAsleep(o, true)
}

private fun BodyState.wakeOrganism(o: Int) {
    val awake = constraintBlocks?.awake
    val block = constraintBlocks?.particleBlock
    for (i in sleep.first[o]..sleep.last[o]) {
        if (organismOf[i] != o) continue
        invMass[i] = if (cellDead[i]) 0.0 else restInvMass[i]
        if (awake != null) awake[block!![i]]++
    }
    sleep.setAsleep(o, false)
}

/** Выключить сон (клавиша стенда): все просыпаются сразу. */
fun BodyState.setSleepOn(value: Boolean) {
    if (!value) for (o in 0 until organismCount) if (sleep.asleep.size > o && sleep.asleep[o]) wakeOrganism(o)
    sleep.on = value
}
