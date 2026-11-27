package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.commands.aliveIn
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.PROFILE_INTERPOLATION
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.features.settings.GlobalSettings
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.systems.simulation.ParallelExecutor
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.simulation.ThreadManager
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OpticalLinks
import io.github.some_example_name.render.pack.DebugAreaIndices
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import io.github.some_example_name.old.physics.bridge.GAME_PER_PHYSICS
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Длины массивов — до цикла (CLAUDE.md, «JIT»; physics/.../solver/ArrayHeaders.kt): после неё
 * компилятор знает, что массивы не null, и выносит чтение их длин из цикла. Своя копия: та
 * внутренняя в модуле физики.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun lengthsBeforeLoop(lengths: Int) {
    if (lengths < 0) negativeArrayLength()
}

/** Недостижимо; отдельным методом, чтобы не раздувать проход. */
private fun negativeArrayLength(): Nothing = throw IllegalStateException("отрицательная длина массива")

/**
 * Снимок мира для рендера: поток симуляции раз в тик собирает его в [TripleBuffer], поток
 * рендера берёт самый свежий.
 *
 * Снимок — сразу раскладка GPU: клетки int-массивом в раскладке CellInstanceBuffer,
 * оверлей и феромоны — готовыми буферами инстансов. Раньше снимок лежал полями в шести
 * массивах, и GL-поток КАЖДЫЙ КАДР перекладывал их в буфер инстансов (0.64 мс на 100k
 * частиц). Теперь он копирует массив одним memcpy (0.21 мс на 100k) и только когда
 * пришёл новый снимок. Отдавать рендеру свои массивы без копии стало можно благодаря
 * тройному буферу: слот, который читает рендер, симуляция не трогает.
 *
 * Кроме поз снимок несёт позу каждой частицы в прошлом снимке — между ними шейдеры
 * рисуют движение плавно (CellInstanceBuffer: «Интерполяция между тиками»). Прошлые позы
 * живут в [history] и [nonCellHistory].
 *
 * TODO(XPBD): снимок содержит конечную позу, которую вернул мир физики. На сильном
 * замедлении линейная интерполяция между тиками теряет детали движения внутри шага:
 * клетка поедет по хорде, а не по дуге, и столкновение «проскочит» между двумя снимками.
 * Тогда снимок надо снимать и с подшагов решателя — прошлой позой каждого снимка будет
 * поза прошлого подшага, а рендер пройдёт их по очереди. См. TODO.md, раздел 11.
 */
