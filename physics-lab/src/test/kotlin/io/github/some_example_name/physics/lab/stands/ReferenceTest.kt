package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.REFERENCE_SUBSTEPS
import io.github.some_example_name.physics.SUBSTEPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Сверка стендов с эталонным решателем (Reference.kt): сцена шагает эталоном теми же вызовами,
 * спокойная сцена сходится, разные сцены расходятся, а то, что судить нельзя, честно не судится.
 * Сам эталонный решатель проверяет ReferenceSolverTest в модуле физики.
 */
class ReferenceTest {
    private fun scene(ticks: Int, limits: Limits = Limits.INTACT, setup: StandWorld.() -> Unit) =
        Scenario("t/reference", "тест", "сверка с эталоном", "", "", ticks, limits, setup = setup)

    /** Тело летит в среде, вращаясь и работая мышцей: ни одного касания, решатели отличаются только устройством. */
    private val calm = scene(160) {
        val s = Shapes.rect(10, 4).withMuscles("all", "целиком мышца") { _, _, _ -> 0 }
        val b = body(s, 0.0, 0.0)
        kick(b, 0.5, 0.2, 0.01)
        at(20) { muscles(b, 0.8f) }; at(90) { muscles(b, 1f) }
    }

    @Test
    fun `мир стенда с эталоном шагает эталонным решателем и не спит`() {
        val run = StandRun(calm, reference = REFERENCE_SUBSTEPS)
        assertFalse("сон выключен", run.w.world.sleepOn)
        repeat(5) { run.step() }
        assertEquals(REFERENCE_SUBSTEPS, run.w.world.reference!!.substeps)
        assertEquals("прогон не упал", null, run.crash)
        // Обычный мир эталона не заводит.
        val fast = StandRun(calm).also { it.step() }
        assertEquals(null, fast.w.world.reference)
    }

    @Test
    fun `эталон идёт ровно столько тиков, сколько шёл быстрый`() {
        val run = StandRun(calm, reference = SUBSTEPS, stopAt = 37).runAll()
        assertEquals(37, run.tick)
    }

    @Test
    fun `спокойная сцена сходится с эталоном и на шаге игры, и на мелком`() {
        val control = Control(calm)
        assertTrue("сцена спокойна: ${control.nudge.describe()}", control.calm)
        assertTrue("разнобой тел её не меняет: ${control.phase.describe()}", control.phase.close)
        val result = checkReference(calm, REFERENCE_SUBSTEPS, control, particles = 60)
        assertEquals("шаг игры: ${result.same.describe()}", ReferenceVerdict.CLOSE, result.same.verdict)
        assertEquals("мелкий шаг: ${result.fine!!.describe()}", ReferenceVerdict.CLOSE, result.fine!!.verdict)
        assertEquals(SUBSTEPS, result.same.substeps); assertEquals(REFERENCE_SUBSTEPS, result.fine!!.substeps)
        // Тело пролетело десятки связей, а решатели разошлись на доли связи (замер 16.11.2026: 0.42 связи на
        // мелком шаге — мышца при 64 подшагах сжимает тело чуть иначе, и среда уводит его чуть в сторону).
        assertTrue("тело летело", control.base.travel.last()[0] > 20.0)
        println("сверка с эталоном, спокойная сцена: путь %.1f связи, расхождение на шаге игры %.3f связи, на мелком %.3f".format(
            control.base.travel.last()[0], result.same.deviation!!.score, result.fine!!.deviation!!.score))
        assertTrue("расхождение ${result.fine!!.deviation!!.score} связи", result.fine!!.deviation!!.score < 1.0)
        assertTrue("на шаге игры — ближе: ${result.same.deviation!!.score} связи", result.same.deviation!!.score <= result.fine!!.deviation!!.score)
    }

    @Test
    fun `без мелкого шага сверяется только шаг игры`() {
        val result = checkReference(calm, SUBSTEPS, Control(calm), particles = 60)
        assertEquals(ReferenceVerdict.CLOSE, result.same.verdict)
        assertEquals(null, result.fine)
    }

    @Test
    fun `сцена не по карману эталону не сверяется и говорит почему`() {
        // 60 частиц × 160 тиков — даром; миллион частиц — за бюджет и мелкого шага, и шага игры.
        val result = checkReference(calm, REFERENCE_SUBSTEPS, Control(calm), particles = 1_000_000)
        assertEquals(ReferenceVerdict.SKIPPED, result.same.verdict)
        assertEquals(ReferenceVerdict.SKIPPED, result.fine!!.verdict)
        assertTrue(result.fine!!.reason, "велика" in result.fine!!.reason)
    }

