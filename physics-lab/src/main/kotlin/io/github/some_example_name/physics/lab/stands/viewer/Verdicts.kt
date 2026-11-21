package io.github.some_example_name.physics.lab.stands.viewer

import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.lab.stands.Rule
import io.github.some_example_name.physics.lab.stands.Scenario
import io.github.some_example_name.physics.lab.stands.StandRun
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Итог сцены для списка: прошла ли, тик первого нарушения и что нарушено. */
internal class Verdict(val passed: Boolean, val firstTick: Int, val why: String,
                       /**
                        * Нарушения правил, которые судит только пакетный прогон (повтор, воркеры, симметрия,
                        * эталон), тем же текстом, что в [why]. Живой прогон их не снимает: он их не проверяет.
                        */
                       val batchWhy: String = "") {
    /**
     * Сцена прошла вхолостую: не случилось то, ради чего она поставлена (тела не встретились,
     * росток не дорос до стены). Это не ошибка физики, поэтому в списке у неё свой цвет.
     */
    val vacant: Boolean get() = !passed && why.contains(VACANT_MARK)

    companion object {
        const val VACANT_MARK = "сцена не вхолостую:"
        /** Правила, которые судит только пакетный прогон: на них нужен второй прогон сцены. */
        val BATCH_ONLY = arrayOf(Rule.REPEAT, Rule.WORKERS, Rule.SYMMETRY, Rule.REFERENCE)
    }
}

/**
 * ИТОГИ СЦЕН ДЛЯ СПИСКА. Берутся из файла пакетного прогона (gradlew :physics-lab:standsCheck),
 * а если его нет или его просят обновить — считаются здесь же в фоне: каждая сцена — свой мир,
 * друг другу и окну они не мешают. Фоновый счёт сверку повтора не делает (на неё нужен второй
 * прогон), это остаётся пакетному прогону.
 *
 * ИТОГ ИЗ ФАЙЛА МОЖЕТ БЫТЬ СТАРЫМ. Файл пишет прогон без -Preport, а код после него меняется:
 * 17.11.2026 список два дня показывал красным сцену, которую починка мышц уже сделала зелёной.
 * Поэтому (1) живой прогон — окна или фоновый — заменяет итог из файла по всем правилам, которые
 * судит сам (record); (2) если файл старше собранного кода, шапка списка говорит об этом ([stale]).
 */
internal class Verdicts(private val scenes: List<Scenario>) {
    private val byId = ConcurrentHashMap<String, Verdict>()
    private var pool: ExecutorService? = null
    private val done = AtomicInteger(0)
    private val changes = AtomicInteger(0)
    @Volatile private var total = 0
    @Volatile var source = "итогов нет: запустите gradlew :physics-lab:standsCheck или нажмите B"
        private set
    /** Файл итогов старше кода, который сейчас считает сцены: непросмотренные сцены помечены по старому коду. */
    @Volatile var stale = false
        private set

    operator fun get(id: String): Verdict? = byId[id]

    /** Растёт с каждым новым итогом: по нему окно решает, пора ли пересчитать сводки списка. */
    val version: Int get() = changes.get()

    /** Сколько сцен фоновый счёт уже проверил и сколько всего; total = 0 — счёт не идёт. */
    val progress: Int get() = done.get()
    val goal: Int get() = total
    val running: Boolean get() = total > 0 && done.get() < total

    fun put(id: String, v: Verdict) { byId[id] = v; changes.incrementAndGet() }

    /**
     * Итог живого прогона (окна или фонового счёта). Правила, которые такой прогон судит сам,
     * пишутся заново, даже если файл говорил «не прошла»: тот итог мог быть получен на старом коде.
     * Нарушения повтора, воркеров, симметрии и эталона из файла остаются — их снимает только новый
     * пакетный прогон.
     */
    fun record(id: String, passed: Boolean, firstTick: Int, why: String) {
        val before = byId[id]
        val kept = before?.batchWhy.orEmpty()
        val first = if (!passed) firstTick else if (before != null && kept.isNotEmpty()) before.firstTick else -1
        put(id, Verdict(passed && kept.isEmpty(), first, listOf(why, kept).filter { it.isNotEmpty() }.joinToString(" | "), kept))
    }

