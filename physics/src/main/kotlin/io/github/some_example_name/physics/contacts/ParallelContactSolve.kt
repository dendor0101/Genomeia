package io.github.some_example_name.physics

import kotlin.math.sqrt

/**
 * Итог куска контактов: заявки на смерть в порядке обхода и счётчики для стендов. wi/wj —
 * обратные массы концов последнего решённого контакта. Массив-заглушка разводит соседние
 * объекты по разным кэш-линиям: wi и wj пишутся на каждый контакт, а объекты создаются пачкой.
 */
internal class ContactSolveSink {
    var kills = IntArray(32)
    var killN = 0
    var attract = 0
    var impulse = 0.0
    var wi = 0.0
    var wj = 0.0
    @Suppress("unused") private val pad = LongArray(16)

    fun addKill(v: Int) {
        if (killN == kills.size) kills = kills.copyOf(killN * 2)
        kills[killN++] = v
    }

    fun reset() { killN = 0; attract = 0; impulse = 0.0 }
}

private class LongBuffer {
    var items = LongArray(16)
    var n = 0
    fun add(v: Long) {
        if (n == items.size) items = items.copyOf(n * 2)
        items[n++] = v
    }
}

/**
 * РЕШАТЕЛЬ КОНТАКТОВ ПО ВОРКЕРАМ — ПОБИТОВО ТОТ ЖЕ, ЧТО В ОДНОМ ПОТОКЕ.
 *
 * Контакты считаются от состояния на входе в подшаг, а силы складываются отдельно
 * (BoundaryContacts.applyForces), поэтому расчёт контакта зависит только от него самого: он идёт
 * кусками в любом порядке. Порядок важен в трёх местах, и они сделаны так, чтобы от раздачи
 * ничего не зависело:
 *  - заявки на смерть копятся по кускам и сливаются в порядке обхода;
 *  - сумма сил на клетке зависит от порядка сложения. Частицы поделены на диапазоны номеров,
 *    контакт записан в список диапазона каждого своего конца, списки — по возрастанию номера
 *    контакта. Диапазон пишет только свои частицы, порядок сложения на клетке — как в обходе;
 *  - сила в жёсткую кость идёт в общий кластер: её складывает один поток в порядке первого
 *    касания клеток, том же, что у frcList в одном потоке.
 *
 * Замер 09.11, 34 червя (96k клеток, 16 подшагов, 8 воркеров): решатель контактов был 15% тика
 * в одном потоке, из них сумма сил на клетку (случайные обращения) — больше половины. Вместе с
 * мышцами (ParallelMuscles) тик стал 144–146 мс вместо 156–169, при 12 воркерах 126–128 вместо 143–151.
 * Память: 16 байт на контакт, 4 на запись списка (~2 на контакт), счётчики — кусков × диапазонов.
 */
internal class ParallelContactSolve(private val ct: BoundaryContacts, workers: Int) {
    private val sinks = Array(16 * workers) { ContactSolveSink() }
    private val solveFrom = IntArray(16 * workers + 1)
    private var chunks = 0
    private var wi = DoubleArray(4096)
    private var wj = DoubleArray(4096)

    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var vx = DoubleArray(0); private var vy = DoubleArray(0)
    private var invMass = DoubleArray(0)
    private var h = 0.0
    private var backwards = false
    private var rigid = false

    /** Куски построения списков и их счётчики по диапазонам (ряды разнесены на 64 байта). */
    private val parts = 4 * workers
    private val partFrom = IntArray(parts + 1)
    private var buckets = 0
    private var stride = 0
    private var counts = IntArray(0)
    private var bucketStart = IntArray(1)
    private var entries = IntArray(4096)
    private var capped = BooleanArray(0)
    private var touchedBones = emptyArray<LongBuffer>()
    private var boneOrder = LongArray(64)

    private val solveJob = PhysicsJob { k, _ -> solveChunk(k) }
    private val countJob = PhysicsJob { p, _ -> countPart(p) }
    private val fillJob = PhysicsJob { p, _ -> fillPart(p) }
    private val accumulateJob = PhysicsJob { b, _ -> accumulateBucket(b) }
    private val capContactsJob = PhysicsJob { k, _ -> capContacts(solveFrom[k], solveFrom[k + 1]) }
    private val applyJob = PhysicsJob { b, _ -> applyBucket(b) }