    @Test
    fun `сцена, которая сама управляет сном, не сверяется`() {
        val s = scene(60) { val b = body(Shapes.square(5), 0.0, 0.0); kick(b, 0.3, 0.0); at(10) { world.sleepOn = true } }
        val result = checkReference(s, SUBSTEPS, Control(s), particles = 30)
        assertEquals(ReferenceVerdict.SKIPPED, result.same.verdict)
        assertTrue(result.same.reason, "сном" in result.same.reason)
    }

    @Test
    fun `разнобой сдвигает тела по-разному и не трогает сцену как написана`() {
        assertEquals(0.0, Frame.SCENE.jiggle(3), 0.0)
        val shifts = (0 until 10).map { Frame.PHASE.jiggle(it) }
        assertTrue("сдвиги разные: $shifts", shifts.toSet().size >= 4)
        assertTrue("не больше двух долей", shifts.all { abs(it) <= 2 * Frame.PHASE.spread + 1e-12 })
        // Два тела сцены при разнобое стоят на другом расстоянии друг от друга, чем написано.
        fun gap(frame: Frame): Double {
            val w = StandWorld(frame = frame)
            val a = w.body(Shapes.square(4), 0.0, 0.0); val b = w.body(Shapes.square(4), 8.0, 0.0)
            return w.centerOf(b)[0] - w.centerOf(a)[0]
        }
        assertEquals(8.0, gap(Frame.SCENE), 1e-6)
        assertTrue("зазор изменился: ${gap(Frame.PHASE)}", abs(gap(Frame.PHASE) - 8.0) > 0.02)
    }

    @Test
    fun `сцена, чувствительная к разнобою, считается хаотичной и не судится`() {
        // Частица летит точно в стык двух клеток торца: сдвиг на сотые клетки решает, в какую сторону она отскочит.
        val s = scene(200) {
            noMedium()
            val b = body(Shapes.square(6), 0.0, 0.0)
            ball(-9.0, 0.0, 1.5, 0.0)
            kick(b, 0.0, 0.0)
        }
        val control = Control(s)
        val result = checkReference(s, SUBSTEPS, control, particles = 40)
        assertNotNull(result.same.deviation)
        if (!control.phase.close) assertEquals(ReferenceVerdict.CHAOTIC, result.same.verdict)
        else assertTrue("сцена оказалась нечувствительной — тогда она обязана быть осуждена по существу", result.same.verdict != ReferenceVerdict.SKIPPED)
    }

    @Test
    fun `отчёт сверки называет итог каждого прогона`() {
        val out = Outcome(calm)
        out.reference = checkReference(calm, REFERENCE_SUBSTEPS, Control(calm), particles = 60)
        val text = referenceReport(listOf(out))
        assertTrue(text, "Тот же шаг" in text && "Мелкий шаг" in text && "| сошлось | 1 |" in text)
        assertTrue(referenceSummary(listOf(out)), "сошлось 1" in referenceSummary(listOf(out)))
    }

    @Test
    fun `эталон на шаге игры не разбрасывает обломки по миру`() {
        // gen/user-genome-9/crash: тела бьются на предельной скорости, после удара остаются обрывки из
        // двух-трёх клеток. Пока эталон сохранял момент импульса поворотом положений каждый подшаг
        // (отвергнуто 16.11.2026, см. ReferenceSolver.cancelMomentum), такой обрывок к 15-му тику уносил
        // координаты в 1e29, и монитор правил падал. Дальше, чем пускает потолок скорости, не улетает никто.
        val s = Catalog.all().first { it.id == "gen/user-genome-9/crash" && it.variation == Variation.BASE }
        val run = StandRun(s, reference = SUBSTEPS, stopAt = 40)
        val w = run.w.world
        fun reach(): Double {
            var far = 0.0
            for (i in 0 until w.particleCount) far = maxOf(far, abs(w.x[i]).toDouble(), abs(w.y[i]).toDouble())
            return far
        }
        val start = reach()
        run.runAll()
        assertTrue("эталон упал: ${run.crash}", run.crash == null)
        assertEquals("сцена дошла до своего тика", 40, run.tick)
        // Двойной запас к потолку: поправки положений внутри подшага потолком скорости не ограничены.
        val limit = start + 2 * 40 * MAX_SPEED_CELLS_PER_TICK * STAND_WORLD_SCALE.meanLinkLength
        assertTrue("обломки разлетелись на ${reach()} при пределе $limit", reach() < limit)
    }

