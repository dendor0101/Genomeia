package io.github.some_example_name.physics

/** Начальный размер принятого блочного решателя, ещё не оптимум для всех CPU.
 * 21.10: плотный вариант 1000 при 16×4 ускорил запись на 8,3%, но замедлил 100k.
 * Оставлен по решению пользователя также как основа будущей параллельности.
 * Размер меняет порядок стадий и численный результат: подбирать с проверкой повторов.
 */
const val CONSTRAINT_BLOCK_SIZE = 1000

/**
 * ТЕЛА ДЛЯ РАЗБИЕНИЯ НА БЛОКИ — связные части запечённого графа по ВСЕМ рёбрам, включая порванные
 * и нерождённые. Разбиение по текущим организмам зависело от истории: мёртвая или нерождённая
 * клетка и оторванный кусок — отдельный организм посреди диапазона тела, и блок резался на
 * каждой такой границе. Замер 09.11, 34 червя (96.8k клеток, 16 подшагов): после уплотнения
 * мира 1401 блок, из них 1256 меньше 100 частиц, соседи через две связи выходили за соседний
 * блок, и весь мир считался в одном потоке — 374–395 мс на тик. По телам: 108 блоков, 156–169 мс.
 */
private fun BodyState.bakedBodies(): IntArray {
    val body = IntArray(n) { -1 }
    val queue = IntArray(n)
    val start = baked.vertStart; val other = baked.vertOther
    var count = 0
    for (s in 0 until n) {
        if (body[s] >= 0) continue
        var head = 0; var tail = 0
        body[s] = count; queue[tail++] = s
        while (head < tail) {
            val v = queue[head++]
            for (k in start[v] until start[v + 1]) {
                val u = other[k]
                if (body[u] < 0) { body[u] = count; queue[tail++] = u }
            }
        }
        count++
    }
    return body
}

internal fun BodyState.initializeConstraintBlocks() {
    val layout = constraintBlocks ?: ConstraintBlocks(n, bakedBodies(), CONSTRAINT_BLOCK_SIZE,
        conA.size, triA.size, rigidBones.size).also { constraintBlocks = it }
    layout.links.clear(); layout.limits.clear(); layout.areas.clear(); layout.bones.clear()
    layout.bends.clear(); layout.flaps.clear(); layout.edges.clear(); layout.triangleSlot.fill(-1)
    for (c in 0 until conCount) {
        layout.links.add(c, layout.blockOf(conA[c], conB[c]))
        if (conBoundary[c] && !conDead[c]) layout.addLimit(c, conA[c], conB[c])
    }
    for (t in 0 until triCount) {
        layout.areas.add(t, layout.blockOf(triA[t], triB[t], triC[t]))
        layout.triangleSlot[triId[t]] = t
    }
    // Список у порога наполнит первый же ранний проход пределов: сюда addLimit сложил весь контур.
    layout.nearCount.fill(0)
    // Связи блока — в порядке номеров, а не по плану DistanceSweepOrder: план переставляет только операции над
    // непересекающимися частицами, итог побитово тот же (PERFHASH совпал), а данные связей (conRest, conA, conB,
    // conCellScale, conSoftness) читаются подряд. По плану номера в обходе скакали с шагом ~43: 65% обращений в
    // новую кэш-линию против ~12% подряд. Замер 10.11, 34 червя, 3 пары по 600 тиков: проход блоков
    // 16.66/16.20/16.41 → 15.54/15.46/15.44 мс. Независимые цепочки арифметики плана стоили меньше, чем промахи.
    layout.links.finishBuild(); layout.areas.finishBuild(); layout.buildDispatchOrder()
    for (b in rigidBones.indices) {
        val ids = rigidBones[b]
        var block = layout.particleBlock[ids[0]]
        for (i in ids) if (layout.particleBlock[i] != block) { block = layout.seamBucket[layout.particleBlock[ids[0]]]; break }
        layout.bones.add(b, block)
    }
    for (c in 0 until bendCount) layout.bends.add(c, layout.blockOf(bendA[c], bendB[c]))
    layout.awake.fill(0)
    for (i in 0 until n) if (!sleep.isAsleep(organismOf[i])) layout.awake[layout.particleBlock[i]]++
    for (c in 0 until flapCount) layout.flaps.add(c, layout.blockOf(flapA[c], flapB[c]))
    localBoundary.bend.blockLayout = layout; localBoundary.bend.blockIndex = layout.bends
    localBoundary.flap.blockLayout = layout; localBoundary.flap.blockIndex = layout.flaps
    for (k in 0 until localBoundary.count) layout.edges.add(k, localBoundary.blockOfSlot(k, layout))
    localBoundary.blockLayout = layout; localBoundary.blockIndex = layout.edges
    computeSerialBlocks(layout)
    // Черновики воркеров по размерам этой топологии: после сброса собрать заново.
    parallelPhysicsState = null
}