class RenderBufferManager(
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val cellList: List<Cell>,
    val specialEntity: SpecialEntity,
    initialCellCapacity: Int = 50_000,
    initialOverlayCapacity: Int = 4_096,
    genomes: List<io.github.some_example_name.old.systems.genomics.genome.Genome> = emptyList()
) {
    private val materialCoordinates by lazy(LazyThreadSafetyMode.NONE){GenomeMaterialCoordinates(genomes)}

    /**
     * Режим рендера этого мира. Свой у каждого мира: у симуляции его выбирают списком,
     * а мир в фоне меню всегда рисуется реалистично и чужой выбор не видит.
     *
     * Живёт здесь, а не в RenderSystem, потому что читает его поток симуляции: по нему
     * [updateBuffer] решает, красить ли клетки отладочными цветами и собирать ли оверлей
     * (нейросвязи, стрелки, лучи), — в остальных режимах это работа впустую. Пишет же его
     * GL-поток (список в экране
     * симуляции), отсюда @Volatile: без него поток симуляции имел полное право не увидеть
     * переключение никогда. Переключают руками раз в минуты, цена volatile-чтения ничто.
     */
    @Volatile
    var mode: RenderMode = RenderMode.fromPreference(GlobalSettings.RENDER_MODE)

    /**
     * Фильтр отладочного режима (энергия, нейросигнал). Как и [mode]: пишет GL-поток (список
     * на экране симуляции), читает поток симуляции раз за [updateBuffer] — под него
     * пакуется байт фильтра каждой клетки, и вместе с клетками он уходит в снимок
     * (RenderSnapshot.filter), чтобы кадр не нарисовал байты одного фильтра шкалой
     * другого. Видимость обеспечивает ; переключают руками, цена ничто.
     */
    @Volatile
    var debugFilter: DebugFilter = DebugFilter.ORIGINAL

    /**
     * Граница тел мира физики поверх клеток в отладочном режиме (галочка «Граница»): рёбра
     * контура, включая края дыр и голые связи. Пишет GL-поток, читает поток симуляции раз за
     * [updateBuffer] — как [debugFilter].
     */
    @Volatile
    var showBoundary = false

    /** Все связи тел мира физики тонкими линиями (галочка «Связи»); как [showBoundary]. */
    @Volatile
    var showLinks = false

    @Volatile
    var fillAreas = false

    /**
     * Галочка «Цвет из генома»: в отладке клетка своего цвета (particleEntity.color — его
     * задаёт геном; хамелеон и ошибочный синапс перекрашивают его на ходу), а не
     * defaultColor типа. Рисунок, стрелки и фильтры остаются. Пишет GL-поток, читает поток
     * симуляции раз за [updateBuffer] — как [debugFilter]. По умолчанию включена: в отладке
     * игрок хочет видеть организм в цветах своего генома. На мир и хэши не влияет — только рисунок.
     */
    @Volatile
    var genomeColors = true

    /**
     * Галочка «Нейросвязи»: рисовать ли в отладке нейросвязи с бегущими импульсами. По
     * умолчанию включена; снимают, когда связи закрывают сами клетки. Как и [showLinks],
     * пишет GL-поток, читает поток симуляции раз за [updateBuffer]. Выключенная экономит
     * проход по нейросвязям в оверлей. На мир и хэши не влияет.
     */
    @Volatile
    var showNeuralLinks = true

    /** [genomeColors] текущего [updateBuffer]; читается один раз, как и [debugFrame]. */
    private var frameGenomeColors = false
    private val areaCache = DebugAreaCache()
    private var areaFrame = false

    /** Отметки игрока (средняя кнопка): позиции отмеченных клеток — в конце тика. null — фон меню. */
    var playerMarks: io.github.some_example_name.old.features.simulation.PlayerMarks? = null

    /**
     * Клетки, оверлей, феромоны и строка состояния — одним снимком. Раньше у них были
     * отдельные двойные буферы со своими индексами, и правило «клетки и оверлей
     * переключаются вместе» держалось на дисциплине. Теперь согласованность бесплатна:
     * читатель получает весь снимок целиком или не получает ничего.
     */
    private val snapshots = TripleBuffer(
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity),
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity),
        RenderSnapshot(initialCellCapacity, initialOverlayCapacity)
    )

    /**
     * Поток рендера: взять самый свежий снимок. true — он новый с прошлого вызова (буфер
     * клеток надо перезалить), false — рисовать прежний. Звать один раз за кадр, до
     * [currentSnapshot].
     */
    fun acquireSnapshot(): Boolean = snapshots.acquire()

    /** Поток рендера: снимок, взятый последним [acquireSnapshot]. Симуляция его не трогает. */
    fun currentSnapshot(): RenderSnapshot = snapshots.front()

    // ==================== История для интерполяции ====================

    /**
     * Поза каждой клетки в последнем снимке — по индексу её частицы, [HISTORY_STRIDE] int:
     *   [номер снимка, ключ жильца, x, y, dir, prevX, prevY, —]
     * float лежат битами (floatToRawIntBits — на JVM и ART это бесплатно), dir — уже
     * упакованное направление (CellInstanceBuffer.dir): в следующем снимке оно станет
     * прошлым как есть, без второй упаковки. prevX, prevY — прошлая позиция, с которой
     * клетка ушла в этом снимке: по ней едут концы нейросвязей и подсветка. Только для
     * картинки: в состояние мира не входит, в хэш и сохранение не попадает, на симуляцию
     * не влияет.
     *
     * Поза годится в прошлую, только если записана ровно в прошлом снимке и тем же
     * жильцом — поколением клетки: слот умершей клетки занимает новая, и без ключа она
     * «приехала» бы с места погибшей.
     *
     * РАСКЛАДКА — ПО ЗАМЕРУ
     * ---------------------
     * Восемь полей в одном массиве = 32 байта = половина кэш-линии на частицу. Первый
     * вариант держал позу и метку в двух массивах и для не-клеток шёл по индексу частицы:
     * по профилю JFR треть времени снимка стояла на чтении метки — два случайных промаха
     * на каждую субстанцию. Теперь клетки читают историю потоком в порядке арен, а у
     * не-клеток своя история [nonCellHistory].
     *
     * Объём: 32 байта × 100k частиц = 3.2 МБ — больше L2 на любом железе, но проход
     * последовательный, и подтягивает его префетчер.
     */
    private var history = IntArray(0)

    /**
     * Поза не-клеток (субстанций) — [NON_CELL_STRIDE] int: [номер снимка, ключ, x, y, прошлые x, y], по
     * позиции в particleEntity.nonCellList, а не по индексу частицы. Угла у не-клетки нет,
     * и 24 байта вместо 32. Список обходится по порядку
     * позиций, и история читается потоком; по индексу частицы это был бы случайный адрес
     * на каждую субстанцию.
     *
     * Ключ жильца — индекс частицы. Удаление переставляет в освободившуюся позицию
     * последнюю частицу списка: у неё ключ не совпадёт, и один снимок она простоит без
     * интерполяции — один кадр без плавности у одной субстанции, а не промах на каждую.
     * Переиспользованный индекс частицы ключ не отличит: новая субстанция на месте старой
     * один тик проскользит, если они ближе CellInstanceBuffer.MOTION_RANGE.
     */
    private var nonCellHistory = IntArray(0)

    /**
     * Инстансы клеток собираемого снимка — массив его слота (RenderSnapshot.instances),
     * на время [updateBuffer].
     */
    private var instances = IntArray(0)
    // Только полигональный эксперимент; не состояние мира. 12 B/клетку, без аллокаций за тик.
    private var renderOfCell=IntArray(0)
    private var renderCellStamp=IntArray(0)
    private var cellKeys = LongArray(0)
    private var polygonFrame=false
    private val polygonEpoch=Any()
    private var opticalGroups = IntArray(0)
    private var opticalColors = IntArray(0)
    private var opticalBirthTicks = IntArray(0)
    private var opticalCullRanges: io.github.some_example_name.render.pack.OpticalCullRanges? = null
    /** Террейн в микроскопе — один кусок ткани: его связи в граф изображения (TerrainOpticalLinks). */
    private val terrainLinks = TerrainOpticalLinks(particleEntity)

    /**
     * Номер текущего снимка. С единицы: метка свежевыделенного слота — ноль, и она не
     * должна совпасть с «прошлым снимком» ни при каком значении.
     */
    private var serial = 1

    /** Тик прошлого снимка: разрыв в нумерации — рестарт или загрузка мира, см. [startSnapshot]. */
    private var lastTick = Int.MIN_VALUE

    /**
     * Сглаженный промежуток между снимками, нс. По нему рендер решает, какую долю тика
     * уже прожил снимок (RenderSystem.interpolationLag).
     *
     * Измеряется, а не берётся из targetUPS: симуляция, которая не успевает в цель (40 UPS
     * при цели 60), публикует реже, и по номиналу каждый снимок доигрывался бы раньше
     * времени, а потом кадры стояли бы до следующего. Каждое измерение зажато в
     * [половину, удвоение] текущего значения: пауза или подвисание дают один огромный
     * промежуток, и без зажима после каждой паузы движение на несколько тиков замедлялось
     * бы в разы. При смене заданного темпа первый снимок сразу получает новый период.
     */
    private var tickNanos = 0L
    private var lastPublishNanos = 0L
    private var lastTarget = 0

    // ==================== Отладочный режим ====================

    /**
     * Собирается ли в текущем [updateBuffer] отладочный кадр. Режим читается ОДИН раз за
     * вызов: переключи его GL-поток посреди сборки — и половина клеток получила бы
     * отладочные цвета, а половина обычные.
     */
    private var debugFrame = false

    /** Фильтр текущего [updateBuffer]; читается один раз, как и [debugFrame]. */
    private var frameFilter = DebugFilter.ORIGINAL

    /**
     * Фигуры оверлея вместе с концами в прошлом тике — по накопителю на воркер: куски клеток на
     * воркерах пишут фигуры каждый в свой, склейка в буфер снимка — по порядку кусков (порядок
     * записи — порядок отрисовки). [overlaySink] — накопитель воркера 0, он же для всего, что
     * пишется в одном потоке: кольца феромонов, связи, контур, нейросвязи.
     */
    private var sinks = arrayOf(OverlayMotionSink())
    private val overlaySink: OverlayMotionSink get() = sinks[0]

    /** Накопленное в [overlaySink] — в буфер снимка; накопитель пуст. */
    private fun flushOverlay(overlay: OverlayInstanceBuffer) {
        val sink = overlaySink
        overlay.putAll(sink.ints, 0, sink.count)
        sink.reset()
    }

    /** Рёбра контура частицами игры, пары подряд (WorldPhysics.boundaryParticles); переиспользуется. */
    private val boundaryPairs = it.unimi.dsi.fastutil.ints.IntArrayList()

    /** Цвета, стрелки и лучи отладочного режима — общие с редактором генома. */
    private val palette = DebugCellPalette(cellList)

    /**
     * Клетки организмов — по аренам, то есть подряд.
     *
     * ЗАЧЕМ ИМЕННО ТАК
     * ----------------
     * Прежний проход шёл по particleEntity.aliveList (порядок создания, перемешанный
     * swap-with-last при каждой смерти) и на каждой клетке лез в CellEntity по
     * holderEntityIndex. То есть на частицу приходилось по два случайных прыжка: один
     * за её собственными полями, другой за полями её клетки.
     *
     * Обход по арене снимает оба. Клетки организма лежат подряд, частицы — подряд и
     * параллельно им, так что particleIndex получается арифметикой из слота, без чтения
     * particleIndexes. Поля CellEntity (angleCos/Sin, energy, cellType, а в отладке ещё
     * neuronImpulseOutput) читаются в том же слотовом порядке, то есть потоком. Так же,
     * по индексу частицы, — и [history].
     *
     * Ветки isCell внутри цикла тоже больше нет: здесь заведомо только клетки.
     */
    private fun writeOrganismCells(startIndex: Int, overlay: OverlayInstanceBuffer): Int {
        val organs = cellEntity.organEntity.aliveList
        val executor = threads?.executor
        val chunks = if (executor != null && executor.workerCount > 1 && !PROFILE_INTERPOLATION) planChunks() else 0
        if (executor == null || chunks < 2) {
            var writeIndex = startIndex
            for (organSlot in 0 until organs.size) {
                val organ = organs.getInt(organSlot)
                if (!cellEntity.organEntity.hasArena(organ)) continue
                writeIndex = writeCellRange(organ, cellEntity.organEntity.cellArenaBase[organ], cellEntity.organEntity.cellArenaEnd(organ), writeIndex, overlaySink, -1)
            }
            return writeIndex
        }
        if (debugFrame) {
            // Всё, что записано до клеток (кольца феромонов), — в буфер раньше фигур клеток.
            flushOverlay(overlay)
            ensureSinks(executor.workerCount)
            for (sink in sinks) sink.reset()
        }
        executor.runChunks(chunks, Phase.RENDER, countChunkJob)
        chunkStart[0] = startIndex
        for (k in 0 until chunks) chunkStart[k + 1] += chunkStart[k]
        executor.runChunks(chunks, Phase.RENDER, writeChunkJob)
        if (debugFrame) {
            for (k in 0 until chunks) overlay.putAll(sinks[chunkWorker[k]].ints, chunkFigureFrom[k], chunkFigureTo[k] - chunkFigureFrom[k])
            overlaySink.reset()
        }
        opticalCullRanges?.let { cull -> mergeChunkBounds(cull, chunks) }
        return chunkStart[chunks]
    }

    /**
     * Куски записи — по [CHUNK_CELLS] слотов арены, в порядке организмов и слотов, то есть в
     * порядке снимка: склейка по номеру куска даёт ровно тот буфер, что и проход в одном потоке.
     * Крупный организм режется на несколько кусков: делёж только по организмам упирался в число тел
     * (замер 27.11, A21s, два тела по 3250 клеток на семи потоках — 1.5 мс вместо 0.46 у кусков по 500).
     */
    private fun planChunks(): Int {
        val organEntity = cellEntity.organEntity
        val organs = organEntity.aliveList
        var n = 0
        for (k in 0 until organs.size) {
            val organ = organs.getInt(k)
            if (!organEntity.hasArena(organ)) continue
            val end = organEntity.cellArenaEnd(organ)
            var from = organEntity.cellArenaBase[organ]
            while (from < end) {
                if (n == chunkOrgan.size) growChunks()
                val to = minOf(end, from + CHUNK_CELLS)
                chunkOrgan[n] = organ; chunkFrom[n] = from; chunkTo[n] = to
                n++
                from = to
            }
        }
        if (chunkBounds.size < 4 * n) chunkBounds = FloatArray(8 * n)
        return n
    }

    private fun growChunks() {
        val size = chunkOrgan.size * 2
        chunkOrgan = chunkOrgan.copyOf(size); chunkFrom = chunkFrom.copyOf(size); chunkTo = chunkTo.copyOf(size)
        chunkStart = chunkStart.copyOf(size + 1)
        chunkWorker = chunkWorker.copyOf(size); chunkFigureFrom = chunkFigureFrom.copyOf(size); chunkFigureTo = chunkFigureTo.copyOf(size)
    }

    /** Рамки кусков одного организма (они идут подряд) — одна рамка тела, как в проходе в одном потоке. */
    private fun mergeChunkBounds(cull: io.github.some_example_name.render.pack.OpticalCullRanges, chunks: Int) {
        var k = 0
        while (k < chunks) {
            val organ = chunkOrgan[k]
            val start = chunkStart[k]
            var minX = chunkBounds[4 * k]; var minY = chunkBounds[4 * k + 1]
            var maxX = chunkBounds[4 * k + 2]; var maxY = chunkBounds[4 * k + 3]
            var j = k + 1
            while (j < chunks && chunkOrgan[j] == organ) {
                minX = minOf(minX, chunkBounds[4 * j]); minY = minOf(minY, chunkBounds[4 * j + 1])
                maxX = maxOf(maxX, chunkBounds[4 * j + 2]); maxY = maxOf(maxY, chunkBounds[4 * j + 3])
                j++
            }
            if (chunkStart[j] > start) cull.add(start, chunkStart[j], minX, minY, maxX, maxY)
            k = j
        }
    }

    /** Накопители оверлея на каждого воркера. */
    private fun ensureSinks(workers: Int) {
        if (sinks.size < workers) sinks = Array(workers) { if (it < sinks.size) sinks[it] else OverlayMotionSink() }
    }

    /**
     * Воркеры игры; null — всё в вызывающем потоке (однопоточный контейнер). Ставит контейнер.
     * Запись буфера только читает мир и пишет свои инстансы, историю и фигуры оверлея: у куска
     * свой непрерывный участок буфера, начало считается по числу живых клеток. Замер 09.11,
     * 96k клеток, реалистичный режим: 2.1 → 1.1 мс на тик; буфер при 1, 4, 8, 12 воркерах совпал
     * побитово. С 27.11 так же и отладочный режим (раньше — в одном потоке: A21s, 6.5 тыс. клеток,
     * копия прохода — 4.7 мс в одном потоке против 0.68 на семи).
     */
    var threads: ThreadManager? = null

    /** Для замера (PerfCheck): хэш инстансов снимка на заданном тике — сверка записи на воркерах с записью в одном потоке. */
    @Volatile var instanceHashTick = -1
    @Volatile var instanceHashValue = 0L

    /**
     * Инстансы снимка, фигуры оверлея, диапазоны тел для отсечения и данные микроскопа — одним
     * хэшем: сверка записи на воркерах с записью в одном потоке (PerfCheck, PERFHASH).
     */
    private fun hashSnapshot(count: Int, overlay: OverlayInstanceBuffer): Long {
        var h = 0L
        for (k in 0 until count * CellInstanceBuffer.STRUCT_INTS) h = Fnv.word(h, instances[k])
        val figures = overlay.end()
        for (b in 0 until figures.limit() / 4) h = Fnv.word(h, figures.getInt(b * 4))
        opticalCullRanges?.let { cull ->
            h = Fnv.word(h, cull.count)
            for (r in 0 until cull.count) { h = Fnv.word(h, cull.first(r)); h = Fnv.word(h, cull.end(r)) }
        }
        if (polygonFrame) for (k in 0 until count) {
            h = Fnv.word(h, opticalGroups[k]); h = Fnv.word(h, opticalColors[k]); h = Fnv.word(h, opticalBirthTicks[k])
        }
        return h
    }

    // Куски записи клеток (planChunks): организм, слоты [from, to), начало в снимке (сначала —
    // счёт живых, затем префиксная сумма), рамка для микроскопа, воркер и диапазон его фигур
    // оверлея. Пишет кусок только свои элементы; растут только однопоточно, в planChunks.
    private var chunkOrgan = IntArray(64)
    private var chunkFrom = IntArray(64)
    private var chunkTo = IntArray(64)
    private var chunkStart = IntArray(65)
    private var chunkWorker = IntArray(64)
    private var chunkFigureFrom = IntArray(64)
    private var chunkFigureTo = IntArray(64)
    /** Рамки оптических участков по кускам: minX, minY, maxX, maxY; сливаются по организмам. */
    private var chunkBounds = FloatArray(256)

    private val countChunkJob = ParallelExecutor.ChunkJob { k, _ -> chunkStart[k + 1] = aliveCells(chunkFrom[k], chunkTo[k]) }
    private val writeChunkJob = ParallelExecutor.ChunkJob { k, worker ->
        val sink = sinks[if (debugFrame) worker else 0]
        if (debugFrame) { chunkWorker[k] = worker; chunkFigureFrom[k] = sink.count }
        writeCellRange(chunkOrgan[k], chunkFrom[k], chunkTo[k], chunkStart[k], sink, k)
        if (debugFrame) chunkFigureTo[k] = sink.count
    }

    private fun aliveCells(from: Int, to: Int): Int {
        val cellAlive = cellEntity.isAlive
        var count = 0
        for (cellIndex in from until to) if (cellAlive[cellIndex]) count++
        return count
    }

    /**
     * Живые клетки слотов [cellFrom, cellTo) арены организма [organIndex] — в буфер с места
     * [startIndex]; возвращает место за последней. Фигуры оверлея — в [sink]. [boundsSlot] — куда
     * положить рамку для микроскопа (номер куска), -1 — сразу добавить диапазон тела.
     *
     * Массивы — в локальные до цикла и длины до цикла, клетка — inline-ядром (CLAUDE.md, «JIT»):
     * на Android заголовки больших массивов делят пару наборов L1, и вызов на каждую клетку читал
     * их заново (замер 27.11, A21s, копия прохода: 480 → 300 нс на клетку, инстансы те же).
     */
    private fun writeCellRange(organIndex: Int, cellFrom: Int, cellTo: Int, startIndex: Int, sink: OverlayMotionSink, boundsSlot: Int): Int {
        val organEntity = cellEntity.organEntity
        val arenaCell = organEntity.cellArenaBase[organIndex]
        val arenaParticle = organEntity.particleArenaBase[organIndex]
        val particleBase = arenaParticle + (cellFrom - arenaCell)
        val alive = cellEntity.isAlive
        val physicsIndex = particleEntity.physicsIndex
        val physicsX = particleEntity.physicsX
        val physicsY = particleEntity.physicsY
        val localX = particleEntity.localX
        val localY = particleEntity.localY
        val originX = particleEntity.physicsOriginX
        val originY = particleEntity.physicsOriginY
        val angleCos = cellEntity.angleCos
        val angleSin = cellEntity.angleSin
        val generation = cellEntity.generationArray()
        val energy = cellEntity.energy
        val cellType = cellEntity.cellType
        val color = particleEntity.color
        val radius = particleEntity.radius
        val hist = history
        val target = instances
        val keys = cellKeys
        lengthsBeforeLoop(
            alive.size + physicsIndex.size + physicsX.size + physicsY.size + localX.size + localY.size +
                angleCos.size + angleSin.size + generation.size + energy.size + cellType.size + color.size +
                radius.size + hist.size + target.size + keys.size
        )
        val now = serial
        val extras = debugFrame || polygonFrame || areaFrame
        val bounded = opticalCullRanges != null
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var writeIndex = startIndex
        for (cellIndex in cellFrom until cellTo) {
            if (!alive[cellIndex]) continue
            val particleIndex = particleBase + (cellIndex - cellFrom)

            // Частица берётся АРИФМЕТИКОЙ из слота, а не через particleIndexes.
            // Если параллельность арен где-то нарушится, углы и радиусы поедут от
            // ЧУЖОЙ клетки — картинка при этом останется правдоподобной, поэтому
            // расхождение надо ловить явно, а не глазами.
            if (DEBUG_CHECKS) checkArenaParallel(cellIndex, organIndex, particleIndex, arenaCell, arenaParticle)

            // То же, что ParticleEntity.x/y, — теми же операциями, биты те же.
            val i = physicsIndex[particleIndex]
            val x = if (i >= 0) physicsX[i] * GAME_PER_PHYSICS + originX else localX[particleIndex]
            val y = if (i >= 0) physicsY[i] * GAME_PER_PHYSICS + originY else localY[particleIndex]
            writeCellCore(
                writeIndex, cellIndex, particleIndex, x, y, now,
                angleCos, angleSin, generation, energy, cellType, color, radius, hist, target, keys
            )
            if (extras) writeCellExtras(writeIndex, cellIndex, particleIndex, x, y, sink)
            if (bounded) {
                val h = particleIndex * HISTORY_STRIDE
                val hx = Float.fromBits(hist[h + 5]); val hy = Float.fromBits(hist[h + 6])
                minX = minOf(minX, minOf(x, hx)); minY = minOf(minY, minOf(y, hy))
                maxX = maxOf(maxX, maxOf(x, hx)); maxY = maxOf(maxY, maxOf(y, hy))
            }
            writeIndex++
        }
        if (bounded) {
            if (boundsSlot >= 0) {
                chunkBounds[4 * boundsSlot] = minX; chunkBounds[4 * boundsSlot + 1] = minY
                chunkBounds[4 * boundsSlot + 2] = maxX; chunkBounds[4 * boundsSlot + 3] = maxY
            } else if (writeIndex > startIndex) opticalCullRanges!!.add(startIndex, writeIndex, minX, minY, maxX, maxY)
        }
        return writeIndex
    }

    /**
     * Клетка [cellIndex] с частицей [p] в позе ([x], [y]) — инстанс [w] снимка, ключ клетки и
     * строка истории (см. [history]). Только копирование чисел и упаковка: ни одного
     * округления позы (CellInstanceBuffer: «Почему прошлая поза, а не смещение»).
     *
     * Клетка без годной прошлой позы (новая) или перенесённая дальше MOTION_RANGE получает
     * прошлую позу, равную нынешней: она просто стоит в снимке, без полёта из чужого места.
     * Прошлая поза годится, только если записана ровно в прошлом снимке и тем же жильцом —
     * поколением клетки: слот умершей клетки занимает новая.
     *
     * inline с массивами в параметрах — тело в цикле прохода (CLAUDE.md, «JIT»).
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun writeCellCore(
        w: Int, cellIndex: Int, p: Int, x: Float, y: Float, now: Int,
        angleCos: FloatArray, angleSin: FloatArray, generation: IntArray, energy: FloatArray, cellType: ByteArray,
        color: IntArray, radius: FloatArray, hist: IntArray, target: IntArray, keys: LongArray
    ) {
        val dir = CellInstanceBuffer.dir(angleCos[cellIndex], angleSin[cellIndex])
        val key = generation[cellIndex]
        val h = p * HISTORY_STRIDE
        val xBits = x.toRawBits()
        val yBits = y.toRawBits()
        var prevXBits = xBits
        var prevYBits = yBits
        var prevDir = dir
        if (hist[h] == now - 1 && hist[h + 1] == key) {
            val hx = Float.fromBits(hist[h + 2])
            val hy = Float.fromBits(hist[h + 3])
            if (PROFILE_INTERPOLATION) countStep(x - hx, y - hy)
            if (CellInstanceBuffer.isMotion(x - hx, y - hy)) {
                prevXBits = hist[h + 2]
                prevYBits = hist[h + 3]
                prevDir = hist[h + 4]
            }
        }
        hist[h] = now
        hist[h + 1] = key
        hist[h + 2] = xBits
        hist[h + 3] = yBits
        hist[h + 4] = dir
        hist[h + 5] = prevXBits
        hist[h + 6] = prevYBits
        hist[h + 7] = prevDir
        keys[w] = (cellIndex.toLong() shl 32) or (key.toLong() and 0xffffffffL)

        CellInstanceBuffer.write(
            target,
            w,
            x = x,
            y = y,
            color = color[p],
            // Радиус — физический, без degreeOfShortening. Укорочение мышцы меняет длину
            // её связей, а не размер клетки: сжимавшийся вместе с ним круг мигал на каждом
            // сигнале. Сжатие видно по самому телу и, в отладке, по яркости мышцы.
            shape = CellInstanceBuffer.shape(
                CellInstanceBuffer.radiusByte(radius[p]),
                CellInstanceBuffer.energyByte(energy[cellIndex])
            ),
            // Ключ шума — индекс частицы, а не позиция в буфере.
            //
            // Шейдер доворачивает текстуру на случайный угол, и раньше брал его из
            // gl_InstanceID, то есть из позиции в буфере. Пока буфер собирался в порядке
            // aliveList, позиция живой клетки не менялась, и доворот был постоянным.
            // Теперь порядок задаёт арена: рождение клетки в более раннем слоте сдвигает
            // все последующие, и во время роста доворот пересчитывался каждый тик —
            // текстуры визуально крутились у всего тела, пока организм не дорастал.
            //
            // Индекс частицы это слот арены: он закреплён за клеткой на всю жизнь и от
            // порядка сборки буфера не зависит вообще. По нему же отладка раскладывает рисунок.
            type = CellInstanceBuffer.type(cellType[cellIndex].toInt(), p, true),
            look = 0,
            dir = dir,
            prevX = Float.fromBits(prevXBits),
            prevY = Float.fromBits(prevYBits),
            prevDir = prevDir
        )
    }

    /**
     * Редкое поверх записанной клетки: заливка областей, данные микроскопа, отладочный рисунок и
     * фигуры. Отдельным методом: проход по клеткам обязан оставаться маленьким (CLAUDE.md, «JIT»).
     */
    private fun writeCellExtras(writeIndex: Int, cellIndex: Int, particleIndex: Int, x: Float, y: Float, sink: OverlayMotionSink) {
        if (areaFrame) areaCache.cell(writeIndex, particleEntity.physicsIndex[particleIndex])
        if (polygonFrame) {
            renderOfCell[cellIndex] = writeIndex; renderCellStamp[cellIndex] = serial
            opticalGroups[writeIndex] = if (cellEntity.organIndex[cellIndex] >= 0) cellEntity.organIndex[cellIndex] + 1 else -particleIndex - 1
            opticalColors[writeIndex] = particleEntity.color[particleIndex]
            opticalBirthTicks[writeIndex] = cellEntity.birthTick[cellIndex]
        }
        if (debugFrame) decorateDebugCell(writeIndex, cellIndex, particleIndex, x, y, sink)
    }

    private fun checkArenaParallel(cellIndex: Int, organIndex: Int, particleIndex: Int, cellFrom: Int, particleBase: Int) {
        val expected = cellEntity.particleIndexes[cellIndex]
        if (expected != particleIndex) {
            throw IllegalStateException(
                "рендер: клетка $cellIndex организма $organIndex ссылается на " +
                    "частицу $expected, а по параллельности арен это " +
                    "$particleIndex (cellBase=$cellFrom particleBase=$particleBase)"
            )
        }
        if (!particleEntity.isAlive[particleIndex]) {
            throw IllegalStateException(
                "рендер: живая клетка $cellIndex с мёртвой частицей $particleIndex"
            )
        }
    }

    /**
     * Клетки вне арен (organIndex == -1 либо у организма арены нет).
     *
     * Проход стоит O(живых клеток), поэтому включается только когда такие клетки реально
     * есть: на стенде их нет, и холостой обход всего мира каждый кадр был бы заметен.
     */
    private fun writeOrphanCells(startIndex: Int): Int {
        if (cellEntity.orphanCellCount == 0) return startIndex

        var writeIndex = startIndex
        val organEntity = cellEntity.organEntity
        val alive = cellEntity.aliveList
        val now = serial

        for (i in 0 until alive.size) {
            val cellIndex = alive.getInt(i)
            if (organEntity.hasArena(cellEntity.organIndex[cellIndex])) continue
            val particleIndex = cellEntity.particleIndexes[cellIndex]
            if (particleIndex == -1) continue

            val x = particleEntity.x(particleIndex)
            val y = particleEntity.y(particleIndex)
            writeCellCore(
                writeIndex, cellIndex, particleIndex, x, y, now,
                cellEntity.angleCos, cellEntity.angleSin, cellEntity.generationArray(), cellEntity.energy, cellEntity.cellType,
                particleEntity.color, particleEntity.radius, history, instances, cellKeys
            )
            writeCellExtras(writeIndex, cellIndex, particleIndex, x, y, overlaySink)
            writeIndex++
        }
        return writeIndex
    }

    /**
     * Субстанции и прочие не-клетки — своим списком, без единого обращения в CellEntity.
     * Фигур оверлея у не-клеток нет, поэтому на воркерах и в отладочном режиме: фильтры
     * «коллизии» и «сон» только читают мир.
     */
    private fun writeNonCellParticles(startIndex: Int): Int {
        val nonCells = particleEntity.nonCellList
        val executor = threads?.executor
        // Неподвижные (террейн, стенка) — первые места списка; в отладочном и ностальгическом
        // они едут своим буфером раз на мир ([staticCells]), снимок пишет только подвижные.
        // Микроскоп строит ткань террейна из снимка — ему по-прежнему все.
        val first = if (polygonFrame) 0 else particleEntity.staticNonCellCount
        val count = nonCells.size - first
        nonCellFirst = first
        // Рамки кусков не-клеток — для отсечения по камере в отладочном (VisibleCells), не для
        // микроскопа: у него диапазоны — целые тела.
        nonCellCull = debugFrame && !polygonFrame && opticalCullRanges != null
        val pieces = (count + NON_CELL_CULL - 1) / NON_CELL_CULL
        if (nonCellCull && nonCellBounds.size < 4 * pieces) nonCellBounds = FloatArray(8 * pieces)
        // Инстанс места i — base + i: подвижные встают сразу за клетками.
        val base = startIndex - first
        if (executor != null && executor.workerCount > 1 && !PROFILE_INTERPOLATION && count >= NON_CELL_CHUNK) {
            nonCellStart = base
            executor.runChunks((count + NON_CELL_CHUNK - 1) / NON_CELL_CHUNK, Phase.RENDER, writeNonCellJob)
        } else writeNonCellRange(first, nonCells.size, base)
        if (nonCellCull) {
            val cull = opticalCullRanges!!
            for (j in 0 until pieces) {
                cull.add(startIndex + j * NON_CELL_CULL, startIndex + minOf(count, (j + 1) * NON_CELL_CULL),
                    nonCellBounds[4 * j], nonCellBounds[4 * j + 1], nonCellBounds[4 * j + 2], nonCellBounds[4 * j + 3])
            }
        }
        return startIndex + count
    }

    /** Первое место списка не-клеток, которое пишет снимок (см. [writeNonCellParticles]). */
    private var nonCellFirst = 0

    // ==================== Неподвижный буфер (27.11) ====================
    //
    // Террейн и стенка у рамки за сессию не двигаются (ParticleEntity.bakeStaticGrid), а до
    // 27.11 каждый тик писались в снимок, копировались GL-потоком и заливались на GPU заново:
    // мир 256 с 66 тысячами частиц террейна — 2.7 МБ на снимок, снимок A21s 7.8 мс. Теперь их
    // инстансы собираются раз на мир в свой direct-буфер ([StaticCells]); снимок несёт ссылку,
    // GL-поток заливает его в свой VBO, только когда пришёл другой (CellGeometry.uploadStatic).
    // Пересборка — при новом мире, загрузке, первом тике мира физики (кромка встаёт по его
    // месту) и при смене фильтра отладки (от него вид частицы).

    private var staticCells: StaticCells? = null
    private var staticKey = Long.MIN_VALUE

    /** Неподвижный буфер под нынешний мир и фильтр — готовый или собранный заново. */
    private fun staticCellsFor(): StaticCells {
        val look = if (debugFrame) frameFilter.ordinal + 1 else 0
        val key = (particleEntity.staticVersion.toLong() shl 8) or look.toLong()
        val current = staticCells
        if (current != null && key == staticKey) return current
        val built = buildStaticCells()
        staticCells = built
        staticKey = key
        return built
    }

    /** Инстансы неподвижных не-клеток (первые места nonCellList) и рамки их кусков по [NON_CELL_CULL]. */
    private fun buildStaticCells(): StaticCells {
        val n = particleEntity.staticNonCellCount
        val nonCells = particleEntity.nonCellList
        val buffer = CellInstanceBuffer(maxOf(1, n))
        buffer.begin(n)
        val cull = io.github.some_example_name.render.pack.OpticalCullRanges()
        val scratch = IntArray(NON_CELL_CULL * CellInstanceBuffer.STRUCT_INTS)
        val nonCellType = cellList.size + 1
        val collisionFilter = debugFrame && frameFilter == DebugFilter.COLLISION
        val sleepFilter = debugFrame && frameFilter == DebugFilter.SLEEP
        val physics = particleEntity.physics
        var piece = 0
        while (piece < n) {
            val end = minOf(n, piece + NON_CELL_CULL)
            var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            for (i in piece until end) {
                val p = nonCells.getInt(i)
                val x = particleEntity.x(p)
                val y = particleEntity.y(p)
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                CellInstanceBuffer.write(
                    scratch, i - piece,
                    x = x, y = y,
                    color = particleEntity.color[p],
                    shape = CellInstanceBuffer.shape(CellInstanceBuffer.radiusByte(particleEntity.radius[p]), 0),
                    type = CellInstanceBuffer.type(nonCellType, p, isCell = false),
                    // «Коллизии» — участвует ли в касаниях: кромка и стенка (в мире физики) — да, толща — нет.
                    look = when {
                        collisionFilter -> CellLook.look(0, filter = CellLook.collisionFilterByte(particleEntity.physicsIndex[p] >= 0))
                        sleepFilter -> CellLook.look(0, filter = CellLook.sleepFilterByte(physics?.isAsleep(p) == true))
                        else -> 0
                    },
                    dir = CellInstanceBuffer.DIR_NONE,
                    prevX = x, prevY = y,
                    prevDir = CellInstanceBuffer.DIR_NONE
                )
            }
            buffer.putAll(scratch, end - piece)
            cull.add(piece, end, minX, minY, maxX, maxY)
            piece = end
        }
        return StaticCells(buffer.end(), cull)
    }

    /**
     * Рамки кусков по [NON_CELL_CULL] мест списка не-клеток: minX, minY, maxX, maxY. Террейн
     * создаётся построчно (WorldTerrainManager.initWorld) и почти не перемешивается, поэтому
     * кусок — отрезок ряда с узкой рамкой: при приближении камеры невидимые отрезки отсекаются
     * так же, как тела. Субстанции в хвосте списка просто попадают в рамку своего куска.
     */
    private var nonCellBounds = FloatArray(0)
    private var nonCellCull = false

    private var nonCellStart = 0
    private val writeNonCellJob = ParallelExecutor.ChunkJob { k, _ ->
        val first = nonCellFirst
        writeNonCellRange(first + k * NON_CELL_CHUNK, minOf(particleEntity.nonCellList.size, first + (k + 1) * NON_CELL_CHUNK), nonCellStart)
    }

    /**
     * Места [from, to) списка не-клеток в буфер; у каждого места своя строка истории и свой инстанс.
     * Массивы — в локальные до цикла, история позы — прямо в цикле (CLAUDE.md, «JIT»): в мире с
     * террейном это десятки тысяч частиц, основная часть снимка (A21s, 27.11: 66 тысяч частиц).
     */
    private fun writeNonCellRange(from: Int, to: Int, startIndex: Int) {
        val target = instances
        val nonCells = particleEntity.nonCellList
        val pColor = particleEntity.color
        val pRadius = particleEntity.radius
        val physicsIndex = particleEntity.physicsIndex
        val physicsX = particleEntity.physicsX
        val physicsY = particleEntity.physicsY
        val localX = particleEntity.localX
        val localY = particleEntity.localY
        val originX = particleEntity.physicsOriginX
        val originY = particleEntity.physicsOriginY

        val inGrid = particleEntity.isInGrid

        // Тип «не клетка» один на всех — считается один раз, а не на каждой частице.
        val nonCellType = (cellList.size + 1)
        val collisionFilter = frameFilter == DebugFilter.COLLISION
        val sleepFilter = frameFilter == DebugFilter.SLEEP
        val physics = particleEntity.physics

        val hist = nonCellHistory
        val optics = polygonFrame
        val terrainCell = particleEntity.terrainCell
        lengthsBeforeLoop(
            target.size + pColor.size + pRadius.size + physicsIndex.size + physicsX.size + physicsY.size +
                localX.size + localY.size + inGrid.size + hist.size + terrainCell.size
        )
        val now = serial
        val bounded = nonCellCull
        val bounds = nonCellBounds
        val first = nonCellFirst
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in from until to) {
            val particleIndex = nonCells.getInt(i)
            // То же, что ParticleEntity.x/y, — теми же операциями, биты те же.
            val pi = physicsIndex[particleIndex]
            val x = if (pi >= 0) physicsX[pi] * GAME_PER_PHYSICS + originX else localX[particleIndex]
            val y = if (pi >= 0) physicsY[pi] * GAME_PER_PHYSICS + originY else localY[particleIndex]
            // Направления у не-клетки нет: ни поворота, ни угла в истории. Прошлая поза — как у
            // клетки (writeCellCore), ключ жильца — индекс частицы, см. [nonCellHistory].
            val h = i * NON_CELL_STRIDE
            val xBits = x.toRawBits()
            val yBits = y.toRawBits()
            var prevXBits = xBits
            var prevYBits = yBits
            if (hist[h] == now - 1 && hist[h + 1] == particleIndex) {
                val hx = Float.fromBits(hist[h + 2])
                val hy = Float.fromBits(hist[h + 3])
                if (PROFILE_INTERPOLATION) countStep(x - hx, y - hy)
                if (CellInstanceBuffer.isMotion(x - hx, y - hy)) {
                    prevXBits = hist[h + 2]
                    prevYBits = hist[h + 3]
                }
            }
            hist[h] = now
            hist[h + 1] = particleIndex
            hist[h + 2] = xBits
            hist[h + 3] = yBits
            hist[h + 4] = prevXBits
            hist[h + 5] = prevYBits
            if (bounded) {
                val px = Float.fromBits(prevXBits); val py = Float.fromBits(prevYBits)
                minX = minOf(minX, minOf(x, px)); minY = minOf(minY, minOf(y, py))
                maxX = maxOf(maxX, maxOf(x, px)); maxY = maxOf(maxY, maxOf(y, py))
                // Куски рамок считаются от первого места снимка (без неподвижных).
                val rel = i - first
                if ((rel + 1) % NON_CELL_CULL == 0 || i == to - 1) {
                    val o = 4 * (rel / NON_CELL_CULL)
                    bounds[o] = minX; bounds[o + 1] = minY; bounds[o + 2] = maxX; bounds[o + 3] = maxY
                    minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
                    maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
                }
            }
            if (optics) {
                // Слоты снимка переиспользуются: цвет и тик рождения пишутся явно, иначе от прошлого
                // жильца слота менялась бы подпись тела террейна, и микроскоп перестраивал бы его сетку.
                val w = startIndex + i
                opticalGroups[w] = if (terrainCell[particleIndex] >= 0) OpticalLinks.TERRAIN_GROUP else 0
                opticalColors[w] = pColor[particleIndex]
                opticalBirthTicks[w] = -1
            }

            CellInstanceBuffer.write(
                target,
                startIndex + i,
                x = x,
                y = y,
                color = pColor[particleIndex],
                shape = CellInstanceBuffer.shape(
                    radiusByte = CellInstanceBuffer.radiusByte(pRadius[particleIndex]),
                    energyByte = 0
                ),
                type = CellInstanceBuffer.type(
                    cellType = nonCellType,
                    noiseSeed = particleIndex,
                    // Без ядра в ностальгическом режиме: ядро — признак клетки.
                    isCell = false
                ),
                // В отладке — плоская и непрозрачная, как и была. Из фильтров её касается
                // только «коллизии»: в сетке столкновений она, как и граничные клетки.
                look = when {
                    collisionFilter -> CellLook.look(0, filter = CellLook.collisionFilterByte(inGrid[particleIndex]))
                    sleepFilter -> CellLook.look(0, filter = CellLook.sleepFilterByte(physics?.isAsleep(particleIndex) == true))
                    else -> 0
                },
                dir = CellInstanceBuffer.DIR_NONE,
                prevX = Float.fromBits(prevXBits),
                prevY = Float.fromBits(prevYBits),
                prevDir = CellInstanceBuffer.DIR_NONE
            )
        }
    }

    /**
     * Отладочный режим поверх уже записанной клетки: цвет палитры, рисунок и сигнал в look,
     * стрелка, луч или жгутик — в накопитель оверлея [sink] своего воркера. Прошлая поза — из
     * строки истории, только что записанной writeCellCore: общих полей у кусков на воркерах нет.
     *
     * Отдельным методом, а не веткой в проходе по клеткам: тот обязан оставаться маленьким
     * (см. CLAUDE.md, «JIT»).
     */
    private fun decorateDebugCell(writeIndex: Int, cellIndex: Int, particleIndex: Int, x: Float, y: Float, sink: OverlayMotionSink) {
        val cellType = cellEntity.cellType[cellIndex].toInt()
        // Сигнал действия — выход клетки: мышца сжимается, хвост тянет, присоска
        // присасывается ровно по нему. От него анимация рисунка, см. DebugCellPalette.
        val signal = cellEntity.neuronImpulseOutput[cellIndex]
        val o = writeIndex * CellInstanceBuffer.STRUCT_INTS
        // С галочкой «Цвет из генома» остаётся цвет, уже записанный writeCellCore.
        if (!frameGenomeColors) instances[o + CellInstanceBuffer.OFFSET_COLOR / 4] = palette.color(cellType, particleEntity.color[particleIndex])
        instances[o + CellInstanceBuffer.OFFSET_LOOK / 4] =
            palette.look(cellType, signal, filter = filterByte(cellIndex, particleIndex, signal))

        if (!palette.isEye(cellType) && !palette.hasFlagellum(cellType) && !palette.hasArrow(cellType)) return
        val angleCos = cellEntity.angleCos[cellIndex]
        val angleSin = cellEntity.angleSin[cellIndex]
        val h = particleIndex * HISTORY_STRIDE
        val prevDir = history[h + 7]
        sink.rigid(
            x, y, angleCos, angleSin,
            Float.fromBits(history[h + 5]), Float.fromBits(history[h + 6]),
            CellInstanceBuffer.dirCos(prevDir), CellInstanceBuffer.dirSin(prevDir)
        )
        writeDirection(sink, cellIndex, cellType, particleIndex, x, y, angleCos, angleSin, particleEntity.radius[particleIndex], signal)
    }

    /**
     * Прошлая x клетки с частицей [p] в ТЕКУЩЕМ снимке (после прохода по клеткам). Нет её в
     * снимке — нынешняя [x]: тогда и интерполировать нечего.
     */
    private fun prevXOf(p: Int, x: Float): Float =
        if (p >= 0 && history[p * HISTORY_STRIDE] == serial) Float.fromBits(history[p * HISTORY_STRIDE + 5]) else x

    private fun prevYOf(p: Int, y: Float): Float =
        if (p >= 0 && history[p * HISTORY_STRIDE] == serial) Float.fromBits(history[p * HISTORY_STRIDE + 6]) else y

    /**
     * Байт фильтра отладки для клетки (CellLook): заполненность энергией, нормализованный
     * выход или участие в столкновениях. Без фильтра ноль — лишних чтений нет.
     */
    private fun filterByte(cellIndex: Int, particleIndex: Int, signal: Float): Int = when (frameFilter) {
        DebugFilter.ORIGINAL -> 0
        DebugFilter.ENERGY -> CellLook.energyFilterByte(cellEntity.energy[cellIndex], cellEntity.maxEnergy[cellIndex])
        DebugFilter.SIGNAL -> CellLook.signalFilterByte(signal)
        DebugFilter.COLLISION -> CellLook.collisionFilterByte(particleEntity.isInGrid[particleIndex])
        DebugFilter.SLEEP -> CellLook.sleepFilterByte(particleEntity.physics?.isAsleep(particleIndex) == true)
    }

    /** Стрелка направления, луч глаза или жгутик хвоста — в оверлей, см. DebugCellPalette. */
    private fun writeDirection(
        overlaySink: OverlayMotionSink,
        cellIndex: Int,
        cellType: Int,
        particleIndex: Int,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        signal: Float
    ) {
        if (palette.isEye(cellType)) {
            palette.writeEyeRay(
                overlaySink, cellType, x, y, angleCos, angleSin, radius,
                range = specialEntity.eyeData(cellIndex).visibilityRange,
                signal = signal
            )
        } else if (palette.hasFlagellum(cellType)) {
            palette.writeFlagellum(overlaySink, cellType, x, y, angleCos, angleSin, radius, signal, seed = particleIndex)
        } else if (palette.hasArrow(cellType)) {
            palette.writeArrow(overlaySink, x, y, angleCos, angleSin, radius, signal)
        }
    }

    /** Радиусы действия феромонов — кольцами, под всем остальным оверлеем. Пятна стоят на месте. */
    private fun writePheromoneRings() = with(pheromoneEntity) {
        overlaySink.still()
        val params = OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_RING, 0f, 0)
        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            overlaySink.add(x[i], y[i], sqrt(radiusSquared[i]), 0f, color[i], params)
        }
    }

    /**
     * Пятна феромонов (PheromoneLayer в :render): позиция, накопленная концентрация и цвет —
     * 16 байт на феромон, проход по живым феромонам, без аллокаций. Как было в реалистичном
     * режиме до 06.11.
     */
    private fun writePheromones(snapshot: RenderSnapshot) = with(pheromoneEntity) {
        val pheromones = snapshot.pheromones
        pheromones.begin(aliveList.size)
        for (bufIndex in 0..<aliveList.size) {
            val i = aliveList.getInt(bufIndex)
            pheromones.put(x = x[i], y = y[i], a = time[i], color = color[i])
        }
        pheromones.end()
    }

    /**
     * Контур тел мира физики — рёбра, которые омываются средой, включая края дыр и голые связи
     * (как зелёная граница стенда). Концы едут каждый со своей частицей, как у нейросвязи: их
     * прошлые позиции уже записаны проходом по клеткам. Цена — проход по рёбрам контура, только
     * в отладочном режиме с галочкой.
     */
    private fun writeBoundary() {
        val physics = particleEntity.physics ?: return
        physics.boundaryParticles(boundaryPairs)
        writePairs(OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_EDGE, 0f, 0), DebugCellPalette.BOUNDARY_COLOR)
    }

    /** Все живые связи тел мира физики — тонко, под контуром (галочка «Связи»). */
    private fun writeLinks() {
        val physics = particleEntity.physics ?: return
        physics.linkParticles(boundaryPairs)
        writePairs(OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_LINK, 0f, 0), DebugCellPalette.LINK_COLOR)
    }

    /** Отрезки между частицами пар [boundaryPairs]; концы едут каждый со своей частицей. */
    private fun writePairs(params: Int, color: Int) {
        var k = 0
        while (k < boundaryPairs.size) {
            val a = boundaryPairs.getInt(k)
            val b = boundaryPairs.getInt(k + 1)
            k += 2
            val ax = particleEntity.x(a); val ay = particleEntity.y(a)
            val bx = particleEntity.x(b); val by = particleEntity.y(b)
            overlaySink.points(prevXOf(a, ax), prevYOf(a, ay), prevXOf(b, bx), prevYOf(b, by))
            overlaySink.add(ax, ay, bx, by, color, params)
        }
    }

    /**
     * Нейросвязи — от источника к приёмнику, с сигналом, который источник отдаёт в связь.
     *
     * Направление то же, что у NeuralLinkManager.transportNeuralSignal: при
     * isLink1NeuralDirected сигнал идёт из второй клетки в первую, иначе из первой во
     * вторую. Физические связи в отладке не рисуются вовсе.
     *
     * Концы связи едут каждый со своей частицей: их прошлые позиции уже записаны проходом по
     * клеткам этого же снимка (поэтому связи пишутся после него).
     *
     * На воркерах, кусками по [NEURAL_CHUNK] связей, как и клетки: у каждого воркера свой
     * накопитель, склейка — по номеру куска, то есть в порядке aliveList. Замер 27.11, A21s,
     * 5.4 тысячи клеток осьминогов: в одном потоке связи стоили ~1 мс тика (simpleperf), больше
     * записи самих клеток.
     */
    private fun writeNeuralLinks(overlay: OverlayInstanceBuffer) {
        val links = neuralLinkEntity.aliveList.size
        val executor = threads?.executor
        val chunks = (links + NEURAL_CHUNK - 1) / NEURAL_CHUNK
        if (executor == null || executor.workerCount < 2 || chunks < 2 || PROFILE_INTERPOLATION) {
            writeNeuralLinkRange(0, links, overlaySink)
            return
        }
        // Всё, что записано раньше (кольца, клетки, связи тел, контур), — в буфер до нейросвязей.
        flushOverlay(overlay)
        ensureSinks(executor.workerCount)
        for (sink in sinks) sink.reset()
        if (neuralChunkWorker.size < chunks) {
            neuralChunkWorker = IntArray(chunks * 2); neuralChunkFrom = IntArray(chunks * 2); neuralChunkTo = IntArray(chunks * 2)
        }
        executor.runChunks(chunks, Phase.RENDER, neuralLinkJob)
        for (k in 0 until chunks) overlay.putAll(sinks[neuralChunkWorker[k]].ints, neuralChunkFrom[k], neuralChunkTo[k] - neuralChunkFrom[k])
        overlaySink.reset()
    }

    private var neuralChunkWorker = IntArray(16)
    private var neuralChunkFrom = IntArray(16)
    private var neuralChunkTo = IntArray(16)
    private val neuralLinkJob = ParallelExecutor.ChunkJob { k, worker ->
        val sink = sinks[worker]
        neuralChunkWorker[k] = worker
        neuralChunkFrom[k] = sink.count
        writeNeuralLinkRange(k * NEURAL_CHUNK, minOf(neuralLinkEntity.aliveList.size, (k + 1) * NEURAL_CHUNK), sink)
        neuralChunkTo[k] = sink.count
    }

    /** Нейросвязи мест [from, to) их aliveList — фигурами в [sink]. Только читает мир и историю снимка. */
    private fun writeNeuralLinkRange(from: Int, to: Int, sink: OverlayMotionSink) = with(neuralLinkEntity) {
        val output = cellEntity.neuronImpulseOutput

        for (bufIndex in from until to) {
            val i = aliveList.getInt(bufIndex)
            val linkCellA = links1[i]
            val linkCellB = links2[i]
            val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[i]
            val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[i]
            if (linkCellAIsDead || linkCellBIsDead) continue

            val particleA = cellEntity.getParticleIndex(linkCellA)
            val particleB = cellEntity.getParticleIndex(linkCellB)
            // Клетка без частицы — уже испорченное состояние, но обращаться по индексу -1
            // нельзя: это выход за границу массива, а не битая картинка.
            if (particleA == -1 || particleB == -1) continue

            val fromA = !isLink1NeuralDirected[i]
            val source = if (fromA) linkCellA else linkCellB
            val from = if (fromA) particleA else particleB
            val to = if (fromA) particleB else particleA

            sink.points(prevXOf(from, particleEntity.x(from)), prevYOf(from, particleEntity.y(from)), prevXOf(to, particleEntity.x(to)), prevYOf(to, particleEntity.y(to)))
            // Индекс связи — ключ фазы импульсов: закреплён за связью на всю её жизнь.
            palette.writeNeuralLink(
                sink, particleEntity.x(from), particleEntity.y(from), particleEntity.x(to), particleEntity.y(to), color[i], output[source], seed = i
            )
        }
    }



    /**
     * Начало снимка: номер, проверка разрыва и место в [history] под все частицы.
     *
     * Разрыв в номерах тиков — рестарт, загрузка мира или повтор бага с другого места: позы
     * в истории от другого мира, и сравнивать с ними нельзя. Номер снимка перескакивает
     * через один, и все метки разом становятся «не из прошлого снимка» — без прохода по
     * массиву.
     */
    private fun startSnapshot() {
        val tick = simulationData.tickCounter
        serial += if (tick == lastTick + 1) 1 else 2
        lastTick = tick

        val particles = particleEntity.localX.size
        if (history.size < particles * HISTORY_STRIDE) {
            history = history.copyOf(particles * HISTORY_STRIDE)
        }
        val nonCells = particleEntity.nonCellList.size
        if (nonCellHistory.size < nonCells * NON_CELL_STRIDE) {
            nonCellHistory = nonCellHistory.copyOf(maxOf(nonCells, nonCellHistory.size / NON_CELL_STRIDE * 2) * NON_CELL_STRIDE)
        }
    }

    fun updateBuffer(performanceInfo: String = "") {
        playerMarks?.follow(cellEntity)
        startSnapshot()
        val snapshot = snapshots.back()
        snapshot.tick = simulationData.tickCounter
        snapshot.epoch = SessionJournal.epoch
        cellKeys = snapshot.reserveCellKeys(cellEntity.aliveList.size)
        // Сколько инстансов вообще может быть записано: клетки (в аренах и вне их) и
        // не-клетки. Не aliveList частиц: если их сумма с ним разойдётся (это ловит
        // DEBUG_CHECKS), снимок не должен выйти за массив.
        val snapshotMode=mode
        polygonFrame=snapshotMode==RenderMode.TISSUE_MAP
        instances = snapshot.reserveInstances(cellEntity.aliveList.size + particleEntity.nonCellList.size)
        snapshot.hasOptics=polygonFrame
        if(polygonFrame){
            snapshot.reserveOptics()
            if(renderOfCell.size<cellEntity.isAlive.size){renderOfCell=IntArray(cellEntity.isAlive.size);renderCellStamp=IntArray(renderOfCell.size)}
        }
        opticalCullRanges=null
        if(polygonFrame){
            opticalGroups = snapshot.opticalGroups
            opticalColors = snapshot.opticalColors
            opticalBirthTicks = snapshot.opticalBirthTicks
            opticalCullRanges = snapshot.opticalCullRanges.also{it.clear()}
            opticalGroups.fill(0,0,cellEntity.aliveList.size+particleEntity.nonCellList.size)
        }

        debugFrame = snapshotMode.drawsDebugOverlay
        // Рамки тел и в отладочном снимке: по ним GL-поток рисует только тела в кадре
        // (VisibleCells). Цена — четыре min/max на клетку в том же проходе.
        snapshot.hasCellCull = debugFrame
        if (debugFrame) opticalCullRanges = snapshot.opticalCullRanges.also { it.clear() }
        areaFrame = debugFrame && fillAreas
        if (areaFrame) areaCache.reserveCells(cellEntity.aliveList.size)
        frameFilter = if (debugFrame) debugFilter else DebugFilter.ORIGINAL
        frameGenomeColors = debugFrame && genomeColors
        snapshot.staticCells = if (polygonFrame) null else staticCellsFor()

        val overlay = snapshot.overlay
        overlay.begin()
        overlaySink.reset()

        // Порядок записи оверлея — порядок отрисовки: кольца феромонов снизу, стрелки и
        // лучи (их пишет decorateDebugCell) над ними, нейросвязи сверху.
        if (debugFrame) writePheromoneRings()

        // ==================== CELL ====================
        var written = writeOrganismCells(0, overlay)
        written = writeOrphanCells(written)
        snapshot.areaIndices = if (areaFrame) {
            areaCache.publish(particleEntity.physics?.bridge?.world, written, snapshot.areaStorage)
        } else null
        val writtenCells=written
        snapshot.cellCount = writtenCells
        written = writeNonCellParticles(written)
        if(polygonFrame)writeOpticalLinks(snapshot,written,writtenCells)

        // Буфер обязан содержать РОВНО все живые частицы: клетки организмов, клетки вне
        // арен и не-клетки в сумме дают aliveList. Расхождение означает, что какие-то
        // частицы либо не попали в буфер, либо попали дважды — а обе беды выглядят как
        // «часть картинки ведёт себя странно», а не как явная ошибка.
        // Неподвижные (террейн, стенка) вне микроскопа едут своим буфером, не снимком.
        val expected = particleEntity.aliveList.size - (if (polygonFrame) 0 else particleEntity.staticNonCellCount)
        if (DEBUG_CHECKS && written != expected) {
            throw IllegalStateException(
                "рендер: в буфер записано $written частиц, а ждали $expected: живых " +
                    "${particleEntity.aliveList.size} (клеток ${cellEntity.aliveList.size}, " +
                    "не-клеток ${particleEntity.nonCellList.size}, из них неподвижных " +
                    "${particleEntity.staticNonCellCount}, вне арен ${cellEntity.orphanCellCount})"
            )
        }
        snapshot.instanceCount = written
        snapshot.filter = frameFilter

        // Граница — над клетками, под нейросвязями.
        if (debugFrame && showLinks) writeLinks()
        if (debugFrame && showBoundary) writeBoundary()

        // ==================== NEURAL LINKS ====================
        if (debugFrame && showNeuralLinks) writeNeuralLinks(overlay)
        flushOverlay(overlay)
        overlay.end()
        if (simulationData.tickCounter == instanceHashTick) instanceHashValue = hashSnapshot(written, overlay)
        snapshot.hasOverlay = debugFrame
        // Пятна феромонов — только в отладочный снимок: другим режимам их не рисовать.
        if (debugFrame) writePheromones(snapshot) else snapshot.pheromones.run { begin(); end() }

        writeSpecific(snapshot.spec, performanceInfo)
        publish(snapshot)
    }

    private var opticalCount=IntArray(0)
    private var opticalGraph:io.github.some_example_name.render.pack.OpticalLinks?=null
    private var opticalTotal=0
    private var opticalCells=0
    private var opticalChunks=1
    private val opticalCountJob=ParallelExecutor.ChunkJob{k,_->
        val from=(opticalTotal.toLong()*k/opticalChunks).toInt();val to=(opticalTotal.toLong()*(k+1)/opticalChunks).toInt()
        for(i in from until to){
            if(i>=opticalCells){opticalCount[i]=terrainLinks.count(particleEntity.nonCellList.getInt(i-opticalCells));continue}
            val base=(cellKeys[i] ushr 32).toInt()*CellEntity.MAX_LINKS_PER_CELL
            var c=0
            for(l in 0 until CellEntity.MAX_LINKS_PER_CELL){
                val other=cellEntity.cellLinks[base+l]
                if(other<0)break
                if(renderCellStamp[other]==serial)c++
            }
            opticalCount[i]=c
        }
    }
    private val opticalFillJob=ParallelExecutor.ChunkJob{k,_->
        val graph=opticalGraph!!
        val from=(opticalTotal.toLong()*k/opticalChunks).toInt();val to=(opticalTotal.toLong()*(k+1)/opticalChunks).toInt()
        for(i in from until to){
            if(i>=opticalCells){
                val p=particleEntity.nonCellList.getInt(i-opticalCells)
                graph.vertexParallel(i,terrainKey(p,i))
                terrainLinks.write(p,opticalCells,graph.neighbors,graph.offsets[i])
                continue
            }
            val cell=(cellKeys[i] ushr 32).toInt();val base=cell*CellEntity.MAX_LINKS_PER_CELL
            // Ключ — как в однопоточном обходе: записан вместе с клеткой снимка (writeCell).
            graph.vertexParallel(i,cellKeys[i])
            val organ=cellEntity.organIndex[cell]
            if(organ>=0)materialCoordinates.write(graph,i,cellEntity.organEntity.genomeIndex[organ],cellEntity.cellGenomeId[cell])
            var at=graph.offsets[i]
            for(l in 0 until CellEntity.MAX_LINKS_PER_CELL){
                val other=cellEntity.cellLinks[base+l]
                if(other<0)break
                if(renderCellStamp[other]==serial)graph.neighbors[at++]=renderOfCell[other]
            }
        }
    }

    /** Ключ вершины не-клетки: у террейна — клетка карты (не сдвигается, когда меняется число клеток перед ним). */
    private fun terrainKey(p:Int,i:Int):Long{
        val c=particleEntity.terrainCell[p]
        return if(c>=0)TERRAIN_KEY or c.toLong() else i.toLong()+1
    }

    private fun writeOpticalLinks(snapshot:RenderSnapshot,total:Int,cells:Int){
        val graph=snapshot.opticalLinks
        graph.epoch=polygonEpoch
        terrainLinks.prepare()
        graph.begin(total,linkEntity.aliveList.size*2+terrainLinks.capacity())
        val executor=threads?.executor
        if(executor!=null&&executor.workerCount>1&&total>=4096){
            // Граф изображения по воркерам: счёт соседей, префиксная сумма, запись со своего места.
            val organs=cellEntity.organEntity.aliveList
            for(k in 0 until organs.size)materialCoordinates.prepare(cellEntity.organEntity.genomeIndex[organs.getInt(k)])
            opticalGraph=graph;opticalTotal=total;opticalCells=cells
            opticalChunks=total/2048+1
            if(opticalCount.size<total)opticalCount=IntArray(total*2)
            executor.runChunks(opticalChunks,Phase.RENDER,opticalCountJob)
            var sum=0
            for(i in 0 until total){graph.offsets[i]=sum;sum+=opticalCount[i]}
            executor.runChunks(opticalChunks,Phase.RENDER,opticalFillJob)
            graph.endParallel(sum)
            return
        }
        for(i in 0 until total){
            if(i>=cells){
                val p=particleEntity.nonCellList.getInt(i-cells)
                graph.vertex(i,terrainKey(p,i))
                graph.advance(terrainLinks.write(p,cells,graph.neighbors,graph.size)-graph.size)
                continue
            }
            val cell=(cellKeys[i] ushr 32).toInt();val base=cell*CellEntity.MAX_LINKS_PER_CELL
            // Слот снимка сдвигается при рождении соседей. Ключ клетки сохраняется,
            // а поколение отличает новую клетку, занявшую освобождённый слот.
            graph.vertex(i,cellKeys[i])
            val organ=cellEntity.organIndex[cell]
            if(organ>=0)materialCoordinates.write(graph,i,cellEntity.organEntity.genomeIndex[organ],cellEntity.cellGenomeId[cell])
            for(k in 0 until CellEntity.MAX_LINKS_PER_CELL){
                val other=cellEntity.cellLinks[base+k]
                if(other<0)break
                if(renderCellStamp[other]==serial)graph.add(renderOfCell[other])
            }
        }
        graph.end()
    }

    private fun writeSpecific(spec: RenderSpecificBufferData, performanceInfo: String) = with(spec) {
        ups = simulationData.ups
        updateTime = round(1e5f / simulationData.ups) / 100f
        cellsAmount = cellEntity.lastId - cellEntity.deadStack.size + 1
        particleAmount = particleEntity.lastId - particleEntity.deadStack.size + 1
        linksAmount = linkEntity.lastId - linkEntity.deadStack.size + 1

        detailedPerformance = performanceInfo

        val cellIndex = simulationData.selectedCellIndex
        if (cellIndex != -1 && cellEntity.isAlive[cellIndex] && simulationData.selectedFrameCell?.aliveIn(cellEntity) != false) {
            writeCellInfo(spec, cellIndex)
            isCellSelected = true
            grabbedCellX = cellEntity.getX(cellIndex)
            grabbedCellY = cellEntity.getY(cellIndex)
            val particleIndex = cellEntity.getParticleIndex(cellIndex)
            grabbedPrevX = prevXOf(particleIndex, grabbedCellX!!)
            grabbedPrevY = prevYOf(particleIndex, grabbedCellY!!)
        } else {
            neuronImpulseInput = null
            neuronImpulseOutput = null
            isCellSelected = false
            grabbedCellX = null
            grabbedCellY = null
            grabbedPrevX = 0f
            grabbedPrevY = 0f
            cellName = null
        }
    }

    /**
     * Отдать снимок рендеру. Время публикации и сглаженный промежуток между снимками
     * едут вместе со снимком — по ним рендер считает долю тика (см. [tickNanos]). Время
     * здесь только для картинки: на симуляцию оно не влияет.
     */
    /** Диагностика [PROFILE_INTERPOLATION]: шаги частиц текущего снимка. */
    private var stepMax = 0f
    private var stepTeleports = 0

    /** Поток симуляции или чтение одной клетки под замком между тиками при клике. */
    internal fun writeCellInfo(spec: RenderSpecificBufferData, cellIndex: Int) = with(spec) {
        selectedCellIndex = cellEntity.cellGenomeId[cellIndex]
        neuronImpulseInput = cellEntity.neuronImpulseInput[cellIndex]
        neuronImpulseOutput = cellEntity.neuronImpulseOutput[cellIndex]
        val cellType = cellEntity.cellType[cellIndex].toInt()
        cellName = cellList[cellType].name +
            if (cellEntity.isNeural[cellIndex])
                " ${cellEntity.formulas[cellEntity.getActivationFuncType(cellIndex)].label} " +
                    "${cellEntity.getA(cellIndex)} ${cellEntity.getB(cellIndex)} ${cellEntity.getC(cellIndex)}"
            else ""
    }

    private fun countStep(dx: Float, dy: Float) {
        val step = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        if (step > stepMax) stepMax = step
        if (!CellInstanceBuffer.isMotion(dx, dy)) stepTeleports++
    }

    private fun publish(snapshot: RenderSnapshot) {
        if (PROFILE_INTERPOLATION) {
            snapshot.serial = serial
            snapshot.stepMax = stepMax
            snapshot.stepTeleports = stepTeleports
            stepMax = 0f
            stepTeleports = 0
        }
        val now = System.nanoTime()
        val target = if (simulationData.maxSpeed) 0 else simulationData.targetUPS
        if (lastPublishNanos == 0L || (target != 0 && lastTarget != target)) {
            tickNanos = NANOS_PER_SECOND / simulationData.targetUPS
        } else {
            val gap = (now - lastPublishNanos).coerceIn(tickNanos / 2, tickNanos * 2)
            tickNanos += (gap - tickNanos) / TICK_SMOOTHING
        }
        lastPublishNanos = now
        lastTarget = target

        snapshot.publishedAtNanos = now
        snapshot.tickNanos = tickNanos
        snapshots.publish()
    }

    private companion object {
        /** Поля [history] на частицу: номер снимка, ключ, x, y, dir, prevX, prevY, — — 32 байта. */
        const val HISTORY_STRIDE = 8

        /** Поля [nonCellHistory] на частицу: номер снимка, ключ, x, y, prevX, prevY — 24 байта (прошлая поза строкой истории: кускам на воркерах нужно без общих полей). */
        const val NON_CELL_STRIDE = 6

        /** Кусок записи не-клеток по воркерам: 2048 инстансов — десятки микросекунд работы. */
        const val NON_CELL_CHUNK = 2048

        /**
         * Кусок рамки не-клеток для отсечения по камере, мест списка; делит [NON_CELL_CHUNK] нацело
         * (кусок рамки не пересекает кусок воркера). 256 — отрезок ряда террейна: на мир 256×256
         * с 66 тысячами частиц террейна — около 260 рамок, их перебор на кадр ничего не стоит.
         */
        const val NON_CELL_CULL = 256

        /**
         * Кусок записи клеток — слотов арены. Замер 27.11 на A21s (копия прохода, семь потоков):
         * куски по 500 клеток — 0.46 мс на 6.5 тысячи клеток, по организму из 3250 — 1.5 мс; мельче
         * не нужно: на кусок — пара записей в массивы кусков и одна копия фигур оверлея.
         */
        const val CHUNK_CELLS = 512

        /** Кусок записи нейросвязей в оверлей, связей (на связь — десяток случайных чтений клеток и частиц). */
        const val NEURAL_CHUNK = 512

        /** Ключ вершины террейна в графе изображения: старший бит над ключами клеток (номер клетки << 32). */
        private const val TERRAIN_KEY = 1L shl 62

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Сглаживание [tickNanos]: новое измерение входит пятой частью. */
        const val TICK_SMOOTHING = 5L
    }
}

