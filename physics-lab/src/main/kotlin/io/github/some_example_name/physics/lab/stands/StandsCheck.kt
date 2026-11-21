package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.REFERENCE_SUBSTEPS
import io.github.some_example_name.physics.SUBSTEPS
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * ПАКЕТНЫЙ ПРОГОН СТЕНДОВ: все сцены каталога до конца, без окна, с отчётом.
 *
 *   gradlew :physics-lab:standsCheck                      все сцены во всех вариациях
 *   gradlew :physics-lab:standsCheck -Ponly=prim/,bone/   сцены, в номере которых есть одна из подстрок
 *   gradlew :physics-lab:standsCheck -Pvary=base,len      только эти вариации (base — ровная решётка)
 *   gradlew :physics-lab:standsCheck -Pdetail=prim/square8/tap   одна сцена с числами по тикам
 *   gradlew :physics-lab:standsCheck -Pworkers=4          ещё и сверка «один поток = 4 воркера»
 *   gradlew :physics-lab:standsCheck -Pworkers=4 -PworkersMin=1000   то же, но только у сцен от тысячи частиц
 *   gradlew :physics-lab:standsCheck -Prepeat=0           без сверки повтора
 *   gradlew :physics-lab:standsCheck -Pjobs=8             сцен одновременно
 *   gradlew :physics-lab:standsCheck -Pconfig=RB_SLEEP=0  с переключателями решателя (StandWorld.STAND_CONFIG)
 *   gradlew :physics-lab:standsCheck -Psubsteps=4         с другим числом подшагов
 *   gradlew :physics-lab:standsCheck -Preport=имя         отчёт под своим именем (не затирает основной)
 *   gradlew :physics-lab:standsCheck -Psymmetry=1         ещё и проверка симметрии (Symmetry.kt); -Psymmetry=shift,mirror — только названные
 *   gradlew :physics-lab:standsCheck -Preference=1        ещё и сверка с эталонным решателем (Reference.kt): на шаге игры и на 64 подшагах;
 *                                                         -Preference=same — только на шаге игры, -PreferenceBudget=N — предел частиц × тиков
 *
 * Сцены независимы (у каждой свой мир), поэтому идут по нескольку сразу. Сверка с воркерами —
 * отдельным проходом по одной сцене: пул воркеров один на процесс.
 *
 * Отчёт: physics-lab/build/reports/stands/report.md и verdicts.tsv (его читает окно стендов).
 * Код выхода 1, если хоть одна сцена не прошла.
 */

/** Что одно правило сказало о сцене — без ссылки на мир. */
class RuleLine(val rule: Rule, val failed: Boolean, val firstTick: Int, val violations: Int, val text: String)

/**
 * Итог сцены. Мир и монитор после прогона отпускаются: в каталоге тысячи сцен, среди них тела в
 * десять тысяч клеток, и держать их все до конца прогона — это гигабайты. Остаются числа,
 * строки правил и отпечатки по тикам (по ним идёт сверка с воркерами).
 */
class Outcome(val scenario: Scenario) {
    var error: String = ""
    var seconds = 0.0
    var ran = false
    var passedRules = false
    val lines = ArrayList<RuleLine>()
    var hashes = LongArray(0)
    var ticks = 0
    var particles = 0
    var fingerprint = 0L
    // Числа монитора — в файл итогов и в отчёт.
    var torn = 0; var died = 0; var capHits = 0; var peakEnergy = 0.0; var maxSpeed = 0.0
    var foreignTicks = 0; var foreignLongest = 0; var selfTicks = 0; var terrainTicks = 0
    var invertedPeak = 0; var invertedLongest = 0; var boneWorst = 0.0; var shapeWorst = 0.0; var poseWorst = 0.0
    var endSpeed = 0.0; var awake = 0; var sleepTick = -1; var jitterTicks = 0; var jitterLongest = 0
    var linksTotal = 0; var cellsTotal = 0
    // ЧЕМ РАЗРУШЕНО — счётчики решателя: разрывы растяжением, сжатием и пробоем ребра; гибель от давления,
    // раздавленных без связей, складки; заявки на гибель «центр в чужом радиусе» по парам (KillCauses).
    var tornPull = 0; var tornCrush = 0; var pierced = 0; var pressureDead = 0; var crushDead = 0; var foldDead = 0
    var kills = IntArray(KillCauses.COLUMNS.size); var killBoneBone = 0
    // Худшие меры рождений (BirthLog): зазор до тела и лишняя длина новых связей, в длинах связи новорождённой.
    var birthGap = 0.0; var birthExcess = 0.0

