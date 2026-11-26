package io.github.some_example_name.physics

/**
 * ОБЛАСТИ ТИКА ДЛЯ «ОСТРОВОВ» (docs/physics/islands.md).
 *
 * Остров — тела, которые за тик не могут коснуться никого снаружи; его подшаги считаются отдельно от мира. Стадии
 * подшага обходят ТЕКУЩУЮ область: блоки и швы (ConstraintBlocks.scope), диапазоны частиц (BodySleep, по организмам
 * области), вершины касаний (BoundaryContacts.vertexScope), организмы. Номер области: [SCOPE_WORLD] — мир (всё, кроме
 * островов), k ≥ 0 — остров k, [SCOPE_ALL] — всё сразу (стадии после подшагов и мир без островов). Без островов
 * у всего область мира, и обход прежний.
 *
 * Главное требование — острова не меняют биты: сумма мира с островами и без одна. Поэтому область не вносит своего
 * порядка: куски сумм — куски мира, обрезанные по области (PassChunks), пары касаний — канонические.
 */
internal const val SCOPE_WORLD = -1
internal const val SCOPE_ALL = -2

/**
 * Организмы текущей области: у тени острова — его список (BodySleep.islandOrgs), у мира с островами — все с отбором
 * по области, без островов — все. Порядок не важен: стадии, которые так ходят, пишут каждая только своё на организм.
 */
internal inline fun BodyState.forScopeOrganisms(body: (Int) -> Unit) {
    val s = sleep
    val own = s.islandOrgs
    if (own != null) { for (x in s.islandOrgFrom until s.islandOrgTo) body(own[x]); return }
    if (s.orgScope == null) { for (o in 0 until organismCount) body(o); return }
    for (o in 0 until organismCount) if (orgInScope(o)) body(o)
}

/** Входит ли организм [o] в текущую область (сон не смотрит). Без островов — всегда. */
internal fun BodyState.orgInScope(o: Int): Boolean {
    val s = sleep
    val os = s.orgScope ?: return true
    val sc = s.scope
    return sc == SCOPE_ALL || (if (o < os.size) os[o] else SCOPE_WORLD) == sc
}

/**
 * Разбиение мира на острова на этот тик. Единица — тело раскладки (ConstraintBlocks.bodyRunOf: связный кусок
 * запечённого графа; у блока из одиночных частиц — тело первой из них, и весь блок идёт вместе).
 *
 * Остров — группа тел, чьи рамки с запасом пересекаются, а с остальными — нет. Запас — ход за тик на потолке скорости
 * (MAX_SPEED_CELLS_PER_TICK) и два наибольших радиуса касания, всё вдвое: за тик до острова никто не дойдёт (это
 * допущение практическое — см. docs/physics/islands.md). В остров не идёт группа с телом, у которого спящие или
 * неподвижные частицы, под мышью или с липучкой, и группа крупнее [ISLAND_MAX_CELLS]. Подшаги островов идут по
 * воркерам (IslandRun.kt), сумма мира с островами и без одна.
 *
 * Возвращает число островов; 0 — островов нет, области сброшены (обход прежний).
 */