/**
 * Неподвижный слой кадра: инстансы террейна и стенки у рамки ([cells], раскладка
 * CellInstanceBuffer, прошлая поза = нынешняя) и рамки их кусков ([cull]). Собирается потоком
 * симуляции раз на мир и после не меняется — поэтому один объект свободно ходит через все
 * снимки, а GL-поток заливает его, только когда пришёл другой (RenderFrame.staticCells).
 */
class StaticCells(val cells: java.nio.ByteBuffer, val cull: io.github.some_example_name.render.pack.OpticalCullRanges)

/**
 * Всё, что рендеру нужно от одного тика: буферы инстансов в раскладке GPU и строка
 * состояния. Один из трёх слотов [TripleBuffer]; пишет поток симуляции, читает рендер.
 */
class RenderSnapshot(cellCapacity: Int, overlayCapacity: Int) {
    /**
     * Инстансы клеток в раскладке GPU (CellInstanceBuffer.write), [instanceCount] штук.
     *
     * Обычный int-массив, а не direct-буфер: поток симуляции пишет сюда каждую частицу
     * каждый тик, и запись в массив дешевле десяти вызовов DirectByteBuffer.put на
     * инстанс. В буфер для GPU массив копирует GL-поток одной копией (memcpy) — и только
     * когда пришёл новый снимок, а не каждый кадр (RenderSystem). 40 байт × 100k = 4 МБ
     * на слот.
     */
    var instances = IntArray(cellCapacity * CellInstanceBuffer.STRUCT_INTS)
        private set
    var instanceCount = 0
    var tick = -1
    var epoch = -1
    var cellCount = 0
    var cellKeys = LongArray(0)
        private set

