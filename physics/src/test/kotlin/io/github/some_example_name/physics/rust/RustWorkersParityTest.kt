package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ВОРКЕРЫ RUST (RUST_PORT.md, веха 5): мир Rust при 2, 3 и 8 воркерах с параллельностью с любого размера сверяется с
 * Kotlin побитово каждый тик. Тела крупные — по несколько блоков (CONSTRAINT_BLOCK_SIZE = 500) со швами, чтобы
 * параллельные стадии работали по-настоящему: блоки обеих чётностей, швы, общий проход пределов, доработка блоков,
 * остановленных разрывом.
 */
class RustWorkersParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private val link = RustScenes.LINK
    private val workerCounts = intArrayOf(2, 3, 8)

    private fun big(x0: Float, y0: Float, cols: Int = 40, rows: Int = 30, bone: Boolean = true) =
        RustScenes.lattice(cols, rows, x0 = x0, y0 = y0,
            muscles = listOf(IntArray(cols) { cols * 5 + it }, IntArray(cols) { cols * (rows - 6) + it }),
            bones = if (bone) listOf(intArrayOf(cols * 10 + 5, cols * 10 + 6, cols * 11 + 5, cols * 11 + 6)) else emptyList())

    @Test
    fun gaitAndTearsWithoutContacts() {
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w)).use { p ->
            p.k.addBody(big(1f, 1f)); p.r.addBody(big(1f, 1f))
            val blocks = p.k.state.constraintBlocks!!
            assertTrue("тело в один блок — параллели нет: ${blocks.blockCount}", blocks.blockCount >= 3)
            for (t in 0 until 120) {
                val v = if ((t / 10) % 2 == 0) 1f else 0f
                p.k.setMuscleTarget(0, v); p.r.setMuscleTarget(0, v)
                p.k.setMuscleTarget(1, 1f - v); p.r.setMuscleTarget(1, 1f - v)
                // Удар по угловым клеткам (как tearingKicksMatch) — разрывы внутри блока: событие останавливает блок
                // воркера, доработка — в одном потоке. Поступательный удар целого ряда ткань не рвёт: предел длины держит.
                if (t == 30 || t == 70) for (c in 0 until 6) {
                    p.k.setVelocity(c, -6f, 4f); p.r.setVelocity(c, -6f, 4f); p.k.wake(c); p.r.wake(c)
                }
                if (t == 90) for (r in 0 until 30) { p.k.kill(r * 40 + 20); p.r.kill(r * 40 + 20) }
                p.step("гребок и удары, воркеров $w")
            }
            val st = p.k.state
            println("воркеров $w: разрывов ${st.tornTotal}, погибших ${st.deadCount}, организмов ${st.organismCount}")
            assertTrue("сцена не разделила тело (воркеров $w)", st.organismCount > 1)
        }
    }

    @Test
    fun ramWithContacts() {
        for (w in workerCounts) for (speed in floatArrayOf(20f, 80f)) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            val a = big(1f, 1f)
            val b = big(1f + 40 * link + 0.3f * link, 1.05f, cols = 30, rows = 25, bone = false)
            p.k.addBody(a); p.r.addBody(a)
            p.k.addBody(b); p.r.addBody(b)
            for (c in 0 until 750) { val i = 1200 + c; p.k.setVelocity(i, -speed, 0.3f); p.r.setVelocity(i, -speed, 0.3f) }
            for (t in 0 until 80) p.step("таран $speed, воркеров $w")
            val st = p.k.state
            assertTrue("таран ничего не разрушил (воркеров $w, скорость $speed)", st.tornTotal + st.deadCount > 0)
        }
    }

    @Test
    fun growthCompactionAndFreeParticles() {
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            val grown = big(1f, 1f, cols = 30, rows = 20)
            val born = BooleanArray(grown.count) { it % 4 != 0 }
            p.k.addBody(grown, born); p.r.addBody(grown, born)
            val other = big(1f + 32 * link, 1f, cols = 30, rows = 20, bone = false)
            val id = p.k.addBody(other); p.r.addBody(other)
            for (k in 0 until 200) {
                val x = 1f + (k % 20) * 0.7f * link; val y = 0.9f - (k / 20) * 0.7f * link
                p.k.addFree(x, y, 0.3f * link, 0.1f, 0.4f); p.r.addFree(x, y, 0.3f * link, 0.1f, 0.4f)
            }
            val first = p.k.bodyFirst(1)
            for (t in 0 until 100) {
                if (t % 3 == 0) for (k in 0 until grown.count) if (!born[k] && p.k.isUnborn(first + k)) {
                    p.k.activate(first + k); p.r.activate(first + k); break
                }
                if (t == 60) { p.k.removeBody(id); p.r.removeBody(id) }
                if (t == 70) { p.k.compact(); p.r.compact() }
                p.step("рост, газ, уплотнение, воркеров $w")
            }
        }
    }

    /**
     * Плотные касания и мышцы по воркерам: газ из 3010 свободных частиц внахлёст, запертый рамкой над телом, —
     * больше PARALLEL_SOLVE_MIN (2048) касаний за подшаг, решение касаний идёт кусками; все клетки тела 30×40 —
     * команды мышц: кольца шага мышц (больше 256 сдвинувшихся клеток) и плавность (больше LERP_PARALLEL_MIN = 4096
     * записей) — по воркерам. Сцена обязана включать все три пути.
     */
    /**
     * Газ из 3010 свободных частиц внахлёст (70 в ряд, снизу вверх) над телом 30×40 ([GAS_BODY_CELLS] клеток),
     * запертый рамкой мира: иначе он разлетается и касаний становится меньше порогов. Возвращает номера частиц газа.
     */
    private fun gasOnBody(p: RustScenes.Pair): IntArray {
        val body = big(1f, 1f, cols = 30, rows = 40, bone = false)
        p.k.addBody(body); p.r.addBody(body)
        // Шаг 0.42 связи при радиусе 0.3 — частицы газа внахлёст.
        val y0 = 1f + 35.6f * link
        val gas = IntArray(3010)
        for (k in 0 until 3010) {
            val x = 1f + 0.1f * link + (k % 70) * 0.42f * link; val y = y0 + (k / 70) * 0.42f * link
            gas[k] = p.k.freeParticle(p.k.addFree(x, y, 0.3f * link)); p.r.addFree(x, y, 0.3f * link)
        }
        val top = y0 + 43 * 0.42f * link + 0.35f * link
        p.k.setBounds(1f - link, 1f - link, 1f + 30.5f * link, top); p.r.setBounds(1f - link, 1f - link, 1f + 30.5f * link, top)
        p.k.setEnvironment(-0.3f, 1f, 1f, 1f, 1f); p.r.setEnvironment(-0.3f, 1f, 1f, 1f, 1f)
        return gas
    }

    private val GAS_BODY_CELLS = 30 * 40

    /**
     * Слитая подготовка касаний (prepareFused: отсев CCD, пробой и счёт касаний одной задачей по кускам пар) и её
     * отступление при ударе: газ на теле — больше PARALLEL_PAIRS_MIN (1024) пар; струи газа, брошенные в тело,
     * дают обрезку подшага по времени удара (CCD), и тогда пробой и касания считаются заново отдельными проходами
     * по воркерам. Сцена обязана включать оба пути.
     */
    @Test
    fun fusedPrepareWithImpacts() {
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            val gas = gasOnBody(p)
            var maxPairs = 0
            var fusedSubsteps = 0
            var fusedHits = 0
            p.k.state.dbgSubstepHook = {
                val ct = p.k.state.contacts!!
                val pairs = ct.candidatePairCount
                maxPairs = maxOf(maxPairs, pairs)
                if (pairs >= 1024) {
                    fusedSubsteps++
                    if (ct.lastToiClamps > 0) fusedHits++
                }
            }
            for (t in 0 until 60) {
                // Струя: два нижних ряда газа — вниз, в тело, со скоростью, при которой частица за подшаг проходит
                // больше своего радиуса.
                if (t == 12 || t == 30 || t == 48) for (k in 0 until 140) {
                    val i = gas[k]
                    p.k.setVelocity(i, 0.5f, -40f); p.r.setVelocity(i, 0.5f, -40f); p.k.wake(i); p.r.wake(i)
                }
                p.step("газ, удары струёй, воркеров $w")
            }
            println("воркеров $w: пар до $maxPairs, подшагов слитой подготовки $fusedSubsteps, из них с обрезкой CCD $fusedHits")
            assertTrue("пар меньше порога подготовки по воркерам: $maxPairs", maxPairs >= 1024)
            assertTrue("обрезка CCD ни разу не пришлась на слитую подготовку", fusedHits > 0)
        }
    }

    @Test
    fun denseContactsAndMuscleLerp() {
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            gasOnBody(p)
            var maxContacts = 0
            var maxLerp = 0
            // Списки плавности живут внутри тика (на последнем подшаге пустеют): считать их — в подшагах.
            p.k.state.dbgSubstepHook = {
                maxContacts = maxOf(maxContacts, p.k.state.contacts!!.contactCount)
                maxLerp = maxOf(maxLerp, p.k.state.lerpEdgeN + p.k.state.lerpTriN)
            }
            for (t in 0 until 60) {
                if (t % 20 == 0) {
                    val s = if ((t / 20) % 2 == 0) 0.7f else 1f
                    for (c in 0 until GAS_BODY_CELLS) { p.k.setCellScale(c, s); p.r.setCellScale(c, s) }
                }
                p.step("газ на теле и мышцы, воркеров $w")
            }
            println("воркеров $w: касаний до $maxContacts, записей плавности до $maxLerp")
            assertTrue("касаний меньше порога решения по воркерам: $maxContacts", maxContacts >= 2048)
            assertTrue("записей плавности меньше порога воркеров: $maxLerp", maxLerp >= 4096)
        }
    }

    /**
     * Широкая фаза и проходы раз в тик по воркерам: 720 редких свободных частиц вдали от тела — подвижных вершин больше
     * BOUNDS_PARALLEL_MIN (512) при парах меньше PARALLEL_PAIRS_MIN (1024): границы отсева пробоя по воркерам без
     * слитой подготовки, поиск пар перекладываемых вершин по воркерам. Два треугольника в разных блоках тела вывернуты
     * и закреплены (масса бесконечна): долгая инверсия убивает их в одном тике, гибель — после барьера по возрастанию
     * номеров. Пластика — каждый тик по воркерам. Сцена обязана включать все пути.
     */
    @Test
    fun sparseMoversAndInversionKills() {
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            val cols = 40
            p.k.addBody(big(1f, 1f)); p.r.addBody(big(1f, 1f))
            for (k in 0 until 720) {
                val x = 1f + (45 + (k % 24) * 3) * link; val y = 1f + (k / 24) * 3f * link
                val vx = 0.4f * ((k % 7) - 3); val vy = 0.4f * ((k % 5) - 2)
                p.k.addFree(x, y, 0.3f * link, vx, vy); p.r.addFree(x, y, 0.3f * link, vx, vy)
            }
            // Треугольник (r, c), (r, c+1), (r+1, c) на чётном ряду r; вершина (r, c) — отражением за ребро напротив.
            val st = p.k.state
            val inverted = IntArray(2)
            for ((slot, row) in intArrayOf(4, 24).withIndex()) {
                val a = row * cols + 10; val b = a + 1; val c = (row + 1) * cols + 10
                inverted[slot] = (0 until st.triCount).first { t ->
                    setOf(st.triA[t], st.triB[t], st.triC[t]) == setOf(a, b, c)
                }.let { st.triId[it] }
                for (v in intArrayOf(a, b, c)) { p.k.setMass(v, Float.POSITIVE_INFINITY); p.r.setMass(v, Float.POSITIVE_INFINITY) }
                val ex = st.px[c] - st.px[b]; val ey = st.py[c] - st.py[b]
                val t = ((st.px[a] - st.px[b]) * ex + (st.py[a] - st.py[b]) * ey) / (ex * ex + ey * ey)
                val fx = st.px[b] + t * ex; val fy = st.py[b] + t * ey
                val ax = 2 * fx - st.px[a]; val ay = 2 * fy - st.py[a]
                p.k.place(a, ax, ay); p.r.place(a, ax, ay)
            }
            var boundsSubsteps = 0
            st.dbgSubstepHook = {
                val ct = st.contacts!!
                if (ct.candidatePairCount < 1024 && ct.moverVertexCount >= 512) boundsSubsteps++
            }
            var diedAt = -1
            for (t in 0 until 30) {
                p.step("редкий газ и вывернутые треугольники, воркеров $w")
                if (diedAt < 0 && inverted.all { !st.baked.triAlive[it] }) diedAt = t
            }
            println("воркеров $w: подшагов с границами пробоя без слитой подготовки $boundsSubsteps, " +
                "вывернутые умерли на тике $diedAt, разрывов ${st.tornTotal}")
            assertTrue("подвижных вершин меньше порога или пар больше: $boundsSubsteps", boundsSubsteps > 0)
            assertTrue("вывернутые треугольники не умерли", diedAt >= 0)
        }
    }

    /**
     * Кости по воркерам: у каждого тела ~120 жёстких кластеров по три клетки — больше PARALLEL_BONES_MIN (64), масса,
     * центр и момент кластеров в подготовке касаний считаются по частям. Тела сталкиваются: касания есть в каждом
     * подшаге, кости касаются.
     */
    @Test
    fun manyBonesInContact() {
        fun boned(x0: Float, y0: Float, cols: Int, rows: Int): BodyDefinition {
            val bones = ArrayList<IntArray>()
            for (r in 1 until rows - 2 step 3) for (c in 1 until cols - 2 step 3)
                bones.add(intArrayOf(r * cols + c, r * cols + c + 1, (r + 1) * cols + c))
            return RustScenes.lattice(cols, rows, x0 = x0, y0 = y0, bones = bones)
        }
        for (w in workerCounts) RustScenes.Pair(RustScenes.withWorkers(w), contacts = true).use { p ->
            val a = boned(1f, 1f, 40, 30)
            val b = boned(1f + 40 * link + 0.3f * link, 1.05f, 30, 25)
            p.k.addBody(a); p.r.addBody(a)
            p.k.addBody(b); p.r.addBody(b)
            for (c in 0 until 750) { val i = 1200 + c; p.k.setVelocity(i, -20f, 0.3f); p.r.setVelocity(i, -20f, 0.3f) }
            for (t in 0 until 60) p.step("тела с сотней костей, воркеров $w")
            val bones = p.k.state.rigidBones.size
            println("воркеров $w: кластеров костей $bones")
            assertTrue("костей меньше порога воркеров: $bones", bones >= 64)
        }
    }

    /** Kotlin тоже с воркерами: его биты от их числа не зависят — сверка проверяет обе стороны сразу. */
    @Test
    fun bothSidesWithWorkers() {
        RustScenes.Pair(RustScenes.withWorkers(4), contacts = true, kotlinWorkers = true).use { p ->
            val a = big(1f, 1f)
            val b = big(1f + 40 * link + 0.3f * link, 1.05f, cols = 30, rows = 25, bone = false)
            p.k.addBody(a); p.r.addBody(a)
            p.k.addBody(b); p.r.addBody(b)
            for (c in 0 until 750) { val i = 1200 + c; p.k.setVelocity(i, -20f, 0.3f); p.r.setVelocity(i, -20f, 0.3f) }
            for (t in 0 until 80) p.step("обе стороны с воркерами")
        }
    }
}
