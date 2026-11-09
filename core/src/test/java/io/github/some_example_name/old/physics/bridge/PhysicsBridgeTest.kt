package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Мост к физике-библиотеке без Gdx: тело из раскладки растёт из зиготы делением, мышцы
 * клеток, субстанции, рамка мира. Раскладка — шестиугольник клеток игры по умолчанию
 * (радиус 0.5, связь 0.6), как растит редактор.
 */
class PhysicsBridgeTest {

    /** Шестиугольник радиуса [rings] колец: слоты по порядку обхода, cellGenomeId = слот + 100. */
    private fun hexLayout(rings: Int, boneRing: Int = -1): BakedLayout {
        val qs = ArrayList<Int>(); val rs = ArrayList<Int>()
        for (q in -rings..rings) for (r in -rings..rings) {
            if (kotlin.math.abs(q + r) <= rings) { qs.add(q); rs.add(r) }
        }
        val n = qs.size
        val x = FloatArray(n) { (0.6 * (qs[it] + rs[it] / 2.0)).toFloat() }
        val y = FloatArray(n) { (0.6 * rs[it] * sqrt(3.0) / 2.0).toFloat() }
        val ids = List(n) { it + 100 }
        val near = Array(n) { a -> (0 until n).filter { b -> b != a && hypot((x[a] - x[b]).toDouble(), (y[a] - y[b]).toDouble()) < 0.61 } }
        val pairs = ArrayList<Int>()
        for (a in 0 until n) for (b in near[a]) if (b > a) { pairs.add(ids[a]); pairs.add(ids[b]) }
        val triSlots = ArrayList<Int>(); val areas = ArrayList<Float>()
        for (v in 0 until n) for (a in near[v]) for (b in near[v]) {
            if (a <= v || b <= a || b !in near[a]) continue
            var i1 = a; var i2 = b
            var a2 = (x[i1] - x[v]) * (y[i2] - y[v]) - (y[i1] - y[v]) * (x[i2] - x[v])
            if (a2 < 0f) { val t = i1; i1 = i2; i2 = t; a2 = -a2 }
            triSlots.add(v); triSlots.add(i1); triSlots.add(i2); areas.add(a2)
        }
        val ring = IntArray(n) { maxOf(kotlin.math.abs(qs[it]), kotlin.math.abs(rs[it]), kotlin.math.abs(qs[it] + rs[it])) }
        return BakedLayout(
            cellGenomeIdsInSlotOrder = ids,
            linkPairsInSlotOrder = pairs,
            triangleSlots = triSlots,
            triangleRestArea2 = areas,
            restX = x.toList(), restY = y.toList(),
            restRadius = List(n) { 0.5f },
            restBone = List(n) { ring[it] == boneRing }
        )
    }

    /** Слоты в порядке роста: обход в ширину от зиготы по связям раскладки. */
    private fun growthOrder(layout: BakedLayout, zygote: Int): IntArray {
        val n = layout.cellsInLayout
        val adj = Array(n) { ArrayList<Int>() }
        val p = layout.linkPairsInSlotOrder
        for (s in 0 until layout.linksInLayout) {
            val a = layout.slotByCellGenomeId.get(p[2 * s]); val b = layout.slotByCellGenomeId.get(p[2 * s + 1])
            adj[a].add(b); adj[b].add(a)
        }
        val order = IntArray(n); val seen = BooleanArray(n)
        var head = 0; var tail = 0
        order[tail++] = zygote; seen[zygote] = true
        while (head < tail) for (u in adj[order[head++]]) if (!seen[u]) { seen[u] = true; order[tail++] = u }
        return order
    }

    private fun config() = PhysicsConfig(mapOf("RB_PARALLEL_MIN" to "0"))

    /** Тело растёт по клетке в тик и ещё [settle] тиков стоит; возвращает мост. */
    private fun grow(layout: BakedLayout, workers: PhysicsWorkers?, settle: Int = 60): PhysicsBridge {
        val bridge = PhysicsBridge(workers, config())
        bridge.setBounds(200f, 200f)
        // Зигота — центральная клетка шестиугольника (q = r = 0).
        val zygote = (0 until layout.cellsInLayout).minBy { hypot(layout.restX[it].toDouble(), layout.restY[it].toDouble()) }
        bridge.spawnOrganism(organ = 3, layout = layout, zygoteSlot = zygote, x = 50f, y = 60f)
        for (slot in growthOrder(layout, zygote).drop(1)) {
            bridge.bornCell(3, slot)
            bridge.step()
        }
        repeat(settle) { bridge.step() }
        return bridge
    }