    fun reserveCellKeys(count: Int): LongArray {
        if (cellKeys.size < count) cellKeys = LongArray(maxOf(count, cellKeys.size * 3 / 2))
        return cellKeys
    }
    val areaStorage = DebugAreaIndices()
    var areaIndices: DebugAreaIndices? = null
    // Три массива (12 байт/инстанс) растут только в режиме микроскопа.
    var hasOptics = false
    /** Рамки тел (opticalCullRanges) собраны под отсечение отладочного режима. */
    var hasCellCull = false

    /** Неподвижный слой (террейн, стенка) — в отладочном и ностальгическом; в микроскопе null: там они в [instances]. */
    var staticCells: StaticCells? = null
    var opticalGroups = IntArray(0)
        private set
    var opticalColors = IntArray(0)
        private set
    // 4 B/инстанс в каждом из трёх снимков; запись в существующем обходе.
    var opticalBirthTicks = IntArray(0)
        private set
    val opticalLinks by lazy(LazyThreadSafetyMode.NONE){io.github.some_example_name.render.pack.OpticalLinks()}
    val opticalCullRanges by lazy(LazyThreadSafetyMode.NONE){io.github.some_example_name.render.pack.OpticalCullRanges()}

    /** Массив минимум на [count] инстансов; рост ×1.5, старое содержимое не нужно. */
    fun reserveInstances(count: Int): IntArray {
        val needed = count * CellInstanceBuffer.STRUCT_INTS
        if (instances.size < needed) {
            instances = IntArray(maxOf(needed, instances.size / 2 * 3))
        }
        return instances
    }