    /** Читает verdicts.tsv пакетного прогона. Возвращает, сколько сцен из каталога в нём нашлось. */
    fun load(file: File): Int {
        if (!file.isFile) return 0
        val known = scenes.map { it.id }.toHashSet()
        var found = 0
        val lines = file.readLines()
        if (lines.isEmpty()) return 0
        val head = lines[0].split('\t')
        val idCol = head.indexOf("id"); val passedCol = head.indexOf("passed"); val errorCol = head.indexOf("error")
        val firstCol = head.indexOf("firstTick"); val whyCol = head.indexOf("why")
        if (idCol < 0 || passedCol < 0) return 0
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            val id = f.getOrNull(idCol) ?: continue
            if (id !in known) continue
            val error = f.getOrNull(errorCol).orEmpty()
            val rules = f.getOrNull(whyCol).orEmpty()
            val why = listOf(rules, error).filter { it.isNotEmpty() }.joinToString(" | ")
            // Строка причин — правила через « | », каждое начинается со своего названия (StandsCheck.why).
            val batchWhy = rules.split(" | ").filter { part -> Verdict.BATCH_ONLY.any { part.startsWith(it.title + ":") } }.joinToString(" | ")
            byId[id] = Verdict(f.getOrNull(passedCol) == "1", f.getOrNull(firstCol)?.toIntOrNull() ?: -1, why, batchWhy)
            found++
        }
        if (found > 0) {
            val time = java.text.SimpleDateFormat("dd.MM HH:mm")
            val built = codeBuiltAt()
            stale = built > file.lastModified()
            source = if (stale) "итоги старее кода (${time.format(file.lastModified())}) · B — пересчитать"
            else "итоги из пакетного прогона (${time.format(file.lastModified())})" + if (found < scenes.size) ", нет итога у ${scenes.size - found}" else ""
        }
        changes.incrementAndGet()
        return found
    }

    /**
     * Когда собран код, который считает сцены: самый свежий файл среди классов физики (её jar) и
     * классов стендов — каталога, мира сцены и монитора правил. Классы самого окна и замеров не в
     * счёт: от них итог сцены не зависит. Ложная тревога (пересобрали без смены поведения) стоит
     * одну строку в шапке; пропущенная — красные пометки, которым нельзя верить.
     */
    private fun codeBuiltAt(): Long {
        var newest = 0L
        for (c in arrayOf(PhysicsWorkers::class.java, StandRun::class.java)) {
            val at = try { File(c.protectionDomain.codeSource.location.toURI()) } catch (t: Exception) { continue }
            if (at.isFile) { newest = maxOf(newest, at.lastModified()); continue }
            val stands = File(at, StandRun::class.java.packageName.replace('.', '/'))
            for (f in stands.walkTopDown().onEnter { it.name != "viewer" && it.name != "perf" }) if (f.isFile) newest = maxOf(newest, f.lastModified())
        }
        return newest
    }

    /** Проверить сцены [list] в фоне в этом порядке: что ближе к выбранной, получает итог первым. */
    fun runAll(list: List<Scenario>, what: String) {
        stop()
        done.set(0); total = list.size
        // Весь каталог пересчитан здесь — старый файл больше ни одной пометки не держит.
        if (list.size >= scenes.size) stale = false
        source = "итоги считаются в этом окне: $what" + if (stale) "; остальные — из старого прогона" else ""
        val jobs = maxOf(1, Runtime.getRuntime().availableProcessors() / 2 - 1)
        val p = Executors.newFixedThreadPool(jobs) { r -> Thread(r, "stands-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        pool = p
        for (s in list) {
            p.execute {
                try {
                    val run = StandRun(s).runAll()
                    val broken = run.monitor.results.values.filter { it.failed }
                    val why = (broken.map { "${it.rule.title}: с тика ${it.firstTick} — ${it.text}" } + listOfNotNull(run.crash?.let { "решатель упал: $it" })).joinToString(" | ")
                    record(s.id, run.passed, broken.filter { it.firstTick >= 0 }.minOfOrNull { it.firstTick } ?: -1, why)
                } catch (t: Throwable) {
                    put(s.id, Verdict(false, -1, "сцена не собралась: $t"))
                } finally { done.incrementAndGet() }
            }
        }
        p.shutdown()
    }

    fun stop() {
        pool?.shutdownNow()
        pool = null
        total = 0
    }
}
