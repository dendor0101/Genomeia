package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.entities.ParticleEntity
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/**
 * НЕПОДВИЖНЫЙ ТЕРРЕЙН (решение 11.11, вместо мягкого террейна из плиток со швами 04.11).
 *
 * Частицы террейна игры (WorldTerrainManager, ParticleEntity.terrainCell — клетка карты) — как стенка
 * у рамки: неподвижные бессмертные частицы мира физики (PhysicsWorld.addFixed). Решатель их не
 * двигает, касание отдаёт всю поправку партнёру, гибели нет, в широкой фазе они лежат в неподвижной
 * сетке. Ни связей, ни швов, ни пружин к месту, ни сна по плиткам: мягкий террейн стоил шага при
 * каждом пробуждении куска (замер 04.11: 56–67 мс против 4–6 без террейна при тысяче неспящих частиц)
 * и держал десятки тысяч частиц в списках организмов.
 *
 * В толще (все восемь соседних клеток карты — террейн) частица снаружи недостижима: в мир физики она
 * не идёт вовсе (physicsIndex = −1, место — своё у игры, ParticleEntity.local*). В мир идёт только
 * кромка. Соседи кромки при случайном смещении раскладки (±0.375 при шаге 0.67 и радиусе 0.5)
 * перекрываются или стоят с зазором меньше диаметра клетки: внутрь не пролезть.
 *
 * Террейн и стенка у рамки — одна группа касаний ([TERRAIN_GROUP]): друг с другом не сталкиваются.
 *
 * Строится раз на мир ([build], первый тик после постройки террейна). Порядок — по клетке карты:
 * от порядка списков игры не зависит. В снимке моста — частицы игры и номера свободных частиц мира
 * физики ([write]); номера частиц мира после уплотнения берутся заново по номеру ([remap]).
 */
internal class StaticTerrain(private val particleEntity: ParticleEntity) {
    var built = false
        private set
    /** Частицы игры кромки и номера их свободных частиц в мире физики (PhysicsBridge.addWall). */
    private val particles = IntArrayList()
    private val ids = IntArrayList()

    fun clear() { built = false; particles.clear(); ids.clear() }

    /** Частицы игры кромки — для сетки игры (WorldPhysics.buildTerrain). */
    val count: Int get() = particles.size
    fun particle(k: Int): Int = particles.getInt(k)

    fun build(bridge: PhysicsBridge) {
        built = true
        val pe = particleEntity
        // Частицы террейна по клетке карты — канонический порядок.
        val list = IntArrayList()
        val alive = pe.aliveList
        for (k in 0 until alive.size) { val p = alive.getInt(k); if (pe.terrainCell[p] >= 0) list.add(p) }
        if (list.isEmpty) return
        val sorted = list.toIntArray().also { a -> sortByCell(a, pe.terrainCell) }
        val cells = IntOpenHashSet(sorted.size)
        for (p in sorted) cells.add(pe.terrainCell[p])
        val world = bridge.world
        for (p in sorted) {
            if (inBulk(pe.terrainCell[p], cells)) continue
            val id = bridge.addWall(pe.localX[p], pe.localY[p], pe.radius[p])
            val i = bridge.substanceParticle(id)
            world.setContactGroup(i, TERRAIN_GROUP)
            // Двигаться неподвижной некуда: спит сразу, а не через SLEEP_TICKS тиков.
            world.sleepNow(i)
            pe.physicsIndex[p] = i
            particles.add(p); ids.add(id)
        }
        println("[StaticTerrain] террейн: частиц ${sorted.size}, в мире физики ${particles.size}")
    }

    /** Номера частиц мира физики у частиц игры — заново по номеру свободной частицы (после уплотнения мира). */
    fun remap(bridge: PhysicsBridge) {
        for (k in 0 until particles.size) particleEntity.physicsIndex[particles.getInt(k)] = bridge.substanceParticle(ids.getInt(k))
    }

    fun write(out: java.io.DataOutput) {
        out.writeBoolean(built)
        out.writeInt(particles.size)
        for (k in 0 until particles.size) { out.writeInt(particles.getInt(k)); out.writeInt(ids.getInt(k)) }
    }

    fun read(input: java.io.DataInput) {
        clear()
        built = input.readBoolean()
        repeat(input.readInt()) { particles.add(input.readInt()); ids.add(input.readInt()) }
    }

    private fun inBulk(cell: Int, cells: IntOpenHashSet): Boolean {
        for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && !cells.contains(shift(cell, dx, dy))) return false
        return true
    }

    companion object {
        /** Группа касаний террейна и стенки у рамки: друг с другом не сталкиваются. */
        const val TERRAIN_GROUP = 1

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

        private fun sortByCell(a: IntArray, cell: IntArray) {
            val keys = LongArray(a.size) { (cell[a[it]].toLong() shl 32) or a[it].toLong() }
            java.util.Arrays.sort(keys)
            for (k in a.indices) a[k] = keys[k].toInt()
        }
    }
}