    @Test
    fun `правило потолка скорости видит и эталон`() {
        // Частица брошена вдвое быстрее потолка: потолок срезает её у любого решателя, и правило «потолок
        // скорости молчит» обязано загореться у любого. Пока эталон срабатываний не считал (до 16.11.2026),
        // в таблице сверки «кто нарушает правила» у него в этой строке всегда стоял ноль.
        val s = scene(5) { noMedium(); ball(0.0, 0.0, 2 * SPEED_CAP, 0.0) }
        for (reference in intArrayOf(0, SUBSTEPS, REFERENCE_SUBSTEPS)) {
            val run = StandRun(s, reference = reference).runAll()
            assertEquals("подшагов эталона $reference: прогон не упал", null, run.crash)
            assertTrue("подшагов эталона $reference: потолок сработал", run.monitor.capHits > 0)
            assertTrue("подшагов эталона $reference: правило загорелось", run.monitor.results[Rule.SPEED_CAP]!!.failed)
        }
    }

    @Test
    fun `удар быстрее шага эталона не сверяется`() {
        // Две частицы навстречу, каждая на связь за подшаг игры. На шаге игры эталон (без обрезки по времени удара)
        // пропускает их друг сквозь друга: такой прогон быстрому решателю не судья, и сверка это говорит.
        // На шаге вдесятеро мельче удар эталону по силам.
        // При шести подшагах это были 6 связей за тик и 64 подшага эталона. При шестнадцати (24.11) «связь за
        // подшаг» — 16 связей за тик, вдвое выше потолка скорости, поэтому потолок в сцене снят: законный удар шаг
        // из 16 подшагов уже не проскакивает (на прежних 6 связях за тик сцена выходила просто «хаотичной»), а
        // проверяется здесь то, что сверка узнаёт удар быстрее шага.
        val each = SUBSTEPS.toDouble()
        val fine = maxOf(REFERENCE_SUBSTEPS, 10 * SUBSTEPS)
        val s = scene(20) { noMedium(); world.state.dbgNoClamp = true; ball(-1.2, 0.0, each, 0.0); ball(1.2, 0.0, -each, 0.0) }
        val result = checkReference(s, fine, Control(s), particles = 2)
        assertEquals(ReferenceVerdict.SKIPPED, result.same.verdict)
        assertTrue(result.same.reason, "быстрее шага" in result.same.reason)
        assertEquals("по такому прогону не считается и то, кто нарушил правила", null, result.same.deviation)
        assertTrue("мелкий шаг сверяется: ${result.fine!!.reason}", result.fine!!.verdict != ReferenceVerdict.SKIPPED)
        // В отчёте причина стоит отдельной строкой.
        val out = Outcome(s).also { it.reference = result }
        assertTrue("| удар быстрее шага эталона | 1 |" in referenceReport(listOf(out)))
    }

    @Test
    fun `рост клеток за проскоченный удар не принимается`() {
        // У делящейся клетки дистанция касания меньше обычной, первые тики — десятая связи. Пока глубину
        // мерили радиусом клетки, а потом долей дистанции касания без оглядки на то, сколько пара прошла
        // (первые версии отметки, 16.11.2026), проскоченным ударом считалось обычное касание такой клетки:
        // сцены роста набирали сотни ложных отметок и не сверялись (в этих двух — по 3).
        for (id in listOf("grow/corridor/square8/layers", "grow/corridor/disc5/slow")) {
            val s = Catalog.all().first { it.id == id && it.variation == Variation.BASE }
            val run = StandRun(s, reference = SUBSTEPS).runAll()
            assertEquals("$id: прогон не упал", null, run.crash)
            assertEquals("$id: проскоченных ударов", 0, run.w.world.reference!!.missedHits)
        }
    }

    @Test
    fun `касание в покое эталон на мелком шаге проходит без разрывов`() {
        // Два тела поставлены с перекрытием касания и стоят. Пока снятие старого перекрытия давало скорость
        // «перекрытие за свой подшаг» (до 16.11.2026), эталон на 64 подшагах рвал такие тела: в этой сцене
        // 40 связей и 16 клеток при нуле на шаге игры и у быстрого решателя.
        val s = Catalog.all().first { it.id == "prim/rect16x4/touch" && it.variation == Variation.BASE }
        for (reference in intArrayOf(SUBSTEPS, REFERENCE_SUBSTEPS)) {
            val run = StandRun(s, reference = reference).runAll()
            assertEquals("подшагов $reference: прогон не упал", null, run.crash)
            assertEquals("подшагов $reference: порвано связей", 0, run.monitor.torn)
            assertEquals("подшагов $reference: погибло клеток", 0, run.monitor.died)
        }
    }
}
