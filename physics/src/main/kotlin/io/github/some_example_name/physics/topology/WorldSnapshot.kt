package io.github.some_example_name.physics

import java.io.DataInput
import java.io.DataOutput

/*
 * ПОЛНЫЙ СНИМОК МИРА. Этап A4 плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * Снимок — ЯДРО: всё, что несёт историю и чего нельзя вывести заново: описание мира
 * (склейка тел), частицы, рабочие списки связей и треугольников с пластикой и
 * дорастанием, смерть и нерождённость рёбер и треугольников, организмы с запасом среды,
 * сон, позы костей растущих тел, мышцы, пары покоя контактов (запекаются по телу при
 * дописывании), захват мышью, переключатели решателя.
 *
 * Производное — поверхность, контур, пределы контура, контакты вместе с широкой фазой,
 * план связей, блоки решателя — не пишется, а «пересобирается в известной точке,
 * одинаковой при записи и загрузке» (план, раздел 2): запись сначала уплотняет мир
 * (PhysicsWorld.write → compact → rebuildDerived), чтение строит то же производное тем
 * же путём из прочитанного ядра. Поэтому запись меняет порядок производного и в самом
 * пишущем мире: дальше он детерминирован, но идёт не так, как мир, который снимка не писал.
 *
 * Проверка — SnapshotCheck: мир, загруженный из снимка, продолжает побитово так же, как
 * мир, который его записал; все поля состояния после загрузки совпадают.
 */

internal const val SNAPSHOT_MAGIC = 0x50485753  // "PHWS"
internal const val SNAPSHOT_VERSION = 1

// --- массивы: длина, затем значения; числа — сырыми битами, без нормализации NaN ---

internal fun DataOutput.writeInts(a: IntArray, count: Int) { writeInt(count); for (i in 0 until count) writeInt(a[i]) }
internal fun DataOutput.writeDoubles(a: DoubleArray, count: Int) { writeInt(count); for (i in 0 until count) writeLong(java.lang.Double.doubleToRawLongBits(a[i])) }
internal fun DataOutput.writeFloats(a: FloatArray, count: Int) { writeInt(count); for (i in 0 until count) writeInt(java.lang.Float.floatToRawIntBits(a[i])) }
internal fun DataOutput.writeBooleans(a: BooleanArray, count: Int) { writeInt(count); for (i in 0 until count) writeBoolean(a[i]) }
internal fun DataOutput.writeBytes(a: ByteArray, count: Int) { writeInt(count); write(a, 0, count) }

/** Прочитать массив; длина результата — не меньше [minSize] (ёмкость), записанное — в начале. */
internal fun DataInput.readInts(minSize: Int): IntArray { val c = readInt(); val a = IntArray(maxOf(c, minSize)); for (i in 0 until c) a[i] = readInt(); return a }
internal fun DataInput.readDoubles(minSize: Int): DoubleArray { val c = readInt(); val a = DoubleArray(maxOf(c, minSize)); for (i in 0 until c) a[i] = java.lang.Double.longBitsToDouble(readLong()); return a }
internal fun DataInput.readFloats(minSize: Int): FloatArray { val c = readInt(); val a = FloatArray(maxOf(c, minSize)); for (i in 0 until c) a[i] = java.lang.Float.intBitsToFloat(readInt()); return a }
internal fun DataInput.readBooleans(minSize: Int): BooleanArray { val c = readInt(); val a = BooleanArray(maxOf(c, minSize)); for (i in 0 until c) a[i] = readBoolean(); return a }
internal fun DataInput.readBytes(minSize: Int): ByteArray { val c = readInt(); val a = ByteArray(maxOf(c, minSize)); readFully(a, 0, c); return a }

private fun DataOutput.writeDouble64(v: Double) = writeLong(java.lang.Double.doubleToRawLongBits(v))
private fun DataInput.readDouble64(): Double = java.lang.Double.longBitsToDouble(readLong())

/** Описание мира: склейка описаний тел, в том числе пулов свободных частиц. */
internal fun DataOutput.writeDefinition(d: BodyDefinition) {
    writeFloats(d.x, d.count); writeFloats(d.y, d.count); writeFloats(d.radius, d.count)
    writeBooleans(d.isBone, d.count); writeBooleans(d.isMuscle, d.count)
    writeInts(d.linkA, d.linkCount); writeInts(d.linkB, d.linkCount)
    writeInts(d.triA, d.triCount); writeInts(d.triB, d.triCount); writeInts(d.triC, d.triCount)
    writeFloats(d.triRestArea2, d.triCount)
    for (clusters in listOf(d.boneClusters, d.muscleClusters)) {
        writeInt(clusters.size); for (c in clusters) writeInts(c, c.size)
    }
    writeInt(java.lang.Float.floatToRawIntBits(d.scale))
}

