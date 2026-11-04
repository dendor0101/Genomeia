package io.github.some_example_name.physics

import java.io.DataInput
import java.io.DataOutput
import kotlin.math.sqrt

/*
 * ВРЕМЕННЫЕ СВЯЗИ МЕЖДУ ЧАСТИЦАМИ РАЗНЫХ ТЕЛ (клиент-игра: липучка, Sticky — PhysicsWorld.addJoint).
 *
 * Связь тела — ребро его топологии: живёт в блоках решателя, контуре, треугольниках, и завести
 * её между двумя телами нельзя (тело — арена своих частиц). Липучке нужна другая связь: между
 * любыми двумя частицами мира (клетка чужого тела, еда, стенка), заводится и снимается клиентом
 * каждый тик. Это мягкое ограничение расстояния XPBD отдельной стадией после внутренних связей
 * подшага, в одном потоке: связей единицы–сотни, а концы — в разных телах и блоках решателя.
 *
 * Связь не участвует в контуре и контактах: касание её концов остаётся обычным, поэтому длина
 * покоя — фактическая на момент сцепки (касание), и связь держит концы у касания, не стягивая их
 * внутрь друг друга. Учёт импульса за тик (TickMomentum) объединяет группы её концов, как контакт:
 * иначе поправка по организмам сняла бы с каждого то, что связь передала другому.
 *
 * Рвётся, как связь тела: растянутая сверх LINK_MAX_STRETCH + linkTearStrain длины покоя (solveJoints).
 *
 * Номера — слоты пула (стабильны, как у свободных частиц); концы — номера частиц мира, их
 * перенумеровывает уплотнение. Связь с погибшей или удалённой частицей снимается сама (шаг мира,
 * уплотнение, removeFree) — клиент видит это по isJointLive. Входит в снимок.
 *
 * Объём: 33 байта на слот; при сотне липучек — 3 КБ, в L1.
 */

/** Связи с погибшим концом — снять; спящий конец связи с бодрствующим — будить. До шага решателя. */
internal fun BodyState.prepareJoints() {
    for (k in 0 until jointSlots) {
        if (!jointLive[k]) continue
        val a = jointA[k]; val b = jointB[k]
        if (cellDead[a] || cellDead[b]) { freeJoint(k); continue }
        // Спящая частица заморожена (обратная масса 0): связь тянула бы бодрствующий конец как к
        // стенке. Неподвижная (isFixed) не спит по смыслу и не будится.
        val sa = sleep.isAsleep(organismOf[a]) && !isFixed(a)
        val sb = sleep.isAsleep(organismOf[b]) && !isFixed(b)
        if (sa != sb) sleep.requestWake(if (sa) a else b)
    }
}

/** Подшаг [h]: мягкое ограничение расстояния по каждой связи, по порядку слотов. */
internal fun BodyState.solveJoints(h: Double) {
    if (jointLiveCount == 0) return
    val px = px; val py = py; val invMass = invMass
    val inv = 1.0 / (h * h)
    // Рвётся, как связь тела: растянутая сверх предела длины и запаса до разрыва. Мягче
    // (податливость больше) — то же растяжение набирается меньшей силой.
    val tear = LINK_MAX_STRETCH + linkTearStrain
    for (k in 0 until jointSlots) {
        if (!jointLive[k]) continue
        val i = jointA[k]; val j = jointB[k]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0) continue
        var dx = px[i] - px[j]; var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len > tear * jointRest[k]) { freeJoint(k); continue }
        if (len < 1e-12) continue
        dx /= len; dy /= len
        val dL = -(len - jointRest[k]) / (w + jointCompliance[k] * inv)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

/** Связи объединяют группы своих концов в учёте импульса за тик — как контакт (noteContacts). */
internal fun BodyState.noteJointsMomentum() {
    if (jointLiveCount == 0) return
    for (k in 0 until jointSlots) if (jointLive[k]) tickMomentum.noteJoint(jointA[k], jointB[k], invMass)
}

internal fun BodyState.allocJoint(a: Int, b: Int, rest: Double, compliance: Double): Int {
    val k = if (jointFreeN > 0) jointFree[--jointFreeN] else {
        if (jointSlots == jointA.size) {
            val cap = maxOf(8, jointSlots * 2)
            jointA = jointA.copyOf(cap); jointB = jointB.copyOf(cap)
            jointRest = jointRest.copyOf(cap); jointCompliance = jointCompliance.copyOf(cap)
            jointLive = jointLive.copyOf(cap); jointFree = jointFree.copyOf(cap)
        }
        jointSlots++
    }
    jointA[k] = a; jointB[k] = b; jointRest[k] = rest; jointCompliance[k] = compliance
    jointLive[k] = true; jointLiveCount++
    return k
}

internal fun BodyState.freeJoint(k: Int) {
    if (!jointLive[k]) return
    jointLive[k] = false; jointLiveCount--
    jointFree[jointFreeN++] = k
}

/** Снять все связи частицы [i] — она уходит (removeFree: слот займёт другая частица). O(слотов). */
internal fun BodyState.dropJointsOf(i: Int) {
    if (jointLiveCount == 0) return
    for (k in 0 until jointSlots) if (jointLive[k] && (jointA[k] == i || jointB[k] == i)) freeJoint(k)
}

/** Уплотнение: концы — по новым номерам [pNew], связь с выброшенной частицей снимается. */
internal fun BodyState.remapJoints(pNew: IntArray) {
    for (k in 0 until jointSlots) {
        if (!jointLive[k]) continue
        val a = pNew[jointA[k]]; val b = pNew[jointB[k]]
        if (a < 0 || b < 0) { freeJoint(k); continue }
        jointA[k] = a; jointB[k] = b
    }
}

internal fun BodyState.writeJoints(out: DataOutput) {
    out.writeInt(jointSlots); out.writeInt(jointLiveCount)
    out.writeInts(jointA, jointSlots); out.writeInts(jointB, jointSlots)
    out.writeDoubles(jointRest, jointSlots); out.writeDoubles(jointCompliance, jointSlots)
    out.writeBooleans(jointLive, jointSlots); out.writeInts(jointFree, jointFreeN)
}

internal fun BodyState.readJoints(input: DataInput) {
    jointSlots = input.readInt(); jointLiveCount = input.readInt()
    jointA = input.readInts(0); jointB = input.readInts(0)
    jointRest = input.readDoubles(0); jointCompliance = input.readDoubles(0)
    jointLive = input.readBooleans(0)
    jointFree = input.readInts(0).let { jointFreeN = it.size; it.copyOf(maxOf(jointA.size, it.size)) }
}
