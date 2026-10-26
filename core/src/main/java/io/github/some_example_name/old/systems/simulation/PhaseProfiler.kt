package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.core.PROFILE_LOG
import io.github.some_example_name.old.core.PROFILE_WINDOW_TICKS
import java.io.File
import java.util.Locale

/**
 * Номера фаз тика. Именно const val Int, а не enum: профайлер индексирует массивы
 * напрямую, без ordinal() и без values(), и на горячем пути не появляется ни объекта,
 * ни лукапа по строке.
 *
 * Прошлая версия профайлера в SimulationSystem держала LinkedHashMap<String, ArrayDeque<Double>>:
 * на каждую фазу каждого тика это хэш-лукап по строке плюс боксинг Double в объект.
 * 12 фаз * 145 тиков = ~1700 боксов в секунду прямо в цикле симуляции — немного, но
 * измерительный инструмент не должен сам мусорить в том, что измеряет.
 */
object Phase {
    const val LINKS = 0
    const val NEURAL = 1
    const val COLLIDE = 2
    const val CELLS = 3
    const val PHEROMONE = 4
    const val ARRANGE = 5
    const val WORLD_CMD = 6
    const val ORGANS = 7
    const val USER_CMD = 8
    const val LAST_WORLD_CMD = 9
    const val REBUILD = 10
    const val RENDER = 11

    /** Полный тик. Считается отдельно, а не суммой фаз: так видно неучтённое время. */
    const val TICK = 12

    const val COUNT = 13

    val CSV_NAMES = arrayOf(
        "links", "neural", "collide", "cells", "pherom", "arrange",
        "wcmd", "organs", "ucmd", "lastcmd", "rebuild", "render", "tick"
    )

    val SCREEN_NAMES = arrayOf(
        "1. Links Physics",
        "2. Neural Links",
        "3. Particle Collision",
        "4. Cell System",
        "5. Pheromones",
        "6. Physics World",
        "7. World Commands",
        "8. Organs",
        "9. User Commands",
        "10. Last World Cmds",
        "10.5 Grid Rebuild",
        "11. Render Buffer",
        "TICK TOTAL"
    )
}

/**
 * Снимок состояния мира на момент закрытия окна усреднения.
 *
 * Переиспользуемый объект: заполняется раз в PROFILE_WINDOW_TICKS тиков, аллокаций не даёт.
 * Нужен, чтобы каждая строка лога отвечала не только "сколько миллисекунд", но и
 * "при каком объёме мира" — иначе по логу нельзя построить кривую "время на единицу работы
 * от плотности", а именно она отвечает на вопрос, упёрлись мы в кэш или нет.
 */
class WorldStats {
    var tick = 0L
    var ups = 0

    var cells = 0
    var particles = 0
    var links = 0

    /** Сколько связей реально обошла фаза 1 (сумма длин odd/even списков слотов). */
    var linksProcessed = 0

    var gridSize = 0
    var occupiedCells = 0
    var maxParticlesInCell = 0

    /**
     * Суммы за всё окно, профайлер сам поделит на число тиков.
     *
     * Три величины вместо одной, потому что внутри repulse три пути с разной ценой:
     *  - кандидат, отброшенный по расстоянию (дёшево),
     *  - пересечение без вызова onContact,
     *  - пересечение с onContact (мегаморфный вызов, дорого).
     * Имея объём каждого пути на многих замерах с разным соотношением, время фазы
     * раскладывается на три слагаемых; по одному числу пар это неразличимо.
     */
    var pairCandidatesTotal = 0L
    var collisionsTotal = 0L
    var linkedSkipsTotal = 0L
    var contactsTotal = 0L

    /** То же для фазы связей: разрывы и пересчёты углов. */
    var linkBreaksTotal = 0L
    var linkAnglesTotal = 0L

    /**
     * Занятость воркеров по фазам за окно: сколько суммарно работали и сколько длилась
     * стадия. КПД = busy / (workers * wall); всё, чего не хватает до единицы, — простой
     * на барьере из-за неравномерной нагрузки.
     */
    val stageBusyNanos = LongArray(Phase.COUNT)
    val stageWallNanos = LongArray(Phase.COUNT)

