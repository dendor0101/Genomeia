package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SOFT_COMPLIANCE
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * МЯГКИЙ ТЕРРЕЙН ИЗ ПЛИТОК (решение 04.11; стенд — :physics-lab:terrainLab).
 *
 * Частицы террейна игры (WorldTerrainManager, ParticleEntity.terrainCell — клетка карты) становятся
 * одним телом мира физики. Сон и пробуждение у мира — по организму, то есть по связному куску:
 * связный террейн одним куском не засыпал бы никогда. Поэтому связи тела проводятся только внутри
 * плиток — квадратов [TILE]×[TILE] клеток карты, — и каждый связный кусок плитки выходит своим
 * организмом. Соседние частицы разных кусков держат швы — временные связи мира физики
 * (PhysicsWorld.addJoint) с порогом пробуждения: спящий сосед для проснувшейся плитки — опора,
 * будится он только силой через шов. Плитки и стенка у рамки — одна группа касаний
 * ([TERRAIN_GROUP]): друг с другом не сталкиваются, шов держат связи. Каждая частица — на мягкой
 * пружине к своему месту на карте и с хваткой о дно: удар гаснет в ткани, а не уходит волной по
 * швам. Без хватки террейн дрожит и не засыпает вовсе (стенд 04.11: тик 6.3 мс против 0.26 на 64×64).
 *
 * Соседство клеток карты — вправо, вверх и одна диагональ (сетка из треугольников): по нему и связи
 * внутри куска, и треугольники, и швы.
 *
 * Строится раз на мир ([build], первый тик после постройки террейна), одним телом — дописывание
 * тела стоит порядка мира, а плиток сотни. Порядок частиц — по клетке карты: от порядка списков игры
 * не зависит. В снимке моста — номер тела и слоты частиц игры ([write]); номера частиц после
 * уплотнения мира берутся заново по телу ([remap]).
 */
internal class TerrainBodies(private val particleEntity: ParticleEntity) {
    /** Тело террейна в мире физики; −1 — террейна нет или ещё не построен. */
    var body = -1
        private set
    var built = false
        private set
    private val particles = IntArrayList()
    private val slots = IntArrayList()

    fun clear() { body = -1; built = false; particles.clear(); slots.clear() }

    /** Частицы игры в теле террейна — для сетки игры (WorldPhysics.updateGrid). */
    val count: Int get() = particles.size
    fun particle(k: Int): Int = particles.getInt(k)

    fun build(world: PhysicsWorld) {
        built = true
        val pe = particleEntity
        // Частицы террейна по клетке карты — канонический порядок.
        val list = IntArrayList()
        val alive = pe.aliveList
        for (k in 0 until alive.size) { val p = alive.getInt(k); if (pe.terrainCell[p] >= 0) list.add(p) }
        if (list.isEmpty) return
        val sorted = list.toIntArray().also { a -> sortByCell(a, pe.terrainCell) }
        val at = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }
        for (k in sorted.indices) at.put(pe.terrainCell[sorted[k]], k)

        // Куски плиток: обход по соседству внутри квадрата.
        val n = sorted.size
        val piece = IntArray(n) { -1 }
        val order = IntArray(n)          // частицы тела подряд по кускам
        var placed = 0
        val stack = IntArray(n)
        var pieces = 0
        for (s in 0 until n) {
            if (piece[s] >= 0) continue
            val tile = tileOf(pe.terrainCell[sorted[s]])
            var top = 0
            stack[top++] = s; piece[s] = pieces
            while (top > 0) {
                val c = stack[--top]
                order[placed++] = c
                val cell = pe.terrainCell[sorted[c]]
                for (d in NEIGHBORS) for (sign in SIGNS) {
                    val o = at.get(shift(cell, d[0] * sign, d[1] * sign))
                    if (o < 0 || piece[o] >= 0 || tileOf(pe.terrainCell[sorted[o]]) != tile) continue
                    piece[o] = pieces; stack[top++] = o
                }
            }
            pieces++
        }
        val slotOf = IntArray(n)
        for (k in 0 until n) slotOf[order[k]] = k

