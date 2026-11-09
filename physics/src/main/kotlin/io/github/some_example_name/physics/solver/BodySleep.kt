package io.github.some_example_name.physics

/**
 * СОН ТЕЛ: организм, который долго стоит, перестаёт считаться.
 *
 * Нужен прежде всего рельефу карты: сотни тел лежат годами и ничего не делают, а
 * стоят столько же, сколько плывущие. Единица сна — организм (связная компонента),
 * а не частица и не блок: половина тела спать не может, иначе связи между спящей и
 * бодрствующей половиной тянули бы только одну сторону.
 *
 * ЗАСЫПАЕТ организм, у которого SLEEP_TICKS тиков подряд ни одна живая клетка не ушла
 * от места начала серии дальше SLEEP_DRIFT_CELLS, мышцы не работают и его не держит мышь.
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
 *  - касание клетки другого тела, ушедшей с начала тика дальше WAKE_DRIFT_CELLS (см.
 *    там, почему не любое касание); неподвижную частицу (isFixed) касание не будит;
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
 * ПОКОЙ МЕРИТСЯ МЕСТОМ, А НЕ СКОРОСТЬЮ: лёгкие клетки дрожат с частотой подшагов, и
 * мгновенная скорость неподвижного тела держится выше порога вечно (запись 22.10:
 * 280 клеток из 925); бывают и колебания с периодом в несколько тиков. См.
 * SLEEP_DRIFT_CELLS и TickMotion.
 *
 * ЦЕНА: удаление от места начала серии считается попутно в removeTickJitter,
 * отдельного прохода нет; место серии — 16 байт на частицу. Засыпание и пробуждение проходят диапазон индексов своего
 * организма, а не весь мир.
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

    /**
     * ДИАПАЗОНЫ БОДРСТВУЮЩИХ ЧАСТИЦ [spanStart, spanEnd): по ним идут поштучные проходы
     * (интегрирование, скорости, учёт импульса). У спящей частицы каждый такой проход —
     * пустая работа: скорость ноль, prevX = px. Организм лежит непрерывным куском
     * индексов, поэтому диапазонов столько, сколько чередований «спит/не спит», а не
     * частиц. Пересобираются одним проходом по частицам, только если в этом тике кто-то
     * уснул или проснулся. Без сна — один диапазон [0, n), порядок обхода прежний.
     */
    var spanStart = IntArray(1); private set
    var spanEnd = IntArray(1); private set
    var spanCount = 0; private set
    private var spansDirty = true

    fun isAsleep(organism: Int): Boolean = asleepCount > 0 && asleep[organism]

    /** Разбудить организм клетки в начале следующего подшага; бодрствующему — сбросить покой. */
    fun requestWake(cell: Int) {
        if (!on) return
        if (wakeQueueN == wakeQueue.size) wakeQueue = wakeQueue.copyOf(wakeQueue.size * 2)
        wakeQueue[wakeQueueN++] = cell
    }

    /** Счётчик засыпаний, пробуждений и сбросов: см. OrgBuckets. */
    internal var version = 0
        private set

    internal fun reset(n: Int, organismOf: IntArray, organismCount: Int) {
        version++
        if (asleep.size != n) {
            asleep = BooleanArray(n); calmTicks = IntArray(n); busy = BooleanArray(n)
            first = IntArray(n); last = IntArray(n)
        } else {
            asleep.fill(false); calmTicks.fill(0); busy.fill(false)
        }
        asleepCount = 0; wakeQueueN = 0; spansDirty = true
        java.util.Arrays.fill(first, 0, organismCount, Int.MAX_VALUE)
        java.util.Arrays.fill(last, 0, organismCount, -1)
        for (i in 0 until n) {
            val o = organismOf[i]
            if (i < first[o]) first[o] = i
            last[o] = i
        }
    }

    /**
     * МИР УПЛОТНЁН: организм [o] стал [orgNew][o] (-1 — удалён), частица [i] — [particleNew][i].
     * Сон, спокойствие и занятость оставшихся едут с ними, диапазоны частиц переводятся в
     * новые номера; просьбы разбудить удалённые клетки пропадают. Ёмкость — по числу частиц.
     */
    internal fun compact(orgNew: IntArray, orgCount: Int, particleNew: IntArray, n: Int) {
        version++
        val a = BooleanArray(n); val c = IntArray(n); val b = BooleanArray(n)
        val f = IntArray(n); val l = IntArray(n)
        var count = 0
        if (asleep.size >= orgCount) for (o in 0 until orgCount) {
            val k = orgNew[o]
            if (k < 0) continue
            a[k] = asleep[o]; c[k] = calmTicks[o]; b[k] = busy[o]
            f[k] = particleNew[first[o]]; l[k] = particleNew[last[o]]
            if (a[k]) count++
        }
        asleep = a; calmTicks = c; busy = b; first = f; last = l
        asleepCount = count
        var m = 0
        for (k in 0 until wakeQueueN) { val v = particleNew[wakeQueue[k]]; if (v >= 0) wakeQueue[m++] = v }
        wakeQueueN = m
        java.util.Arrays.fill(wakeQueue, m, wakeQueue.size, 0)
        spansDirty = true
    }

    /** Сон в снимок мира (WorldSnapshot): по организму, [orgCount] штук; просьбы разбудить. */
    internal fun write(out: java.io.DataOutput, orgCount: Int) {
        out.writeBoolean(on)
        out.writeBooleans(asleep, orgCount); out.writeInts(calmTicks, orgCount); out.writeBooleans(busy, orgCount)
        out.writeInts(first, orgCount); out.writeInts(last, orgCount)
        out.writeInts(wakeQueue, wakeQueueN)
    }

    /** Сон из снимка; ёмкость — по числу частиц [n], как в reset. Диапазоны соберутся заново. */
    internal fun read(input: java.io.DataInput, n: Int) {
        version++
        on = input.readBoolean()
        asleep = input.readBooleans(n); calmTicks = input.readInts(n); busy = input.readBooleans(n)
        first = input.readInts(n); last = input.readInts(n)
        val q = input.readInts(0)
        wakeQueueN = q.size
        wakeQueue = q.copyOf(maxOf(16, q.size))
        asleepCount = 0
        for (o in asleep.indices) if (asleep[o]) asleepCount++
        spansDirty = true
    }

    /**
     * Мир дописал тело: частицы [fromParticle, n), организмы [fromOrganism, organismCount).
     * Новые бодрствуют; сон прежних не трогается. Ёмкость — по числу частиц, как в reset.
     */
    internal fun append(n: Int, organismOf: IntArray, fromParticle: Int, fromOrganism: Int, organismCount: Int) {
        version++
        if (asleep.size < n) {
            asleep = asleep.copyOf(n); calmTicks = calmTicks.copyOf(n); busy = busy.copyOf(n)
            first = first.copyOf(n); last = last.copyOf(n)
        }
        java.util.Arrays.fill(asleep, fromOrganism, organismCount, false)
        java.util.Arrays.fill(calmTicks, fromOrganism, organismCount, 0)
        java.util.Arrays.fill(busy, fromOrganism, organismCount, false)
        java.util.Arrays.fill(first, fromOrganism, organismCount, Int.MAX_VALUE)
        java.util.Arrays.fill(last, fromOrganism, organismCount, -1)
        for (i in fromParticle until n) {
            val o = organismOf[i]
            if (i < first[o]) first[o] = i
            last[o] = i
        }
        spansDirty = true
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
        version++
        asleep[organism] = value
        if (value) asleepCount++ else asleepCount--
        calmTicks[organism] = 0
        spansDirty = true
    }

    internal fun rebuildSpansIfNeeded(n: Int, organismOf: IntArray) {
        if (!spansDirty) return
        spansDirty = false
        spanCount = 0
        if (asleepCount == 0) { addSpan(0, n); return }
        var start = -1
        for (i in 0 until n) {
            val awake = !asleep[organismOf[i]]
            if (awake && start < 0) start = i
            else if (!awake && start >= 0) { addSpan(start, i); start = -1 }
        }
        if (start >= 0) addSpan(start, n)
    }

    private fun addSpan(start: Int, end: Int) {
        if (spanCount == spanStart.size) {
            spanStart = spanStart.copyOf(spanCount * 2); spanEnd = spanEnd.copyOf(spanCount * 2)
        }
        spanStart[spanCount] = start; spanEnd[spanCount] = end; spanCount++
    }

    internal fun countCalm(organism: Int): Int = ++calmTicks[organism]
    internal fun resetCalm(organism: Int) { calmTicks[organism] = 0 }
}

