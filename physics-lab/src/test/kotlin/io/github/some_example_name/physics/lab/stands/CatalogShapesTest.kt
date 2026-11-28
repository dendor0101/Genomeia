package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог сцен собирается целиком, и тела в нём такие, какими их считают сцены и монитор.
 * Проверка без шагов физики: только сборка мира каждой сцены, в её вариации.
 *
 * Сцены на решётке собираются все, во всех вариациях: вариация меняет размеры тел, и сцена,
 * расставившая тела по готовым координатам, могла поставить их внахлёст. Сцены с телами
 * геномов собираются выборочно (каждая девятая и все с огромными телами пропущены): тел
 * геномов полторы сотни, сцен с ними пять тысяч, а собираются они одним и тем же кодом.
 */
class CatalogShapesTest {
    /** Что осталось от сборки сцены: миры не держим — тысячи миров в кучу теста не помещаются. */
    private class Built(val scenario: Scenario, val particles: Int, val startGap: Double, val terrainGap: Double, val error: String)

    companion object {
        private val shapes = LinkedHashMap<String, ShapeSpec>()
        private val all: List<Scenario> by lazy { Catalog.all() }
        private val built: List<Built> by lazy {
            var genome = 0
            all.mapNotNull { s ->
                val lattice = s.variation != Variation.BASE || !s.baseId.startsWith("gen")
                if (!lattice && genome++ % 9 != 0) return@mapNotNull null
                try {
                    val w = StandWorld()
                    Variation.use(s.variation) {
                        s.setup(w)
                        if (s.variation == Variation.BASE) for (b in w.bodies) if (b.shape.genome == null) shapes.putIfAbsent(b.shape.id + "/" + b.shape.count, b.shape)
                        Built(s, w.world.particleCount, startGap(w), terrainGap(w), "")
                    }
                } catch (t: Throwable) {
                    Built(s, 0, Double.MAX_VALUE, Double.MAX_VALUE, t.toString())
                }
            }
        }

        /** Наименьший зазор между кругами касания разных взрослых тел на старте, единицы игры; отрицательный — внахлёст. */
        private fun startGap(w: StandWorld): Double {
            var worst = Double.MAX_VALUE
            // В большой толпе все пары не перебираем: хватает соседей по номеру — тела расставлены решёткой.
            val bodies = w.bodies.filter { it.growth == null }
            for (i in bodies.indices) for (j in i + 1 until minOf(bodies.size, i + 40)) {
                val gap = w.clearance(bodies[i], bodies[j])
                if (gap < worst) worst = gap
            }
            return worst
        }

        /**
         * Наименьший зазор между кругами касания клеток взрослых тел и частиц рельефа или стенки на старте, единицы
         * игры; отрицательный — тело поставлено в рельеф. Вариация с длинными связями делает тело в полтора раза
         * крупнее, а рельеф остаётся прежним: сцена, расставившая тело по готовым числам, могла загнать его в стену.
         */
        private fun terrainGap(w: StandWorld): Double {
            if (w.fixed.isEmpty()) return Double.MAX_VALUE
            val rocks = w.rocksOutline()
            val st = w.world.state
            var worst = Double.MAX_VALUE
            for (b in w.bodies) {
                if (b.growth != null) continue
                val c = b.shape.contact
                for (k in 0 until b.count) {
                    if (c[k] <= 0.0) continue
                    val x = st.px[b.particle(k)] / PHYSICS_PER_GAME; val y = st.py[b.particle(k)] / PHYSICS_PER_GAME
                    var i = 0
                    while (i < rocks.size) {
                        val gap = Math.hypot(rocks[i] - x, rocks[i + 1] - y) - (c[k] + rocks[i + 2])
                        if (gap < worst) worst = gap
                        i += 3
                    }
                }
            }
            return worst
        }

        private fun shapes(): Collection<ShapeSpec> { built.size; return shapes.values }
    }

    @Test
    fun `номера сцен не повторяются и каждая сцена собирается`() {
        assertTrue("сцен слишком мало: ${all.size}", all.size > 5000)
        val twice = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("повторяются номера сцен: $twice", twice.isEmpty())
        for (s in all) assertTrue("у сцены ${s.id} нет описания", s.about.isNotBlank() && s.expect.isNotBlank() && s.title.isNotBlank())
        val broken = built.filter { it.error.isNotEmpty() || it.particles == 0 }
        assertTrue("не собрались:\n" + broken.take(40).joinToString("\n") { "${it.scenario.id}: ${it.error.ifEmpty { "сцена пуста" }}" }, broken.isEmpty())
    }

