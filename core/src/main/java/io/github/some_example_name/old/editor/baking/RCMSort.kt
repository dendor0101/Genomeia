package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap

/**
 * Запекание раскладки организма: обратный алгоритм Катхилла-Макки по графу физических связей.
 *
 * ЧТО МИНИМИЗИРУЕТСЯ
 * ------------------
 * Ширина ленты графа — максимальное |slotA - slotB| по всем связям. Это не абстрактная
 * метрика, а прямой размер горячего окна фазы связей: обходя связи по порядку, поток держит
 * в кэше ровно те клетки, чьи слоты попадают в ленту. При ширине 40 и 16 байтах на клетку
 * это 640 байт, то есть десяток кэш-линий, независимо от того, 150 в организме клеток
 * или 1500.
 *
 * ПОЧЕМУ ИМЕННО RCM, А НЕ ПОРЯДОК РОСТА
 * -------------------------------------
 * Порядок роста — это почти обход в ширину от зиготы, то есть почти сам Катхилл-Макки:
 * на компактных телах он уже неплох, и большого выигрыша ждать не стоит. Ценность RCM
 * в другом — он страхует от вырожденных геномов. Тело, растущее «в длину» (отросток за
 * отростком), даёт обход в глубину и ширину ленты порядка n; окно перестаёт помещаться
 * в кэш, и фаза связей деградирует тем сильнее, чем крупнее организм. RCM возвращает
 * такие случаи к O(sqrt(n)).
 *
 * ПОЧЕМУ ОБРАТНЫЙ
 * ---------------
 * Разворот порядка Катхилла-Макки не меняет ширину ленты, но заметно уменьшает профиль
 * (сумму длин строк). Стоит он одно вычитание, поэтому берётся всегда.
 *
 * ГДЕ ЭТО ВЫПОЛНЯЕТСЯ
 * -------------------
 * Только в редакторе генома, в момент сохранения, когда организм уже выращен целиком.
 * В симуляции не вызывается никогда — там раскладка просто читается из генома. Поэтому
 * сложность и аллокации здесь значения не имеют, и код написан на понятность.
 */