internal fun BodyState.partitionIslands(observing: Boolean): Int {
    clearIslands()
    val layout = constraintBlocks ?: return 0
    if (!islandsOn || observing || dbgSubstepHook != null || dbgStages || watchCell >= 0 || dbgSealOn ||
        (DEBUG_CHECKS && dbgLinkExtremaOn)) return 0
    // Несобранный контур (вершины, задетые до тика) первый же refreshSurface подшага собрал бы в тени — общие списки.
    if (baked.dirtyCount != 0) return 0
    val runs = layout.bodyRuns
    if (runs < 2) return 0
    val isl = islandScratch
    isl.ensureRuns(runs)
    for (r in 0 until runs) {
        isl.minX[r] = Float.POSITIVE_INFINITY; isl.minY[r] = Float.POSITIVE_INFINITY
        isl.maxX[r] = Float.NEGATIVE_INFINITY; isl.maxY[r] = Float.NEGATIVE_INFINITY
        isl.cells[r] = 0; isl.ok[r] = true; isl.blocks[r] = 0; isl.parent[r] = r
    }
    val px = px; val py = py
    for (b in 0 until layout.blockCount) {
        val r = layout.bodyRunOf[b]
        isl.blocks[r]++
        for (i in layout.firstParticle[b] until layout.firstParticle[b + 1]) {
            if (cellDead[i]) continue
            val x = px[i]; val y = py[i]
            if (x < isl.minX[r]) isl.minX[r] = x; if (x > isl.maxX[r]) isl.maxX[r] = x
            if (y < isl.minY[r]) isl.minY[r] = y; if (y > isl.maxY[r]) isl.maxY[r] = y
            isl.cells[r]++
            if (invMass[i] <= 0.0f || sleep.isAsleep(organismOf[i])) isl.ok[r] = false
        }
    }
    if (dragId >= 0) isl.ok[layout.bodyRunOf[layout.particleBlock[dragId]]] = false
    for (k in 0 until jointActiveN) {
        val j = jointActive[k]
        isl.ok[layout.bodyRunOf[layout.particleBlock[jointA[j]]]] = false
        isl.ok[layout.bodyRunOf[layout.particleBlock[jointB[j]]]] = false
    }
    // Запас на сторону: ход за тик на потолке скорости, радиус касания и запас широкой фазы — с запасом вдвое.
    val maxR = contacts?.maxContactRadiusNow ?: 0.0f
    val pad = 2.0f * (MAX_SPEED_CELLS_PER_TICK * linkScale + 2.0f * maxR)
    // Остров — связная группа тел, чьи рамки с запасом пересекаются (куски одного генома, тела вплотную): внутри
    // группы касания свои, снаружи их нет. Группа идёт в остров целиком, если в ней нет тела, которому нельзя, и
    // она не крупнее ISLAND_MAX_CELLS.
    unionNearRuns(isl, runs, pad)
    for (r in 0 until runs) { isl.groupCells[r] = 0; isl.groupOk[r] = true; isl.groupRuns[r] = 0; isl.groupIsland[r] = GROUP_UNSET }
    for (r in 0 until runs) {
        if (isl.cells[r] == 0) continue
        val g = isl.find(r)
        isl.groupCells[g] += isl.cells[r]; isl.groupRuns[g]++
        if (!isl.ok[r]) isl.groupOk[g] = false
    }
    var count = 0
    java.util.Arrays.fill(islandRejects, 0)
    for (r in 0 until runs) {
        if (isl.cells[r] == 0) { isl.island[r] = SCOPE_WORLD; continue }
        val g = isl.find(r)
        if (isl.groupIsland[g] == GROUP_UNSET) {
            isl.groupIsland[g] = if (isl.groupOk[g] && isl.groupCells[g] <= ISLAND_MAX_CELLS) count++ else SCOPE_WORLD
        }
        val k = isl.groupIsland[g]
        isl.island[r] = k
        if (k < 0) islandRejects[if (!isl.groupOk[g]) 0 else 1]++
        else if (isl.groupRuns[g] > 1) islandRejects[2]++
    }
    if (count == 0) return 0
    // Области: блоки и швы тела, его организмы и частицы.
    if (islandOrgScope.size < organismCount) islandOrgScope = IntArray(maxOf(organismCount, islandOrgScope.size * 2)) { SCOPE_WORLD }
    if (islandVertexScope.size < n) islandVertexScope = IntArray(maxOf(n, islandVertexScope.size * 2)) { SCOPE_WORLD }
    for (b in 0 until layout.blockCount) {
        val r = layout.bodyRunOf[b]
        val k = isl.island[r]
        if (k < 0) continue
        layout.bucketScope[b] = k
        // Свой набор швов — только у тела из нескольких блоков (общий первый набор одноблочных тел пуст).
        if (isl.blocks[r] > 1) layout.bucketScope[layout.seamBucket[b]] = k
        for (i in layout.firstParticle[b] until layout.firstParticle[b + 1]) {
            islandVertexScope[i] = k
            islandOrgScope[organismOf[i]] = k
        }
    }
    sleep.orgScope = islandOrgScope
    contacts?.vertexScope = islandVertexScope
    islandCount = count
    buildIslandPlan(layout, isl, count)
    prepareSharedForIslands()
    return count
}

