package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.PROFILE_COUNTERS
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer.threadManager
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.SimCounters
import io.github.some_example_name.old.systems.simulation.SimulationData

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager,
    val diContext: DIContext,
    val simulationData: SimulationData
) {

    companion object {
        /** Номер куска «организм целиком» в работах фазы связей. */
        private const val WHOLE = -1
    }

    /**
     * ОБХОД ИДЁТ ПО АРЕНАМ, а не по спискам чётности.
     *
     * Списки хранили индексы связей в порядке регистрации, то есть обход прыгал по массивам
     * LinkEntity как попало — и запечённый RCM-порядок не давал ровно ничего: он определял,
     * ГДЕ связь лежит, но не в каком порядке её читают. Замер это и показал: локальность
     * улучшили, время фазы не изменилось.
     *
     * Диапазон арены обходится подряд, а внутри него связи уложены по возрастанию
     * min(слот концов) — значит подряд идущие связи трогают соседние клетки, и окно
     * горячих данных сжимается до ширины ленты графа вместо всего тела.
     *
     * ПРО ГОНКИ
     * ---------
     * Гонка в этой фазе возможна только между связями с ОБЩЕЙ КЛЕТКОЙ, а общие клетки
     * бывают исключительно внутри одного организма. Организм целиком достаётся одному
     * воркеру, значит гонок нет — и это более прямое обоснование, чем прежняя приписка
     * по якорю: она давала то же свойство окольным путём, через геометрию чанков.
     *
     * КРУПНОЕ ТЕЛО РЕЖЕТСЯ НА КУСКИ
     * ----------------------------
     * Организм целиком — одна работа, пока он мелкий. Крупный режется по слотам клеток на
     * куски (LayoutChunks), и фаза идёт двумя стадиями: сначала чётные куски всех крупных
     * тел вместе с мелкими телами целиком, потом нечётные. Почему чётные куски одного тела
     * не гоняются между собой и почему результат не зависит от воркеров — см. LayoutChunks.
     * Цена — один лишний барьер за тик, и только когда в мире есть крупное тело.
     *
     * ГРАНИЦА ПРИМЕНИМОСТИ
     * --------------------
     * Обходятся ТОЛЬКО связи организмов с аренами. Связь между клетками без организма
     * (organIndex == -1) в арену не попадёт и обсчитана не будет. Сейчас таких клеток в
     * мире нет: организм с ареной заводится раньше зиготы, см. OrganismSpawner. Появятся
     * бесхозные клетки со связями — этот путь придётся вернуть.
     */
    fun iterateLinksInParallel() {
        planWorks()

        // Работ больше, чем воркеров, и это нормально: раздача динамическая, а организмы
        // разного размера. Номер работы и номер воркера здесь РАЗНЫЕ вещи — в буферы
        // команд индексируемся вторым, иначе слот №9 писал бы за границу массива.
        threadManager.runWorkStage(evenCount, Phase.LINKS) { work, workerId ->
            processWork(evenOrgan[work], evenChunk[work], workerId)
        }
        // Вторая стадия — свой номер в WorkOrder, поэтому разрывы связей из неё применятся
        // после разрывов первой, кто бы что ни считал.
        if (oddCount > 0) {
            threadManager.runWorkStage(oddCount, Phase.LINKS) { work, workerId ->
                processWork(oddOrgan[work], oddChunk[work], workerId)
            }
        }
    }

    // Работы двух стадий: организм и номер куска, WHOLE — организм целиком. Пересобираются
    // каждый тик в однопоточной части (организмы рождаются и умирают), массивы растут только
    // когда работ стало больше, чем было когда-либо.
    private var evenOrgan = IntArray(64)
    private var evenChunk = IntArray(64)
    private var evenCount = 0
    private var oddOrgan = IntArray(64)
    private var oddChunk = IntArray(64)
    private var oddCount = 0

    private fun planWorks() {
        val organEntity = linkEntity.organEntity
        val organs = organEntity.aliveList
        evenCount = 0
        oddCount = 0
        for (i in 0 until organs.size) {
            val organIndex = organs.getInt(i)
            if (!organEntity.hasArena(organIndex)) continue
            val chunks = splittableChunks(organIndex)
            if (chunks == null) {
                addEven(organIndex, WHOLE)
                continue
            }
            for (chunk in 0 until chunks.count) {
                if (chunk % 2 == 0) addEven(organIndex, chunk) else addOdd(organIndex, chunk)
            }
        }
    }

    /**
     * Нарезка организма, если тело достаточно крупное, иначе null — тогда оно считается
     * целиком. Ширина ленты гарантирована: все клетки и связи лежат в своих слотах раскладки
     * (OrganEntity.takeCellSlot), сверх неё в арене ничего не бывает.
     */
    private fun splittableChunks(organIndex: Int): LayoutChunks? {
        val organEntity = linkEntity.organEntity
        val layout = organEntity.arenaLayout[organIndex] ?: return null
        val chunks = layout.chunks
        if (chunks.count < 2) return null
        return chunks
    }

    private fun addEven(organIndex: Int, chunk: Int) {
        if (evenCount == evenOrgan.size) {
            evenOrgan = evenOrgan.copyOf(evenCount * 2)
            evenChunk = evenChunk.copyOf(evenCount * 2)
        }
        evenOrgan[evenCount] = organIndex
        evenChunk[evenCount] = chunk
        evenCount++
    }

    private fun addOdd(organIndex: Int, chunk: Int) {
        if (oddCount == oddOrgan.size) {
            oddOrgan = oddOrgan.copyOf(oddCount * 2)
            oddChunk = oddChunk.copyOf(oddCount * 2)
        }
        oddOrgan[oddCount] = organIndex
        oddChunk[oddCount] = chunk
        oddCount++
    }

    private fun processWork(organIndex: Int, chunk: Int, workerId: Int) {
        val organEntity = linkEntity.organEntity
        val base = organEntity.linkArenaBase[organIndex]
        val from: Int
        val to: Int
        if (chunk == WHOLE) {
            from = base
            to = organEntity.linkArenaEnd(organIndex)
        } else {
            val chunks = organEntity.arenaLayout[organIndex]!!.chunks
            from = base + chunks.linkStart[chunk]
            to = base + chunks.linkStart[chunk + 1]
            if (DEBUG_CHECKS) verifyChunkWindow(organIndex, chunk, chunks, from, to)
        }

        val alive = linkEntity.isAlive
        for (linkIndex in from until to) {
            // Дырки в арене: связь умерла, а слот ещё не переиспользован.
            if (!alive[linkIndex]) continue
            processLink(linkIndex, workerId)
        }
    }

    /**
     * Связи куска трогают только клетки своего окна [k·W, (k+1)·W + band) — на этом держится
     * отсутствие гонок. Нарушиться может, если связь легла в слот не по раскладке.
     */
    private fun verifyChunkWindow(organIndex: Int, chunk: Int, chunks: LayoutChunks, from: Int, to: Int) {
        val organEntity = linkEntity.organEntity
        val cellBase = organEntity.cellArenaBase[organIndex]
        val low = chunk * chunks.slotsPerChunk
        val high = low + chunks.slotsPerChunk + chunks.band
        for (linkIndex in from until to) {
            if (!linkEntity.isAlive[linkIndex]) continue
            val a = linkEntity.links1[linkIndex] - cellBase
            val b = linkEntity.links2[linkIndex] - cellBase
            if (a < low || b < low || a >= high || b >= high) {
                throw IllegalStateException(
                    "связь $linkIndex организма $organIndex куска $chunk касается слотов $a и $b " +
                        "вне окна [$low, $high) — чётные куски могут гоняться"
                )
            }
        }
    }

    /**
     * Отчёт о локальности обхода связей. Только под DEBUG_CHECKS, вызывать раз в окно
     * профиля — стоит O(связей).
     *
     * ЧТО МЕРЯЕТ И ЗАЧЕМ
     * ------------------
     * Ширина ленты — max |слотA - слотB| по связям организма, где слот это смещение клетки
     * внутри её арены. Это и есть РАЗМЕР ГОРЯЧЕГО ОКНА: обходя связи по порядку, поток
     * держит в кэше клетки в пределах ленты. Средняя лента показывает типичный случай,
     * максимальная — худший.
     *
     * Ради чего это нужно видеть: «RCM применился» и «RCM дал эффект» — разные утверждения.
     * Раскладка может быть запечена, но если обход идёт не по ней (как было со списками
     * чётности) или геном сохранён до появления запекания, лента окажется порядка размера
     * тела, и никакого выигрыша не будет. По одному лишь времени фазы это неразличимо,
     * а по ленте видно сразу.
     *
     * Ориентир: для плоского тела RCM даёт ленту порядка sqrt(числа клеток). При 947
     * клетках это ~30-60. Лента в сотни — раскладка не применилась.
     */
    fun describeLinkLocality(): String {
        val organEntity = linkEntity.organEntity
        val organs = organEntity.aliveList
        val sb = StringBuilder("=== LINK LOCALITY ===\n")

        for (i in 0 until organs.size) {
            val organIndex = organs.getInt(i)
            if (!organEntity.hasArena(organIndex)) {
                sb.append("organ ").append(organIndex).append(": арены нет\n")
                continue
            }

            val cellBase = organEntity.cellArenaBase[organIndex]
            val from = organEntity.linkArenaBase[organIndex]
            val to = organEntity.linkArenaEnd(organIndex)

            // Считаем ЖИВЫЕ клетки: арена с первого тика размером с раскладку, и её размер
            // ничего не говорит о том, сколько тела уже выросло.
            var aliveCells = 0
            for (cellIndex in cellBase until organEntity.cellArenaEnd(organIndex)) {
                if (cellEntity.isAlive[cellIndex]) aliveCells++
            }

            var count = 0
            var maxBand = 0
            var sumBand = 0L
            for (linkIndex in from until to) {
                if (!linkEntity.isAlive[linkIndex]) continue
                val cellA = linkEntity.links1[linkIndex]
                val cellB = linkEntity.links2[linkIndex]
                if (cellA == -1 || cellB == -1) continue

                val band = kotlin.math.abs((cellA - cellBase) - (cellB - cellBase))
                if (band > maxBand) maxBand = band
                sumBand += band
                count++
            }

            val layout = organEntity.arenaLayout[organIndex]
            sb.append("organ ").append(organIndex)
                .append(": RCM=").append(if (layout == null) "НЕТ" else "да")
            if (layout != null) {
                sb.append(" (клеток в раскладке ").append(layout.cellsInLayout)
                    .append(", связей ").append(layout.linksInLayout).append(')')
            }
            sb.append(", живых связей ").append(count)
                .append(", лента avg ").append(if (count == 0) 0 else (sumBand / count))
                .append(" max ").append(maxBand)
                .append(", живых клеток ").append(aliveCells)
                .append(" из ").append(organEntity.cellArenaCapacity[organIndex])
                .append(", стадия ").append(organEntity.stage[organIndex])
                .append('/').append(organEntity.genomeSize[organIndex])
                .append(if (organEntity.alreadyGrownUp[organIndex]) " (вырос)" else " (растёт)")
                .append(", ждём делений ").append(organEntity.divideCounterThisStage[organIndex])
                .append(" мутаций ").append(organEntity.mutateCounterThisStage[organIndex])
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * Обработка одной связи. Вызывается для каждой связи каждый тик, из нескольких потоков.
     *
     * Раньше тело метода жило в трёх вложенных with (particleEntity, cellEntity, linkEntity),
     * из-за чего каждое обращение вида x[i] или links1[i] превращалось в чтение поля
     * соответствующего объекта плюс чтение элемента. Поля сущностей — var (их
     * переоткрывают при росте), а в середине метода стоят вызовы transportEnergy,
     * processCellAngle и reinitParentIndex, которые для JIT могут записать что угодно
     * в любое поле, поэтому после каждого вызова все ссылки на массивы приходилось
     * перечитывать. Теперь массивы поднимаются в локальные переменные (регистры) один раз.
     *
     * Инвариант тот же, что и в repulse: в параллельной фазе массивы не пересоздаются,
     * меняются только элементы, а рост сущностей идёт в однопоточной фазе применения команд.
     */
    fun processLink(linkIndex: Int, threadId: Int = 0) {
        val linkEntity = linkEntity
        val cellEntity = cellEntity
        val particleEntity = particleEntity

        val linkCellA = linkEntity.links1[linkIndex]
        val linkCellB = linkEntity.links2[linkIndex]


        // Проверки "жива ли клетка на конце связи" здесь больше нет.
        //
        // Она стоила шесть чтений из шести разных массивов (isAlive дважды, generation
        // клетки дважды, linksGeneration1/2 связи) на КАЖДУЮ связь КАЖДЫЙ тик — то есть
        // примерно треть всех обращений к памяти в этом методе, — и вся эта работа была
        // пропорциональна числу связей в мире, хотя само событие пропорционально числу
        // смертей. Теперь связи умирающей клетки снимаются сразу, в LinkEntity.detachAllLinks
        // из обработчика DELETE_CELL, и до этого метода мёртвая связь просто не доходит:
        // detachAllLinks убирает её из списков слотов до начала следующего тика.
        //
        // Инвариант, на котором это держится: единственный путь смерти клетки — команда
        // DELETE_CELL, а она однопоточная и идёт до фазы связей следующего тика.
        //
        // Если инвариант когда-нибудь нарушат (появится ещё один путь смерти, который не
        // зовёт detachAllLinks), симуляция начнёт молча считать физику по мёртвым индексам.
        // Поэтому под DEBUG_CHECKS он проверяется явно — включать при любой правке путей
        // жизненного цикла клетки.
        if (DEBUG_CHECKS) {
            val isAlive = cellEntity.isAlive
            if (!isAlive[linkCellA] || !isAlive[linkCellB] ||
                cellEntity.getGeneration(linkCellA) != linkEntity.linksGeneration1[linkIndex] ||
                cellEntity.getGeneration(linkCellB) != linkEntity.linksGeneration2[linkIndex]
            ) {
                throw IllegalStateException(
                    "живая связь $linkIndex ссылается на мёртвую клетку: " +
                        "A=$linkCellA B=$linkCellB — detachAllLinks не был вызван"
                )
            }

            // Обе клетки связи обязаны принадлежать ОДНОМУ организму.
            //
            // Точное условие отсутствия гонки, без допущений о геометрии.
            //
            // Организм целиком достаётся одному воркеру (обход идёт по его арене),
            // поэтому конфликтовать могут только линки с общей клеткой, а общие клетки
            // бывают исключительно внутри организма. Единственный способ это сломать —
            // связать клетки разных организмов.
            //
            // Все пути создания связей резолвят вторую клетку внутри одного organIndex,
            // а морфогенез вдобавок фильтрует по нему явно; проверка ловит любой путь,
            // который это обошёл.
            if (cellEntity.organIndex[linkCellA] != cellEntity.organIndex[linkCellB]) {
                throw IllegalStateException(
                    "связь $linkIndex соединяет клетки разных организмов: " +
                        "A=$linkCellA organIndex=${cellEntity.organIndex[linkCellA]}, " +
                        "B=$linkCellB organIndex=${cellEntity.organIndex[linkCellB]} " +
                        "— их связи обошёл бы не тот воркер, возможна гонка на vx/vy"
                )
            }
        }

        // Денормализовать индексы частиц в саму связь пробовали — стало медленнее,
        // подробности в комментарии к LinkEntity.links1. Эти два обращения случайные,
        // но particleIndexes всего ~780 КБ и живёт в L2/L3, так что они дёшевы.
        val linkParticleA = cellEntity.getParticleIndex(linkCellA)
        val linkParticleB = cellEntity.getParticleIndex(linkCellB)
//        println("linkIndex: $linkIndex: cell1: $linkCellA cell2: $linkCellB particle1: $linkParticleA particle2: $linkParticleB")

        // Дубль проверки из LinkEntity.addLink, но уже по факту расчёта: ловит связи,
        // испортившиеся ПОСЛЕ создания.
        //
        // Ровно (0, 0) — точный признак удалённой частицы: ParticleEntity.deleteParticle
        // обнуляет x и y, а живая частица туда попасть не может, processWorldBorders
        // зажимает координаты в [radius, gridSize - radius] при ненулевом радиусе.
        if (DEBUG_CHECKS) {
            val particles = particleEntity
            if (linkParticleA == -1 || linkParticleB == -1) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на клетку без частицы: " +
                        "A=$linkCellA particle=$linkParticleA, B=$linkCellB particle=$linkParticleB"
                )
            }
            if (!particles.isAlive[linkParticleA] || !particles.isAlive[linkParticleB]) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на мёртвую частицу: " +
                        "A=$linkCellA particle=$linkParticleA alive=${particles.isAlive[linkParticleA]}, " +
                        "B=$linkCellB particle=$linkParticleB alive=${particles.isAlive[linkParticleB]}"
                )
            }
            if ((particles.x[linkParticleA] == 0f && particles.y[linkParticleA] == 0f) ||
                (particles.x[linkParticleB] == 0f && particles.y[linkParticleB] == 0f)
            ) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на частицу в нулевой координате: " +
                        "A=$linkCellA particle=$linkParticleA " +
                        "pos=(${particles.x[linkParticleA]}, ${particles.y[linkParticleA]}), " +
                        "B=$linkCellB particle=$linkParticleB " +
                        "pos=(${particles.x[linkParticleB]}, ${particles.y[linkParticleB]})"
                )
            }
        }

        // Силы связи (пружина, демпфер), разрыв по длине и площадь треугольников считает
        // физика-библиотека (physics/bridge/WorldPhysics, этап B1). Здесь — логика игры по
        // связям: энергия, угол клетки к родителю, сам родитель.
        cellSystem.transportEnergy(linkCellA, linkCellB)

        //TODO можно попробовать сделать отдельную сущность под углы, там будут только пары родитель - потомок
        val parentIndices = cellEntity.parentIndex
        val parentCellA = parentIndices[linkCellA]
        val parentCellB = parentIndices[linkCellB]
        if (linkCellA == parentCellB) {
            if (PROFILE_COUNTERS) SimCounters.increment(threadId, SimCounters.LINK_ANGLES)
            cellSystem.processCellAngle(linkCellB, linkCellA)
        }
        if (linkCellB == parentCellA) {
            if (PROFILE_COUNTERS) SimCounters.increment(threadId, SimCounters.LINK_ANGLES)
            cellSystem.processCellAngle(linkCellA, linkCellB)
        }

        //TODO сделать инкрементально
        //Элементы читаются заново (а не берутся из parentCellA/B): reinitParentIndex
        //мог только что записать сюда значение.
        if (parentIndices[linkCellA] == -1) linkEntity.reinitParentIndex(linkCellA, linkCellB)
        if (parentIndices[linkCellB] == -1) linkEntity.reinitParentIndex(linkCellB, linkCellA)

    }
}