    fun reserveOptics() {
        if(opticalGroups.size < instances.size / CellInstanceBuffer.STRUCT_INTS){
            opticalGroups = IntArray(instances.size / CellInstanceBuffer.STRUCT_INTS)
            opticalColors = IntArray(opticalGroups.size)
            opticalBirthTicks = IntArray(opticalGroups.size) { -1 }
        }
    }

    val overlay = OverlayInstanceBuffer(overlayCapacity)

    /** Феромоны отладочного режима; пустые в снимке другого режима. 16 Б на феромон. */
    val pheromones = PheromoneInstanceBuffer()

    /** Под какой фильтр отладки упакованы байты look, см. RenderBufferManager.debugFilter. */
    var filter = DebugFilter.ORIGINAL

    /** Собран ли оверлей: только в снимке, собранном под отладочный режим. */
    var hasOverlay = false

    val spec = RenderSpecificBufferData()

    /** Когда снимок отдан рендеру, System.nanoTime. */
    var publishedAtNanos = 0L

    /** Сглаженный промежуток между снимками, нс: за столько снимок «доигрывает» свой тик. */
    var tickNanos = 0L

    /** Только для PROFILE_INTERPOLATION: номер снимка, наибольший шаг частицы и число переносов. */
    var serial = 0
    var stepMax = 0f
    var stepTeleports = 0

