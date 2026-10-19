package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.old.systems.genomics.genome.Genome
import it.unimi.dsi.fastutil.ints.IntArrayList


class OrganEntity(
    organStartMaxAmount: Int,
    /**
     * Выдавать ли организмам арены.
     *
     * Выключено в редакторе генома: там живёт один организм в сотне слотов, а арена
     * рассчитана на взрослое тело и растянула бы все массивы
     * редактора на порядок без всякой пользы — параллельных фаз там нет.
     *
     * При false [allocateArenas] ничего не делает, [hasArena] всегда false, и все пути
     * создания сущностей уходят в общий аллокатор ровно как до появления арен.
     */
    private val arenasEnabled: Boolean = true
): Entity(organStartMaxAmount) {

    var genomeIndex = IntArray(maxAmount) { -1 }
    var genomeSize = IntArray(maxAmount)
    var stage = IntArray(maxAmount)
    var dividedTimes = IntArray(maxAmount)
    var mutatedTimes = IntArray(maxAmount)
    var alreadyGrownUp = BooleanArray(maxAmount)
    var divideCounterThisStage = IntArray(maxAmount)
    var mutateCounterThisStage = IntArray(maxAmount)
    var divideAmountThisStage = IntArray(maxAmount)
    var mutateAmountThisStage = IntArray(maxAmount)
    var justChangedStage = BooleanArray(maxAmount)

    // ===================================================================================
    // АРЕНЫ ОРГАНИЗМА
    //
    // Организм при рождении резервирует непрерывный диапазон индексов в CellEntity,
    // ParticleEntity, LinkEntity и NeuralLinkEntity и дальше по мере роста раздаёт слоты
    // только из него. Смысл — в том, чтобы все клетки, частицы и связи одного тела лежали
    // в массивах ПОДРЯД.
    //
    // Зачем: общий аллокатор (Entity.add) сначала опустошает deadStack, а тот в
    // установившемся режиме (рождений примерно столько же, сколько смертей) не пуст почти
    // никогда. Поэтому индексы растущего организма — это переиспользованные слоты умерших
    // клеток со всего мира, разбросанные по всему адресному пространству. Фаза связей на
    // каждую связь делает два случайных прыжка (связь -> клетки -> частицы), и попадания
    // в кэш зависят от истории мира, а не от геометрии тела.
    //
    // С аренами тот же обход становится потоковым: связи организма идут подряд, а клетки
    // и частицы, на которые они ссылаются, лежат внутри одного узкого окна.
    //
    // ПАРАЛЛЕЛЬНЫЕ АРЕНЫ КЛЕТОК И ЧАСТИЦ
    // ----------------------------------
    // Арены клеток и частиц выдаются ОДНОГО размера и заполняются синхронно, поэтому
    // клетка со смещением k владеет частицей с тем же смещением k:
    //
    //     particleIndex = particleArenaBase + (cellIndex - cellArenaBase)
    //
    // Это самый горячий переход всей фазы связей, и здесь он становится арифметикой
    // вместо чтения из cellEntity.particleIndexes. Сам particleIndexes остаётся —
    // он нужен субстанциям (у них организма нет) и служит проверкой под DEBUG_CHECKS.
    //
    // ЖИЗНЬ АРЕНЫ
    // -----------
    // Размер фиксирован при рождении и равен запечённой раскладке: тело растёт только по
    // геному (зигота от деления и мутации — пустышка, см. CellEntity.isDormantZygote), и
    // каждая клетка и связь ложится в СВОЙ слот раскладки — умершая отдаёт его только
    // себе самой, если отрастёт. Поэтому у клеток и связей нет ни свободного списка, ни
    // курсора сверх раскладки: клетка вне раскладки — нарушение инварианта (addCell и
    // addLink падают с объяснением). Вся арена умершего организма уходит в свободные куски
    // сущностей (Entity.releaseRange) и выдаётся следующему рождению.
    // ===================================================================================

    /**
     * Запечённая раскладка организма, взятая из его генома, или null если геном не запечён.
     *
     * Живёт здесь, а не читается из GenomeManager по ходу дела, чтобы сущности не тянули
     * зависимость на геномы: слот выдаётся в addCell/addLink, а там из контекста есть
     * только organIndex.
     */
    var arenaLayout = arrayOfNulls<BakedLayout>(maxAmount)

    /**
     * Освободившиеся слоты арены нейросвязей — по списку на организм.
     *
     * Нейросвязи при росте организма активно ПЕРЕВЯЗЫВАЮТСЯ — создаются, удаляются и
     * создаются заново, — поэтому без списка арена расходовалась бы по числу всех когда-либо
     * созданных, а не живых (так падала первая итерация арен: 124 живых при ёмкости 161).
     * Список свой, а не общий deadStack: общий отдал бы слот чужому организму.
     *
     * У клеток и связей такого списка нет: у них слот задан раскладкой.
     *
     * LIFO намеренно: последний освобождённый слот с большой вероятностью ещё горячий в кэше.
     */
    private var neuralFreeSlots = arrayOfNulls<IntArrayList>(maxAmount)

    /** Начало диапазона организма в CellEntity, -1 если арены нет. */
    var cellArenaBase = IntArray(maxAmount) { -1 }

    /** Слотов под клетки (и столько же под частицы) — ровно клеток в раскладке. */
    var cellArenaCapacity = IntArray(maxAmount)

    /** Начало диапазона организма в ParticleEntity. Ёмкость общая с клетками. */
    var particleArenaBase = IntArray(maxAmount) { -1 }

    var linkArenaBase = IntArray(maxAmount) { -1 }

    /** Слотов под связи — ровно связей в раскладке. */
    var linkArenaCapacity = IntArray(maxAmount)

    // Арена нейросвязей. Раскладки у неё нет: RCM для них не имеет смысла (нейросвязи не
    // физика и читаются по одной на клетку), поэтому слоты раздаются только свободным
    // списком и курсором. Ёмкость — пик одновременно живых нейросвязей за весь рост
    // (Genome.neuralLinksPeak): геном перевязывает их по ходу стадий, и удалённая
    // уступает слот следующей. -1 в начале — арены нет, нейросвязи идут в общий пул.
    var neuralArenaBase = IntArray(maxAmount) { -1 }
    var neuralArenaCapacity = IntArray(maxAmount)
    var neuralArenaUsed = IntArray(maxAmount)

    // ===================================================================================
    // СМЕРТЬ ОРГАНИЗМА
    //
    // Организм умирает вместе с последней клеткой. До этого он не удалялся никогда
    // (DELETE_ORGAN — заглушка): пустой организм оставался в aliveList, получал работу в
    // фазе связей, OrganManager гонял его стадии, а арена держалась вечно.
    //
    // Удаляется он не в момент смерти клетки, а в конце тика (SimulationSystem зовёт
    // [deleteEmptiedOrgans] после всех команд). Команды того же тика ещё читают поля
    // организма: продюсер, умерший раньше в этом проходе, всё равно рожает зиготу с
    // genomeIndex своего организма, а счётчики стадии получают свои декременты.
    // ===================================================================================

    /**
     * Живых клеток организма. Выводится из клеток и не сохраняется — его пересчитывает
     * CellEntity.restoreAfterLoad.
     */
    var liveCells = IntArray(maxAmount)
        private set

    /** Опустевшие в этом тике, в порядке опустения — он детерминирован, как и команды. */
    private val emptiedOrgans = IntArrayList()

    fun cellAdded(organIndex: Int) {
        if (organIndex != -1) liveCells[organIndex]++
    }

    fun cellRemoved(organIndex: Int) {
        if (organIndex == -1) return
        // В редакторе организм не удаляется (см. deleteEmptiedOrgans), копить незачем.
        if (--liveCells[organIndex] == 0 && arenasEnabled) emptiedOrgans.add(organIndex)
    }

    /** После загрузки: счётчики считает CellEntity, пустые (утекшие раньше) — к удалению. */
    fun resetLiveCells() = liveCells.fill(0)

    fun scheduleIfEmpty(organIndex: Int) {
        if (liveCells[organIndex] == 0 && arenasEnabled) emptiedOrgans.add(organIndex)
    }

    /**
     * Удалить организмы, оставшиеся без клеток. Однопоточно, после всех команд тика.
     *
     * Не в редакторе: там организм один на всё время работы редактора, и его индекс 0
     * зашит в команды. Арен там нет, так что и освобождать нечего.
     */
    fun deleteEmptiedOrgans() {
        if (!arenasEnabled) {
            emptiedOrgans.clear()
            return
        }
        for (i in 0 until emptiedOrgans.size) {
            val organIndex = emptiedOrgans.getInt(i)
            // Повтор в списке (опустел, ожил, опустел) или клетка уже вернулась.
            if (!isAlive[organIndex] || liveCells[organIndex] != 0) continue
            deleteOrgan(organIndex)
        }
        emptiedOrgans.clear()
    }


    /**
     * Сущности, у которых бронируются диапазоны. Связываются один раз из DI-контейнера
     * через [bindEntities] — конструктором нельзя, потому что OrganEntity создаётся
     * раньше всех остальных (и CellEntity уже зависит от него).
     *
     * Пока не связаны, арены не выдаются: [allocateArenas] выходит сразу. Так редактор и
     * любой другой контейнер, забывший про связывание, продолжают работать прежним путём,
     * а не падают.
     */
    private var cellEntityRef: Entity? = null
    private var particleEntityRef: Entity? = null
    private var linkEntityRef: Entity? = null
    private var neuralLinkEntityRef: Entity? = null

    fun bindEntities(
        cellEntity: Entity,
        particleEntity: Entity,
        linkEntity: Entity,
        neuralLinkEntity: Entity? = null
    ) {
        cellEntityRef = cellEntity
        particleEntityRef = particleEntity
        linkEntityRef = linkEntity
        neuralLinkEntityRef = neuralLinkEntity
    }

    /** Есть ли у организма арена. Клетки без организма (organIndex == -1) идут общим путём. */
    fun hasArena(organIndex: Int) = organIndex != -1 && cellArenaBase[organIndex] != -1

    /**
     * Резервирует диапазоны под организм. Вызывать сразу после [addOrgan], до создания
     * первой клетки. Ёмкости — ровно размеры запечённой раскладки.
     */
    fun allocateArenas(
        organIndex: Int,
        /**
         * Запечённая раскладка из генома: в каком слоте арены окажется клетка с данным
         * cellGenomeId и связь с данной парой. См. BakedLayout.
         */
        layout: BakedLayout?,
        /** Пик нейросвязей из генома; -1 — неизвестен (геном старше), арены нет. */
        maxNeuralLinks: Int = -1
    ) {
        if (!arenasEnabled) return

        val cells = cellEntityRef ?: return
        val particles = particleEntityRef ?: return
        val links = linkEntityRef ?: return

        // Геномы печёт редактор при сохранении, а свои и импортированные допекаются при
        // запуске (GenomeImport.withBaking), так что сюда незапечённый не попадает. Раньше
        // такой получал арену нулевой ёмкости и падал на первой же клетке — здесь то же
        // падение, только сразу и с причиной.
        checkNotNull(layout) { "организм $organIndex: у генома нет запечённой раскладки — арену не из чего выдать" }
        arenaLayout[organIndex] = layout

        val cellCapacity = layout.cellsInLayout
        val linkCapacity = layout.linksInLayout

        // Бронь делает сама сущность: она знает свой lastId, то есть где кончается уже
        // розданное. Свои курсоры здесь вести нельзя — субстанции и террейн создаются
        // раньше первого организма и занимают индексы с нуля.
        //
        // Зазор в конце — против false sharing на границе арен. Округления ёмкости до
        // кратного 64 недостаточно: данные массива в JVM начинаются после заголовка
        // объекта, а сам объект выровнен по 8/16 байт, не по 64, поэтому граница арены
        // попадает в середину кэш-линии со смещением, которое из Kotlin не контролируется.
        // Нужен именно реальный пропуск элементов, и 64 покрывают линию даже у
        // ByteArray/BooleanArray, где элемент — байт.
        cellArenaBase[organIndex] = cells.reserveRange(cellCapacity + ARENA_GAP)
        cellArenaCapacity[organIndex] = cellCapacity

        particleArenaBase[organIndex] = particles.reserveRange(cellCapacity + ARENA_GAP)

        linkArenaBase[organIndex] = links.reserveRange(linkCapacity + ARENA_GAP)
        linkArenaCapacity[organIndex] = linkCapacity

        // Нейросвязей у генома может не быть вовсе — тогда и брони нет.
        val neural = neuralLinkEntityRef
        if (neural != null && maxNeuralLinks > 0) {
            neuralArenaBase[organIndex] = neural.reserveRange(maxNeuralLinks + ARENA_GAP)
            neuralArenaCapacity[organIndex] = maxNeuralLinks
            neuralArenaUsed[organIndex] = 0
        }
    }

    /**
     * Слот клетки в арене — её слот по раскладке, или -1, если клетки с таким номером в
     * раскладке нет (нарушение инварианта: вызывающий падает с объяснением).
     *
     * Именно этот порядок и есть результат RCM, ради которого всё запекалось. Клетка,
     * умершая и отросшая снова, получает тот же слот.
     */
    fun takeCellSlot(organIndex: Int, cellGenomeId: Int): Int {
        val slot = arenaLayout[organIndex]?.slotByCellGenomeId?.get(cellGenomeId) ?: -1
        return if (slot == -1) -1 else cellArenaBase[organIndex] + slot
    }

    /** То же для связи: её слот по паре cellGenomeId концов, в любом порядке. */
    fun takeLinkSlot(organIndex: Int, cellGenomeIdA: Int, cellGenomeIdB: Int): Int {
        val slot = arenaLayout[organIndex]?.slotByLinkPair?.get(cellGenomeIdA, cellGenomeIdB) ?: -1
        return if (slot == -1) -1 else linkArenaBase[organIndex] + slot
    }

    /**
     * Частица клетки по параллельности арен, без чтения cellEntity.particleIndexes.
     * Вызывать только когда [hasArena] истинно.
     */
    fun particleIndexOfCell(organIndex: Int, cellIndex: Int) =
        particleArenaBase[organIndex] + (cellIndex - cellArenaBase[organIndex])

    /** Конец арены клеток — предел обхода организма. */
    fun cellArenaEnd(organIndex: Int) = cellArenaBase[organIndex] + cellArenaCapacity[organIndex]

    fun linkArenaEnd(organIndex: Int) = linkArenaBase[organIndex] + linkArenaCapacity[organIndex]


    /** Есть ли у организма арена нейросвязей. */
    fun hasNeuralArena(organIndex: Int) = organIndex != -1 && neuralArenaBase[organIndex] != -1

    /**
     * Слот для нейросвязи организма, или -1, если арена полна. Сначала освобождённый —
     * перевязка нейросвязей идёт всё время роста, — потом следующий по курсору.
     */
    fun takeNeuralSlot(organIndex: Int): Int {
        val reused = popFree(neuralFreeSlots, organIndex)
        if (reused != -1) return reused
        val used = neuralArenaUsed[organIndex]
        if (used >= neuralArenaCapacity[organIndex]) return -1
        neuralArenaUsed[organIndex] = used + 1
        return neuralArenaBase[organIndex] + used
    }

    fun releaseNeuralSlot(organIndex: Int, linkIndex: Int) {
        if (organIndex == -1) return
        freeListOf(neuralFreeSlots, organIndex).add(linkIndex)
    }

    fun neuralArenaEnd(organIndex: Int) = neuralArenaBase[organIndex] + neuralArenaUsed[organIndex]

    private fun freeListOf(lists: Array<IntArrayList?>, organIndex: Int): IntArrayList {
        var list = lists[organIndex]
        if (list == null) {
            // Создаётся лениво: у организма, который ничего не терял, списка нет вовсе.
            list = IntArrayList(16)
            lists[organIndex] = list
        }
        return list
    }

    /** Снять слот со свободного списка, или -1 если он пуст. */
    private fun popFree(lists: Array<IntArrayList?>, organIndex: Int): Int {
        val list = lists[organIndex] ?: return -1
        if (list.isEmpty) return -1
        return list.removeInt(list.size - 1)
    }

    fun addOrgan(
        genomeIndex: Int,
        genomeSize: Int,
        dividedTimes: Int = 0,
        mutatedTimes: Int = 0,
    ): Int {
        val organIndex = add()

        this.genomeIndex[organIndex] = genomeIndex
        this.genomeSize[organIndex] = genomeSize
        this.stage[organIndex] = 0
        this.dividedTimes[organIndex] = dividedTimes
        this.mutatedTimes[organIndex] = mutatedTimes
        this.alreadyGrownUp[organIndex] = false
        this.divideCounterThisStage[organIndex] = 0
        this.mutateCounterThisStage[organIndex] = 0
        this.divideAmountThisStage[organIndex] = dividedTimes
        this.mutateAmountThisStage[organIndex] = mutatedTimes
        this.justChangedStage[organIndex] = true
        return organIndex
    }

    fun deleteOrgan(organIndex: Int) {
        delete(organIndex)

        genomeIndex[organIndex] = -1
        genomeSize[organIndex] = 0
        stage[organIndex] = 0
        dividedTimes[organIndex] = 0
        mutatedTimes[organIndex] = 0
        alreadyGrownUp[organIndex] = false
        divideCounterThisStage[organIndex] = 0
        mutateCounterThisStage[organIndex] = 0
        divideAmountThisStage[organIndex] = 0
        mutateAmountThisStage[organIndex] = 0
        justChangedStage[organIndex] = true

        // Арена возвращается в оборот — следующему рождению (Entity.releaseRange). Размер
        // тот же, что брался в allocateArenas, вместе с зазором. Дескриптор гасится, чтобы
        // мёртвый organIndex не выглядел как владелец диапазона — иначе обход по аренам
        // прошёлся бы по чужой памяти, если индекс организма переиспользуется.
        if (cellArenaBase[organIndex] != -1) {
            val cellRange = cellArenaCapacity[organIndex] + ARENA_GAP
            cellEntityRef?.releaseRange(cellArenaBase[organIndex], cellRange)
            particleEntityRef?.releaseRange(particleArenaBase[organIndex], cellRange)
            linkEntityRef?.releaseRange(linkArenaBase[organIndex], linkArenaCapacity[organIndex] + ARENA_GAP)
        }
        if (neuralArenaBase[organIndex] != -1) {
            neuralLinkEntityRef?.releaseRange(neuralArenaBase[organIndex], neuralArenaCapacity[organIndex] + ARENA_GAP)
        }
        liveCells[organIndex] = 0
        arenaLayout[organIndex] = null
        // Списки чистятся, но не выбрасываются: слот организма переиспользуется, и вместе
        // с ним переиспользуется уже выделенный IntArrayList.
        neuralFreeSlots[organIndex]?.clear()
        cellArenaBase[organIndex] = -1
        cellArenaCapacity[organIndex] = 0
        particleArenaBase[organIndex] = -1
        linkArenaBase[organIndex] = -1
        linkArenaCapacity[organIndex] = 0
        neuralArenaBase[organIndex] = -1
        neuralArenaCapacity[organIndex] = 0
        neuralArenaUsed[organIndex] = 0
    }

    override fun persistFields(state: EntityState) {
        state.ints(::genomeIndex)
        state.ints(::genomeSize)
        state.ints(::stage)
        state.ints(::dividedTimes)
        state.ints(::mutatedTimes)
        state.booleans(::alreadyGrownUp)
        state.ints(::divideCounterThisStage)
        state.ints(::mutateCounterThisStage)
        state.ints(::divideAmountThisStage)
        state.ints(::mutateAmountThisStage)
        state.booleans(::justChangedStage)

        state.ints(::cellArenaBase)
        state.ints(::cellArenaCapacity)
        state.ints(::particleArenaBase)
        state.ints(::linkArenaBase)
        state.ints(::linkArenaCapacity)
        state.ints(::neuralArenaBase)
        state.ints(::neuralArenaCapacity)
        state.ints(::neuralArenaUsed)
        state.packed("neuralFreeSlots", write = { packFreeSlots(neuralFreeSlots) }, read = { unpackFreeSlots(it, neuralFreeSlots) })
        // arenaLayout — ссылка внутрь генома, её ставит restoreArenaLayouts.
    }

    /** Свободные списки подряд: [organIndex, size, слоты...] на каждый непустой список. */
    private fun packFreeSlots(lists: Array<IntArrayList?>): IntArray {
        val packed = IntArrayList()
        for (organIndex in 0..lastId) {
            val list = lists[organIndex] ?: continue
            if (list.isEmpty) continue
            packed.add(organIndex)
            packed.add(list.size)
            packed.addAll(list)
        }
        return packed.toIntArray()
    }

    private fun unpackFreeSlots(packed: IntArray, lists: Array<IntArrayList?>) {
        var i = 0
        while (i < packed.size) {
            val organIndex = packed[i]
            val size = packed[i + 1]
            freeListOf(lists, organIndex).addElements(0, packed, i + 2, size)
            i += 2 + size
        }
    }

    /** Раскладки арен после загрузки мира: их источник — геном организма, как в allocateArenas. */
    fun restoreArenaLayouts(genomes: List<Genome>) {
        val particlesInDeadStack = particleEntityRef?.deadStackMask() ?: BooleanArray(0)
        for (i in 0 until aliveList.size) {
            val organIndex = aliveList.getInt(i)
            if (cellArenaBase[organIndex] == -1) continue
            arenaLayout[organIndex] = genomes.getOrNull(genomeIndex[organIndex])?.sortedGraph
            // До починки частицы клеток выдавались без владельца (addParticleAt), и
            // умершая уходила в общий стек — под субстанцию. С возвратом арен такой слот
            // попал бы и в стек, и в свободный кусок, то есть был бы выдан дважды.
            particleEntityRef?.adoptRange(
                particleArenaBase[organIndex], cellArenaCapacity[organIndex] + ARENA_GAP, organIndex, particlesInDeadStack
            )
        }
        particleEntityRef?.dropOwnedFromDeadStack()
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        genomeIndex.clear(-1)
        genomeSize.clear()
        stage.clear()
        dividedTimes.clear()
        mutatedTimes.clear()
        alreadyGrownUp.clear(false)
        divideCounterThisStage.clear()
        mutateCounterThisStage.clear()
        divideAmountThisStage.clear()
        mutateAmountThisStage.clear()
        justChangedStage.clear(true)

        liveCells.clear()
        emptiedOrgans.clear()
        arenaLayout.fill(null)
        for (list in neuralFreeSlots) list?.clear()
        cellArenaBase.clear(-1)
        cellArenaCapacity.clear()
        particleArenaBase.clear(-1)
        linkArenaBase.clear(-1)
        linkArenaCapacity.clear()
        neuralArenaBase.clear(-1)
        neuralArenaCapacity.clear()
        neuralArenaUsed.clear()
        // Откатывать здесь нечего: курсор брони — это lastId самой сущности, а его
        // сбрасывает её собственный clear().
    }

    override fun onResize(oldMax: Int) {
        genomeIndex = genomeIndex.resize(-1)
        genomeSize = genomeSize.resize()
        stage = stage.resize()
        dividedTimes = dividedTimes.resize()
        mutatedTimes = mutatedTimes.resize()
        alreadyGrownUp = alreadyGrownUp.resize(false)
        divideCounterThisStage = divideCounterThisStage.resize()
        mutateCounterThisStage = mutateCounterThisStage.resize()
        divideAmountThisStage = divideAmountThisStage.resize()
        mutateAmountThisStage = mutateAmountThisStage.resize()
        justChangedStage = justChangedStage.resize(true)

        liveCells = liveCells.resize()
        arenaLayout = arenaLayout.copyOf(maxAmount)
        neuralFreeSlots = neuralFreeSlots.copyOf(maxAmount)
        cellArenaBase = cellArenaBase.resize(-1)
        cellArenaCapacity = cellArenaCapacity.resize()
        particleArenaBase = particleArenaBase.resize(-1)
        linkArenaBase = linkArenaBase.resize(-1)
        linkArenaCapacity = linkArenaCapacity.resize()
        neuralArenaBase = neuralArenaBase.resize(-1)
        neuralArenaCapacity = neuralArenaCapacity.resize()
        neuralArenaUsed = neuralArenaUsed.resize()
    }

    companion object {
        /**
         * Пропуск между аренами, в элементах.
         *
         * 64 элемента — это 64 байта даже у ByteArray/BooleanArray, то есть гарантированно
         * не меньше кэш-линии в КАЖДОМ массиве сущности, независимо от типа элемента и от
         * того, как JVM выровняла сам объект массива. Без этого зазора последний элемент
         * арены A и первый элемент арены B делили бы линию, и два воркера, считающие
         * соседние организмы, гоняли бы её между ядрами (false sharing).
         *
         * Цена — 64 слота на организм на массив. При арене в ~1200 клеток это ~5%.
         * Для мелких организмов доля вырастет, и тогда зазор имеет смысл считать
         * по типу массива (16 элементов хватает для Float/Int), но пока организмы
         * крупные, единое число проще и надёжнее.
         */
        const val ARENA_GAP = 64
    }
}