    val passed: Boolean get() = error.isEmpty() && ran && passedRules && lines.none { it.failed }
    val failed: List<Rule> get() = lines.filter { it.failed }.map { it.rule }

    fun take(run: StandRun) {
        ran = true
        val m = run.monitor
        passedRules = run.crash == null
        lines.clear()
        for (r in m.results.values) if (r.applies) lines.add(RuleLine(r.rule, r.failed, r.firstTick, r.violations, r.text))
        ticks = run.tick
        hashes = m.hashes.copyOf(minOf(run.tick, m.hashes.size - 1) + 1)
        particles = run.w.world.particleCount
        fingerprint = fingerprint(hashes)
        torn = m.torn; died = m.died; capHits = m.capHits; peakEnergy = m.peakEnergy; maxSpeed = m.maxSpeed
        foreignTicks = m.foreignTicks; foreignLongest = m.foreignLongest; selfTicks = m.selfTicks; terrainTicks = m.terrainTicks
        invertedPeak = m.invertedPeak; invertedLongest = m.invertedLongest; boneWorst = m.boneWorst; shapeWorst = m.shapeWorst; poseWorst = m.poseWorst
        endSpeed = m.endSpeed; awake = m.awake; sleepTick = m.sleepTick; jitterTicks = m.jitterTicks; jitterLongest = m.jitterLongest
        linksTotal = m.linksTotal; cellsTotal = m.cellsTotal
        val st = run.w.world.state
        tornPull = st.dbgTornPull; tornCrush = st.dbgTornCrush; pierced = st.piercedCount
        pressureDead = st.pressureBurstCount; crushDead = st.crushBurstCount; foldDead = st.foldKillCount + st.trapKillCount
        kills = run.kills.count.copyOf(); killBoneBone = run.kills.boneBone
        birthGap = m.birthGapWorst; birthExcess = m.birthExcessWorst
    }

    /** Итог сверки повтора или сверки с воркерами: строка правила с тиком расхождения. */
    fun judge(rule: Rule, divergedAt: Int) {
        lines.removeAll { it.rule == rule }
        lines.add(RuleLine(rule, divergedAt >= 0, divergedAt, if (divergedAt >= 0) 1 else 0, if (divergedAt >= 0) "разошлось на тике $divergedAt" else ""))
    }

    /** Итог проверки, у которой есть свой текст (симметрия, эталон): [problems] пусто — правило выполнено. */
    fun judge(rule: Rule, tick: Int, problems: List<String>) {
        lines.removeAll { it.rule == rule }
        lines.add(RuleLine(rule, problems.isNotEmpty(), if (problems.isEmpty()) -1 else tick, problems.size, problems.joinToString("; ")))
    }

    /** Проверка симметрии (Symmetry.kt); null — не запускалась. */
    var symmetry: SymmetryResult? = null
    /** Сверка с эталонным решателем (Reference.kt); null — не запускалась. */
    var reference: ReferenceResult? = null
}