    init {
        // Пустой, но перевёрнутый под чтение: до первой публикации рендер рисует ничто, а
        // не мусор из неинициализированной ёмкости.
        overlay.begin()
        overlay.end()
        pheromones.begin()
        pheromones.end()
    }
}

/**
 * Фигуры оверлея — в буфер снимка вместе с их концами в прошлом тике
 * (OverlayInstanceBuffer: prevFrom/prevTo). Откуда брать прошлые концы, задаёт тот, кто
 * пишет фигуру, перед вызовом палитры:
 *   - [rigid] — фигура жёстко привязана к клетке (стрелка, луч, жгутик): каждый конец
 *     переносится вместе с центром клетки и поворачивается вокруг него на её поворот;
 *   - [points] — у концов свои прошлые позиции (нейросвязь между двумя частицами);
 *   - [still] — фигура стоит (кольца феромонов).
 */
class OverlayMotionSink : DebugCellPalette.OverlaySink {

    /**
     * Фигуры в раскладке OverlayInstanceBuffer — [OverlayInstanceBuffer.STRUCT_INTS] int на
     * фигуру, [count] штук; в буфер снимка их копирует OverlayInstanceBuffer.putAll. Свой
     * массив, а не сразу direct-буфер: накопитель у каждого воркера свой (куски клеток пишутся
     * параллельно), а запись в массив на Android дешевле десяти putFloat на фигуру.
     */
    var ints = IntArray(INITIAL_FIGURES * OverlayInstanceBuffer.STRUCT_INTS)
        private set
    var count = 0
        private set