    @Test
    fun `у каждой сцены на решётке есть все вариации, у сцены с геномом — одна`() {
        val byBase = all.groupBy { it.baseId }
        for ((base, list) in byBase) {
            val have = list.map { it.variation }.toSet()
            assertTrue("$base: нет ровной решётки", Variation.BASE in have)
            assertTrue("$base: вариации повторяются", have.size == list.size)
            for (s in list) assertEquals("$base: номер вариации", base + s.variation.suffix, s.id)
        }
    }

    @Test
    fun `фигуры стоят серединой рамки в нуле`() {
        for (shape in shapes()) {
            assertEquals("${shape.id}: рамка по X не посередине\n${shape.ascii()}", 0.0, (shape.lx.min() + shape.lx.max()) / 2, 1e-9)
            assertEquals("${shape.id}: рамка по Y не посередине\n${shape.ascii()}", 0.0, (shape.ly.min() + shape.ly.max()) / 2, 1e-9)
        }
    }

    @Test
    fun `фигура с тканью жёсткая, если не названа шарнирной`() {
        // Гибкие по построению фигуры: у них нет позы покоя, монитор судит их только по длинам связей.
        val floppy = shapes().filter { it.hasTissue && !it.rigid }
        // Тонкостенные фигуры семейства «необычная геометрия» шарнирны нарочно: гантель с перемычкой в одну клетку,
        // подкова и рамка со стенкой в две клетки (на шестиугольной решётке такая стенка — цепочка ромбов на общих вершинах).
        // Вырожденные тела (семейство 32): треугольник с хвостом в одну клетку — шарнир по построению.
        val known = sortedSetOf("U8x5_2", "dumbbell3_6", "dumbbell4_4", "frame11_2", "odd-kite")
        val text = floppy.filter { it.id !in known }.joinToString("\n\n") { "${it.id} (${it.name}):\n${it.ascii()}" }
        assertTrue("фигуры с шарниром, о которых каталог не знает:\n$text", floppy.map { it.id }.toSortedSet() == known)
    }

    @Test
    fun `соседи по решётке связаны, остальные клетки дальше порога касания`() {
        // Инвариант редактора «перекрытие = связь» на ровной решётке: без него решатель видел бы ложные касания внутри тела.
        for (shape in shapes()) {
            // Вырожденные тела (семейство 32) нарочно не на решётке: нулевые и длинные связи, клетки почти в
            // одной точке, связи крест-накрест — ими проверяется, что решатель такое переживает.
            if (shape.id.startsWith("odd-")) continue
            for (i in 0 until shape.count) for (j in i + 1 until shape.count) {
                val d = Math.hypot(shape.lx[i] - shape.lx[j], shape.ly[i] - shape.ly[j])
                if (shape.linked(i, j)) assertEquals("${shape.id}: длина связи $i-$j", GAME_LINK, d, 1e-9)
                else assertTrue("${shape.id}: клетки $i и $j ближе диаметра и не связаны ($d)", d > 2 * GAME_RADIUS - 1e-9)
            }
        }
    }

    @Test
    fun `тела на старте не касаются, если сцена сама не ставит их внахлёст`() {
        // Стая стоит в касании нарочно (перекрытие 0.05): иначе мышцы до соседей не дотягиваются и сцена идёт вхолостую.
        val onPurpose = Regex(".*/(touch|lean)$|^grow/.*|^gen-grow/.*|^gen-zoo/school/.*")
        // Зазор «ноль» — тела поставлены вплотную нарочно (башня, шестерёнки); внахлёст — это перекрытие кругов касания
        // больше чем на тысячную единицы.
        val bad = built.filter { it.error.isEmpty() && !onPurpose.matches(it.scenario.baseId) && it.startGap < -1e-3 }
        assertTrue("тела касаются уже на старте (${bad.size} сцен):\n" + bad.take(60).joinToString("\n") { "${it.scenario.id}: зазор ${"%.3f".format(it.startGap)}" }, bad.isEmpty())
    }

    @Test
    fun `тела на старте не стоят в рельефе, если сцена сама не прижимает их к нему`() {
        // Нарочно прижаты: покой у стены с перекрытием, тело на присосках у стены, зажатое между частицами рельефа.
        val onPurpose = Regex(".*/(touch|lean)$|^sleep/.*|^grow/.*|^gen-grow/.*|^xgeo/(slit-posts|c-ring|bridge|wrap)/.*|^xgrow/.*|^xball/(packed|gasbody|pocketfill)/.*")
        val bad = built.filter { it.error.isEmpty() && !onPurpose.matches(it.scenario.baseId) && it.terrainGap < -1e-3 }
        assertTrue("тела стоят в рельефе уже на старте (${bad.size} сцен):\n" + bad.take(80).joinToString("\n") { "${it.scenario.id}: зазор ${"%.3f".format(it.terrainGap)}" }, bad.isEmpty())
    }
}