        val x = FloatArray(n); val y = FloatArray(n); val r = FloatArray(n)
        for (k in 0 until n) {
            val p = sorted[order[k]]
            x[k] = (pe.localX[p] * PHYSICS_PER_GAME).toFloat(); y[k] = (pe.localY[p] * PHYSICS_PER_GAME).toFloat()
            r[k] = (pe.radius[p] * PHYSICS_PER_GAME).toFloat()
        }
        val la = IntArrayList(); val lb = IntArrayList()
        val ta = IntArrayList(); val tb = IntArrayList(); val tc = IntArrayList(); val area = ArrayList<Float>()
        for (k in 0 until n) {
            val s = order[k]; val cell = pe.terrainCell[sorted[s]]
            fun same(dx: Int, dy: Int): Int { val o = at.get(shift(cell, dx, dy)); return if (o >= 0 && piece[o] == piece[s]) slotOf[o] else -1 }
            for (d in NEIGHBORS) { val o = same(d[0], d[1]); if (o >= 0) { la.add(k); lb.add(o) } }
            val right = same(1, 0); val up = same(0, 1); val diag = same(1, 1)
            if (right >= 0 && diag >= 0) addTriangle(x, y, k, right, diag, ta, tb, tc, area)
            if (up >= 0 && diag >= 0) addTriangle(x, y, k, diag, up, ta, tb, tc, area)
        }
        val def = BodyDefinition(x, y, r, BooleanArray(n), BooleanArray(n), la.toIntArray(), lb.toIntArray(),
            ta.toIntArray(), tb.toIntArray(), tc.toIntArray(), area.toFloatArray(), emptyArray(), emptyArray(), 1f)
        body = world.addBody(def)
        val first = world.bodyFirst(body)
        for (k in 0 until n) {
            val i = first + k
            world.setContactGroup(i, TERRAIN_GROUP)
            world.setHome(i, x[k].toDouble(), y[k].toDouble(), HOME_COMPLIANCE)
            world.setGrip(i, GRIP)
            world.setSoftness(i, SOFTNESS)
            // В толще (все восемь соседних клеток карты — террейн) частица снаружи недостижима:
            // касания ей не нужны. Иначе в набор касаний шёл бы край каждой плитки — десятки тысяч
            // частиц, и каждое пробуждение куска перестраивало бы широкую фазу по ним (замер в игре
            // 04.11: шаг 56–67 мс против 4–6 без террейна, при тысяче неспящих частиц террейна).
            if (inBulk(pe.terrainCell[sorted[order[k]]], at)) world.setContactless(i, true)
            val p = sorted[order[k]]
            pe.physicsIndex[p] = i
            particles.add(p); slots.add(k)
        }
        // Швы: соседи по той же сетке в разных кусках.
        for (s in 0 until n) {
            val cell = pe.terrainCell[sorted[s]]
            for (d in NEIGHBORS) {
                val o = at.get(shift(cell, d[0], d[1]))
                if (o < 0 || piece[o] == piece[s]) continue
                val a = first + slotOf[s]; val b = first + slotOf[o]
                val rest = kotlin.math.hypot(world.x[a] - world.x[b], world.y[a] - world.y[b])
                world.addJoint(a, b, rest, SOFT_COMPLIANCE * SOFTNESS, WAKE_STRAIN, wake = false)
            }
        }
        // Собран в покое — спит сразу: иначе первые SLEEP_TICKS тиков считался бы весь (замер в игре
        // 04.11: 73 тысячи частиц, шаг до 95 мс на первых тиках).
        for (k in 0 until n) world.sleepNow(first + k)
        println("[TerrainBodies] террейн: частиц $n, плиток-кусков $pieces")
    }

    /** Номера частиц мира физики у частиц игры — заново по телу (после уплотнения мира). */
    fun remap(world: PhysicsWorld) {
        if (body < 0) return
        val first = world.bodyFirst(body)
        for (k in 0 until particles.size) particleEntity.physicsIndex[particles.getInt(k)] = first + slots.getInt(k)
    }

    fun write(out: java.io.DataOutput) {
        out.writeBoolean(built); out.writeInt(body)
        out.writeInt(particles.size)
        for (k in 0 until particles.size) { out.writeInt(particles.getInt(k)); out.writeInt(slots.getInt(k)) }
    }

    fun read(input: java.io.DataInput) {
        clear()
        built = input.readBoolean(); body = input.readInt()
        repeat(input.readInt()) { particles.add(input.readInt()); slots.add(input.readInt()) }
    }

    private fun inBulk(cell: Int, at: Int2IntOpenHashMap): Boolean {
        for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && at.get(shift(cell, dx, dy)) < 0) return false
        return true
    }

    private fun addTriangle(x: FloatArray, y: FloatArray, a: Int, b: Int, c: Int,
                            ta: IntArrayList, tb: IntArrayList, tc: IntArrayList, area: ArrayList<Float>) {
        var i1 = b; var i2 = c
        var a2 = (x[i1] - x[a]) * (y[i2] - y[a]) - (y[i1] - y[a]) * (x[i2] - x[a])
        if (a2 < 0f) { val t = i1; i1 = i2; i2 = t; a2 = -a2 }
        if (a2 < 1e-12f) return
        ta.add(a); tb.add(i1); tc.add(i2); area.add(a2)
    }

    companion object {
        /** Клеток карты в стороне плитки: 8 клеток мира (1.5 клетки карты на клетку мира). */
        const val TILE = 12
        /** Группа касаний террейна и стенки у рамки: друг с другом не сталкиваются. */
        const val TERRAIN_GROUP = 1
        /** Хватка о дно: при 0 террейн не засыпает, 0.3 и 0.6 гасят удар одинаково, 0.3 засыпает быстрее. */
        const val GRIP = 0.3f
        /** Мягкость ткани террейна — как у жира в первом варианте. */
        const val SOFTNESS = 2.5f
        /** Пружина к месту: мягче связи — плитка проминается, но держит форму. */
        const val HOME_COMPLIANCE = 4 * SOFT_COMPLIANCE
        /** Порог пробуждения соседа через шов, доля длины покоя. */
        const val WAKE_STRAIN = 0.02

        private val NEIGHBORS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1))
        private val SIGNS = intArrayOf(1, -1)

        fun packCell(x: Int, y: Int): Int = y * 65536 + x
        /** Метка частицы стенки у рамки номер [k] в раскладке (WorldTerrainManager.forEachWallParticle): −2 − k. */
        fun wallMark(k: Int): Int = -2 - k
        fun wallIndexOf(mark: Int): Int = -2 - mark
        private fun cellX(c: Int) = c and 0xFFFF
        private fun cellY(c: Int) = c ushr 16
        private fun shift(c: Int, dx: Int, dy: Int): Int {
            val x = cellX(c) + dx; val y = cellY(c) + dy
            return if (x < 0 || y < 0 || x > 0xFFFF) -1 else packCell(x, y)
        }
        private fun tileOf(c: Int): Int = packCell(cellX(c) / TILE, cellY(c) / TILE)

        private fun sortByCell(a: IntArray, cell: IntArray) {
            val keys = LongArray(a.size) { (cell[a[it]].toLong() shl 32) or a[it].toLong() }
            java.util.Arrays.sort(keys)
            for (k in a.indices) a[k] = keys[k].toInt()
        }
    }
}