    fun reset() {
        count = 0
    }

    private var mode = STILL

    private var centerX = 0f
    private var centerY = 0f
    private var prevCenterX = 0f
    private var prevCenterY = 0f

    /** Поворот от нынешнего направления клетки к прошлому. */
    private var backCos = 1f
    private var backSin = 0f

    private var prevFromX = 0f
    private var prevFromY = 0f
    private var prevToX = 0f
    private var prevToY = 0f

    fun still() {
        mode = STILL
    }

    /**
     * Клетка сейчас в ([x], [y]) смотрит по ([dirCos], [dirSin]), а тиком раньше была в
     * ([prevX], [prevY]) и смотрела по ([prevCos], [prevSin]).
     */
    fun rigid(
        x: Float,
        y: Float,
        dirCos: Float,
        dirSin: Float,
        prevX: Float,
        prevY: Float,
        prevCos: Float,
        prevSin: Float
    ) {
        mode = RIGID
        centerX = x
        centerY = y
        prevCenterX = prevX
        prevCenterY = prevY
        // Поворот R: R · dir = prevDir. cos и sin разности углов — без тригонометрии.
        backCos = dirCos * prevCos + dirSin * prevSin
        backSin = dirCos * prevSin - dirSin * prevCos
    }

    fun points(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        mode = POINTS
        prevFromX = fromX
        prevFromY = fromY
        prevToX = toX
        prevToY = toY
    }

