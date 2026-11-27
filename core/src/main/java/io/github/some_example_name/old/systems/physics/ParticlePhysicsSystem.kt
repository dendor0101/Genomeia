package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.PROFILE_COUNTERS
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.simulation.SimCounters
import io.github.some_example_name.old.systems.simulation.SimulationData

class ParticlePhysicsSystem(
    val entity: ParticleEntity,
    val gridManager: GridManager,
    val substrateSettings: SubstrateSettings,
    val worldCommandsManager: WorldCommandsManager,
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val cellList: List<Cell>,
    val substancesEntity: SubstancesEntity,
    val pheromonesManager: PheromonesManager,
    val collisionManager: CollisionManager
) {

    /**
     * Ядро широкой фазы.
     *
     * Ключевые моменты по производительности:
     *  - НЕТ аллокаций: частицы клетки читаются прямо из CSR-массива gridManager.particleIdx,
     *    без copyOfRange (раньше это было ~150k аллокаций массивов за тик).
     *  - НЕТ разреженной сетки: частицы лежат подряд (34 КБ), а не в 256 КБ слотов по 4
     *    на клетку, из которых заняты единицы. Меньше кэш-линий на тот же объём данных.
     *  - НЕТ ветки на переполнение клетки и походов в хэш-мапу хвостов: путь всегда один.
     *  - НЕТ повторного чтения границы клетки: правая граница предыдущей клетки — это
     *    левая граница следующей, поэтому на клетку приходится одно чтение cellStart.
     *  - НЕТ целочисленного деления на клетку: x/y ведутся инкрементально
     *    (раньше было i % gridWidth и i / gridWidth = 2 idiv на каждую из 16k клеток).
     *  - НЕТ раскладки частиц по стекам чанков: фаза больше ничего не собирает для
     *    движения. Список для moveParticle берётся из aliveList (см.
     *    SimulationSystem.arrangementOfPositionsInTheGrid), потому что движению
     *    пространственная изоляция не нужна, а привязка к сетке молча выключала
     *    движение у частиц, которых в сетке нет.
     *  - Ссылки на массивы подняты в локальные переменные: поля cellStart/particleIdx
     *    объявлены как var, поэтому JIT обязан перечитывать их после каждой записи в память.
     *

     * Прямой обход сетки безопасен, потому что в этой фазе сетка не мутирует:
     * repulse и onContact пишут только в vx/vy/energy/radius и в отложенные команды.
     */
    fun processGridRangePhysics(start: Int, end: Int, threadId: Int) {
        val starts = gridManager.cellStart
        val indices = gridManager.particleIdx
        val width = gridManager.gridWidth

        // Двойной цикл по ряду/столбцу вместо i % width и i / width на каждую клетку.
        // gridWidth — это var, поэтому JIT не может свернуть деление в сдвиг: раньше на
        // каждую из ~16k клеток приходилось два настоящих idiv (20-40 циклов, divider не
        // пайплайнится и блокирует порт). Теперь деление считается один раз на весь чанк,
        // дальше только инкременты, и заодно исчезла проверка "x == width" на клетку.
        var cellIndex = start
        var y = start / width
        var x = start - y * width

        // Накопитель пар-кандидатов держится в регистре и уезжает в разделяемый массив
        // один раз в конце чанка — тот же приём, что и со счётчиком стека.
        var pairCandidates = 0L

        // Левая граница первой клетки чанка. Дальше она не перечитывается: правая граница
        // клетки — это левая граница следующей, а клетки внутри чанка идут подряд.
        var from = starts[cellIndex]

        while (cellIndex < end) {
            // Конец текущего ряда сетки. Внутри ряда индексы клеток идут подряд, так что
            // внутренний цикл — это линейный проход по cellStart и по particleIdx,
            // идеальный для аппаратного префетчера.
            var rowEnd = (y + 1) * width
            if (rowEnd > end) rowEnd = end

            while (cellIndex < rowEnd) {
                val to = starts[cellIndex + 1]

                if (to > from) {
                    if (PROFILE_COUNTERS) {
                        // Ровно та же арифметика, что делают циклы ниже: C(n,2) пар внутри
                        // клетки плюс n * (сколько частиц в просматриваемых соседях).
                        // Считается по границам клеток, а не инкрементом в repulse:
                        // на клетку это ~4 чтения из cellStart, который здесь и так
                        // стримится через кэш, вместо миллиона инкрементов за тик.
                        val n = (to - from).toLong()
                        pairCandidates += n * (n - 1) / 2 + n * neighborCandidateCount(x, y)
                    }

                    // Пары внутри одной клетки сетки: каждая пара ровно один раз (i < j).
                    // Частицы клетки лежат в particleIdx подряд, ограничения на их
                    // количество больше нет.
                    for (i in from until to) {
                        val particleA = indices[i]
                        for (j in i + 1 until to) {
                            collisionManager.repulse(particleA, indices[j], threadId)
                        }
                    }

                    // Соседние клетки.
                    for (i in from until to) {
                        processNeighborsCellsCollision(indices[i], x, y, threadId)
                    }

                    // Неподвижный слой (террейн, стенка у рамки) — со стороны подвижной частицы.
                    if (gridManager.staticCount > 0) {
                        for (i in from until to) processStaticNeighbors(indices[i], x, y, threadId)
                    }
                }

                from = to
                cellIndex++
                x++
            }

            x = 0
            y++
        }


        if (PROFILE_COUNTERS) {
            SimCounters.add(threadId, SimCounters.PAIR_CANDIDATES, pairCandidates)
        }
    }

    /**
     * Сколько частиц лежит в клетках, которые просматривает [processNeighborsCellsCollision]
     * для клетки (x, y): верхний отрезок из трёх клеток плюс одна справа.
     *
     * Логика клампинга границ повторяет forEachParticleInRowSegment и forEachParticleAt
     * один в один — если там что-то поменяется, здесь надо поменять тоже, иначе счётчик
     * начнёт врать. Дублирование сознательное: звать сами обходы ради счёта означало бы
     * лишний проход по particleIdx, а так это чистая арифметика по cellStart.
     */
    private fun neighborCandidateCount(gridX: Int, gridY: Int): Long {
        val starts = gridManager.cellStart
        val width = gridManager.gridWidth
        var total = 0L

        val upY = gridY + 1
        if (upY < gridManager.gridHeight) {
            val from = if (gridX - 1 < 0) 0 else gridX - 1
            val to = if (gridX + 1 >= width) width - 1 else gridX + 1
            if (from <= to) {
                val rowBase = upY * width
                total += (starts[rowBase + to + 1] - starts[rowBase + from]).toLong()
            }
        }

        val rightX = gridX + 1
        if (rightX < width) {
            val right = gridY * width + rightX
            total += (starts[right + 1] - starts[right]).toLong()
        }

        return total
    }

    /**
     * Полуобход соседей: три клетки сверху (x-1, x, x+1) и одна справа.
     * Так каждая пара соседних клеток обрабатывается ровно один раз.
     *
     * Верхний ряд идёт одним отрезком — индексы клеток там последовательные,
     * это дешевле трёх независимых обращений и дружелюбнее к префетчеру.
     */
    fun processNeighborsCellsCollision(cellId: Int, gridX: Int, gridY: Int, threadId: Int = 0) {
        gridManager.forEachParticleInRowSegment(gridY + 1, gridX - 1, gridX + 1) { id ->
            collisionManager.repulse(cellId, id, threadId)
        }
        gridManager.forEachParticleAt(gridX + 1, gridY) { id ->
            collisionManager.repulse(cellId, id, threadId)
        }
    }

    /**
     * Подвижная частица против неподвижного слоя сетки (GridManager.buildStatic) в клетках
     * 3×3 вокруг своей: пары «подвижная — неподвижная» видны только отсюда, каждая ровно раз,
     * а пар «неподвижная — неподвижная» нет вовсе (у них ничего не бывает: сливаются только
     * субстанции). Пишет repulse только на стороне подвижной частицы (касание её клетки), так
     * что чётные и нечётные куски сетки по-прежнему не гоняются.
     *
     * Толща террейна в мире физики не живёт (StaticTerrain: physicsIndex = −1) и снаружи
     * недостижима — её пропускаем: у неё и радиус больше предела сетки (WorldTerrainManager).
     */
    private fun processStaticNeighbors(particle: Int, gridX: Int, gridY: Int, threadId: Int) {
        val physicsIndex = entity.physicsIndex
        for (dy in -1..1) {
            gridManager.forEachStaticInRowSegment(gridY + dy, gridX - 1, gridX + 1) { s ->
                if (physicsIndex[s] >= 0) collisionManager.repulse(particle, s, threadId)
            }
        }
    }

    /**
     * Оставлено для совместимости: попарный перебор по готовому массиву индексов.
     * В горячем пути не используется — там работает processGridRangePhysics.
     */
    fun processCollisionsInTheSameCell(cells: IntArray, threadId: Int = 0) {
        processCollisionsInTheSameCell(cells, cells.size, threadId)
    }

    fun processCollisionsInTheSameCell(cells: IntArray, count: Int, threadId: Int = 0) {
        for (i in 0 until count) {
            val particleA = cells[i]
            for (j in i + 1 until count) {
                collisionManager.repulse(particleA, cells[j], threadId)
            }
        }
    }

}
