package io.github.some_example_name.physics

/*
 * КОСТЬ ДЕЛИТСЯ ВМЕСТЕ С ТЕЛОМ (16.11.2026, план стабильности: «что чинить в первую очередь», пункт 1).
 *
 * ЧТО БЫЛО. Состав кости задавался один раз — кластером описания тела — и больше не пересматривался.
 * Клетка кости погибла (клиент: голод, поедание; стенд: разрез) — и ничего не менялось:
 *   - погибшая клетка оставалась в кости с весом 1: проекция таскала замороженную клетку за костью,
 *     а её положение входило в подгонку позы;
 *   - половины кости по разные стороны разреза оставались ОДНИМ твёрдым телом: куски разрезанного
 *     организма не расходились, хотя между ними не осталось ни одной живой связи. Проекция, контакт
 *     (масса и момент инерции «кости»), тяга мышью и упор в рамку считали их целым;
 *   - сон кости читался по её ПЕРВОЙ клетке (BlockedIteration, ParallelBlocks): у погибшей первой
 *     клетки номер организма старый, и кость куска, который не спит, могла не проецироваться вовсе.
 * Стенды: hydro/cut/spine, xmix/bone-cut, logic/death на костных телах.
 *
 * ЧТО ТЕПЕРЬ. Гибель клетки кости и гибель ребра между клетками одной кости помечают кость
 * (markBoneDirty). Между тиками (killCell, начало шага, уплотнение мира) помеченные кости
 * пересматриваются (splitDirtyBones):
 *   1. погибшие клетки выходят из кости насовсем: вес ноль, boneOf и boneClusterOf = -1;
 *   2. живые клетки кластера делятся на связные куски по живым рёбрам (нерождённое ребро — тоже
 *      связь: клетка родится и войдёт в кость);
 *   3. самый большой кусок остаётся прежней костью, остальные получают новые номера костей;
 *   4. поза покоя куска — та же, что была у его клеток в общей кости (форма, с которой кость жила,
 *      в том числе снятая при включении мутацией), отсчитанная от центра куска;
 *   5. одиночная клетка костью быть перестаёт: поворот по одной точке не определён (см. rigidBones).
 *      Кусок из двух клеток остаётся костью — жёстким стержнем: связь между ними из решателя
 *      вынута насовсем (activateBone, fillConstraints), и держать её больше нечему.
 *
 * ПОЧЕМУ МЕЖДУ ТИКАМИ. Клетка кости в контактах и от давления не гибнет (кости бессмертны),
 * внутрикостной связи в решателе нет — рваться нечему. Состав кости меняет только клиент, а он
 * зовёт мир между тиками. Поэтому внутри подшага списки костей не меняются, и ни одна стадия не
 * видит кость наполовину пересобранной.
 *
 * ЦЕНА. Обход клеток одной кости и их рёбер — порядка размера кости. Новая кость дописывается в
 * rigidBones копированием массива ссылок (число костей мира, без клеток) и сбросом черновиков
 * воркеров — так же, как при дописывании тела; событие редкое. Блоки решателя не пересобираются:
 * кость переставляется в индексе блоков по месту. Память: 4 байта на частицу (черновик меток),
 * заводится при первом разделении.
 *
 * СНИМОК. Состав костей больше не выводится из описания, поэтому пишется в снимок (версия 4):
 * списки клеток костей и boneClusterOf. См. WorldSnapshot.
 */

/** Кость [b] потеряла клетку или внутреннюю связь: проверить её связность между тиками. */
internal fun BodyState.markBoneDirty(b: Int) {
    if (boneDirtyMark.size <= b) boneDirtyMark = boneDirtyMark.copyOf(maxOf(rigidBones.size, b + 1))
    if (boneDirtyMark[b]) return
    boneDirtyMark[b] = true
    if (boneDirtyN == boneDirty.size) boneDirty = boneDirty.copyOf(boneDirtyN * 2)
    boneDirty[boneDirtyN++] = b
}

/** Ребро [e] погибло: если оно соединяло клетки одного костного кластера — пометить кость. */
internal fun BodyState.noteBoneEdgeDeath(e: Int) {
    val b = boneClusterOf[baked.edgeU(e)]
    if (b >= 0 && b == boneClusterOf[baked.edgeV(e)]) markBoneDirty(b)
}

