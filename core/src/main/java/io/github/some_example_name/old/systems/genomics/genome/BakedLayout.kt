package io.github.some_example_name.old.systems.genomics.genome

import io.github.some_example_name.old.core.utils.OrderedIntPairMap
import io.github.some_example_name.old.systems.physics.LayoutChunks
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Версия признака границы тела (BakedLayout.interiorVersion), которым печёт InteriorCells.
 *  - 0 — «рядом нет места для новой клетки» (19.10), с фиксированными расстояниями редактора;
 *  - 1 — внешний контур объединения дисков клеток с их радиусами (20.10): граница без швов.
 * Поменял признак — увеличь: свои геномы игроков перепекутся при запуске, встроенные —
 * :lwjgl3:bakeBundledGenomes.
 */
const val INTERIOR_VERSION = 1

/**
 * Запечённая раскладка организма: в каком порядке его клетки, связи и нейросвязи должны
 * лечь в арены.
 *
 * ЗАЧЕМ
 * -----
 * Арена даёт организму непрерывный кусок массивов, но порядок ВНУТРИ куска определялся
 * порядком роста — то есть тем, в какой последовательности геном делит клетки. Для кэша
 * важен не он, а ширина ленты графа связей: максимальное |slotA - slotB| по всем связям.
 * Именно она задаёт размер окна, которое фаза связей держит горячим.
 *
 * У порядка роста ширина ленты может выродиться до O(n) — достаточно генома, растящего
 * тело «в длину», отросток за отростком. Обратный алгоритм Катхилла-Макки приводит её
 * к O(sqrt(n)) для плоских сеток, то есть окно перестаёт зависеть от размера организма:
 * тело на 1500 клеток обходится тем же килобайтом горячих данных, что и на 150.
 *
 * ПОЧЕМУ ЭТО МОЖНО ПЕЧЬ ЗАРАНЕЕ
 * ----------------------------
 * Геном полностью определяет, какие клетки вырастут и как они будут связаны: топология
 * взрослого тела от физики не зависит, физика меняет только координаты. Значит порядок
 * считается один раз при сохранении генома в редакторе, где организм уже выращен целиком,
 * и дальше просто читается. Стоимость сортировки в рантайме — ноль.
 *
 * ЧЕМ АДРЕСУЕМСЯ
 * --------------
 * Индексами клеток здесь пользоваться нельзя: в редакторе и в симуляции они разные.
 * Устойчивый идентификатор клетки внутри генома — это cellGenomeId (по нему же построена
 * CellEntity.organToIdToIndex, и он уникален в пределах организма). Связь адресуется парой
 * cellGenomeId своих концов.
 *
 * ФОРМАТ
 * ------
 * Списки хранят ЗНАЧЕНИЯ в порядке слотов: позиция в списке и есть смещение внутри арены.
 * Обратные карты (идентификатор -> слот), которые нужны в рантайме, строятся один раз при
 * загрузке — держать их в файле смысла нет, они вдвое больше.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BakedLayout(
    /** Слот k занимает клетка с cellGenomeId == cellGenomeIdsInSlotOrder[k]. */
    @ProtoNumber(1) val cellGenomeIdsInSlotOrder: List<Int> = emptyList(),

    /**
     * Пары cellGenomeId концов, разложенные подряд: слот k — это элементы 2k и 2k+1.
     *
     * Плоский список, а не список пар: protobuf для List<Pair> завёл бы вложенное сообщение
     * с заголовком на каждую связь, а связей втрое больше, чем клеток.
     */
    @ProtoNumber(2) val linkPairsInSlotOrder: List<Int> = emptyList(),

    /** То же для нейросвязей. */
    @ProtoNumber(3) val neuralLinkPairsInSlotOrder: List<Int> = emptyList(),

    /**
     * Треугольники сетки тела: тройки СЛОТОВ арены подряд, тройка k — элементы 3k..3k+2.
     *
     * Хранятся слоты, а не cellGenomeId: раскладка их и так задаёт, поэтому в рантайме
     * `cellIndex = cellArenaBase + slot`, а благодаря параллельности арен и
     * `particleIndex = particleArenaBase + slot` — ни одного поиска.
     *
     * Порядок вершин внутри тройки выбран так, чтобы удвоенная знаковая площадь в позе
     * покоя была ПОЛОЖИТЕЛЬНОЙ. На этом держится обнаружение выворачивания: у сложившегося
     * наизнанку треугольника она меняет знак.
     */
    @ProtoNumber(4) val triangleSlots: List<Int> = emptyList(),

    /**
     * Удвоенная знаковая площадь каждого треугольника в позе покоя, снятая в редакторе
     * с выращенного тела. Удвоенная — чтобы в рантайме не делить на два.
     */
    @ProtoNumber(5) val triangleRestArea2: List<Float> = emptyList(),

    /**
     * Граница тела, в порядке слотов: сколько физических связей у клетки во взрослом теле,
     * если она ВНУТРЕННЯЯ, и 0, если граничная. См. InteriorCells и CellEntity.refreshOnEdge.
     *
     * Внутренняя — та, что не выходит на внешний контур взрослого тела (InteriorCells). В мире
     * клетка внутренняя, пока у неё ВСЕ её взрослые связи: пока тело растёт, части связей
     * ещё нет; умер сосед или порвалась связь — их меньше. В обоих случаях клетка у края,
     * и это верно на любой стадии и для любой сетки, без запекания каждой стадии.
     *
     * Пусто у геномов, запечённых раньше: тогда работает прежний порог по числу связей.
     */
    @ProtoNumber(6) val interiorLinks: List<Int> = emptyList(),

    /**
     * Каким признаком запечена граница [interiorLinks] — см. [INTERIOR_VERSION]. Ноль — у
     * раскладок, запечённых до этого поля, то есть прежним признаком «нет места для новой
     * клетки»: такую границу GenomeImport.withBaking перепекает, как и отсутствующую.
     * Симуляция поле не читает — оно только для допекания.
     */
    @ProtoNumber(7) val interiorVersion: Int = 0
) {

    /** Есть ли запечённая граница — у старых раскладок её нет. */
    val hasInteriorLinks: Boolean get() = interiorLinks.isNotEmpty() && interiorLinks.size == cellsInLayout

    /** Граница есть и запечена нынешним признаком — перепекать её незачем. */
    val hasCurrentInterior: Boolean get() = hasInteriorLinks && interiorVersion >= INTERIOR_VERSION

    /** [interiorLinks] массивом: читается при каждом изменении связей клетки. */
    @Transient
    val interiorLinksArray: IntArray = IntArray(interiorLinks.size) { interiorLinks[it] }

    /**
     * Эта же раскладка с границей из [other] — для допекания старых геномов. Порядок слотов
     * остаётся СВОИМ: от него зависит, как уже живущие организмы лежат в аренах, поэтому
     * граница переносится по cellGenomeId. Клетка, которой в [other] нет, — граничная.
     */
    fun withInteriorFrom(other: BakedLayout?): BakedLayout {
        if (other == null || !other.hasInteriorLinks) return this
        return copy(
            interiorLinks = cellGenomeIdsInSlotOrder.map { genomeId ->
                val slot = other.slotByCellGenomeId.get(genomeId)
                if (slot == -1) 0 else other.interiorLinks[slot]
            },
            interiorVersion = other.interiorVersion
        )
    }

    /**
     * cellGenomeId -> смещение слота в арене клеток.
     *
     * -1 для незнакомого идентификатора: геном мог мутировать после запекания, и клетка,
     * которой в раскладке нет, должна честно уйти на общий bump-путь, а не занять чужой слот.
     */
    @Transient
    val slotByCellGenomeId: Int2IntOpenHashMap =
        Int2IntOpenHashMap(cellGenomeIdsInSlotOrder.size).apply {
            defaultReturnValue(-1)
            cellGenomeIdsInSlotOrder.forEachIndexed { slot, genomeId -> put(genomeId, slot) }
        }

    /**
     * Пара cellGenomeId -> смещение слота в арене связей.
     *
     * Кладётся в обоих порядках: морфогенез создаёт связь той стороной, какой ему удобно,
     * а слот у неё один и тот же.
     */
    @Transient
    val slotByLinkPair: OrderedIntPairMap = buildPairMap(linkPairsInSlotOrder)

    @Transient
    val slotByNeuralLinkPair: OrderedIntPairMap = buildPairMap(neuralLinkPairsInSlotOrder)

    /**
     * Те же треугольники, но массивами и с уже обращённой площадью покоя.
     *
     * Списки из protobuf — это `List<Int>`/`List<Float>`, то есть боксинг на каждый
     * элемент и разыменование на каждое чтение. В фазе связей треугольников примерно
     * вдвое больше, чем клеток, и читаются они каждый тик — поэтому один раз при загрузке
     * они разворачиваются в примитивные массивы.
     *
     * Обратная площадь считается здесь, а не в цикле: деление это ~14 тактов, и на двух
     * тысячах треугольников шести организмов оно вылезло бы в десятки микросекунд за тик
     * на ровном месте.
     */
    @Transient
    val triangleSlotsArray: IntArray = IntArray(triangleSlots.size) { triangleSlots[it] }

    @Transient
    val triangleInvRestArea2: FloatArray =
        FloatArray(triangleRestArea2.size) { 1f / triangleRestArea2[it] }

    @Transient
    val triangleRestArea2Array: FloatArray =
        FloatArray(triangleRestArea2.size) { triangleRestArea2[it] }

    val trianglesInLayout: Int get() = triangleRestArea2.size

    /** Сколько слотов реально занято раскладкой — ёмкость арены считается от них. */
    val cellsInLayout: Int get() = cellGenomeIdsInSlotOrder.size
    val linksInLayout: Int get() = linkPairsInSlotOrder.size / 2
    val neuralLinksInLayout: Int get() = neuralLinkPairsInSlotOrder.size / 2

    // ===================================================================================
    // ТРЕУГОЛЬНИКИ ПО СВЯЗЯМ (TriangleEntity)
    //
    // Треугольник сформирован, когда живы три его связи. Чтобы пересчитывать это по месту,
    // когда рождается или умирает связь, нужно знать и обратное — треугольники на связи.
    // Всё выводится из раскладки один раз при чтении; O(треугольников + связей).
    // ===================================================================================

    /**
     * Слоты связей треугольника t: 3t — (s0, s1), 3t+1 — (s1, s2), 3t+2 — (s2, s0). -1 у всех
     * трёх, если какой-то пары нет среди связей раскладки: такой треугольник не формируется
     * никогда и в счётах ниже не участвует.
     */
    @Transient
    val triangleLinkSlots: IntArray = IntArray(trianglesInLayout * 3).also { edges ->
        val ids = cellGenomeIdsInSlotOrder
        val slots = triangleSlotsArray
        for (t in 0 until trianglesInLayout) {
            val a = ids[slots[3 * t]]; val b = ids[slots[3 * t + 1]]; val c = ids[slots[3 * t + 2]]
            val ab = slotByLinkPair.get(a, b)
            val bc = slotByLinkPair.get(b, c)
            val ca = slotByLinkPair.get(c, a)
            val valid = ab != -1 && bc != -1 && ca != -1
            edges[3 * t] = if (valid) ab else -1
            edges[3 * t + 1] = if (valid) bc else -1
            edges[3 * t + 2] = if (valid) ca else -1
        }
    }

    /** Треугольники на связи: связь k — trianglesOfLink[trianglesOfLinkStart[k] until trianglesOfLinkStart[k + 1]]. */
    @Transient
    val trianglesOfLinkStart: IntArray = IntArray(linksInLayout + 1).also { start ->
        for (e in triangleLinkSlots) if (e != -1) start[e + 1]++
        for (k in 0 until linksInLayout) start[k + 1] += start[k]
    }

    @Transient
    val trianglesOfLink: IntArray = IntArray(trianglesOfLinkStart[linksInLayout]).also { list ->
        val fill = trianglesOfLinkStart.copyOf()
        for (i in triangleLinkSlots.indices) {
            val e = triangleLinkSlots[i]
            if (e != -1) list[fill[e]++] = i / 3
        }
    }

    /**
     * Сколько формируемых треугольников у клетки в каждом слоте во взрослом теле.
     * Внутренняя клетка обязана иметь их все (CellEntity.refreshOnEdge): иначе рядом щель.
     */
    @Transient
    val trianglesAroundSlot: IntArray = IntArray(cellsInLayout).also { around ->
        for (t in 0 until trianglesInLayout) {
            if (triangleLinkSlots[3 * t] == -1) continue
            for (v in 0..2) around[triangleSlotsArray[3 * t + v]]++
        }
    }

    /**
     * Нарезка тела на чётные и нечётные куски для фазы связей. Стоит последней: читает
     * [slotByCellGenomeId] и [triangleSlotsArray], а поля класса создаются по порядку.
     */
    @Transient
    val chunks: LayoutChunks = LayoutChunks.of(this)

    private companion object {
        fun buildPairMap(flatPairs: List<Int>): OrderedIntPairMap {
            val map = OrderedIntPairMap(flatPairs.size)
            var slot = 0
            var i = 0
            while (i + 1 < flatPairs.size) {
                val a = flatPairs[i]
                val b = flatPairs[i + 1]
                map.put(a, b, slot)
                map.put(b, a, slot)
                slot++
                i += 2
            }
            return map
        }
    }
}