private fun runOne(s: Scenario, repeat: Boolean, symmetry: List<Frame> = emptyList(), reference: Int = 0): Outcome {
    val out = Outcome(s)
    val t0 = System.nanoTime()
    try {
        val run = StandRun(s).runAll()
        out.take(run)
        run.crash?.let { out.error = "решатель упал на тике ${run.tick}: $it" }
        if (repeat && run.crash == null) {
            // Та же сцена второй раз в том же процессе: остатки прошлого мира, время и порядок
            // обхода таблиц на результат влиять не должны.
            val again = StandRun(s).runAll()
            out.judge(Rule.REPEAT, firstDifference(out.hashes, out.ticks, again))
        }
        // Исходный прогон с набросками и контрольный со сдвигом — общие для симметрии и эталона. Сцена
        // дороже десяти бюджетов эталона (сто тысяч частиц, тело в десять тысяч клеток) повторно не
        // прогоняется вовсе: каждая проверка — это ещё от трёх до восьми её прогонов.
        val heavy = out.particles.toLong() * out.ticks > REFERENCE_BUDGET * 10
        val control = if ((symmetry.isNotEmpty() || reference > 0) && run.crash == null && !heavy) Control(s) else null
        if (symmetry.isNotEmpty() && control != null) {
            // Та же сцена в других системах отсчёта: итог в координатах сцены обязан совпасть. См. Symmetry.kt.
            val result = checkSymmetry(s, symmetry, control)
            out.symmetry = result
            val worst = result.checks.filter { !it.close || !it.clean }.minOfOrNull { it.tick } ?: -1
            if (result.calm) out.judge(Rule.SYMMETRY, worst, result.problems)
        }
        if (reference > 0 && control != null) {
            // Та же сцена эталонным решателем: крупные величины обязаны сойтись. См. Reference.kt.
            val result = checkReference(s, reference, control, out.particles)
            out.reference = result
            // Судит прогон на шаге игры; мелкий шаг только считается (Reference.kt).
            val same = result.same
            if (same.verdict == ReferenceVerdict.CLOSE || same.failed)
                out.judge(Rule.REFERENCE, same.deviation?.tick ?: -1, if (same.failed) listOf(same.describe()) else emptyList())
        }
    } catch (t: Throwable) {
        out.error = "сцена не собралась: $t"
    }
    out.seconds = (System.nanoTime() - t0) / 1e9
    return out
}

/**
 * Отпечаток всей сцены: свёртка отпечатков состояния на каждом тике. Одна и та же сцена на
 * другом устройстве обязана дать то же число; расхождение — это другая арифметика (float,
 * sin/cos) или недетерминизм, и искать его надо с первого разошедшегося тика.
 */
internal fun fingerprint(hashes: LongArray): Long {
    var h = 1125899906842597L
    for (v in hashes) h = 31 * h + v
    return h
}

internal fun fingerprint(run: StandRun): Long =
    fingerprint(run.monitor.hashes.copyOf(minOf(run.tick, run.monitor.hashes.size - 1) + 1))

/** Первый тик, на котором отпечатки двух прогонов разошлись; -1 — совпали все. */
private fun firstDifference(ha: LongArray, ticks: Int, b: StandRun): Int {
    val hb = b.monitor.hashes
    for (t in 0 until minOf(ha.size, hb.size)) if (ha[t] != hb[t]) return t
    return if (ticks != b.tick) minOf(ticks, b.tick) else -1
}

