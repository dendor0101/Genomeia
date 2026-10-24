package io.github.some_example_name.physics

/** Начальный размер принятого блочного решателя, ещё не оптимум для всех CPU.
 * 21.10: плотный вариант 1000 при 16×4 ускорил запись на 8,3%, но замедлил 100k.
 * Оставлен по решению пользователя также как основа будущей параллельности.
 * Размер меняет порядок стадий и численный результат: подбирать с проверкой повторов.
 */
const val CONSTRAINT_BLOCK_SIZE = 1000

internal fun BodyState.initializeConstraintBlocks() {
    val layout = constraintBlocks ?: ConstraintBlocks(n, organismOf, CONSTRAINT_BLOCK_SIZE,
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
    layout.links.finishBuild(distanceSweepOrder?.forward); layout.areas.finishBuild()
    for (b in rigidBones.indices) {
        val ids = rigidBones[b]
        var block = layout.particleBlock[ids[0]]
        for (i in ids) if (layout.particleBlock[i] != block) { block = layout.seam; break }
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
    layout.parallelSafe = computeParallelSafe(layout)
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
    val first = layout.firstParticle
    val w = activeWorkers
    if (w != null && parallelBlocksAllowed(w, layout)) {
        // Блоки одной чётности — по воркерам; швы и доработка событий — здесь. См. ParallelBlocks.
        tick0(); parallelPhysics(w).solveBlocks(h, integrate, layout); tick1(0)
    } else for (parity in 0..1) {
        var block = parity
        while (block < layout.blockCount) {
            // Спящий блок: все частицы заморожены сном, двигать нечего. См. BodySleep.
            if (layout.awake[block] > 0) {
                if (integrate) { tick0(); integrateRange(first[block], first[block + 1], h); tick1(15) }
                solveConstraintBlock(h, block, layout)
            }
            block += 2
        }
    }
    solveConstraintBlock(h, layout.seam, layout)
    // Шов или кость через границу блока могли растянуть уже обработанный контур.
    // Закрываем его до контактов; это дополнительный проход, его цена входит в A/B.
    if (layout.hasSeams()) { tick0(); solveLinkMaxLength(); tick1(2); refreshSurface() }
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
        if (areasOn) { tick0(); dbgMark(); solveBlockAreas(h, block, layout); dbgMeasure(1, h); tick1(3) }
        tick0(); dbgMark(); solveBlockBend(h, block, layout); dbgMeasure(2, h); tick1(4)
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
    tick0(); dbgMark(); solveBlockLimits(block, layout, from); dbgMeasure(4, h); tick1(2); refreshSurface()
}

internal fun BodyState.solveBlockLinks(h: Double, block: Int, layout: ConstraintBlocks) {
    val alpha = SOFT_COMPLIANCE / (h * h)
    val px = px; val py = py; val mass = invMass
    val a = conA; val b = conB; val rest = conRest; val muscles = conMuscle
    val dead = conDead; val scales = muscleScaleOf
    val list = layout.links
    if (sweepBackwards) {
        for (slot in list.start[block] + list.count[block] - 1 downTo list.start[block]) {
            val c = list.items[slot]
            if (c >= 0) solveDistanceConstraint(c, c, px, py, mass, a, b, rest, muscles, dead, scales, alpha)
        }
    } else {
        for (slot in list.start[block] until list.start[block] + list.count[block]) {
            val c = list.items[slot]
            if (c >= 0) solveDistanceConstraint(c, c, px, py, mass, a, b, rest, muscles, dead, scales, alpha)
        }
    }
}