/** Группа тела ещё не решена (остров или мир). */
private const val GROUP_UNSET = -3

/**
 * Тела, чьи рамки с запасом [pad] пересекаются, — в одну группу ([IslandScratch.union]). Сортировка по началу рамки
 * вдоль оси, где тела разбросаны шире, и проход вперёд, пока следующая рамка начинается до конца текущей: O(тел·log +
 * пар соседей по оси) вместо перебора всех пар. Тела без живых клеток рамки не имеют.
 */
private fun unionNearRuns(isl: IslandScratch, runs: Int, pad: Float) {
    var loX = Float.POSITIVE_INFINITY; var hiX = Float.NEGATIVE_INFINITY
    var loY = Float.POSITIVE_INFINITY; var hiY = Float.NEGATIVE_INFINITY
    for (r in 0 until runs) {
        if (isl.cells[r] == 0) continue
        if (isl.minX[r] < loX) loX = isl.minX[r]; if (isl.maxX[r] > hiX) hiX = isl.maxX[r]
        if (isl.minY[r] < loY) loY = isl.minY[r]; if (isl.maxY[r] > hiY) hiY = isl.maxY[r]
    }
    val alongX = hiX - loX >= hiY - loY
    val aMin = if (alongX) isl.minX else isl.minY; val aMax = if (alongX) isl.maxX else isl.maxY
    val bMin = if (alongX) isl.minY else isl.minX; val bMax = if (alongX) isl.maxY else isl.maxX
    val keys = isl.keys
    var m = 0
    for (r in 0 until runs) if (isl.cells[r] > 0) keys[m++] = (sortableBits(aMin[r]).toLong() shl 32) or r.toLong()
    java.util.Arrays.sort(keys, 0, m)
    for (a in 0 until m) {
        val r = keys[a].toInt()
        val end = aMax[r] + pad
        for (b in a + 1 until m) {
            val q = keys[b].toInt()
            if (aMin[q] - pad > end) break
            if (bMin[r] - pad <= bMax[q] + pad && bMin[q] - pad <= bMax[r] + pad) isl.union(r, q)
        }
    }
}

/** Биты float, упорядоченные как сами числа (отрицательные — по возрастанию тоже). */
private fun sortableBits(f: Float): Int {
    val b = java.lang.Float.floatToRawIntBits(f)
    return if (b < 0) b xor 0x7fffffff else b
}

/**
 * Перед островами, в главном потоке: всё, что подшаг дозаказывает или собирает лениво в общих объектах, — заранее.
 * Иначе первый же остров собрал бы это в тени, одновременно с другими: куски мира (PassChunks), бодрствующие
 * диапазоны, массивы касаний скоростей и удаления от места покоя, множители мышц, счёт хваток.
 */
private fun BodyState.prepareSharedForIslands() {
    val s = sleep
    s.rebuildAllSpansIfNeeded(n, organismOf)
    val canon = passChunkCache
    if (!canon.valid(structureVersion, s.version, n)) {
        canon.build(s.allSpanCount, s.allSpanStart, s.allSpanEnd, organismOf, s.last)
        canon.markBuilt(structureVersion, s.version, n)
    }
    if (velTouchStamp.size < n) { velTouchStamp = IntArray(n); velTouchList = IntArray(n) }
    if (velBoneStamp.size < rigidBones.size) velBoneStamp = IntArray(rigidBones.size)
    if (orgSleepDrift2.size < n) { orgSleepDrift2 = FloatArray(n); sleepAnchorX = FloatArray(n); sleepAnchorY = FloatArray(n) }
    if (muscleScaleOf.size != muscleActivation.size) muscleScaleOf = FloatArray(muscleActivation.size)
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = FloatArray(muscleActivation.size)
    gripCountNow()
    contacts?.prepareIslands()
}