/** Внутренние ограничения блока подряд; швы исполняются один раз после обеих
 * чётностей. Контакты остаются общим барьером: их перенос внутрь каждого блока
 * потребовал бы отдельного распределения межблочных контактов и костей.
 * Сейчас обход последовательный: общие очереди пределов/разрушения, обновление
 * топологии и счётчики ещё не имеют отдельных владельцев для разных воркеров.
 */
/**
 * [integrate] — интегрировать частицы блока прямо перед его ограничениями, а не
 * отдельным проходом по всему миру до обхода. Частицы блока тогда уже в кэше, когда
 * до них доходят связи и площади. Результат побитово прежний: интегрирование
 * считается для каждой частицы отдельно, ограничения блока трогают только его
 * частицы, а шов решается после обеих чётностей, когда проинтегрированы все.
 * Пропущенный спящий блок интегрировать незачем: у его частиц prevX и так равен px.
 */
internal fun BodyState.solveBlockedIteration(h: Double, integrate: Boolean = false) {
    val layout = constraintBlocks!!
    val w = activeWorkers
    if (w != null && parallelBlocksAllowed(w, layout)) {
        // Блоки одной чётности — по воркерам; швы и доработка событий — здесь. См. ParallelBlocks.
        tick0(); parallelPhysics(w).solveBlocks(h, integrate, layout); tick1(0)
    } else for (parity in 0..1) {
        var block = parity
        while (block < layout.blockCount) {
            // Спящий блок: все частицы заморожены сном, двигать нечего. См. BodySleep.
            if (layout.awake[block] > 0) solveBlockSerial(h, block, layout, integrate)
            block += 2
        }
    }
    if (w != null && parallelBlocksAllowed(w, layout) && layout.buckets - layout.seam > 1) {
        tick0(); parallelPhysics(w).solveSeams(h, layout); tick1(0)
    } else for (bucket in layout.seam until layout.buckets) solveConstraintBlock(h, bucket, layout)
    // Шов или кость через границу блока могли растянуть уже обработанный контур.
    // Закрываем его до контактов; это дополнительный проход, его цена входит в A/B.
    if (layout.hasSeams()) { tick0(); solveLinkMaxLength(); tick1(2); refreshSurface() }
}

/** Блок целиком в вызывающем потоке: обычный обход и тела из ConstraintBlocks.serialBlock. */
internal fun BodyState.solveBlockSerial(h: Double, block: Int, layout: ConstraintBlocks, integrate: Boolean) {
    if (integrate) { tick0(); integrateRange(layout.firstParticle[block], layout.firstParticle[block + 1], h); tick1(15) }
    solveConstraintBlock(h, block, layout)
}

private fun BodyState.solveConstraintBlock(h: Double, block: Int, layout: ConstraintBlocks) =
    solveConstraintBlockFrom(h, block, layout, BLOCK_STAGE_START, 0)

/** Стадии блока, с которых можно продолжить: остановка параллельного прохода — только в пределах. */
internal const val BLOCK_STAGE_START = 0
internal const val BLOCK_STAGE_LIMITS_EARLY = 1
internal const val BLOCK_STAGE_LIMITS_LATE = 2

/**
 * Ограничения блока подряд, начиная со стадии [stage] и записи [cursor] её очереди
 * пределов. С середины начинает только доработка блока, который параллельный проход
 * остановил перед событием топологии (ParallelBlocks): всё до этой точки уже
 * посчитано воркером той же арифметикой.
 */
internal fun BodyState.solveConstraintBlockFrom(h: Double, block: Int, layout: ConstraintBlocks,
                                                stage: Int, cursor: Int) {
    if (stage == BLOCK_STAGE_START) {
        tick0(); dbgMark(); solveBlockLinks(h, block, layout); dbgMeasure(0, h); tick1(0)
        tick0(); dbgMark(); solveBlockFlaps(h, block, layout); dbgMeasure(9, h); tick1(1)
    }
    if (stage <= BLOCK_STAGE_LIMITS_EARLY) {
        val from = if (stage == BLOCK_STAGE_LIMITS_EARLY) cursor else 0
        if (MAXLEN_EARLY) { tick0(); dbgMark(); solveBlockLimits(block, layout, from); dbgMeasure(4, h); tick1(2); refreshSurface() }
        if (slowConstraintsNow) {
            if (areasOn) { tick0(); dbgMark(); solveBlockAreas(h * SLOW_CONSTRAINT_PERIOD, block, layout); dbgMeasure(1, h); tick1(3) }
            tick0(); dbgMark(); solveBlockBend(h * SLOW_CONSTRAINT_PERIOD, block, layout); dbgMeasure(2, h); tick1(4)
        }
        tick0(); dbgMark()
        if (bonesRigid) {
            var b = layout.bones.head[block]
            while (b >= 0) {
                // Кость держит позу по matchWeight, а не по invMass: спящую надо пропустить явно.
                if (!sleep.isAsleep(organismOf[rigidBones[b][0]])) projectBone(b)
                b = layout.bones.next[b]
            }
        }
        dbgMeasure(3, h); tick1(5)
    }
    val from = if (stage == BLOCK_STAGE_LIMITS_LATE) cursor else 0
    // После раннего прохода — только по связям у порога, которые он собрал. См. ConstraintBlocks.nearItems.
    // Разбору крайних значений нужен весь контур; воркеров при нём нет (parallelBlocksAllowed).
    tick0(); dbgMark()
    if (MAXLEN_EARLY && !(DEBUG_CHECKS && dbgLinkExtremaOn)) solveBlockNearLimits(block, layout, from)
    else solveBlockLimits(block, layout, from)
    dbgMeasure(4, h); tick1(2); refreshSurface()
}

