package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.old.systems.genomics.genome.INTERIOR_VERSION
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

        val triangles = collectTriangles(organIndex, cellGenomeIds, adjacency, slotOfGenomeId)

        return BakedLayout(
            interiorLinks = interiorLinks(organIndex, cellIdsInSlotOrder).toList(),
            interiorVersion = INTERIOR_VERSION,
            triangleSlots = triangles.first,
            triangleRestArea2 = triangles.second,
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
            )
        )
    }

    // ===================================================================================
    // ГРАНИЦА ТЕЛА
    // ===================================================================================

    /**
     * По слотам: число физических связей внутренней клетки во взрослом теле, 0 у граничной
     * (см. BakedLayout.interiorLinks, [InteriorCells]).
     *
     * Число — linkAmount, тот же счётчик, с которым его сравнивает мир
     * (CellEntity.refreshOnEdge): связи редактор растит тем же делением, что и мир.
     */
    private fun interiorLinks(organIndex: Int, cellIdsInSlotOrder: IntArray): IntArray {
        val cellOfGenomeId = Int2IntOpenHashMap(cellIdsInSlotOrder.size).apply {
            defaultReturnValue(-1)
            val alive = cellEntity.aliveList
            for (i in 0 until alive.size) {
                val cellIndex = alive.getInt(i)
                if (cellEntity.organIndex[cellIndex] != organIndex) continue
                put(cellEntity.cellGenomeId[cellIndex], cellIndex)
            }
        }
        val cells = IntArray(cellIdsInSlotOrder.size) { cellOfGenomeId.get(cellIdsInSlotOrder[it]) }
        val interior = InteriorCells.find(
            xs = FloatArray(cells.size) { cellEntity.getX(cells[it]) },
            ys = FloatArray(cells.size) { cellEntity.getY(cells[it]) },
            rs = FloatArray(cells.size) { cellEntity.getRadius(cells[it]) }
        )
        return IntArray(cells.size) { slot ->
            if (interior[slot]) cellEntity.linkAmount[cells[slot]] else 0
        }
    }

    // ===================================================================================
    // ТРЕУГОЛЬНИКИ
    // ===================================================================================

    /**
     * Треугольники сетки тела: тройки взаимно связанных клеток.
     *
     * ЗАЧЕМ
     * -----
     * Сеть пружин с фиксированными длинами в 2D жёсткая на сдвиг и растяжение, но НЕ
     * защищена от выворачивания: вершина проходит сквозь противоположное ребро, все три
     * длины остаются в норме, а тело складывается наизнанку. Раньше это блокировал
     * внутренний repulse — он физически не давал клеткам сойтись; после того как внутренние
     * клетки убрали из сетки коллизий, остался только этот вырожденный путь, и тело
     * складывается даже от собственных мышц.
     *
     * Знаковая площадь при выворачивании меняет ЗНАК, поэтому ограничение на неё ловит
     * именно этот случай, а не просто «стало тесно».
     *
     * ПОЧЕМУ ЭТО ПЕЧЁТСЯ
     * ------------------
     * Треугольник — это топология: пара соседей клетки, связанных между собой. Найти их
     * значит перебрать C(deg,2) пар на клетку и для каждой спросить смежность — в рантайме
     * это тысячи проверок за тик, при запекании ноль.
     *
     * ПОРЯДОК ВЕРШИН
     * --------------
     * Тройка разворачивается так, чтобы площадь покоя была положительной. Иначе знак у
     * половины треугольников оказался бы отрицательным просто из-за порядка обхода, и
     * «вывернулся» стало бы неотличимо от «так и было».
     *
     * Сортировка по минимальному слоту — та же причина, что у связей: обход треугольников
     * идёт вдоль ленты RCM и попадает в то же горячее окно, что и обход связей.
     */
    private fun collectTriangles(
        organIndex: Int,
        cellGenomeIds: IntArray,
        adjacency: Array<MutableList<Int>>,
        slotOfGenomeId: Int2IntOpenHashMap
    ): Pair<List<Int>, List<Float>> {
        // Клетка по геномному id — нужна за позициями: площадь покоя снимается с реального
        // выращенного тела, а не выводится из длин связей.
        val cellOfGenomeId = Int2IntOpenHashMap(cellGenomeIds.size).apply {
            defaultReturnValue(-1)
            val alive = cellEntity.aliveList
            for (i in 0 until alive.size) {
                val cellIndex = alive.getInt(i)
                if (cellEntity.organIndex[cellIndex] != organIndex) continue
                put(cellEntity.cellGenomeId[cellIndex], cellIndex)
            }
        }

        data class Tri(val minSlot: Int, val s0: Int, val s1: Int, val s2: Int, val restArea2: Float)

        val result = ArrayList<Tri>()

        for (v in adjacency.indices) {
            val neighbours = adjacency[v]
            for (i in neighbours.indices) {
                val a = neighbours[i]
                // v < a < b — канонический порядок, чтобы каждый треугольник встретился
                // ровно один раз, а не трижды (по разу от каждой вершины).
                if (a <= v) continue
                for (j in i + 1 until neighbours.size) {
                    val b = neighbours[j]
                    if (b <= a) continue
                    if (!adjacency[a].contains(b)) continue

                    val cell0 = cellOfGenomeId.get(cellGenomeIds[v])
                    val cell1 = cellOfGenomeId.get(cellGenomeIds[a])
                    val cell2 = cellOfGenomeId.get(cellGenomeIds[b])
                    if (cell0 == -1 || cell1 == -1 || cell2 == -1) continue

                    var slot0 = slotOfGenomeId.get(cellGenomeIds[v])
                    var slot1 = slotOfGenomeId.get(cellGenomeIds[a])
                    var slot2 = slotOfGenomeId.get(cellGenomeIds[b])
                    if (slot0 == -1 || slot1 == -1 || slot2 == -1) continue

                    val x0 = cellEntity.getX(cell0); val y0 = cellEntity.getY(cell0)
                    var x1 = cellEntity.getX(cell1); var y1 = cellEntity.getY(cell1)
                    var x2 = cellEntity.getX(cell2); var y2 = cellEntity.getY(cell2)

                    var area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                    if (area2 < 0f) {
                        // Разворачиваем обход, чтобы площадь покоя была положительной.
                        val ts = slot1; slot1 = slot2; slot2 = ts
                        val tx = x1; x1 = x2; x2 = tx
                        val ty = y1; y1 = y2; y2 = ty
                        area2 = -area2
                    }

                    // Вырожденный треугольник: три клетки на одной прямой. Обратная площадь
                    // ушла бы в бесконечность, а ограничение — в NaN.
                    if (area2 < MIN_REST_AREA2) continue

                    result.add(
                        Tri(minOf(slot0, slot1, slot2), slot0, slot1, slot2, area2)
                    )
                }
            }
        }

        result.sortBy { it.minSlot }

        val slots = ArrayList<Int>(result.size * 3)
        val areas = ArrayList<Float>(result.size)
        for (t in result) {
            slots.add(t.s0); slots.add(t.s1); slots.add(t.s2)
            areas.add(t.restArea2)
        }
        return slots to areas
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

    private companion object {
        /**
         * Ниже этой удвоенной площади треугольник считается вырожденным и не запекается.
         *
         * Три клетки на одной прямой дают нулевую площадь, обратная к ней — бесконечность,
         * а ограничение — NaN, который расползётся по vx/vy всего тела. Порог на порядки
         * меньше площади нормального треугольника (сторона ~0.5 даёт ~0.2).
         */
        const val MIN_REST_AREA2 = 1e-6f
    }
}