    override fun add(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, params: Int) {
        when (mode) {
            RIGID -> put(
                x0, y0, x1, y1, color, params,
                rigidX(x0, y0), rigidY(x0, y0), rigidX(x1, y1), rigidY(x1, y1)
            )
            POINTS -> put(x0, y0, x1, y1, color, params, prevFromX, prevFromY, prevToX, prevToY)
            else -> put(x0, y0, x1, y1, color, params, x0, y0, x1, y1)
        }
    }

    /** Поля фигуры в порядке OverlayInstanceBuffer.put. */
    private fun put(
        x0: Float, y0: Float, x1: Float, y1: Float, color: Int, params: Int,
        prevX0: Float, prevY0: Float, prevX1: Float, prevY1: Float
    ) {
        val o = count * OverlayInstanceBuffer.STRUCT_INTS
        if (o + OverlayInstanceBuffer.STRUCT_INTS > ints.size) ints = ints.copyOf(ints.size / 2 * 3 + OverlayInstanceBuffer.STRUCT_INTS)
        val a = ints
        a[o] = x0.toRawBits(); a[o + 1] = y0.toRawBits(); a[o + 2] = x1.toRawBits(); a[o + 3] = y1.toRawBits()
        a[o + 4] = color; a[o + 5] = params
        a[o + 6] = prevX0.toRawBits(); a[o + 7] = prevY0.toRawBits(); a[o + 8] = prevX1.toRawBits(); a[o + 9] = prevY1.toRawBits()
        count++
    }

    /**
     * Прошлое положение точки, привязанной к клетке: нынешнее, повёрнутое на поворот
     * клетки вокруг её центра и перенесённое к прошлому центру.
     */
    private fun rigidX(px: Float, py: Float): Float =
        prevCenterX + (px - centerX) * backCos - (py - centerY) * backSin

    private fun rigidY(px: Float, py: Float): Float =
        prevCenterY + (px - centerX) * backSin + (py - centerY) * backCos

    private companion object {
        const val STILL = 0
        const val RIGID = 1
        const val POINTS = 2

        /** Начальная ёмкость накопителя, фигур; растёт ×1.5. */
        const val INITIAL_FIGURES = 1024
    }
}

data class RenderSpecificBufferData(
    var ups: Int = 0,
    var updateTime: Float = 0f,
    var cellsAmount: Int = 0,
    var particleAmount: Int = 0,
    var linksAmount: Int = 0,
    var neuronImpulseInput: Float? = null,
    var neuronImpulseOutput: Float? = null,
    var isCellSelected: Boolean = false,
    var grabbedCellX: Float? = null,
    var grabbedCellY: Float? = null,
    /** Где выбранная клетка была тиком раньше: подсветка и камера едут вместе с её интерполяцией. */
    var grabbedPrevX: Float = 0f,
    var grabbedPrevY: Float = 0f,
    var cellName: String? = null,
    var selectedCellIndex: Int = -1,
    var detailedPerformance: String = ""          // ← добавлено
)