/** Диапазоны бодрствующих частиц актуальны. Зовётся в начале каждого поштучного прохода: одна проверка. */
internal fun BodyState.prepareAwakeSpans() = sleep.rebuildSpansIfNeeded(n, organismOf)

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
 * Касание бодрствующей клетки, ушедшей с начала тика дальше порога, будит спящего
 * соседа. Зовётся после каждой подготовки контактов, до removeTickJitter: в
 * tickJitter ещё лежат положения начала тика. Пока никто не спит — одна проверка.
 * true — касание заказало пробуждение: будить надо до решения этих контактов (см. BodySimulation).
 */
internal fun BodyState.noteSleepContacts(ct: BoundaryContacts): Boolean {
    val s = sleep
    if (s.asleepCount == 0 || tickJitterX.size != n) return false
    val wake = WAKE_DRIFT_CELLS * linkScale
    val wake2 = wake * wake
    val ground = GROUND_WAKE_DRIFT_CELLS * linkScale
    val ground2 = ground * ground
    var any = false
    for (k in 0 until ct.contactCount) {
        val i = ct.contactI(k); val j = ct.contactJ(k)
        val si = s.asleep[organismOf[i]]; val sj = s.asleep[organismOf[j]]
        if (si == sj) continue
        val mover = if (si) j else i
        val sleeper = if (si) i else j
        if (isFixed(sleeper)) continue
        val dx = px[mover] - tickJitterX[mover]; val dy = py[mover] - tickJitterY[mover]
        val limit2 = if (cellHomeCompliance.size > sleeper && cellHomeCompliance[sleeper] > 0.0) ground2 else wake2
        if (dx * dx + dy * dy > limit2) { s.requestWake(sleeper); any = true }
    }
    return any
}