    fun run(w: PhysicsWorkers, px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray,
            invMass: DoubleArray, h: Double) {
        this.px = px; this.py = py; this.vx = vx; this.vy = vy; this.invMass = invMass; this.h = h
        backwards = ct.ctBackwards
        rigid = ct.rigidForcesApply
        val cN = ct.cN
        if (wi.size < cN) { wi = DoubleArray(maxOf(cN, wi.size * 2)); wj = DoubleArray(wi.size) }
        chunks = minOf(sinks.size, maxOf(1, cN / 128))
        for (k in 0..chunks) solveFrom[k] = (cN.toLong() * k / chunks).toInt()
        w.run(chunks, solveJob)
        if (backwards) for (k in chunks - 1 downTo 0) ct.addSolved(sinks[k])
        else for (k in 0 until chunks) ct.addSolved(sinks[k])

        buildLists(w, cN)
        w.run(buckets, accumulateJob)
        var anyCapped = false
        for (b in 0 until buckets) if (capped[b]) anyCapped = true
        if (anyCapped) w.run(chunks, capContactsJob)
        w.run(buckets, applyJob)
        if (rigid) applyRigid()
    }

    private fun solveChunk(k: Int) {
        val sink = sinks[k]
        sink.reset()
        val from = solveFrom[k]; val to = solveFrom[k + 1]
        if (backwards) { var c = to - 1; while (c >= from) { solveOne(c, sink); c-- } }
        else for (c in from until to) solveOne(c, sink)
    }

    private fun solveOne(c: Int, sink: ContactSolveSink) {
        if (ct.solveContactPair(c, px, py, vx, vy, invMass, h, 1.0, sink)) { wi[c] = sink.wi; wj[c] = sink.wj }
    }

    private fun buildLists(w: PhysicsWorkers, cN: Int) {
        buckets = (ct.n ushr BUCKET_SHIFT) + 1
        stride = buckets + 16
        if (counts.size < parts * stride) counts = IntArray(parts * stride)
        if (bucketStart.size < buckets + 1) bucketStart = IntArray(buckets + 1)
        if (capped.size < buckets) capped = BooleanArray(buckets)
        if (touchedBones.size < buckets) touchedBones = Array(buckets) { LongBuffer() }
        for (p in 0..parts) partFrom[p] = (cN.toLong() * p / parts).toInt()
        w.run(parts, countJob)
        var total = 0
        for (b in 0 until buckets) {
            bucketStart[b] = total
            for (p in 0 until parts) {
                val slot = p * stride + b
                val c = counts[slot]
                counts[slot] = total
                total += c
            }
        }
        bucketStart[buckets] = total
        if (entries.size < total) entries = IntArray(maxOf(total, entries.size * 2))
        w.run(parts, fillJob)
    }

    private fun countPart(p: Int) {
        val row = p * stride
        java.util.Arrays.fill(counts, row, row + buckets, 0)
        val ci = ct.cI; val cj = ct.cJ; val ax = ct.cAx; val ay = ct.cAy
        for (c in partFrom[p] until partFrom[p + 1]) {
            if (ax[c] == 0.0 && ay[c] == 0.0) continue
            val bi = ci[c] ushr BUCKET_SHIFT; val bj = cj[c] ushr BUCKET_SHIFT
            counts[row + bi]++
            if (bj != bi) counts[row + bj]++
        }
    }

    private fun fillPart(p: Int) {
        val row = p * stride
        val ci = ct.cI; val cj = ct.cJ; val ax = ct.cAx; val ay = ct.cAy
        for (c in partFrom[p] until partFrom[p + 1]) {
            if (ax[c] == 0.0 && ay[c] == 0.0) continue
            val bi = ci[c] ushr BUCKET_SHIFT; val bj = cj[c] ushr BUCKET_SHIFT
            entries[counts[row + bi]++] = c
            if (bj != bi) entries[counts[row + bj]++] = c
        }
    }

    /** Силы на частицы диапазона в порядке обхода, затем потолок поправки — как первые два шага applyForces. */
    private fun accumulateBucket(b: Int) {
        val from = bucketStart[b]; val to = bucketStart[b + 1]
        capped[b] = false
        touchedBones[b].n = 0
        if (from == to) return
        if (backwards) { var q = to - 1; while (q >= from) { addForces(entries[q], b); q-- } }
        else for (q in from until to) addForces(entries[q], b)
        capBucket(b)
    }

    private fun addForces(c: Int, b: Int) {
        val i = ct.cI[c]; val j = ct.cJ[c]
        val ax = ct.cAx[c]; val ay = ct.cAy[c]
        val rank = (if (backwards) ct.cN - 1 - c else c).toLong() * 2
        if (i ushr BUCKET_SHIFT == b) touch(i, ax, ay, wi[c], rank, b)
        if (j ushr BUCKET_SHIFT == b) touch(j, -ax, -ay, wj[c], rank + 1, b)
    }