/** Сбросить области прошлого тика: все наборы, организмы и вершины — снова мира. */
internal fun BodyState.clearIslands() {
    val layout = constraintBlocks
    if (islandCount > 0 && layout != null) {
        java.util.Arrays.fill(layout.bucketScope, SCOPE_WORLD)
        java.util.Arrays.fill(islandOrgScope, SCOPE_WORLD)
        java.util.Arrays.fill(islandVertexScope, SCOPE_WORLD)
    }
    if (islandCount > 0 || sleep.orgScope != null) {
        sleep.orgScope = null
        contacts?.vertexScope = null
        contacts?.setScope(SCOPE_WORLD)     // широкая фаза мира — снова по всем вершинам
    }
    islandCount = 0
    scopeParticles = -1
    setTickScope(SCOPE_WORLD)
}

/**
 * Подшаги мира после островов: область мира, и диапазоны частиц с широкой фазой — заново, даже если номер области
 * тот же. Состав мира сменился (острова ушли, откатившиеся вернулись), а прошлый тик мог кончиться без островов —
 * тогда сетка касаний мира ещё держит вершины нынешних островов.
 */
internal fun BodyState.enterWorldScope() {
    constraintBlocks?.scope = SCOPE_WORLD
    sleep.forceScope(SCOPE_WORLD)
    contacts?.setScope(SCOPE_WORLD)
}

/** Сделать [sc] текущей областью стадий: блоки и швы, диапазоны частиц, вершины касаний. */
internal fun BodyState.setTickScope(sc: Int) {
    constraintBlocks?.scope = sc
    sleep.setScope(sc)
    val ct = contacts
    if (ct != null && ct.scope != sc) ct.setScope(sc)
}

/** Наибольшее тело острова, клеток. Крупнее — в мир, по блокам на всех воркерах (находка 42: hybrid). */
internal const val ISLAND_MAX_CELLS = 2000

/** Черновики разбиения: по телу раскладки. */
internal class IslandScratch {
    var minX = FloatArray(0); var minY = FloatArray(0); var maxX = FloatArray(0); var maxY = FloatArray(0)
    var cells = IntArray(0); var blocks = IntArray(0); var island = IntArray(0); var ok = BooleanArray(0)
    /** Группы тел с пересекающимися рамками (unionNearRuns): дерево по телам, корень — наименьшее тело группы. */
    var parent = IntArray(0)
    var groupCells = IntArray(0); var groupOk = BooleanArray(0); var groupRuns = IntArray(0); var groupIsland = IntArray(0)
    var keys = LongArray(0)

    fun ensureRuns(runs: Int) {
        if (minX.size >= runs) return
        val cap = maxOf(runs, minX.size * 2, 16)
        minX = FloatArray(cap); minY = FloatArray(cap); maxX = FloatArray(cap); maxY = FloatArray(cap)
        cells = IntArray(cap); blocks = IntArray(cap); island = IntArray(cap); ok = BooleanArray(cap)
        parent = IntArray(cap); groupCells = IntArray(cap); groupOk = BooleanArray(cap); groupRuns = IntArray(cap)
        groupIsland = IntArray(cap); keys = LongArray(cap)
    }

    fun find(r: Int): Int {
        var x = r
        while (parent[x] != x) x = parent[x]
        var y = r
        while (parent[y] != x) { val next = parent[y]; parent[y] = x; y = next }
        return x
    }

    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }
}