internal fun DataInput.readDefinition(): BodyDefinition {
    val x = readFloats(0); val y = readFloats(0); val r = readFloats(0)
    val bone = readBooleans(0); val muscle = readBooleans(0)
    val la = readInts(0); val lb = readInts(0)
    val ta = readInts(0); val tb = readInts(0); val tc = readInts(0); val area = readFloats(0)
    val bones = Array(readInt()) { readInts(0) }
    val muscles = Array(readInt()) { readInts(0) }
    val scale = java.lang.Float.intBitsToFloat(readInt())
    return BodyDefinition(x, y, r, bone, muscle, la, lb, ta, tb, tc, area, bones, muscles, scale)
}

/**
 * Ядро состояния решателя. Мир перед этим уплотнён: отложенных разрывов нет, рабочие
 * списки без мёртвых записей удалённых тел, производное каноническое.
 */
internal fun BodyState.writeCore(out: DataOutput) {
    check(!tearsPending && tearQueueN == 0) { "снимок — между тиками, после уплотнения" }
    out.writeInt(n); out.writeInt(organismCount)
    // Частицы.
    for (a in listOf(px, py, prevX, prevY, vx, vy, invMass, restInvMass, matchWeight, radius,
            sleepAnchorX, sleepAnchorY, tickJitterX, tickJitterY, contactPressure)) out.writeDoubles(a, a.size)
    for (a in listOf(cellDead, cellUnborn, inContact, crushedCell)) out.writeBooleans(a, a.size)
    for (a in listOf(trapTicks, trapOrg, foldTicks, contactPressureN, boneOf, muscleOf, organismOf)) out.writeInts(a, a.size)
    // Организмы.
    out.writeInts(organismSize, organismCount)
    for (a in listOf(organismMass, flowVX, flowVY)) out.writeDoubles(a, organismCount)
    out.writeDoubles(orgSleepDrift2, minOf(orgSleepDrift2.size, organismCount))
    // Кости: позы покоя (у растущего тела — по рождённой части).
    out.writeInt(rigidBones.size)
    for (b in rigidBones.indices) { out.writeDoubles(boneRestQx[b], boneRestQx[b].size); out.writeDoubles(boneRestQy[b], boneRestQy[b].size) }
    // Мышцы.
    for (a in listOf(muscleActivation, muscleActivationPrev, muscleTarget, muscleScaleOf)) out.writeDoubles(a, a.size)
    // Рабочие списки: длины, затем записи.
    out.writeInt(lnkCount); out.writeInt(conCount); out.writeInt(triCount); out.writeInt(growEdgeN); out.writeInt(growTriN)
    out.writeInts(lnkA, lnkCount); out.writeInts(lnkB, lnkCount); out.writeInts(lnkEdge, lnkCount)
    out.writeInts(conA, conCount); out.writeInts(conB, conCount); out.writeDoubles(conRest, conCount)
    out.writeInts(conMuscle, conCount); out.writeInts(conEdge, conCount)
    out.writeBooleans(conDead, conCount); out.writeBooleans(linkTorn, conCount)
    out.writeBytes(conHold, minOf(conHold.size, conCount)); out.writeDoubles(conGrowTarget, minOf(conGrowTarget.size, conCount))
    out.writeInts(triA, triCount); out.writeInts(triB, triCount); out.writeInts(triC, triCount)
    out.writeFloats(triRestArea2, triCount); out.writeInts(triId, triCount); out.writeInts(triMuscle, triCount)
    out.writeBooleans(triInverted, triCount); out.writeBytes(triHold, minOf(triHold.size, triCount))
    out.writeFloats(triGrowTarget, triGrowTarget.size)
    out.writeInts(growEdges, growEdgeN); out.writeInts(growTris, growTriN)
    // Топология, сон, пары покоя. Пары покоя запекаются по телу при дописывании, а не по
    // миру разом: запечённые по всему описанию они нашли бы пары и между телами, чьи позы
    // покоя перекрываются (слоты пула свободных частиц лежат поверх сцены), — история.
    baked.writeState(out)
    sleep.write(out, organismCount)
    val rp = restPairs ?: RestPairs.EMPTY
    out.writeInts(rp.start, rp.start.size); out.writeInts(rp.other, rp.other.size); out.writeInts(rp.mirror, rp.mirror.size)
    out.writeDoubles(rp.d2, rp.d2.size); out.writeDoubles(rp.dist, rp.dist.size); out.writeBooleans(rp.ever, rp.ever.size)
    // Захват мышью, счёт разрывов, чередование обхода связей, переключатели решателя.
    out.writeInt(dragId); out.writeDouble64(mouseX); out.writeDouble64(mouseY)
    out.writeDouble64(dragShiftX); out.writeDouble64(dragShiftY)
    out.writeInt(tornTotal); out.writeBoolean(sweepBackwards); out.writeInt(playTick)
    for (b in booleanArrayOf(contactsOn, probeOn, bonesRigid, tearingOn, areasOn, crushBurstOn, membranePassTear,
            trapKillFolds, cancelSpinOn)) out.writeBoolean(b)
    for (d in doubleArrayOf(muscleContraction, muscleRateContract, muscleRateRelax, gaitDuty, tendonTear,
            linkTearStrain, tangentDrag, pressureBurst, membraneTearStrain)) out.writeDouble64(d)
    for (i in intArrayOf(gaitPeriod, muscleTouch, sweepFlip, trapKillTicks, bendLevel)) out.writeInt(i)
}