/**
 * Конец тика: счёт покоя и засыпание. Только бодрствующие организмы, по их
 * наибольшей средней скорости за тик.
 */
internal fun BodyState.updateSleep() {
    val s = sleep
    if (!s.on) return
    val drift = SLEEP_DRIFT_CELLS * linkScale
    val drift2 = drift * drift
    for (o in 0 until organismCount) {
        if (s.asleep[o]) continue
        if (s.takeBusy(o) || organismSize[o] == 0) { s.resetCalm(o); continue }
        // Ушла ли клетка от места начала серии дальше порога. На первом тике серии
        // место только поставлено (removeTickJitter), мерить нечего.
        if (s.calmTicks[o] > 0 && (orgSleepDrift2.size <= o || orgSleepDrift2[o] > drift2)) { s.resetCalm(o); continue }
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
        // Поштучные проходы спящего больше не видят (см. spanStart): всё, что они
        // обнуляли бы каждый подшаг, обнуляется здесь один раз.
        inContact[i] = false
        if (awake != null) awake[block!![i]]--
    }
    // Запас среды — скорость увлечённой воды; у стоящего тела ей не с чего быть.
    flowVX[o] = 0.0; flowVY[o] = 0.0
    sleep.setAsleep(o, true)
    parkBoundaryLimits(o, park = true)
    // Пары спящего со спящими соседями больше не нужны: см. BoundaryContacts.broadphase.
    contacts?.invalidateBroadphase()
}

private fun BodyState.wakeOrganism(o: Int) {
    val awake = constraintBlocks?.awake
    val block = constraintBlocks?.particleBlock
    for (i in sleep.first[o]..sleep.last[o]) {
        if (organismOf[i] != o) continue
        invMass[i] = if (cellDead[i]) 0.0 else restInvMass[i]
        // Средняя скорость за остаток тика — от места пробуждения. См. TickMotion.
        if (tickJitterX.size == n) { tickJitterX[i] = px[i]; tickJitterY[i] = py[i] }
        if (cancelSpinOn) tickMomentum.admitFrozen(i)
        if (awake != null) awake[block!![i]]++
    }
    sleep.setAsleep(o, false)
    parkBoundaryLimits(o, park = false)
    // Пары проснувшегося со спящими соседями в списке кандидатов отсутствуют.
    contacts?.invalidateBroadphase()
}

/**
 * Граничные связи спящего убираются из общего прохода предела длины и возвращаются
 * при пробуждении. Проход идёт по контуру всего мира 32 раза за тик, и у спящих он
 * ничего не делает: длины заморожены. Связи ищутся по рёбрам частиц организма —
 * локально, не по всему списку. Порядок остальных связей в списке меняется так же,
 * как при разрыве; без сна список прежний.
 */
private fun BodyState.parkBoundaryLimits(o: Int, park: Boolean) {
    val vs = baked.vertStart; val ve = baked.vertEdge
    val limits = boundaryLinkLimits
    for (i in sleep.first[o]..sleep.last[o]) {
        if (organismOf[i] != o) continue
        for (s in vs[i] until vs[i + 1]) {
            var c = conOfEdge[ve[s]]
            while (c >= 0) {
                if (park) limits.remove(c) else if (!conDead[c] && conBoundary[c]) limits.add(c)
                c = conNextForEdge[c]
            }
        }
    }
}

/** Выключить сон (клавиша стенда): все просыпаются сразу. */
fun BodyState.setSleepOn(value: Boolean) {
    if (!value) for (o in 0 until organismCount) if (sleep.asleep.size > o && sleep.asleep[o]) wakeOrganism(o)
    sleep.on = value
}

/**
 * Усыпить организм частицы [i] сейчас, не дожидаясь SLEEP_TICKS покоя (клиент: только что собранный
 * террейн стоит в покое по построению; ждать 60 тиков значило бы 60 тиков считать его целиком). Между
 * тиками; при выключенном сне — ничего.
 */
internal fun BodyState.sleepNow(i: Int) {
    if (!sleep.on || cellDead[i]) return
    val o = organismOf[i]
    if (o < 0 || sleep.isAsleep(o)) return
    sleepOrganism(o)
}