/** Пересмотреть помеченные кости. Между тиками; без пометок — одна проверка. */
internal fun BodyState.splitDirtyBones() {
    if (boneDirtyN == 0) return
    // Новые кости в очередь не попадают: куски связны по построению.
    val count = boneDirtyN
    var changed = false
    for (k in 0 until count) {
        val b = boneDirty[k]
        boneDirtyMark[b] = false
        if (b < rigidBones.size && splitBone(b)) changed = true
    }
    boneDirtyN = 0
    if (!changed) return
    // Число костей могло вырасти: учёт ударов о рамку и черновики воркеров — по новому числу.
    if (wallHits.capacity != rigidBones.size) wallHits = BoneWallHits(rigidBones.size)
    parallelPhysicsState = null
    contacts?.invalidateStatic()
    refreshSurface()
    structureVersion++
}

/**
 * Пересмотреть состав кости [b]. Возвращает, изменился ли он.
 * Порядок кусков и клеток в них — по порядку клеток прежнего списка: от истории не зависит.
 */
private fun BodyState.splitBone(b: Int): Boolean {
    val ids = rigidBones[b]
    if (ids.isEmpty()) return false
    if (boneLocal.size < n) boneLocal = IntArray(n)
    val local = boneLocal

    // 1. Живые клетки кластера получают место в черновике (номер в списке + 1), погибшие выходят из кости.
    var live = 0
    for (k in ids.indices) {
        val i = ids[k]
        if (cellDead[i]) { leaveBone(i, dead = true); continue }
        local[i] = k + 1
        live++
    }

    // 2. Связные куски: обход в ширину по живым и нерождённым рёбрам внутри кластера.
    val piece = IntArray(ids.size) { -1 }          // кусок клетки списка
    val queue = IntArray(live)
    var pieces = 0
    var largest = -1; var largestSize = 0
    for (k in ids.indices) {
        if (local[ids[k]] == 0 || piece[k] >= 0) continue
        var head = 0; var tail = 0
        piece[k] = pieces; queue[tail++] = ids[k]
        while (head < tail) {
            val v = queue[head++]
            for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
                val e = baked.vertEdge[s]
                if (baked.edgeDead[e] && !baked.isUnborn(e)) continue
                val u = baked.vertOther[s]
                val lu = local[u]
                if (lu == 0 || piece[lu - 1] >= 0) continue
                piece[lu - 1] = pieces; queue[tail++] = u
            }
        }
        if (tail > largestSize) { largestSize = tail; largest = pieces }
        pieces++
    }
    for (i in ids) local[i] = 0

    if (pieces == 1 && live == ids.size) return false      // кость цела
    bonesSplit = true

    // 3. Списки и позы кусков. Самый большой остаётся костью b (при равенстве — первый по порядку).
    val oldQx = boneRestQx[b]; val oldQy = boneRestQy[b]
    val sizes = IntArray(pieces)
    for (k in ids.indices) if (piece[k] >= 0) sizes[piece[k]]++
    // Номер кости каждого куска: -1 — одиночная клетка, костью не остаётся.
    val boneOfPiece = IntArray(pieces) { -1 }
    var extra = 0
    for (p in 0 until pieces) if (sizes[p] >= 2) boneOfPiece[p] = if (p == largest) b else rigidBones.size + extra++

    if (extra > 0) {
        val b0 = rigidBones.size
        rigidBones = Array(b0 + extra) { if (it < b0) rigidBones[it] else IntArray(0) }
        boneRestQx = Array(b0 + extra) { if (it < b0) boneRestQx[it] else DoubleArray(0) }
        boneRestQy = Array(b0 + extra) { if (it < b0) boneRestQy[it] else DoubleArray(0) }
    }
    val wasMember = BooleanArray(ids.size) { boneOf[ids[it]] == b }
    val fill = IntArray(pieces)
    for (p in 0 until pieces) {
        val to = boneOfPiece[p]
        if (to < 0) continue
        rigidBones[to] = IntArray(sizes[p]); boneRestQx[to] = DoubleArray(sizes[p]); boneRestQy[to] = DoubleArray(sizes[p])
    }
    // Кость b без большого куска (все куски — одиночки или клеток не осталось) — пустая запись:
    // номера остальных костей не сдвигаются, уплотнение мира её выбросит.
    if (largest < 0 || boneOfPiece[largest] < 0) { rigidBones[b] = IntArray(0); boneRestQx[b] = DoubleArray(0); boneRestQy[b] = DoubleArray(0) }
    for (k in ids.indices) {
        val p = piece[k]
        if (p < 0) continue
        val i = ids[k]
        val to = boneOfPiece[p]
        if (to < 0) { leaveBone(i, dead = false); continue }
        val at = fill[p]++
        rigidBones[to][at] = i
        boneRestQx[to][at] = oldQx[k]; boneRestQy[to][at] = oldQy[k]
        boneClusterOf[i] = to
        if (wasMember[k]) boneOf[i] = to
    }
    for (p in 0 until pieces) if (boneOfPiece[p] >= 0) recentreBonePose(boneOfPiece[p])

    // 4. Блоки решателя: кость b — заново по своему нынешнему списку, новые — по своим.
    val layout = constraintBlocks
    if (layout != null) {
        layout.bones.remove(b)
        for (p in 0 until pieces) if (boneOfPiece[p] >= 0) placeBoneInBlocks(boneOfPiece[p], layout)
    }
    // 5. Контур и поверхность считают соседей по кости: у клеток, сменивших кость, они другие.
    for (i in ids) { baked.touch(i); if (!cellDead[i]) sleep.requestWake(i) }
    return true
}