private fun detail(s: Scenario) {
    val run = StandRun(s)
    val m = run.monitor
    println("СЦЕНА ${s.id}: ${s.title}")
    println("  вариация: ${s.variation.title} — ${s.variation.about}")
    println("  ${s.about}")
    println("  ожидание: ${s.expect}")
    println("  частиц в мире ${run.w.world.particleCount}, тел ${run.w.bodies.size}, свободных ${run.w.balls.size}, неподвижных ${run.w.fixed.size}, тиков ${s.ticks}, правила «${s.limits.name}»")
    val names = run.w.probes.keys.toList()
    println("  тик   смещ/тик  энергия  макс.скор  не спит  порвано  погибло  чужая  своя  рельеф  вывернуто  дрожь" + names.joinToString("") { "  $it" })
    val every = maxOf(1, s.ticks / 40)
    // Журнал разрывов решателя: по нему видно, что рвётся первым и чем.
    val st = run.w.world.state
    val tears = StringBuilder().also { st.dbgTearLog = it }
    // Первые гибели клеток: тик, клетка и где она была (единицы игры) — видно, какое место тела гибнет.
    val deaths = ArrayList<String>()
    run.kills.keepLines = DETAIL_TEAR_LINES
    while (!run.done) {
        run.step()
        val world = run.w.world
        for (k in 0 until world.cellDeathCount) if (deaths.size < DETAIL_TEAR_LINES) {
            val p = world.cellDeath(k)
            deaths.add(String.format(Locale.ROOT, "    тик %d: клетка #%d в (%.2f, %.2f)", run.tick, p, run.w.gameX(p), run.w.gameY(p)))
        }
        if (run.tick % every == 0 || run.done) {
            val t = minOf(run.tick, s.ticks)
            println(String.format(Locale.ROOT, "  %4d  %8.5f  %7.3f  %9.4f  %7d  %7d  %7d  %5d  %4d  %6d  %9d  %5d%s",
                run.tick, m.speed[t], m.energy[t], m.maxSpeed, m.awake, m.torn, m.died,
                m.foreignTicks, m.selfTicks, m.terrainTicks, m.invertedPeak, m.jitterTicks,
                names.joinToString("") { name -> String.format(Locale.ROOT, "  %.4f", run.inVariation { run.w.probes[name]!!() }) }))
        }
    }
    run.crash?.let { println("  РЕШАТЕЛЬ УПАЛ: $it"); it.printStackTrace() }
    println()
    for (r in m.results.values) {
        if (!r.applies) continue
        println(String.format("  %-4s %-34s %s", if (r.failed) "FAIL" else "ok", r.rule.title,
            if (r.failed) "с тика ${r.firstTick}, раз: ${r.violations} — ${r.text}" else ""))
    }
    println(String.format(Locale.ROOT, "  уснуло на тике %d; в конце смещение %.5f связи/тик; кости %.3f, связи %.3f, поза %.3f; порвано %d из %d, погибло %d из %d",
        m.sleepTick, m.endSpeed, m.boneWorst, m.shapeWorst, m.poseWorst, m.torn, m.linksTotal, m.died, m.cellsTotal))
    // ЧЕМ РАЗРУШЕНО — по счётчикам решателя. «Порвано» выше считает и связи погибших клеток, поэтому
    // сумма причин разрыва меньше него.
    val burst = st.pressureBurstCount + st.crushBurstCount + st.trapKillCount + st.foldKillCount
    println("  разрывы связей: растяжением ${st.dbgTornPull} (из них мышечных ${st.dbgTornPullMuscle}), сжатием ${st.dbgTornCrush} " +
        "(мышечных ${st.dbgTornCrushMuscle}), пробоем ребра ${st.piercedCount}")
    println("  гибель клеток: центр в чужом радиусе ${st.deadCount - burst}, давлением ${st.pressureBurstCount}, " +
        "раздавлена и осталась без связей ${st.crushBurstCount}, складка ${st.foldKillCount + st.trapKillCount}")
    val lines = tears.lines().filter { it.isNotBlank() }
    if (lines.isNotEmpty()) {
        println("  первые разрывы (всего в журнале ${lines.size}):")
        for (line in lines.take(DETAIL_TEAR_LINES)) println(line)
    }
    if (deaths.isNotEmpty()) {
        println("  первые гибели клеток:")
        for (line in deaths) println(line)
    }
    val kills = run.kills
    if (kills.total > 0) {
        // Об кого и как: см. KillCauses.
        println("  гибель «центр в чужом радиусе» — об кого и как, клеток:")
        for (k in KillCauses.KINDS.indices) if (KillCauses.WAYS.indices.any { kills.of(k, it) > 0 })
            println("    ${KillCauses.KINDS[k]}: " + KillCauses.WAYS.indices.filter { kills.of(k, it) > 0 }.joinToString(", ") { "${KillCauses.WAYS[it]} ${kills.of(k, it)}" })
        println("  первые заявки:")
        for (line in kills.lines) println(line)
    }
    if (kills.boneBone > 0) println("  заявок «кость о кость» (не гибнет никто): ${kills.boneBone}")
}

/** Сколько первых разрывов печатает подробный режим. */
private const val DETAIL_TEAR_LINES = 40