    private fun meanLinkRatio(bridge: PhysicsBridge, layout: BakedLayout): Double {
        var sum = 0.0
        val p = layout.linkPairsInSlotOrder
        for (s in 0 until layout.linksInLayout) {
            val a = bridge.particleOf(3, layout.slotByCellGenomeId.get(p[2 * s]))
            val b = bridge.particleOf(3, layout.slotByCellGenomeId.get(p[2 * s + 1]))
            sum += hypot((bridge.x(a) - bridge.x(b)).toDouble(), (bridge.y(a) - bridge.y(b)).toDouble()) / 0.6
        }
        return sum / layout.linksInLayout
    }

    @Test
    fun bodyGrowsFromZygoteIntact() {
        val layout = hexLayout(rings = 4, boneRing = 2)
        val bridge = grow(layout, null)
        val n = layout.cellsInLayout
        assertEquals(0, bridge.world.state.tornTotal, "рост из зиготы без разрывов")
        var cx = 0.0; var cy = 0.0
        for (slot in 0 until n) {
            val i = bridge.particleOf(3, slot)
            assertTrue(!bridge.world.isUnborn(i) && !bridge.world.state.cellDead[i], "слот $slot рождён и жив")
            cx += bridge.x(i); cy += bridge.y(i)
        }
        // Тело растёт вокруг зиготы, стоящей в точке подсадки.
        assertTrue(hypot(cx / n - 50.0, cy / n - 60.0) < 0.6, "центр тела у точки подсадки: (${cx / n}, ${cy / n})")
        assertEquals(1.0, meanLinkRatio(bridge, layout), 0.03)
    }

    @Test
    fun oneThreadEqualsWorkers() {
        val layout = hexLayout(rings = 5)
        val a = grow(layout, null, settle = 20)
        val b = grow(layout, PhysicsWorkers.shared(3), settle = 20)
        for (i in 0 until a.world.particleCount) {
            assertEquals(a.world.x[i].toRawBits(), b.world.x[i].toRawBits(), "x частицы $i")
            assertEquals(a.world.y[i].toRawBits(), b.world.y[i].toRawBits(), "y частицы $i")
        }
    }

    @Test
    fun cellMusclesShortenLinks() {
        val layout = hexLayout(rings = 3)
        val bridge = grow(layout, null)
        for (slot in 0 until layout.cellsInLayout) bridge.setMuscle(3, slot, 0.8f)
        repeat(120) { bridge.step() }
        assertEquals(0.8, meanLinkRatio(bridge, layout), 0.04)
        assertEquals(0, bridge.world.state.tornTotal)
    }

    @Test
    fun substancesMoveAndStayInsideBounds() {
        val layout = hexLayout(rings = 2)
        val bridge = grow(layout, null, settle = 0)
        // Скорость игры: смещение за тик — vx · 7.5 (PhysicsUnits); без сопротивления.
        val id = bridge.addSubstance(20f, 20f, 0.3f, vx = 0.01f, vy = 0f, drag = 0.0)
        val p = bridge.substanceParticle(id)
        bridge.step()
        assertEquals(20f + 0.075f, bridge.x(p), 0.01f)
        // Пущенная в стенку остаётся внутри рамки.
        val wall = bridge.addSubstance(198f, 100f, 0.3f, vx = 0.2f, vy = 0f, drag = 0.0)
        repeat(30) { bridge.step() }
        val w = bridge.substanceParticle(wall)
        assertTrue(bridge.x(w) <= 200f - 0.3f + 0.15f, "субстанция внутри рамки: x = ${bridge.x(w)}")
        bridge.removeSubstance(id)
        assertTrue(!bridge.world.isFreeInUse(id))
    }

    @Test
    fun organismRemovalAndKill() {
        val layout = hexLayout(rings = 3)
        val bridge = grow(layout, null, settle = 10)
        bridge.killCell(3, 0)
        bridge.step()
        assertTrue(bridge.world.state.cellDead[bridge.particleOf(3, 0)])
        bridge.removeOrganism(3)
        assertTrue(!bridge.hasBody(3))
        bridge.step()
    }
}