    private fun touch(k: Int, ax: Double, ay: Double, w: Double, rank: Long, b: Int) {
        val x = ct.frcX; val y = ct.frcY; val weight = ct.frcW; val stamp = ct.frcStamp
        if (stamp[k] != ct.pass) {
            stamp[k] = ct.pass
            x[k] = 0.0; y[k] = 0.0; weight[k] = 0.0
            if (rigid && ct.boneIdx(k) >= 0) touchedBones[b].add((rank shl 32) or k.toLong())
        }
        x[k] += ax; y[k] += ay
        if (w > weight[k]) weight[k] = w
    }

    private fun capBucket(b: Int) {
        val cap = ct.forceCapPerSubstep
        val x = ct.frcX; val y = ct.frcY; val weight = ct.frcW; val share = ct.frcS; val stamp = ct.frcStamp
        val pass = ct.pass
        var any = false
        for (i in (b shl BUCKET_SHIFT) until minOf(ct.n, (b + 1) shl BUCKET_SHIFT)) {
            if (stamp[i] != pass) continue
            val w = weight[i]
            var s = 1.0
            if (w > 0.0) {
                val d2 = (x[i] * x[i] + y[i] * y[i]) * w * w
                if (d2 > cap * cap) { s = cap / sqrt(d2); any = true }
            }
            share[i] = s
            x[i] = 0.0; y[i] = 0.0
        }
        capped[b] = any
    }

    /** Множитель контакта, урезанного потолком: иначе следующий проход считает невыполненную работу. */
    private fun capContacts(from: Int, to: Int) {
        val ci = ct.cI; val cj = ct.cJ; val ax = ct.cAx; val ay = ct.cAy; val share = ct.frcS
        for (c in from until to) {
            if (ax[c] == 0.0 && ay[c] == 0.0) continue
            val si = share[ci[c]]; val sj = share[cj[c]]
            val s = if (si < sj) si else sj
            if (s < 1.0) {
                ct.cLamTot[c] -= (1.0 - s) * (ax[c] * ct.cNx[c] + ay[c] * ct.cNy[c])
                ct.cLam[c] *= s
            }
        }
    }

    /** Третий и четвёртый шаги applyForces: урезанные силы и сдвиг своих частиц. */
    private fun applyBucket(b: Int) {
        val from = bucketStart[b]; val to = bucketStart[b + 1]
        if (from == to) return
        for (q in from until to) addCapped(entries[q], b)
        val x = ct.frcX; val y = ct.frcY; val stamp = ct.frcStamp
        val pass = ct.pass; val toVelocity = ct.toVelocity; val velH = ct.velH
        for (i in (b shl BUCKET_SHIFT) until minOf(ct.n, (b + 1) shl BUCKET_SHIFT)) {
            if (stamp[i] != pass) continue
            val ax = x[i]; val ay = y[i]
            if (ax == 0.0 && ay == 0.0) continue
            if (toVelocity) {
                vx[i] += invMass[i] * ax / velH; vy[i] += invMass[i] * ay / velH
            } else if (!rigid || ct.boneIdx(i) < 0) {
                px[i] += invMass[i] * ax; py[i] += invMass[i] * ay
            }
        }
    }

    private fun addCapped(c: Int, b: Int) {
        val i = ct.cI[c]; val j = ct.cJ[c]
        val ax = ct.cAx[c]; val ay = ct.cAy[c]
        val si = ct.frcS[i]; val sj = ct.frcS[j]
        val s = if (si < sj) si else sj
        if (s <= 0.0) return
        val pr = ct.pressure; val pn = ct.pressureN
        val push = if (pr != null) sqrt(ax * ax + ay * ay) * s else 0.0
        if (i ushr BUCKET_SHIFT == b) {
            ct.frcX[i] += ax * s; ct.frcY[i] += ay * s
            if (pr != null) { pr[i] += push * invMass[i]; if (pn != null) pn[i]++ }
        }
        if (j ushr BUCKET_SHIFT == b) {
            ct.frcX[j] -= ax * s; ct.frcY[j] -= ay * s
            if (pr != null) { pr[j] += push * invMass[j]; if (pn != null) pn[j]++ }
        }
    }

    private fun applyRigid() {
        var total = 0
        for (b in 0 until buckets) total += touchedBones[b].n
        if (boneOrder.size < total) boneOrder = LongArray(maxOf(total, boneOrder.size * 2))
        var at = 0
        for (b in 0 until buckets) {
            System.arraycopy(touchedBones[b].items, 0, boneOrder, at, touchedBones[b].n)
            at += touchedBones[b].n
        }
        java.util.Arrays.sort(boneOrder, 0, total)
        ct.applyRigidForces(boneOrder, total, px, py, invMass)
    }

    private companion object {
        /** Диапазон — 1024 номера частиц: на 100k их около сотни, раздачу уравнивает общий курсор. */
        const val BUCKET_SHIFT = 10
    }
}