fun main(args: Array<String>) {
    val only = System.getProperty("stands.only").orEmpty()
    val detailId = System.getProperty("stands.detail").orEmpty()
    val repeat = System.getProperty("stands.repeat") != "0"
    val workers = System.getProperty("stands.workers")?.toIntOrNull() ?: 0
    val jobs = System.getProperty("stands.jobs")?.toIntOrNull() ?: maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
    val reportName = System.getProperty("stands.report").orEmpty()
    // Проверка симметрии: -Psymmetry=1 — все системы отсчёта, -Psymmetry=shift,mirror — только названные.
    val symmetry = System.getProperty("stands.symmetry").orEmpty().let { spec ->
        when {
            spec.isEmpty() || spec == "0" -> emptyList()
            spec == "1" || spec == "all" -> Frame.CHECKS
            else -> spec.split(',').map { id -> Frame.NAMED.firstOrNull { it.id == id.trim() } ?: error("нет системы отсчёта «$id»: есть ${Frame.NAMED.joinToString { it.id }}") }
        }
    }
    // Сверка с эталонным решателем: всегда на шаге игры, а мелкий шаг — -Preference=1 (64 подшага),
    // -Preference=128 (свои) или -Preference=same (без мелкого шага, быстрее).
    val reference = System.getProperty("stands.reference").orEmpty().let { spec ->
        when {
            spec.isEmpty() || spec == "0" -> 0
            spec == "1" -> REFERENCE_SUBSTEPS
            spec == "same" -> SUBSTEPS
            else -> spec.toIntOrNull() ?: error("-Preference: 1, same или число подшагов эталона, а не «$spec»")
        }
    }
    // Сверка с воркерами идёт по одной сцене и в одном потоке: на всём каталоге это час. Сцена мельче одного блока
    // решателя (тысяча частиц) делит работу на один кусок, поэтому её можно не сверять: -PworkersMin=1000.
    val workersMin = System.getProperty("stands.workersMin")?.toIntOrNull() ?: 0

    val all = Catalog.all()
    val duplicates = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
    require(duplicates.isEmpty()) { "номера сцен повторяются: $duplicates" }

    if (detailId.isNotEmpty()) {
        val s = all.firstOrNull { it.id == detailId } ?: all.firstOrNull { it.id.contains(detailId) }
        if (s == null) { println("нет сцены $detailId"); exitProcess(2) }
        detail(s)
        return
    }

    val keys = only.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val list = if (keys.isEmpty()) all else all.filter { s -> keys.any { s.id.contains(it) } }
    println("СТЕНДЫ: сцен ${list.size} из ${all.size}, вариации ${ACTIVE_VARIATIONS.joinToString(",") { it.id }}, подшагов $SUBSTEPS, одновременно $jobs" +
        (if (repeat) ", сверка повтора" else "") + (if (workers > 1) ", сверка с $workers воркерами" else "") +
        (if (symmetry.isNotEmpty()) ", симметрия: ${symmetry.joinToString(", ") { it.title }}" else "") +
        (if (reference > 0) ", сверка с эталоном на шаге игры" + (if (reference > SUBSTEPS) " и на $reference подшагах" else "") else "") +
        (if (STAND_CONFIG.isNotEmpty()) ", переключатели решателя $STAND_CONFIG" else ""))

    val t0 = System.nanoTime()
    val pool = Executors.newFixedThreadPool(jobs)
    val finished = AtomicInteger(0)
    val outcomes = try {
        pool.invokeAll(list.map { s -> Callable {
            val out = runOne(s, repeat, symmetry, reference)
            val n = finished.incrementAndGet()
            if (n % 500 == 0) println(String.format(Locale.ROOT, "  … %d из %d сцен, %.0f с", n, list.size, (System.nanoTime() - t0) / 1e9))
            out
        } }).map { it.get() }
    } finally { pool.shutdown() }

    if (workers > 1) {
        val shared = PhysicsWorkers.shared(workers)
        for (out in outcomes) {
            if (!out.ran || out.error.isNotEmpty() || out.particles < workersMin) continue
            try {
                val parallel = StandRun(out.scenario, shared, forceParallel = true).runAll()
                out.judge(Rule.WORKERS, firstDifference(out.hashes, out.ticks, parallel))
            } catch (t: Throwable) {
                out.error = "прогон с воркерами упал: $t"
            }
        }
    }
    val seconds = (System.nanoTime() - t0) / 1e9

    val failed = outcomes.filter { !it.passed }
    val dir = File("physics-lab/build/reports/stands").also { it.mkdirs() }
    val suffix = if (reportName.isEmpty()) "" else "-$reportName"
    writeVerdicts(File(dir, "verdicts$suffix.tsv"), outcomes)
    writeReport(File(dir, "report$suffix.md"), outcomes, seconds, repeat, workers)
    if (symmetry.isNotEmpty()) {
        val text = symmetryReport(outcomes, symmetry)
        File(dir, "symmetry$suffix.md").writeText(text)
        println()
        println(symmetrySummary(outcomes, symmetry))
        println("Отчёт симметрии: " + File(dir, "symmetry$suffix.md").path)
    }
    if (reference > 0) {
        File(dir, "reference$suffix.md").writeText(referenceReport(outcomes))
        println()
        println(referenceSummary(outcomes))
        println("Отчёт сверки с эталоном: " + File(dir, "reference$suffix.md").path)
    }

    println()
    println("ПО ВАРИАЦИЯМ:")
    for (v in Variation.values()) {
        val group = outcomes.filter { it.scenario.variation == v }
        if (group.isNotEmpty()) println(String.format("  %-28s прошло %5d из %5d", v.title, group.count { it.passed }, group.size))
    }
    println()
    println("ПО СЕМЕЙСТВАМ (в скобках — на ровной решётке):")
    for ((family, group) in outcomes.groupBy { it.scenario.family }) {
        val base = group.filter { it.scenario.variation == Variation.BASE }
        println(String.format("  %-44s прошло %5d из %5d   (%4d из %4d)", family, group.count { it.passed }, group.size, base.count { it.passed }, base.size))
    }
    println()
    println("ПО ПРАВИЛАМ (сколько сцен нарушило; в скобках — на ровной решётке):")
    for (rule in Rule.values()) {
        val n = outcomes.count { rule in it.failed }
        if (n > 0) println(String.format("  %-36s %5d   (%d)", rule.title, n, outcomes.count { it.scenario.variation == Variation.BASE && rule in it.failed }))
    }
    val errors = outcomes.count { it.error.isNotEmpty() }
    if (errors > 0) println(String.format("  %-36s %5d", "упало или не собралось", errors))
    val vacant = outcomes.count { Rule.PREMISE in it.failed }
    println()
    println("ВХОЛОСТУЮ (не случилось то, ради чего сцена поставлена; это вопрос к постановке, а не к физике): $vacant")
    println()
    println("КОНТРОЛЬНАЯ СУММА ПРОГОНА: " + checksum(outcomes) + "  (сцен ${outcomes.size}, подшагов $SUBSTEPS; на другом устройстве должна совпасть)")
    println(String.format(Locale.ROOT, "ИТОГ: прошло %d из %d (вхолостую %d) за %.0f с. Отчёт: %s", outcomes.size - failed.size, outcomes.size, vacant, seconds, File(dir, "report$suffix.md").path))
    if (failed.isNotEmpty()) exitProcess(1)
}

