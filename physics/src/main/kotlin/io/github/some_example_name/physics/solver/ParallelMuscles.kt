package io.github.some_example_name.physics

/** Рабочие списки одного куска шага мышц (см. MuscleDriver): изменившиеся клетки и захват «было» для плавности. */
internal class CellScaleLerpBuffer {
    var edgeIds = IntArray(64); var edgeFrom = FloatArray(64); var edgeN = 0
    var triIds = IntArray(64); var triFrom = FloatArray(64); var triN = 0
    /** Клетки куска, которым второй проход пересчитывает связи и треугольники (refreshEffectiveChunk). */
    var changed = IntArray(64); var changedN = 0
    @Suppress("unused") private val pad = LongArray(16)

    fun addChanged(u: Int) {
        if (changedN == changed.size) changed = changed.copyOf(changedN * 2)
        changed[changedN++] = u
    }

    fun addEdge(e: Int, from: Float) {
        if (edgeN == edgeIds.size) { edgeIds = edgeIds.copyOf(edgeN * 2); edgeFrom = edgeFrom.copyOf(edgeN * 2) }
        edgeIds[edgeN] = e; edgeFrom[edgeN] = from; edgeN++
    }

    fun addTri(id: Int, from: Float) {
        if (triN == triIds.size) { triIds = triIds.copyOf(triN * 2); triFrom = triFrom.copyOf(triN * 2) }
        triIds[triN] = id; triFrom[triN] = from; triN++
    }
}

/**
 * МЫШЦЫ КЛЕТОК ПО ВОРКЕРАМ. Шаг множителей идёт в одном потоке и только запоминает, кто
 * сдвинулся; кольца пересчитываются уже по окончательным множителям.
 *
 * Кольцо клетки трогает множители и связи не дальше двух связей от неё, то есть (условие
 * ParallelBlocks) только свой и соседние блоки: B-1..B+1. Значит блоки с разницей номеров 3 и
 * больше общих данных не имеют, и блоки считаются в три прохода по номеру блока mod 3. Это
 * работает и для одного большого тела, а не только для многих маленьких. Тела без
 * параллельного обхода (ConstraintBlocks.serialBlock) считаются в конце в одном потоке.
 *
 * Значения выбираются, а не складываются, поэтому порядок кусков на результат не влияет. Замер 09.11, 34 червя,
 * 8 воркеров: мышцы были 10% тика в одном потоке.
 *
 * С 24.11 проходов по три цвета два (MuscleDriver, «в два прохода»): сначала действующие множители клеток у всех
 * кусков, потом связи и треугольники — они читают множители клеток соседних блоков, и те должны быть уже
 * окончательными. Шесть барьеров на тик вместо трёх, зато каждая клетка, связь и треугольник считаются один раз.
 */
internal class ParallelMuscles(private val st: BodyState) {
    private var moved = IntArray(1024)
    private var movedN = 0
    private var from = IntArray(64)
    private var to = IntArray(64)
    private var blockOf = IntArray(64)
    private var chunks = 0
    private var buffers = Array(64) { CellScaleLerpBuffer() }
    private val colored = Array(COLORS) { IntArray(64) }
    private val colorN = IntArray(COLORS)
    private var running = IntArray(0)

    private val effectiveJob = PhysicsJob { k, _ -> effectiveChunk(running[k]) }
    private val aroundJob = PhysicsJob { k, _ -> aroundChunk(running[k]) }

    // --- плавность по подшагам: куски записей подряд ---
    private var lerpF = 0f
    private var lerpLast = false
    private var edgeChunks = 0
    private var triChunks = 0
    private val lerpJob = PhysicsJob { k, _ ->
        if (k < edgeChunks) st.lerpEdgeRange(k * LERP_CHUNK, minOf(st.lerpEdgeN, (k + 1) * LERP_CHUNK), lerpF, lerpLast)
        else {
            val t = k - edgeChunks
            st.lerpTriRange(t * LERP_CHUNK, minOf(st.lerpTriN, (t + 1) * LERP_CHUNK), lerpF, lerpLast)
        }
    }

    fun begin() { movedN = 0 }

    fun note(v: Int) {
        if (movedN == moved.size) moved = moved.copyOf(movedN * 2)
        moved[movedN++] = v
    }

    /** Кольца всех сдвинувшихся клеток и захват «было» в общие списки плавности. */
    fun refreshRings(w: PhysicsWorkers) {
        if (movedN < PARALLEL_MIN_MOVED) {
            st.refreshMovedRings(moved, movedN)
            return
        }
        val layout = st.constraintBlocks!!
        val block = layout.particleBlock
        chunks = 0
        java.util.Arrays.fill(colorN, 0)
        var q = 0
        while (q < movedN) {
            val b = block[moved[q]]
            var e = q + 1
            while (e < movedN && block[moved[e]] == b) e++
            addChunk(b, q, e, layout)
            q = e
        }
        // Проход 1 — действующие множители клеток: у всех кусков, и только потом проход 2 — связи и треугольники.
        for (color in 0 until COLORS) {
            if (colorN[color] == 0) continue
            running = colored[color]
            w.run(colorN[color], effectiveJob)
        }
        for (x in 0 until chunks) if (layout.serialBlock[blockOf[x]]) effectiveChunk(x)
        for (color in 0 until COLORS) {
            if (colorN[color] == 0) continue
            running = colored[color]
            w.run(colorN[color], aroundJob)
        }
        for (x in 0 until chunks) if (layout.serialBlock[blockOf[x]]) aroundChunk(x)
        for (x in 0 until chunks) st.appendCellScaleLerp(buffers[x])
    }

    private fun addChunk(b: Int, q: Int, e: Int, layout: ConstraintBlocks) {
        if (chunks == from.size) {
            val cap = chunks * 2
            from = from.copyOf(cap); to = to.copyOf(cap); blockOf = blockOf.copyOf(cap)
            val old = buffers
            buffers = Array(cap) { if (it < old.size) old[it] else CellScaleLerpBuffer() }
        }
        from[chunks] = q; to[chunks] = e; blockOf[chunks] = b
        if (!layout.serialBlock[b]) {
            val color = b % COLORS
            if (colorN[color] == colored[color].size) colored[color] = colored[color].copyOf(colorN[color] * 2)
            colored[color][colorN[color]++] = chunks
        }
        chunks++
    }

    private fun effectiveChunk(x: Int) = st.refreshEffectiveChunk(moved, from[x], to[x], buffers[x])

    private fun aroundChunk(x: Int) {
        val out = buffers[x]
        out.edgeN = 0; out.triN = 0
        st.refreshAroundChanged(out.changed, out.changedN, out)
    }

    /** Шаг плавности подшага: записи независимы, каждая про своё ребро или треугольник. */
    fun lerp(w: PhysicsWorkers, f: Float, last: Boolean) {
        lerpF = f; lerpLast = last
        edgeChunks = (st.lerpEdgeN + LERP_CHUNK - 1) / LERP_CHUNK
        triChunks = (st.lerpTriN + LERP_CHUNK - 1) / LERP_CHUNK
        w.run(edgeChunks + triChunks, lerpJob)
    }

    private companion object {
        const val COLORS = 3
        /** Меньше сдвинувшихся клеток — в одном потоке: барьеры дороже такой работы. */
        const val PARALLEL_MIN_MOVED = 256
        const val LERP_CHUNK = 2048
    }
}