    var workers = 1
}

/**
 * Профайлер фаз тика с выводом в CSV.
 *
 * Зачем CSV, а не только таблица на экране: экран показывает моментальное среднее, а нужен
 * РЯД — как меняются времена фаз по мере роста числа клеток. Только по ряду видно, растёт
 * ли время на единицу работы (значит, упёрлись в память) или строго пропорционально объёму
 * (значит, упёрлись в вычисления). Одна строка на окно, окно ~секунда.
 *
 * Про Locale.ROOT: в русской локали String.format печатает дробную часть через запятую,
 * и CSV разъезжается по столбцам. Поэтому форматирование везде явно через ROOT.
 *
 * Про max: время параллельной стадии определяется самым медленным воркером, а сама стадия
 * измеряется по стенным часам целиком, поэтому среднее прячет и всплески от GC, и случаи,
 * когда один перегруженный чанк держал всю стадию. max по окну это показывает.
 */
class PhaseProfiler(private val windowTicks: Int = PROFILE_WINDOW_TICKS) {

    private val sumNanos = LongArray(Phase.COUNT)
    private val maxNanos = LongArray(Phase.COUNT)
    private var ticks = 0

    /** Текст для оверлея. Читается и пишется только потоком симуляции. */
    var text: String = ""
        private set

    private val builder = StringBuilder(1024)

    private var logFile: File? = null
    private var fileDisabled = false
    private var headerWritten = false

    /**
     * Замер одной фазы. inline, поэтому вызова лямбды в байткоде не остаётся —
     * только два nanoTime вокруг тела.
     */
    inline fun measure(phase: Int, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        record(phase, System.nanoTime() - start)
    }

    fun record(phase: Int, nanos: Long) {
        sumNanos[phase] += nanos
        if (nanos > maxNanos[phase]) maxNanos[phase] = nanos
    }

    /** Возвращает true, когда окно закрылось и пора звать [flush]. */
    fun endTick(): Boolean {
        ticks++
        return ticks >= windowTicks
    }

    /**
     * Закрывает окно: строит текст для экрана, пишет строку в лог и обнуляет накопители.
     */
    fun flush(stats: WorldStats) {
        val closedTicks = ticks
        if (closedTicks == 0) return

        // Дальше по коду это уже величины "за тик", отсюда и имена столбцов.
        stats.pairCandidatesTotal /= closedTicks
        stats.collisionsTotal /= closedTicks
        stats.linkedSkipsTotal /= closedTicks
        stats.contactsTotal /= closedTicks
        stats.linkBreaksTotal /= closedTicks
        stats.linkAnglesTotal /= closedTicks

        buildScreenText(stats, closedTicks)
        if (PROFILE_LOG) writeCsv(stats, closedTicks)

        sumNanos.fill(0)
        maxNanos.fill(0)
        ticks = 0
    }

    // ===================================================================================
    // ПРОИЗВОДНЫЕ МЕТРИКИ
    //
    // Это то, ради чего всё затевалось. Миллисекунды фазы сами по себе ничего не говорят:
    // они растут и когда работы стало больше, и когда каждая единица работы подорожала.
    // Разделив на количество работы, получаем величину, которая ДОЛЖНА быть константой,
    // если алгоритм масштабируется линейно. Её рост с плотностью — это и есть подпись
    // упирания в память.
    // ===================================================================================

    /**
     * Тактов процессора на один вызов repulse.
     *
     * Умножаем на число воркеров, потому что фаза измерена по стенным часам, а работали
     * в ней workers ядер: нас интересует стоимость вызова на одном ядре, а не то,
     * во сколько потоков её размазали.
     */
    private fun nanosPerPair(stats: WorldStats, windowTicks: Int): Double {
        val pairs = stats.pairCandidatesTotal
        if (pairs <= 0) return 0.0
        val perTickNanos = sumNanos[Phase.COLLIDE].toDouble() / windowTicks
        return perTickNanos * stats.workers / pairs
    }

    /** То же для фазы связей: наносекунд ядра на одну обработанную связь. */
    private fun nanosPerLink(stats: WorldStats, windowTicks: Int): Double {
        val links = stats.linksProcessed
        if (links <= 0) return 0.0
        val perTickNanos = sumNanos[Phase.LINKS].toDouble() / windowTicks
        return perTickNanos * stats.workers / links
    }

