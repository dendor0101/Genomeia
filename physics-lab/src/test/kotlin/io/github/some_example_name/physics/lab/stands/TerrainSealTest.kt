package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * РЕЛЬЕФ СТЕНДОВ — ПЛОТНАЯ МЕМБРАНА.
 *
 * Сцена «тело о стену» проверяет решатель, только если стена и вправду стена: до 16.11 между
 * частицами кромки были щели шире клетки, и «рельеф не держит клетки» оказалось свойством
 * расстановки (замечание автора игры). Здесь это закреплено: каждая клетка кромки каждой
 * раскладки покрыта кругами касания частиц сплошь — ни одной точки, куда встал бы центр частицы,
 * не задев рельефа, — а радиус касания берётся у самого решателя, не из предположений стенда.
 */
class TerrainSealTest {
    /** Радиусы касания неподвижных частиц, как их считает решатель, в единицах игры. */
    private fun solverRadii(w: StandWorld): DoubleArray {
        // Радиусы касания решатель заводит на первом шаге.
        w.step()
        val contacts = w.world.state.contacts!!
        return DoubleArray(w.fixed.size) { contacts.contactRadiusOf(w.world.freeParticle(w.fixed[it])) / PHYSICS_PER_GAME }
    }

    @Test
    fun `радиус касания частицы рельефа у решателя - 0,6 её радиуса`() {
        val w = StandWorld()
        w.rock(0.0, 0.0); w.rock(5.0, 0.0)
        w.ball(2.5, 3.0)
        for (r in solverRadii(w)) assertEquals(TERRAIN_RADIUS * ShapeSpec.LONE_CONTACT_SCALE, r, 1e-6)   // радиус мира физики во float
    }

    /** Наименьшее расстояние от точки до края ближайшего круга касания: отрицательное — точка накрыта. */
    private fun uncovered(rocks: DoubleArray, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        var i = 0
        while (i < rocks.size) {
            val d = Math.hypot(rocks[i] - x, rocks[i + 1] - y) - rocks[i + 2]
            if (d < best) best = d
            i += 3
        }
        return best
    }

    private fun checkSealed(name: String, w: StandWorld) {
        val rocks = w.rocksOutline()
        assertTrue("$name: рельеф пуст", rocks.isNotEmpty())
        var worst = -Double.MAX_VALUE; var at = ""
        for (t in w.terrains) for (iy in 0 until t.rows) for (ix in 0 until t.cols) {
            if (!t.isSolid(ix, iy) || t.isBulk(ix, iy)) continue
            // Точки клетки кромки с шагом в десятую её стороны, включая границу.
            for (sy in 0..10) for (sx in 0..10) {
                val x = t.centerX(ix) + (sx / 10.0 - 0.5) * TERRAIN_STEP
                val y = t.centerY(iy) + (sy / 10.0 - 0.5) * TERRAIN_STEP
                val gap = uncovered(rocks, x, y)
                if (gap > worst) { worst = gap; at = "клетка ($ix, $iy), точка (%.2f, %.2f)".format(x, y) }
            }
        }
        assertTrue("$name: в кромке рельефа есть точка вне кругов касания, зазор %.3f — $at".format(worst), worst <= 0.0)
    }

    @Test
    fun `кромка каждой раскладки рельефа покрыта кругами касания сплошь`() {
        for (layout in terrainLayouts()) for (jitter in listOf(false, true)) {
            val w = StandWorld()
            w.place(layout, 0.0, jitter)
            checkSealed("раскладка ${layout.id}${if (jitter) ", со случайным сдвигом" else ""}", w)
        }
    }

    @Test
    fun `стенки полостей покрыты сплошь, и набивка в них не стоит`() {
        for (jitter in listOf(false, true)) {
            val w = StandWorld()
            val c = w.cavity(12, 9, jitter) { ix, iy -> !(ix in 4..6 && iy in 3..5) }   // полость с островом посередине
            checkSealed("полость${if (jitter) " со случайным сдвигом" else ""}", w)
            // Частица радиуса 0.5 (касание 0.3), поставленная набивкой у самой рамки полости, стенки не задевает.
            val rocks = w.rocksOutline()
            val r = GAME_RADIUS * ShapeSpec.LONE_CONTACT_SCALE
            for (x in listOf(c.minX + 0.45, c.maxX - 0.45)) {
                val y = (c.minY + c.maxY) / 2
                if (!c.free(x, y)) continue
                assertTrue("частица набивки у рамки полости стоит в стенке: зазор %.3f".format(uncovered(rocks, x, y) - r), uncovered(rocks, x, y) >= r)
            }
        }
    }

    @Test
    fun `стенка у рамки мира - щели уже самой мелкой частицы`() {
        val w = StandWorld()
        w.arena(20, 12)
        val rocks = w.rocksOutline()
        // Самая мелкая частица стендов — радиус 0.2, круг касания 0.12: диаметр 0.24.
        val smallest = 2 * 0.2 * ShapeSpec.LONE_CONTACT_SCALE
        var worst = 0.0
        var i = 0
        while (i < rocks.size) {
            // Ближайший сосед по стенке.
            var near = Double.MAX_VALUE
            var j = 0
            while (j < rocks.size) {
                if (j != i) near = minOf(near, Math.hypot(rocks[i] - rocks[j], rocks[i + 1] - rocks[j + 1]) - rocks[i + 2] - rocks[j + 2])
                j += 3
            }
            if (near > worst) worst = near
            i += 3
        }
        assertTrue("щель между соседними частицами стенки %.3f не меньше диаметра самой мелкой частицы %.3f".format(worst, smallest), worst < smallest)
    }
}