class RCMSort(
    val linkEntity: LinkEntity,
    val cellEntity: CellEntity,
    val neuralLinkEntity: NeuralLinkEntity,
) {

    /**
     * Строит раскладку по текущему содержимому сущностей редактора.
     *
     * [organIndex] — организм, который надо запечь. В редакторе он всегда 0
     * (см. WorldCommandsManager: у зиготы в режиме редактора organIndex = 0).
     *
     * Возвращает null, если печь нечего: тело пустое. Пустая раскладка и отсутствие
     * раскладки — разные вещи, и первое не должно выглядеть как «запечено».
     */
    fun bake(organIndex: Int = 0): BakedLayout? {
        val cellGenomeIds = collectCellGenomeIds(organIndex)
        if (cellGenomeIds.isEmpty()) return null

        // Плотная локальная нумерация вершин: cellGenomeId разрежен (у него дырки от
        // мутаций и удалённых клеток), а всем массивам алгоритма нужен 0..n-1.
        val vertexOfGenomeId = Int2IntOpenHashMap(cellGenomeIds.size).apply {
            defaultReturnValue(-1)
            cellGenomeIds.forEachIndexed { vertex, genomeId -> put(genomeId, vertex) }
        }

        val adjacency = buildAdjacency(organIndex, cellGenomeIds.size, vertexOfGenomeId)
        val order = reverseCuthillMcKee(adjacency)

        // order[slot] = вершина, то есть позиция в списке задаёт слот.
        val cellIdsInSlotOrder = IntArray(order.size) { cellGenomeIds[order[it]] }

        // Обратная карта нужна прямо здесь: связи сортируются по слотам своих концов.
        val slotOfGenomeId = Int2IntOpenHashMap(cellIdsInSlotOrder.size).apply {
            defaultReturnValue(-1)
            cellIdsInSlotOrder.forEachIndexed { slot, genomeId -> put(genomeId, slot) }
        }

        val cells = cellsInSlotOrder(organIndex, cellIdsInSlotOrder)
        val boneType = cellEntity.cellList.requireIndex("bone")

        return BakedLayout(
            cellGenomeIdsInSlotOrder = cellIdsInSlotOrder.toList(),
            linkPairsInSlotOrder = orderLinks(
                organIndex = organIndex,
                slotOfGenomeId = slotOfGenomeId,
                endpoints = ::physicalLinkEndpoints,
                aliveIndices = linkEntity.aliveList.toIntArray()
            ),
            neuralLinkPairsInSlotOrder = orderLinks(
                organIndex = organIndex,
                slotOfGenomeId = slotOfGenomeId,
                endpoints = ::neuralLinkEndpoints,
                aliveIndices = neuralLinkEntity.aliveList.toIntArray()
            ),
            // Поза покоя для физики-библиотеки (BakedLayout.restX): тело в редакторе выращено
            // целиком, координаты — геометрия раскладки клеток, без физики.
            restX = cells.map { cellEntity.getX(it) },
            restY = cells.map { cellEntity.getY(it) },
            restRadius = cells.map { cellEntity.getRadius(it) },
            restBone = cells.map { cellEntity.cellType[it].toInt() == boneType }
        // Треугольники — по связям раскладки и позе покоя, тем же перебором, что и допекание
        // старых раскладок (trianglesOfLinks): запечённое и допечённое не расходятся.
        ).withTrianglesFromLinks()
    }

    /** Клетки организма [organIndex] редактора в порядке слотов. */
    private fun cellsInSlotOrder(organIndex: Int, cellIdsInSlotOrder: IntArray): IntArray {
        val cellOfGenomeId = Int2IntOpenHashMap(cellIdsInSlotOrder.size).apply {
            defaultReturnValue(-1)
            val alive = cellEntity.aliveList
            for (i in 0 until alive.size) {
                val cellIndex = alive.getInt(i)
                if (cellEntity.organIndex[cellIndex] != organIndex) continue
                put(cellEntity.cellGenomeId[cellIndex], cellIndex)
            }
        }
        return IntArray(cellIdsInSlotOrder.size) { cellOfGenomeId.get(cellIdsInSlotOrder[it]) }
    }

    // ===================================================================================
    // СБОР ГРАФА
    // ===================================================================================

    private fun collectCellGenomeIds(organIndex: Int): IntArray {
        val alive = cellEntity.aliveList
        val result = IntArray(alive.size)
        var count = 0
        for (i in 0 until alive.size) {
            val cellIndex = alive.getInt(i)
            if (cellEntity.organIndex[cellIndex] != organIndex) continue
            val genomeId = cellEntity.cellGenomeId[cellIndex]
            // Клетка без идентификатора в геноме не воспроизводима, печь её бессмысленно.
            if (genomeId == -1) continue
            result[count++] = genomeId
        }
        return result.copyOf(count)
    }

    /**
     * Списки смежности по вершинам. Строятся по ФИЗИЧЕСКИМ связям: именно они дают нагрузку
     * фазы связей, и именно их ширину ленты мы минимизируем. Нейросвязи в граф не входят —
     * они лишь укладываются потом в порядке, который задали физические.
     */
    private fun buildAdjacency(
        organIndex: Int,
        vertexCount: Int,
        vertexOfGenomeId: Int2IntOpenHashMap
    ): Array<MutableList<Int>> {
        val adjacency = Array(vertexCount) { mutableListOf<Int>() }
        val alive = linkEntity.aliveList

        for (i in 0 until alive.size) {
            val linkIndex = alive.getInt(i)
            val pair = physicalLinkEndpoints(linkIndex, organIndex) ?: continue

            val a = vertexOfGenomeId.get(pair.first)
            val b = vertexOfGenomeId.get(pair.second)
            if (a == -1 || b == -1 || a == b) continue

            // Дубликаты связей между одной парой встречаются (пружина плюс дополнительная),
            // а алгоритму нужен простой граф: иначе сосед попадёт в очередь дважды.
            if (!adjacency[a].contains(b)) adjacency[a].add(b)
            if (!adjacency[b].contains(a)) adjacency[b].add(a)
        }
        return adjacency
    }

    /** Пара cellGenomeId концов физической связи, или null если связь не из этого организма. */
    private fun physicalLinkEndpoints(linkIndex: Int, organIndex: Int): Pair<Int, Int>? {
        val cellA = linkEntity.links1[linkIndex]
        val cellB = linkEntity.links2[linkIndex]
        return endpointsOf(cellA, cellB, organIndex)
    }

    private fun neuralLinkEndpoints(linkIndex: Int, organIndex: Int): Pair<Int, Int>? {
        val cellA = neuralLinkEntity.links1[linkIndex]
        val cellB = neuralLinkEntity.links2[linkIndex]
        return endpointsOf(cellA, cellB, organIndex)
    }

    private fun endpointsOf(cellA: Int, cellB: Int, organIndex: Int): Pair<Int, Int>? {
        if (cellA == -1 || cellB == -1) return null
        if (!cellEntity.isAlive[cellA] || !cellEntity.isAlive[cellB]) return null
        if (cellEntity.organIndex[cellA] != organIndex) return null
        if (cellEntity.organIndex[cellB] != organIndex) return null

        val idA = cellEntity.cellGenomeId[cellA]
        val idB = cellEntity.cellGenomeId[cellB]
        if (idA == -1 || idB == -1) return null
        return idA to idB
    }

    // ===================================================================================
    // АЛГОРИТМ
    // ===================================================================================

    /**
     * Обратный Катхилл-Макки. Возвращает order, где order[slot] — номер вершины.
     *
     * Классическая схема: обход в ширину, соседи в очередь по возрастанию степени, старт
     * с вершины минимальной степени в компоненте. Граф может быть несвязным (кусок тела
     * оторвался), поэтому обход перезапускается по всем непосещённым вершинам — каждая
     * компонента ложится своим непрерывным куском, что само по себе полезно.
     */
    private fun reverseCuthillMcKee(adjacency: Array<MutableList<Int>>): IntArray {
        val vertexCount = adjacency.size
        val degree = IntArray(vertexCount) { adjacency[it].size }
        val visited = BooleanArray(vertexCount)
        val order = IntArray(vertexCount)
        var written = 0

        // Соседей достаточно упорядочить по степени один раз: при обходе порядок внутри
        // списка не меняется.
        for (list in adjacency) list.sortBy { degree[it] }

        while (written < vertexCount) {
            val start = lowestDegreeUnvisited(degree, visited) ?: break

            visited[start] = true
            order[written++] = start

            // Очередь обхода — это сам order: голова идёт по уже записанным вершинам.
            var head = written - 1
            while (head < written) {
                val vertex = order[head++]
                for (neighbour in adjacency[vertex]) {
                    if (visited[neighbour]) continue
                    visited[neighbour] = true
                    order[written++] = neighbour
                }
            }
        }

        // Разворот: ширину ленты не меняет, профиль уменьшает.
        var left = 0
        var right = written - 1
        while (left < right) {
            val tmp = order[left]
            order[left] = order[right]
            order[right] = tmp
            left++
            right--
        }

        // Изолированные вершины (клетка без связей) в обход не попадут — дописываем их
        // в конец, иначе у них не будет слота вовсе.
        if (written < vertexCount) {
            val tail = IntArray(vertexCount)
            System.arraycopy(order, 0, tail, 0, written)
            var pos = written
            for (vertex in 0 until vertexCount) if (!visited[vertex]) tail[pos++] = vertex
            return tail
        }
        return order
    }

    private fun lowestDegreeUnvisited(degree: IntArray, visited: BooleanArray): Int? {
        var best = -1
        for (vertex in degree.indices) {
            if (visited[vertex]) continue
            if (best == -1 || degree[vertex] < degree[best]) best = vertex
        }
        return if (best == -1) null else best
    }

    // ===================================================================================
    // ПОРЯДОК СВЯЗЕЙ
    // ===================================================================================

    /**
     * Связи укладываются по возрастанию `(min(slotA, slotB), max(slotA, slotB))`.
     *
     * Без этого половина смысла RCM теряется: порядок клеток был бы хорошим, а обход по
     * связям всё равно прыгал бы по всему телу, и окно снова стало бы размером с организм.
     * Отсортированные так связи идут вдоль ленты, и подряд идущие связи попадают в одни
     * и те же кэш-линии.
     */
    private fun orderLinks(
        organIndex: Int,
        slotOfGenomeId: Int2IntOpenHashMap,
        endpoints: (Int, Int) -> Pair<Int, Int>?,
        aliveIndices: IntArray
    ): List<Int> {
        val entries = ArrayList<LinkEntry>(aliveIndices.size)
        for (linkIndex in aliveIndices) {
            val pair = endpoints(linkIndex, organIndex) ?: continue
            val slotA = slotOfGenomeId.get(pair.first)
            val slotB = slotOfGenomeId.get(pair.second)
            if (slotA == -1 || slotB == -1) continue

            entries.add(
                if (slotA <= slotB) LinkEntry(slotA, slotB, pair.first, pair.second)
                else LinkEntry(slotB, slotA, pair.second, pair.first)
            )
        }

        entries.sortWith(compareBy({ it.low }, { it.high }))

        val flat = ArrayList<Int>(entries.size * 2)
        for (entry in entries) {
            flat.add(entry.idA)
            flat.add(entry.idB)
        }
        return flat
    }

    /** Связь на время сортировки: ключ — пара слотов, значение — пара cellGenomeId. */
    private data class LinkEntry(val low: Int, val high: Int, val idA: Int, val idB: Int)
}