/** Сумма отпечатков всех сцен прогона в порядке каталога. */
private fun checksum(outcomes: List<Outcome>): String {
    var h = 7L
    for (o in outcomes) h = 1099511628211L * h + (if (o.ran) o.fingerprint else 0L)
    return String.format("%016x", h)
}

private fun why(o: Outcome): String =
    o.lines.filter { it.failed }.joinToString(" | ") { "${it.rule.title}: с тика ${it.firstTick} — ${it.text}" }.replace('\t', ' ').replace('\n', ' ')

private fun writeVerdicts(file: File, outcomes: List<Outcome>) {
    val sb = StringBuilder()
    sb.append("id\tpassed\tfailed\terror\ttorn\tdied\tcapHits\tpeakEnergy\tmaxSpeed\tforeignTicks\tforeignLongest\tselfTicks\tterrainTicks\tinvertedPeak\tinvertedLongest\tboneWorst\tshapeWorst\tposeWorst\tendSpeed\tawake\tsleepTick\tseconds\tfirstTick\twhy\tfingerprint\tparticles\tvariation\tbaseId\tjitterTicks\tjitterLongest\tlinks\tcells\tticks\tsymCalm\tsymNudge\tsymFrames\trefVerdict\trefScore\trefExcess\trefTorn\trefDied\trefFailed\trefSeconds\trefReason\tfineVerdict\tfineScore\tfineExcess\tfineTorn\tfineDied\tfineFailed\tfineSeconds\tfineReason" +
        "\ttornPull\ttornCrush\tpierced\tpressureDead\tcrushDead\tfoldDead\t" + KillCauses.COLUMNS.joinToString("\t") + "\tkillBoneBone\tbirthGap\tbirthExcess\n")
    for (o in outcomes) {
        sb.append(o.scenario.id).append('\t').append(if (o.passed) 1 else 0).append('\t')
            .append(o.failed.joinToString(",") { it.name }).append('\t').append(o.error.replace('\t', ' ').replace('\n', ' ')).append('\t')
        if (o.ran) sb.append(String.format(Locale.ROOT, "%d\t%d\t%d\t%.3f\t%.3f\t%d\t%d\t%d\t%d\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.5f\t%d\t%d\t%.2f",
            o.torn, o.died, o.capHits, o.peakEnergy, o.maxSpeed, o.foreignTicks, o.foreignLongest, o.selfTicks, o.terrainTicks,
            o.invertedPeak, o.invertedLongest, o.boneWorst, o.shapeWorst, o.poseWorst, o.endSpeed, o.awake, o.sleepTick, o.seconds))
        else sb.append("\t".repeat(17))
        // Для окна: тик первого нарушения (туда оно умеет перематывать) и что именно нарушено.
        sb.append('\t').append(o.lines.filter { it.failed && it.firstTick >= 0 }.minOfOrNull { it.firstTick } ?: -1).append('\t').append(why(o))
        sb.append('\t').append(if (o.ran) String.format("%016x", o.fingerprint) else "")
        sb.append('\t').append(o.particles).append('\t').append(o.scenario.variation.id).append('\t').append(o.scenario.baseId)
        sb.append('\t').append(o.jitterTicks).append('\t').append(o.jitterLongest).append('\t').append(o.linksTotal).append('\t').append(o.cellsTotal).append('\t').append(o.ticks)
        // Симметрия: спокойная ли сцена, расхождение с малым сдвигом и с каждой системой отсчёта, связей.
        val sym = o.symmetry
        sb.append('\t').append(if (sym == null) "" else if (sym.calm) "1" else "0")
        sb.append('\t').append(if (sym == null) "" else String.format(Locale.ROOT, "%.4f", sym.nudge?.score ?: 0.0))
        sb.append('\t').append(if (sym == null) "" else sym.checks.joinToString(",") { String.format(Locale.ROOT, "%s=%.4f", it.frame.id, it.score) })
        // Эталон: итог сверки, расхождение в связях и в долях допуска, счёт разрывов и гибели у эталона, его нарушенные правила, время.
        for (ref in listOf(o.reference?.same, o.reference?.fine)) {
            val dev = ref?.deviation
            sb.append('\t').append(ref?.verdict?.name ?: "")
            sb.append('\t').append(if (dev == null) "" else String.format(Locale.ROOT, "%.4f", dev.score))
            sb.append('\t').append(if (dev == null) "" else String.format(Locale.ROOT, "%.3f", dev.excess))
            sb.append('\t').append(dev?.tornOther?.toString() ?: "").append('\t').append(dev?.diedOther?.toString() ?: "")
            sb.append('\t').append(ref?.failedRules?.joinToString(",") { it.name } ?: "")
            sb.append('\t').append(if (ref == null) "" else String.format(Locale.ROOT, "%.2f", ref.seconds))
            sb.append('\t').append(ref?.reason?.replace('\t', ' ')?.replace('\n', ' ') ?: "")
        }
        // Чем разрушено: причины разрывов и гибели, заявки на гибель по парам.
        sb.append('\t').append(o.tornPull).append('\t').append(o.tornCrush).append('\t').append(o.pierced)
        sb.append('\t').append(o.pressureDead).append('\t').append(o.crushDead).append('\t').append(o.foldDead)
        for (k in o.kills) sb.append('\t').append(k)
        sb.append('\t').append(o.killBoneBone)
        sb.append('\t').append(if (o.ran) String.format(Locale.ROOT, "%.3f\t%.3f", o.birthGap, o.birthExcess) else "\t")
        sb.append('\n')
    }
    file.writeText(sb.toString())
}

