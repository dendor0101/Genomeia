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
    // Размер фиксирован при рождении: тело растёт только по запечённому геному (зигота
    // от деления и мутации — пустышка, см. CellEntity.isDormantZygote). Слоты ВНУТРИ живой
    // арены переиспользуются через свободный список, а вся арена умершего организма
    // уходит в свободные куски сущностей (Entity.releaseRange) и выдаётся следующему
    // рождению. Переноса арен (уплотнения) пока нет.
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
     * Освободившиеся слоты внутри арены — по одному списку на организм и на вид сущности.
     *
     * ЗАЧЕМ ОНИ ПОЯВИЛИСЬ
     * -------------------
     * Первая итерация арен обходилась без переиспользования: умерший слот просто терялся,
     * а платой был запас ёмкости ARENA_HEADROOM_PERCENT. Это оказалось несостоятельным.
     * Нейросвязи при росте организма активно ПЕРЕВЯЗЫВАЮТСЯ — создаются, удаляются и
     * создаются заново, — поэтому арена расходовалась не по числу живых нейросвязей, а по
     * числу всех когда-либо созданных. При 124 живых и ёмкости 161 организм упирался
     * в границу и падал. Любая константа лишь отодвигает этот момент: расход растёт со
     * временем, а ёмкость фиксирована.
     *
     * Свободный список делает расход пропорциональным ЖИВЫМ сущностям, а не истории.
     *
     * ПОЧЕМУ СПИСОК СВОЙ, А НЕ ОБЩИЙ deadStack
     * ----------------------------------------
     * Общий стек отдал бы слот организма A организму B, и тело B оказалось бы посреди
     * чужой арены — ровно то, ради чего аренами и занимались. Здесь слот возвращается
     * строго в свою арену, поэтому непрерывность сохраняется.
     *
     * LIFO намеренно: последний освобождённый слот с большой вероятностью ещё горячий
     * в кэше, а порядок внутри арены нам важен только для запечённых слотов — их выдаёт
     * не список, а карта раскладки.
     */
    private var cellFreeSlots = arrayOfNulls<IntArrayList>(maxAmount)
    private var linkFreeSlots = arrayOfNulls<IntArrayList>(maxAmount)

    /** Начало диапазона организма в CellEntity, -1 если арены нет. */
    var cellArenaBase = IntArray(maxAmount) { -1 }

    /** Сколько слотов зарезервировано под клетки (и, столько же, под частицы). */
    var cellArenaCapacity = IntArray(maxAmount)

    /** Сколько слотов клеток уже роздано: bump-указатель внутри арены. */
    var cellArenaUsed = IntArray(maxAmount)

    /** Начало диапазона организма в ParticleEntity. Ёмкость общая с клетками. */
    var particleArenaBase = IntArray(maxAmount) { -1 }

    var linkArenaBase = IntArray(maxAmount) { -1 }
    var linkArenaCapacity = IntArray(maxAmount)
    var linkArenaUsed = IntArray(maxAmount)

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

    fun bindEntities(
        cellEntity: Entity,
        particleEntity: Entity,
        linkEntity: Entity
    ) {
        cellEntityRef = cellEntity
        particleEntityRef = particleEntity
        linkEntityRef = linkEntity
    }

    /** Есть ли у организма арена. Клетки без организма (organIndex == -1) идут общим путём. */
    fun hasArena(organIndex: Int) = organIndex != -1 && cellArenaBase[organIndex] != -1

    /**
     * Резервирует диапазоны под организм. Вызывать сразу после [addOrgan], до создания
     * первой клетки.
     *
     * Ёмкости берутся из размеров, снятых при запекании генома, плюс
     * [ARENA_HEADROOM_PERCENT] на клетки сверх запечённых.
     */
    fun allocateArenas(
        organIndex: Int,
        /**
         * Запечённая раскладка из генома, или null если геном не запечён. Задаёт, в каком
         * слоте арены окажется клетка с данным cellGenomeId. См. BakedLayout.
         */
        layout: BakedLayout? = null,
        maxCells: Int,
        maxLinks: Int
    ) {
        if (!arenasEnabled) return

        val cells = cellEntityRef ?: return
        val particles = particleEntityRef ?: return
        val links = linkEntityRef ?: return

        arenaLayout[organIndex] = layout

        // Ёмкость не может быть меньше запечённой раскладки, чего бы ни просили в maxCells:
        // раскладка адресует слоты напрямую, и слот за границей арены попал бы в чужую.
        val cellCapacity = withHeadroom(maxOf(maxCells, layout?.cellsInLayout ?: 0))
        val linkCapacity = withHeadroom(maxOf(maxLinks, layout?.linksInLayout ?: 0))

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

        // Bump-курсор ставится ЗА запечённой областью, а не в ноль.
        //
        // Слоты внутри раскладки раздаются по cellGenomeId напрямую, курсором не через.
        // Если оставить курсор в нуле, первая же клетка, которой в раскладке нет (геном
        // мутировал после запекания), получила бы слот 0 — то есть чужой, уже принадлежащий
        // запечённой клетке. Так области не пересекаются: запечённая занимает начало арены,
        // курсор выдаёт только хвост.
        cellArenaUsed[organIndex] = layout?.cellsInLayout ?: 0
        linkArenaUsed[organIndex] = layout?.linksInLayout ?: 0
    }

    /**
     * Следующий свободный индекс клетки внутри арены организма, или -1 если арена исчерпана.
     *
     * -1 означает, что организм вырос больше, чем под него зарезервировали. Вызывающий
     * обязан это проверить: молча уйти в общий аллокатор нельзя, иначе часть тела окажется
     * вне арены, и обход по диапазону её просто не увидит.
     */
    fun takeCellSlot(organIndex: Int, cellGenomeId: Int): Int {
        // Запечённая клетка идёт в свой слот, а не в следующий свободный: именно этот
        // порядок и есть результат RCM, ради которого всё запекалось.
        val baked = arenaLayout[organIndex]?.slotByCellGenomeId?.get(cellGenomeId) ?: -1
        if (baked != -1 && baked < cellArenaCapacity[organIndex]) {
            val slot = cellArenaBase[organIndex] + baked
            return slot
        }

        val reused = popFree(cellFreeSlots, organIndex)
        if (reused != -1) return reused

        val used = cellArenaUsed[organIndex]
        if (used >= cellArenaCapacity[organIndex]) return -1
        cellArenaUsed[organIndex] = used + 1
        return cellArenaBase[organIndex] + used
    }

    fun takeLinkSlot(organIndex: Int, cellGenomeIdA: Int, cellGenomeIdB: Int): Int {
        val baked = arenaLayout[organIndex]?.slotByLinkPair?.get(cellGenomeIdA, cellGenomeIdB) ?: -1
        if (baked != -1 && baked < linkArenaCapacity[organIndex]) {
            val slot = linkArenaBase[organIndex] + baked
            return slot
        }

        val reused = popFree(linkFreeSlots, organIndex)
        if (reused != -1) return reused

        val used = linkArenaUsed[organIndex]
        if (used >= linkArenaCapacity[organIndex]) return -1
        linkArenaUsed[organIndex] = used + 1
        return linkArenaBase[organIndex] + used
    }

    /**
     * Частица клетки по параллельности арен, без чтения cellEntity.particleIndexes.
     * Вызывать только когда [hasArena] истинно.
     */
    fun particleIndexOfCell(organIndex: Int, cellIndex: Int) =
        particleArenaBase[organIndex] + (cellIndex - cellArenaBase[organIndex])

    /** Верхняя граница занятой части арены клеток — предел обхода организма. */
    fun cellArenaEnd(organIndex: Int) = cellArenaBase[organIndex] + cellArenaUsed[organIndex]

    fun linkArenaEnd(organIndex: Int) = linkArenaBase[organIndex] + linkArenaUsed[organIndex]


    // ===================================================================================
    // ВОЗВРАТ СЛОТОВ
    //
    // Вызывать из delete-путей сущностей ДО того, как сбросится содержимое: владелец
    // берётся из Entity.arenaOwnerOf, то есть запомнен при выдаче и не зависит от того,
    // жива ли ещё клетка, через которую связь узнавала свой организм.
    // ===================================================================================

    // Запечённый слот в свободный список НЕ возвращается: он принадлежит своей клетке
    // (связи) по раскладке, и та получает его напрямую, мимо списка. Попади он в список,
    // его отдали бы и постороннему — тогда либо посторонний ляжет на место вернувшейся
    // запечённой (addAt падает: «арена выдала занятый индекс»), либо вернувшаяся найдёт
    // свой слот занятым. Так бывало, когда рвалась и создавалась заново запечённая связь,
    // а следом приходила связь вне раскладки (ArenaFreeSlotsTest). Пока запечённая клетка
    // мертва, её слот просто ждёт: место под неё в арене и так зарезервировано.

    fun releaseCellSlot(organIndex: Int, cellIndex: Int) {
        if (organIndex == -1) return
        if (cellIndex - cellArenaBase[organIndex] < bakedCells(organIndex)) return
        freeListOf(cellFreeSlots, organIndex).add(cellIndex)
    }

    fun releaseLinkSlot(organIndex: Int, linkIndex: Int) {
        if (organIndex == -1) return
        if (linkIndex - linkArenaBase[organIndex] < bakedLinks(organIndex)) return
        freeListOf(linkFreeSlots, organIndex).add(linkIndex)
    }

    /** Сколько слотов в начале арены занято раскладкой — их адресует карта, а не список. */
    private fun bakedCells(organIndex: Int) = arenaLayout[organIndex]?.cellsInLayout ?: 0
    private fun bakedLinks(organIndex: Int) = arenaLayout[organIndex]?.linksInLayout ?: 0

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

    private fun withHeadroom(size: Int) = size + (size * ARENA_HEADROOM_PERCENT / 100)

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
        liveCells[organIndex] = 0
        arenaLayout[organIndex] = null
        // Списки чистятся, но не выбрасываются: слот организма переиспользуется, и вместе
        // с ним переиспользуется уже выделенный IntArrayList.
        cellFreeSlots[organIndex]?.clear()
        linkFreeSlots[organIndex]?.clear()
        cellArenaBase[organIndex] = -1
        cellArenaCapacity[organIndex] = 0
        cellArenaUsed[organIndex] = 0
        particleArenaBase[organIndex] = -1
        linkArenaBase[organIndex] = -1
        linkArenaCapacity[organIndex] = 0
        linkArenaUsed[organIndex] = 0
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
        state.ints(::cellArenaUsed)
        state.ints(::particleArenaBase)
        state.ints(::linkArenaBase)
        state.ints(::linkArenaCapacity)
        state.ints(::linkArenaUsed)
        state.packed("cellFreeSlots", write = { packFreeSlots(cellFreeSlots) }, read = { unpackFreeSlots(it, cellFreeSlots) })
        state.packed("linkFreeSlots", write = { packFreeSlots(linkFreeSlots) }, read = { unpackFreeSlots(it, linkFreeSlots) })
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
            // Сохранения, сделанные до починки releaseCellSlot, могут нести запечённые слоты
            // в свободных списках. Раскладка известна только сейчас — здесь их и убираем.
            dropBakedSlots(cellFreeSlots[organIndex], cellArenaBase[organIndex], bakedCells(organIndex))
            dropBakedSlots(linkFreeSlots[organIndex], linkArenaBase[organIndex], bakedLinks(organIndex))
            // До починки частицы клеток выдавались без владельца (addParticleAt), и
            // умершая уходила в общий стек — под субстанцию. С возвратом арен такой слот
            // попал бы и в стек, и в свободный кусок, то есть был бы выдан дважды.
            particleEntityRef?.adoptRange(
                particleArenaBase[organIndex], cellArenaCapacity[organIndex] + ARENA_GAP, organIndex, particlesInDeadStack
            )
        }
        particleEntityRef?.dropOwnedFromDeadStack()
    }

    private fun dropBakedSlots(list: IntArrayList?, base: Int, baked: Int) {
        if (list == null || baked == 0) return
        var write = 0
        for (read in 0 until list.size) {
            val slot = list.getInt(read)
            if (slot - base >= baked) list.set(write++, slot)
        }
        list.size(write)
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
        for (list in cellFreeSlots) list?.clear()
        for (list in linkFreeSlots) list?.clear()
        cellArenaBase.clear(-1)
        cellArenaCapacity.clear()
        cellArenaUsed.clear()
        particleArenaBase.clear(-1)
        linkArenaBase.clear(-1)
        linkArenaCapacity.clear()
        linkArenaUsed.clear()
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
        cellFreeSlots = cellFreeSlots.copyOf(maxAmount)
        linkFreeSlots = linkFreeSlots.copyOf(maxAmount)
        cellArenaBase = cellArenaBase.resize(-1)
        cellArenaCapacity = cellArenaCapacity.resize()
        cellArenaUsed = cellArenaUsed.resize()
        particleArenaBase = particleArenaBase.resize(-1)
        linkArenaBase = linkArenaBase.resize(-1)
        linkArenaCapacity = linkArenaCapacity.resize()
        linkArenaUsed = linkArenaUsed.resize()
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

        /**
         * Запас ёмкости арены сверх размера взрослого организма, в процентах.
         *
         * Нужен потому, что переиспользования слотов внутри арены пока нет: умершая клетка
         * освобождает место физически, но не логически. Запас определяет, сколько смертей
         * организм переживёт, прежде чем упрётся в границу арены.
         */
        /**
         * Запас ёмкости арены сверх размеров, снятых при запекании, в процентах.
         *
         * Ноль — потому что запас больше не за что платить. Он обосновывался тем, что
         * умерший слот терялся навсегда; со свободным списком внутри арены расход стал
         * пропорционален ЖИВЫМ сущностям, а не истории создания.
         *
         * Поднимать придётся, когда геном научится создавать клеток больше, чем было при
         * запекании: запечённые слоты адресуются напрямую, а всё сверх них берётся
         * bump-указателем из этого запаса.
         */
        const val ARENA_HEADROOM_PERCENT = 0
    }
}