internal fun BodyState.solveBlockLinks(h: Double, block: Int, layout: ConstraintBlocks) {
    // Мышц-кластеров нет (игра: мышца — множитель клетки): без чтения conMuscle. См. solveBlockLinksCells.
    if (cellScalesOn && muscleScaleOf.isEmpty()) { solveBlockLinksCells(h, block, layout); return }
    val alpha = SOFT_COMPLIANCE / (h * h)
    val px = px; val py = py; val mass = invMass
    val a = conA; val b = conB; val rest = conRest; val muscles = conMuscle
    val dead = conDead; val scales = muscleScaleOf; val cellScales = if (cellScalesOn) conCellScale else null
    val soft = if (softnessOn) conSoftness else null; val cellScaleAll = conCellScale
    val list = layout.links
    if (sweepBackwards) {
        for (slot in list.start[block] + list.count[block] - 1 downTo list.start[block]) {
            val c = list.items[slot]
            if (c < 0) continue
            if (soft != null) solveDistanceConstraintSoft(c, c, px, py, mass, a, b, rest, muscles, dead, scales, cellScaleAll, soft, alpha)
            else if (cellScales == null) solveDistanceConstraint(c, c, px, py, mass, a, b, rest, muscles, dead, scales, alpha)
            else solveDistanceConstraintScaled(c, c, px, py, mass, a, b, rest, muscles, dead, scales, cellScales, alpha)
        }
    } else {
        for (slot in list.start[block] until list.start[block] + list.count[block]) {
            val c = list.items[slot]
            if (c < 0) continue
            if (soft != null) solveDistanceConstraintSoft(c, c, px, py, mass, a, b, rest, muscles, dead, scales, cellScaleAll, soft, alpha)
            else if (cellScales == null) solveDistanceConstraint(c, c, px, py, mass, a, b, rest, muscles, dead, scales, alpha)
            else solveDistanceConstraintScaled(c, c, px, py, mass, a, b, rest, muscles, dead, scales, cellScales, alpha)
        }
    }
}

/**
 * Связи блока без мышц-кластеров: muscleScaleOf пуст, значит у каждой связи conMuscle < 0, и ядро его
 * не читает. Те же биты: множитель мышцы при m < 0 равен 1.0, а 1.0 · cs — ровно cs. На связь потоком
 * читается на 4 байта меньше (было 29: conDead, conA, conB, conMuscle, conRest, conCellScale, conSoftness).
 */
private fun BodyState.solveBlockLinksCells(h: Double, block: Int, layout: ConstraintBlocks) {
    val alpha = SOFT_COMPLIANCE / (h * h)
    val px = px; val py = py; val mass = invMass
    val a = conA; val b = conB; val rest = conRest; val dead = conDead; val cs = conCellScale
    val soft = if (softnessOn) conSoftness else null
    val list = layout.links
    val from = list.start[block]; val to = from + list.count[block]
    if (sweepBackwards) {
        for (slot in to - 1 downTo from) {
            val c = list.items[slot]
            if (c < 0) continue
            if (soft != null) solveDistanceConstraintSoftCells(c, px, py, mass, a, b, rest, dead, cs, soft, alpha)
            else solveDistanceConstraintCells(c, px, py, mass, a, b, rest, dead, cs, alpha)
        }
    } else {
        for (slot in from until to) {
            val c = list.items[slot]
            if (c < 0) continue
            if (soft != null) solveDistanceConstraintSoftCells(c, px, py, mass, a, b, rest, dead, cs, soft, alpha)
            else solveDistanceConstraintCells(c, px, py, mass, a, b, rest, dead, cs, alpha)
        }
    }
}