    /**
     * Наносекунд ядра на одно РЕАЛЬНОЕ пересечение.
     *
     * Вместе с ns_pair даёт разложение: если ns_pair падает при росте hitRate, а
     * ns_collision держится — значит цену задаёт дорогой путь, и оптимизировать надо его,
     * а не широкую фазу.
     */
    private fun nanosPerCollision(stats: WorldStats, windowTicks: Int): Double {
        val collisions = stats.collisionsTotal
        if (collisions <= 0) return 0.0
        val perTickNanos = sumNanos[Phase.COLLIDE].toDouble() / windowTicks
        return perTickNanos * stats.workers / collisions
    }

    /**
     * КПД распараллеливания фазы: доля времени, которую воркеры реально работали,
     * а не стояли на барьере. 1.0 — идеальная балансировка.
     *
     * Без этого числа ns_pair и ns_link смешивают цену вызова с простоем: при КПД 0.6
     * реальная цена вызова на 40% ниже той, что они показывают.
     */
    private fun utilization(stats: WorldStats, phase: Int): Double {
        val wall = stats.stageWallNanos[phase]
        if (wall <= 0 || stats.workers <= 0) return 0.0
        return stats.stageBusyNanos[phase].toDouble() / (wall.toDouble() * stats.workers)
    }

    private fun ratio(part: Long, whole: Long): Double =
        if (whole <= 0) 0.0 else part.toDouble() / whole

    /** Микросекунды тика на 1000 частиц: интегральная метрика "цена единицы мира". */
    private fun microsPer1kParticles(stats: WorldStats, windowTicks: Int): Double {
        if (stats.particles <= 0) return 0.0
        val perTickMicros = sumNanos[Phase.TICK].toDouble() / windowTicks / 1000.0
        return perTickMicros * 1000.0 / stats.particles
    }

    // ===================================================================================
    // ВЫВОД
    // ===================================================================================

    private fun writeCsv(stats: WorldStats, windowTicks: Int) {
        if (!headerWritten) {
            headerWritten = true
            emit(buildHeader())
        }

        val sb = builder
        sb.setLength(0)

        sb.append(stats.tick).append(',')
        sb.append(stats.ups).append(',')
        sb.append(stats.cells).append(',')
        sb.append(stats.particles).append(',')
        sb.append(stats.links).append(',')
        sb.append(stats.linksProcessed).append(',')
        sb.append(stats.occupiedCells).append(',')
        sb.append(stats.gridSize).append(',')
        appendFixed(sb, occupancy(stats)).append(',')
        sb.append(stats.maxParticlesInCell).append(',')
        sb.append(stats.pairCandidatesTotal).append(',')
        sb.append(stats.collisionsTotal).append(',')
        sb.append(stats.linkedSkipsTotal).append(',')
        sb.append(stats.contactsTotal).append(',')
        sb.append(stats.linkBreaksTotal).append(',')
        sb.append(stats.linkAnglesTotal).append(',')
        sb.append(stats.workers).append(',')

        for (phase in 0 until Phase.COUNT) {
            appendFixed(sb, sumNanos[phase] / 1_000_000.0 / windowTicks).append(',')
        }
        for (phase in 0 until Phase.COUNT) {
            appendFixed(sb, maxNanos[phase] / 1_000_000.0).append(',')
        }

        appendFixed(sb, nanosPerPair(stats, windowTicks)).append(',')
        appendFixed(sb, nanosPerCollision(stats, windowTicks)).append(',')
        appendFixed(sb, nanosPerLink(stats, windowTicks)).append(',')
        appendFixed(sb, microsPer1kParticles(stats, windowTicks)).append(',')

        appendFixed(sb, ratio(stats.collisionsTotal, stats.pairCandidatesTotal)).append(',')
        appendFixed(sb, ratio(stats.linkedSkipsTotal, stats.collisionsTotal)).append(',')
        appendFixed(sb, ratio(stats.contactsTotal, stats.collisionsTotal)).append(',')

        appendFixed(sb, utilization(stats, Phase.LINKS)).append(',')
        appendFixed(sb, utilization(stats, Phase.COLLIDE)).append(',')
        appendFixed(sb, utilization(stats, Phase.CELLS)).append(',')
        appendFixed(sb, utilization(stats, Phase.ARRANGE))

        emit(sb.toString())
    }