/**
 * Ядро из снимка поверх мира, только что загруженного по тому же описанию (load), и
 * производное заново — тем же путём, что после уплотнения.
 */
internal fun BodyState.readCore(input: DataInput) {
    check(input.readInt() == n) { "снимок не от этого описания" }
    organismCount = input.readInt()
    px = input.readDoubles(0); py = input.readDoubles(0); prevX = input.readDoubles(0); prevY = input.readDoubles(0)
    vx = input.readDoubles(0); vy = input.readDoubles(0); invMass = input.readDoubles(0); restInvMass = input.readDoubles(0)
    matchWeight = input.readDoubles(0); radius = input.readDoubles(0)
    sleepAnchorX = input.readDoubles(0); sleepAnchorY = input.readDoubles(0)
    tickJitterX = input.readDoubles(0); tickJitterY = input.readDoubles(0); contactPressure = input.readDoubles(0)
    cellDead = input.readBooleans(0); cellUnborn = input.readBooleans(0); inContact = input.readBooleans(0)
    crushedCell = input.readBooleans(0)
    trapTicks = input.readInts(0); trapOrg = input.readInts(0); foldTicks = input.readInts(0)
    contactPressureN = input.readInts(0); boneOf = input.readInts(0); muscleOf = input.readInts(0)
    organismOf = input.readInts(0)
    organismSize = input.readInts(n)
    organismMass = input.readDoubles(n); flowVX = input.readDoubles(n); flowVY = input.readDoubles(n)
    orgSleepDrift2 = input.readDoubles(0).let { if (it.isEmpty()) it else it.copyOf(n) }
    check(input.readInt() == rigidBones.size) { "кости снимка не от этого описания" }
    for (b in rigidBones.indices) { boneRestQx[b] = input.readDoubles(0); boneRestQy[b] = input.readDoubles(0) }
    muscleActivation = input.readDoubles(0); muscleActivationPrev = input.readDoubles(0)
    muscleTarget = input.readDoubles(0); muscleScaleOf = input.readDoubles(0)
    val lc = body.linkCount; val tc = body.triCount
    lnkCount = input.readInt(); conCount = input.readInt(); triCount = input.readInt()
    growEdgeN = input.readInt(); growTriN = input.readInt()
    lnkA = input.readInts(lc); lnkB = input.readInts(lc); lnkEdge = input.readInts(lc)
    conA = input.readInts(lc); conB = input.readInts(lc); conRest = input.readDoubles(lc)
    conMuscle = input.readInts(lc); conEdge = input.readInts(lc)
    conDead = input.readBooleans(lc); linkTorn = input.readBooleans(lc)
    conHold = input.readBytes(0).let { if (it.isEmpty()) it else it.copyOf(lc) }
    conGrowTarget = input.readDoubles(0).let { if (it.isEmpty()) it else it.copyOf(lc) }
    triA = input.readInts(tc); triB = input.readInts(tc); triC = input.readInts(tc)
    triRestArea2 = input.readFloats(tc); triId = input.readInts(tc); triMuscle = input.readInts(tc)
    triInverted = input.readBooleans(tc)
    triHold = input.readBytes(0).let { if (it.isEmpty()) it else it.copyOf(tc) }
    triGrowTarget = input.readFloats(0)
    growEdges = input.readInts(16); growTris = input.readInts(16)
    conBoundary = BooleanArray(lc); conTendon = BooleanArray(lc)
    tearsPending = false; tearQueueN = 0
    baked.readState(input)
    sleep.read(input, n)
    val rp = RestPairs(input.readInts(0), input.readInts(0), input.readInts(0),
        input.readDoubles(0), input.readDoubles(0), input.readBooleans(0))
    if (restPairs != null) restPairs = rp
    dragId = input.readInt(); mouseX = input.readDouble64(); mouseY = input.readDouble64()
    dragShiftX = input.readDouble64(); dragShiftY = input.readDouble64()
    tornTotal = input.readInt(); sweepBackwards = input.readBoolean(); playTick = input.readInt()
    contactsOn = input.readBoolean(); probeOn = input.readBoolean(); bonesRigid = input.readBoolean()
    tearingOn = input.readBoolean(); areasOn = input.readBoolean(); crushBurstOn = input.readBoolean()
    membranePassTear = input.readBoolean(); trapKillFolds = input.readBoolean(); cancelSpinOn = input.readBoolean()
    muscleContraction = input.readDouble64(); muscleRateContract = input.readDouble64(); muscleRateRelax = input.readDouble64()
    gaitDuty = input.readDouble64(); tendonTear = input.readDouble64(); linkTearStrain = input.readDouble64()
    tangentDrag = input.readDouble64(); pressureBurst = input.readDouble64(); membraneTearStrain = input.readDouble64()
    gaitPeriod = input.readInt(); muscleTouch = input.readInt(); sweepFlip = input.readInt()
    trapKillTicks = input.readInt(); bendLevel = input.readInt()
    rebuildDerived(null, null)
}
