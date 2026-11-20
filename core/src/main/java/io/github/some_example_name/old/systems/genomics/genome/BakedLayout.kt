package io.github.some_example_name.old.systems.genomics.genome

import io.github.some_example_name.old.core.utils.OrderedIntPairMap
import io.github.some_example_name.old.systems.physics.LayoutChunks
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

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

    // ProtoNumber 6 и 7 — бывшая граница тела (interiorLinks, interiorVersion; удалена 03.11:
    // край тела в мире берётся из физики, PhysicsWorld.isSurfaceCell). Номера не переиспользовать.

    /**
     * ПОЗА ПОКОЯ ВЗРОСЛОГО ТЕЛА по слотам: координаты клетки в мире редактора (единицы мира
     * игры) и её радиус. Физика-библиотека (`:physics`, мост — physics/bridge) заводит тело
     * сразу всей раскладкой: длины покоя связей, площади треугольников, позы костей и место
     * рождения клетки берутся отсюда (рождение — поворот и масштаб позы по рождённым соседям).
     * Начало координат — любое: мост ставит тело зиготой в точку подсадки.
     *
     * Пусто у раскладок, запечённых раньше: GenomeImport.withBaking допекает позу, не трогая
     * порядок слотов (как границу, [withRestPoseFrom]).
     */
    @ProtoNumber(8) val restX: List<Float> = emptyList(),
    @ProtoNumber(9) val restY: List<Float> = emptyList(),
    @ProtoNumber(10) val restRadius: List<Float> = emptyList(),

    /**
     * Кость во взрослом теле, по слотам (клетка типа «bone», cells/Bone.kt): связные куски
     * костных клеток становятся кластерами shape matching. Тип клетки по ходу роста меняется
     * (мутация), а тело в библиотеке заводится раз — поэтому берётся взрослый.
     */
    @ProtoNumber(11) val restBone: List<Boolean> = emptyList(),

    /**
     * Каким перебором найдены треугольники [triangleSlots] — см. [TRIANGLES_VERSION]. Ноль — у
     * раскладок до 27.10, где терялась треть треугольников: GenomeImport.withBaking находит их
     * заново по связям и позе покоя ([withTrianglesFromLinks]), не трогая порядок слотов.
     */
    @ProtoNumber(12) val trianglesVersion: Int = 0,

    /**
     * Связи поперёк граней — номера связей [linkPairsInSlotOrder] по возрастанию: они не стороны
     * граней, а лежат поверх ткани, и для физики голая такая связь — край тела посреди него (см.
     * trianglesOfLinks / TriangleFaces.crossingLinks). Находятся вместе с треугольниками,
     * [trianglesVersion] 3 и новее. Физика их не получает (LayoutBodies), в игре это обычные связи.
     * Первая попытка 27.10 взорвала Crayfish при росте: недоросший участок держался на них,
     * растягивался, и дорастающие связи рождённых в нём клеток рвал падающий предел. После того
     * как дорастающая связь вышла из-под предела (isGrowingLink, 02.11), Burdock leaf (1131 такая
     * связь) растёт целым: погибших рёбер 41 → 18, голых рёбер контура 1113 → 1.
     */
    @ProtoNumber(13) val crossingLinks: List<Int> = emptyList(),

    /**
     * Кластеры мотонейронов (cells/MotorNeuron.kt, MotorClusters) по слотам: номер кластера у
     * мышц и мотонейронов кластера, -1 у остальных. Пусто — в теле нет мотонейронов (и у всех
     * раскладок до 20.11: мотонейрона тогда не было, допекать нечего). Взрослое тело, как
     * [restBone]: разрыв ткани в мире кластер не делит.
     */
    @ProtoNumber(14) val motorClusterOfSlot: List<Int> = emptyList(),

    /** Слоты мотонейронов по возрастанию; мышцы кластера — его участники не из этого списка. */
    @ProtoNumber(15) val motorNeuronSlots: List<Int> = emptyList(),

    /**
     * Направление мотонейрона k ([motorNeuronSlots]) во взрослом теле — волокно его мышц:
     * единичный вектор в позе покоя ([restX]/[restY]), пары x, y подряд. Снимается со стрелки
     * клетки при запекании (RCMSort). Числа, а не угол: в рантайме ни одной тригонометрии, и на
     * любой платформе одно и то же волокно. Пусто — волокон нет, мышцы сжимаются во все стороны.
     */
    @ProtoNumber(16) val motorNeuronFiber: List<Float> = emptyList()
) {

    /** Треугольники найдены нынешним перебором — пересчитывать незачем. */
    val hasCurrentTriangles: Boolean get() = trianglesVersion >= TRIANGLES_VERSION

    /**
     * Эта же раскладка с треугольниками заново по её связям и позе покоя ([trianglesOfLinks]) —
     * запекание (RCMSort) и допекание старых раскладок. Порядок слотов и связей свой. Без позы
     * покоя — без изменений: площадь снимать не с чего.
     */
    fun withTrianglesFromLinks(): BakedLayout {
        if (!hasRestPose) return this
        val links = linksInLayout
        val linkA = IntArray(links) { slotByCellGenomeId.get(linkPairsInSlotOrder[2 * it]) }
        val linkB = IntArray(links) { slotByCellGenomeId.get(linkPairsInSlotOrder[2 * it + 1]) }
        val mesh = trianglesOfLinks(cellsInLayout, linkA, linkB, restX.toFloatArray(), restY.toFloatArray())
        return copy(
            triangleSlots = mesh.slots, triangleRestArea2 = mesh.areas, crossingLinks = mesh.crossing,
            trianglesVersion = TRIANGLES_VERSION
        )
    }

    /**
     * Эта же раскладка с кластерами мотонейронов по её связям ([MotorClusters]) — запекание
     * (RCMSort) и стенды гайда. [isMuscle]/[isMotorNeuron] — тип клетки взрослого тела по слотам;
     * [dirX]/[dirY] — направление клетки по слотам (стрелка во взрослом теле, читается только у
     * мотонейронов), null — волокон нет.
     */
    fun withMotorClusters(
        isMuscle: BooleanArray,
        isMotorNeuron: BooleanArray,
        dirX: FloatArray? = null,
        dirY: FloatArray? = null
    ): BakedLayout {
        val links = linksInLayout
        val linkA = IntArray(links) { slotByCellGenomeId.get(linkPairsInSlotOrder[2 * it]) }
        val linkB = IntArray(links) { slotByCellGenomeId.get(linkPairsInSlotOrder[2 * it + 1]) }
        val clusters = MotorClusters.of(cellsInLayout, linkA, linkB, isMuscle, isMotorNeuron)
        val fiber = if (dirX == null || dirY == null) emptyList() else clusters.motorNeuronSlots.flatMap { s ->
            // Нормировка здесь, раз при запекании: стрелка клетки — косинус и синус, но после
            // поворотов в float её длина может уйти от единицы на ulp-ы.
            val len = kotlin.math.sqrt(dirX[s] * dirX[s] + dirY[s] * dirY[s])
            if (len < 1e-6f) listOf(0f, 0f) else listOf(dirX[s] / len, dirY[s] / len)
        }
        return copy(
            motorClusterOfSlot = clusters.clusterOfSlot, motorNeuronSlots = clusters.motorNeuronSlots,
            motorNeuronFiber = fiber
        )
    }

    /** Есть ли запечённая поза покоя — у старых раскладок её нет. */
    val hasRestPose: Boolean
        get() = cellsInLayout > 0 && restX.size == cellsInLayout && restY.size == cellsInLayout &&
            restRadius.size == cellsInLayout && restBone.size == cellsInLayout

    /**
     * Эта же раскладка с позой покоя из [other] — для допекания старых геномов: порядок слотов
     * свой, поза переносится по cellGenomeId. Клетка, которой в [other] нет, оставляет позу
     * без изменений; если какой-то клетки не нашлось — поза не переносится вовсе (половинная
     * поза хуже отсутствующей: тело собралось бы из клеток в нуле координат).
     */
    fun withRestPoseFrom(other: BakedLayout?): BakedLayout {
        if (other == null || !other.hasRestPose) return this
        val slots = IntArray(cellsInLayout) { other.slotByCellGenomeId.get(cellGenomeIdsInSlotOrder[it]) }
        if (slots.any { it == -1 }) return this
        // Кластеры мотонейронов и их волокна — тоже свойство взрослого тела: переносятся вместе с
        // позой. Мотонейроны идут в порядке своих новых слотов, волокно — за своей клеткой.
        val hasMotors = other.motorClusterOfSlot.size == other.cellsInLayout && other.motorNeuronSlots.isNotEmpty()
        val motorIndexInOther = HashMap<Int, Int>().apply { other.motorNeuronSlots.forEachIndexed { k, s -> put(s, k) } }
        val motors = if (hasMotors) slots.indices.filter { slots[it] in motorIndexInOther } else emptyList()
        val hasFiber = other.motorNeuronFiber.size == 2 * other.motorNeuronSlots.size
        return copy(
            restX = slots.map { other.restX[it] },
            restY = slots.map { other.restY[it] },
            restRadius = slots.map { other.restRadius[it] },
            restBone = slots.map { other.restBone[it] },
            motorClusterOfSlot = if (hasMotors) slots.map { other.motorClusterOfSlot[it] } else emptyList(),
            motorNeuronSlots = motors,
            motorNeuronFiber = if (hasMotors && hasFiber) motors.flatMap { s ->
                val k = motorIndexInOther.getValue(slots[s])
                listOf(other.motorNeuronFiber[2 * k], other.motorNeuronFiber[2 * k + 1])
            } else emptyList()
        )
    }

    // ===================================================================================
    // КЛАСТЕРЫ МОТОНЕЙРОНОВ (NeuralLinkManager.driveMotorClusters)
    //
    // Нейрофаза каждый тик обходит мотонейроны организма и их мышцы — поэтому примитивными
    // массивами, без боксинга List<Int>. Мышцы кластера — непрерывный кусок
    // [motorClusterMuscles] по возрастанию слота: обход идёт по арене вперёд, как фаза клеток.
    // Объём — по 4 байта на мышцу кластера и на мотонейрон: на теле в тысячу клеток единицы КБ.
    // ===================================================================================

    @Transient
    val motorNeuronSlotsArray: IntArray =
        if (motorClusterOfSlot.size == cellsInLayout) motorNeuronSlots.toIntArray() else IntArray(0)

    /** Кластер мотонейрона k — по [motorNeuronSlotsArray]. */
    @Transient
    val motorNeuronClusterArray: IntArray = IntArray(motorNeuronSlotsArray.size) { motorClusterOfSlot[motorNeuronSlotsArray[it]] }

    /** Мышцы кластера c — motorClusterMuscles[motorClusterMuscleStart[c] until motorClusterMuscleStart[c + 1]]. */
    @Transient
    val motorClusterMuscleStart: IntArray = run {
        if (motorNeuronSlotsArray.isEmpty()) return@run IntArray(1)
        val clusters = motorClusterOfSlot.max() + 1
        val start = IntArray(clusters + 1)
        val motors = motorNeuronSlotsArray.toHashSet()
        for (s in motorClusterOfSlot.indices) {
            val c = motorClusterOfSlot[s]
            if (c >= 0 && s !in motors) start[c + 1]++
        }
        for (c in 0 until clusters) start[c + 1] += start[c]
        start
    }

    @Transient
    val motorClusterMuscles: IntArray = run {
        val list = IntArray(motorClusterMuscleStart.last())
        if (list.isEmpty()) return@run list
        val fill = motorClusterMuscleStart.copyOf()
        val motors = motorNeuronSlotsArray.toHashSet()
        for (s in motorClusterOfSlot.indices) {
            val c = motorClusterOfSlot[s]
            if (c >= 0 && s !in motors) list[fill[c]++] = s
        }
        list
    }

    /**
     * Волокно клетки по слотам, пары x, y подряд (физика — PhysicsWorld.setCellFiber при подсадке
     * тела): у мышц и мотонейронов кластера — направление ближайшего к клетке мотонейрона её
     * кластера в позе покоя (при равных — с меньшим номером), у остальных клеток — ноль. Пусто —
     * волокон нет. Только сложения и умножения float: на любой платформе одно и то же. Считается
     * раз на раскладку, O(клеток кластеров × мотонейронов).
     */
    @Transient
    val fiberBySlot: FloatArray = run {
        if (motorNeuronSlotsArray.isEmpty() || motorNeuronFiber.size != 2 * motorNeuronSlotsArray.size) return@run FloatArray(0)
        val out = FloatArray(2 * cellsInLayout)
        val hasPose = restX.size == cellsInLayout && restY.size == cellsInLayout
        for (s in 0 until cellsInLayout) {
            val c = motorClusterOfSlot[s]
            if (c < 0) continue
            var best = -1
            var bestD = 0f
            for (k in motorNeuronSlotsArray.indices) {
                if (motorNeuronClusterArray[k] != c) continue
                val m = motorNeuronSlotsArray[k]
                val d = if (!hasPose) 0f else {
                    val dx = restX[s] - restX[m]; val dy = restY[s] - restY[m]
                    dx * dx + dy * dy
                }
                if (best == -1 || d < bestD) { best = k; bestD = d }
            }
            out[2 * s] = motorNeuronFiber[2 * best]; out[2 * s + 1] = motorNeuronFiber[2 * best + 1]
        }
        out
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