    private fun buildHeader(): String {
        val sb = builder
        sb.setLength(0)
        sb.append("tick,ups,cells,particles,links,linksProc,occCells,gridSize,")
        sb.append("perOccCell,maxPerCell,pairsPerTick,collisions,linkedSkips,contacts,")
        sb.append("linkBreaks,linkAngles,workers")
        for (name in Phase.CSV_NAMES) sb.append(",avg_").append(name)
        for (name in Phase.CSV_NAMES) sb.append(",max_").append(name)
        sb.append(",ns_pair,ns_collision,ns_link,us_per_1k_particles")
        sb.append(",hitRate,linkedRate,contactRate")
        sb.append(",util_links,util_collide,util_cells,util_arrange")
        return sb.toString()
    }

    /**
     * Пишем и в консоль, и в файл. Консоль удобна, пока смотришь вживую; файл нужен потому,
     * что сбор кривой по плотности идёт минутами и в консоли строки успевают уехать.
     * appendText открывает и закрывает файл на каждую строку — раз в секунду это ничто,
     * зато файл всегда дописан на диск, даже если приложение убить.
     */
    private fun emit(line: String) {
        println(line)

        if (fileDisabled) return
        try {
            var file = logFile
            if (file == null) {
                file = File("sim_profile.csv")
                logFile = file
                println("PROFILE: пишу CSV в ${file.absolutePath}")
            }
            file.appendText(line + "\n")
        } catch (e: Throwable) {
            fileDisabled = true
            println("PROFILE: файл недоступен (${e.message}), остаётся только консоль")
        }
    }

    private fun buildScreenText(stats: WorldStats, windowTicks: Int) {
        val sb = builder
        sb.setLength(0)

        sb.append("=== PERFORMANCE (ms) ===\n")

        for (phase in 0 until Phase.COUNT) {
            if (phase == Phase.TICK) sb.append("-----------------------------\n")
            sb.append(
                String.format(
                    Locale.ROOT,
                    "%-21s %6.2f %6.2f\n",
                    Phase.SCREEN_NAMES[phase],
                    sumNanos[phase] / 1_000_000.0 / windowTicks,
                    maxNanos[phase] / 1_000_000.0
                )
            )
        }

        sb.append("-----------------------------\n")
        sb.append(
            String.format(
                Locale.ROOT,
                "ns pair %.1f coll %.1f link %.1f\n",
                nanosPerPair(stats, windowTicks),
                nanosPerCollision(stats, windowTicks),
                nanosPerLink(stats, windowTicks)
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "hit %.3f linked %.3f contact %.3f\n",
                ratio(stats.collisionsTotal, stats.pairCandidatesTotal),
                ratio(stats.linkedSkipsTotal, stats.collisionsTotal),
                ratio(stats.contactsTotal, stats.collisionsTotal)
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "util lnk %.2f col %.2f cel %.2f arr %.2f\n",
                utilization(stats, Phase.LINKS),
                utilization(stats, Phase.COLLIDE),
                utilization(stats, Phase.CELLS),
                utilization(stats, Phase.ARRANGE)
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "pairs/tick %d  occ %d/%d\n",
                stats.pairCandidatesTotal,
                stats.occupiedCells,
                stats.gridSize
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "per cell %.2f  max %d  workers %d\n",
                occupancy(stats),
                stats.maxParticlesInCell,
                stats.workers
            )
        )

        text = sb.toString()
    }

    /** Среднее число частиц в НЕПУСТОЙ клетке: честнее, чем деление на всю сетку. */
    private fun occupancy(stats: WorldStats): Double =
        if (stats.occupiedCells <= 0) 0.0
        else stats.particles.toDouble() / stats.occupiedCells

    private fun appendFixed(sb: StringBuilder, value: Double): StringBuilder =
        sb.append(String.format(Locale.ROOT, "%.3f", value))
}