private fun writeReport(file: File, outcomes: List<Outcome>, seconds: Double, repeat: Boolean, workers: Int) {
    val sb = StringBuilder()
    val failed = outcomes.filter { !it.passed }
    sb.append("# Стенды физики: отчёт прогона\n\n")
    sb.append(String.format(Locale.ROOT, "Сцен %d, прошло %d, не прошло %d. Подшагов %d. Время %.0f с. Сверка повтора: %s. Сверка с воркерами: %s.\n\n",
        outcomes.size, outcomes.size - failed.size, failed.size, SUBSTEPS, seconds, if (repeat) "да" else "нет", if (workers > 1) "$workers" else "нет"))
    if (STAND_CONFIG.isNotEmpty()) sb.append("Переключатели решателя: `$STAND_CONFIG` — это не мир игры, а проверка правки или самих стендов.\n\n")
    sb.append("Контрольная сумма прогона: `${checksum(outcomes)}` — свёртка отпечатков состояния всех сцен по всем тикам; на другом устройстве с теми же подшагами должна совпасть.\n\n")

    val variations = Variation.values().filter { v -> outcomes.any { it.scenario.variation == v } }
    sb.append("## По вариациям\n\n| Вариация | Что меняется | Сцен | Прошло | Не прошло |\n|---|---|---:|---:|---:|\n")
    for (v in variations) {
        val group = outcomes.filter { it.scenario.variation == v }
        sb.append("| ${v.title} | ${v.about} | ${group.size} | ${group.count { it.passed }} | ${group.count { !it.passed }} |\n")
    }

    sb.append("\n## По семействам и вариациям (прошло / всего)\n\n| Семейство |")
    for (v in variations) sb.append(" ${v.title} |")
    sb.append("\n|---|").append("---:|".repeat(variations.size)).append('\n')
    for ((family, group) in outcomes.groupBy { it.scenario.family }) {
        sb.append("| $family |")
        for (v in variations) {
            val part = group.filter { it.scenario.variation == v }
            sb.append(if (part.isEmpty()) " — |" else " ${part.count { it.passed }} / ${part.size} |")
        }
        sb.append('\n')
    }

    sb.append("\n## По правилам и вариациям (сколько сцен нарушило)\n\n| Правило |")
    for (v in variations) sb.append(" ${v.title} |")
    sb.append("\n|---|").append("---:|".repeat(variations.size)).append('\n')
    for (rule in Rule.values()) {
        if (outcomes.none { rule in it.failed }) continue
        sb.append("| ${rule.title} |")
        for (v in variations) sb.append(" ${outcomes.count { it.scenario.variation == v && rule in it.failed }} |")
        sb.append('\n')
    }
    val errors = outcomes.count { it.error.isNotEmpty() }
    if (errors > 0) sb.append("\nУпало или не собралось: $errors.\n")

    val vacant = outcomes.filter { Rule.PREMISE in it.failed }
    if (vacant.isNotEmpty()) {
        sb.append("\n## Прошли вхолостую\n\nНе случилось то, ради чего сцена поставлена (тела не встретились, росток не дорос до стены): остальные правила в такой сцене ничего не значат. Это вопрос к постановке, а не к физике.\n\n")
        for (o in vacant) sb.append("- `${o.scenario.id}` — ${o.lines.first { it.rule == Rule.PREMISE }.text}\n")
    }
    sb.append("\n## Не прошли\n\n")
    for ((family, group) in failed.groupBy { it.scenario.family }) {
        sb.append("### $family\n\n")
        for (o in group) {
            sb.append("- `${o.scenario.id}` — ${o.scenario.title}\n")
            if (o.error.isNotEmpty()) sb.append("  - ${o.error}\n")
            for (r in o.lines) if (r.failed) sb.append("  - ${r.rule.title}: с тика ${r.firstTick}, раз ${r.violations} — ${r.text}\n")
        }
        sb.append('\n')
    }
    file.writeText(sb.toString())
}