/** Клетка [i] перестаёт быть костью: погибшая — без веса, одиночная живая — обычная ткань. */
private fun BodyState.leaveBone(i: Int, dead: Boolean) {
    boneOf[i] = -1; boneClusterOf[i] = -1
    if (dead) matchWeight[i] = 0.0 else if (dragId != i) matchWeight[i] = 1.0
    // Треугольник с костной вершиной у мышцы клетки не сокращается — кольцо заново.
    if (!dead) refreshCellScaleRing(i)
}

/**
 * Поза кости [b] — от центра её включённых клеток. Смещения клеток уже перенесены из прежней
 * кости; у не включённых (нерождённых, ещё не ставших костью) — ноль, как в restPoseOfBornBone.
 * Сумма досаживается в ноль теми же тремя проходами, что buildBoneRestPose.
 */
private fun BodyState.recentreBonePose(b: Int) {
    val ids = rigidBones[b]
    val qx = boneRestQx[b]; val qy = boneRestQy[b]
    var posed = 0
    for (k in ids.indices) {
        val i = ids[k]
        if (boneOf[i] == b && !cellUnborn[i]) posed++ else { qx[k] = 0.0; qy[k] = 0.0 }
    }
    if (posed == 0) return
    for (pass in 0 until 3) {
        var sx = 0.0; var sy = 0.0
        for (k in ids.indices) { sx += qx[k]; sy += qy[k] }
        if (sx == 0.0 && sy == 0.0) break
        val dx = sx / posed; val dy = sy / posed
        for (k in ids.indices) { val i = ids[k]; if (boneOf[i] == b && !cellUnborn[i]) { qx[k] -= dx; qy[k] -= dy } }
    }
}

/** Место кости [b] в блоках решателя — тем же правилом, что initializeConstraintBlocks. */
internal fun BodyState.placeBoneInBlocks(b: Int, layout: ConstraintBlocks) {
    val ids = rigidBones[b]
    if (ids.isEmpty()) return
    var block = layout.particleBlock[ids[0]]
    for (i in ids) if (layout.particleBlock[i] != block) { block = layout.seamBucket[layout.particleBlock[ids[0]]]; break }
    layout.bones.remove(b)
    layout.bones.add(b, block)
}

/**
 * СБРОС МИРА: кости — снова по описанию, целые и включённые, с позой описания (так стенд и заводит
 * тела; игра мир не сбрасывает). Без разделений с загрузки — ничего не делает.
 */
internal fun BodyState.restoreSplitBones() {
    if (!bonesSplit) return
    bonesSplit = false
    rigidBones = body.boneClusters.filter { it.size >= 3 }.toTypedArray()
    java.util.Arrays.fill(boneOf, -1)
    rigidBones.forEachIndexed { b, ids -> for (i in ids) boneOf[i] = b }
    boneClusterOf = boneOf.copyOf()
    buildBoneRestPose()
    boneDirtyN = 0; boneDirtyMark.fill(false)
    if (wallHits.capacity != rigidBones.size) wallHits = BoneWallHits(rigidBones.size)
    parallelPhysicsState = null
}
