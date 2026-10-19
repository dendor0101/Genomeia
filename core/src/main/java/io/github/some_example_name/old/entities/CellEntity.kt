package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.cells.base.CellTypeList
import io.github.some_example_name.old.cells.base.Formula
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.UnknownCellTypeException
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.utils.OrderedIntPairMap
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.simulation.SimulationData
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList

class CellEntity(
    cellsStartMaxAmount: Int,
    private val particleEntity: ParticleEntity,
    val simulationData: SimulationData,
    val substrateSettings: SubstrateSettings,
    val cellList: CellTypeList,
    private val neuralEntity: NeuralEntity,
    val specialEntity: SpecialEntity,
    /**
     * Источник арен. Клетка берёт слот из диапазона своего организма, а не из общего
     * deadStack, чтобы всё тело лежало в массивах подряд — см. OrganEntity.
     *
     * Клетки без организма (organIndex == -1) и режим редактора идут прежним путём:
     * hasArena вернёт false, и выдачей займётся обычный add().
     */
    val organEntity: OrganEntity
) : Entity(cellsStartMaxAmount) {
    /**
     * Формулы активации по номеру — копия ссылки в поле: activation читает её на каждый
     * нейрон за тик, а Formulas.all() идёт через lazy (volatile-чтение на вызов).
     */
    val formulas: Array<Formula> = Formulas.all()

    //Particle entity
    var particleIndexes = IntArray(maxAmount) { -1 }
    fun getParticleIndex(index: Int) = particleIndexes[index]
    fun getX(index: Int) = particleEntity.x[particleIndexes[index]]
    fun getY(index: Int) = particleEntity.y[particleIndexes[index]]
    fun setX(index: Int, value: Float) { particleEntity.x[particleIndexes[index]] = value }
    fun setY(index: Int, value: Float) { particleEntity.y[particleIndexes[index]] = value }
    fun getVx(index: Int) = particleEntity.vx[particleIndexes[index]]
    fun getVy(index: Int) = particleEntity.vy[particleIndexes[index]]
    fun setVx(index: Int, value: Float) { particleEntity.vx[particleIndexes[index]] = value }
    fun setVy(index: Int, value: Float) { particleEntity.vy[particleIndexes[index]] = value }
    fun getDragCoefficient(index: Int) = particleEntity.dragCoefficient[particleIndexes[index]]
    fun setDragCoefficient(index: Int, value: Float) { particleEntity.dragCoefficient[particleIndexes[index]] = value }
    fun getEffectOnContact(index: Int) = particleEntity.effectOnContact[particleIndexes[index]]
    fun setEffectOnContact(index: Int, value: Boolean) { particleEntity.effectOnContact[particleIndexes[index]] = value }
    fun getIsCollidable(index: Int) = particleEntity.isCollidable[particleIndexes[index]]
    fun setIsCollidable(index: Int, value: Boolean) { particleEntity.isCollidable[particleIndexes[index]] = value }
    fun getCellStiffness(index: Int) = particleEntity.cellStiffness[particleIndexes[index]]
    fun setCellStiffness(index: Int, value: Float) { particleEntity.cellStiffness[particleIndexes[index]] = value }
    fun getRadius(index: Int) = particleEntity.radius[particleIndexes[index]]
    fun setRadius(index: Int, value: Float) { particleEntity.radius[particleIndexes[index]] = value }
    fun getGridId(index: Int) = particleEntity.gridId[particleIndexes[index]]
    fun seGridId(index: Int, value: Int) { particleEntity.gridId[particleIndexes[index]] = value }
    fun getSimTime(index: Int) = simulationData.timeSimulation
    fun getColor(index: Int) = particleEntity.color[particleIndexes[index]]
    fun setColor(index: Int, value: Int) { particleEntity.color[particleIndexes[index]] = value }
    fun getIsPheromoneEmitter(index: Int) = particleEntity.isPheromoneEmitter[particleIndexes[index]]
    fun setIsPheromoneEmitter(index: Int, value: Boolean) { particleEntity.isPheromoneEmitter[particleIndexes[index]] = value }
    /**
     * Сколько живых клеток лежит ВНЕ арен (organIndex == -1 либо у организма арены нет).
     *
     * Нужен ровно для того, чтобы фаза клеток могла не заводить работу по «сиротам», когда
     * их нет: сама эта работа стоит проход по всему aliveList с проверкой, и на стенде,
     * где все клетки в аренах, это был бы холостой обход тысяч клеток на одном воркере
     * в каждом тике. Точный счётчик дешевле любой оценки: два инкремента на создание и
     * удаление против прохода по миру за тик.
     */
    var orphanCellCount = 0
        private set

    var cellGenomeId = IntArray(maxAmount) { -1 }
    var cellActions: Array<CellAction?> = arrayOfNulls(maxAmount)
    var organIndex = IntArray(maxAmount) { -1 }
    var parentIndex = IntArray(maxAmount) { -1 }
    var angleCos = FloatArray(maxAmount)
    var angleSin = FloatArray(maxAmount)
    var angleDirectedCos = FloatArray(maxAmount)
    var angleDirectedSin = FloatArray(maxAmount)
    var angleCompensationCos = FloatArray(maxAmount)
    var angleCompensationSin = FloatArray(maxAmount)
    var energyNecessaryToDivide = FloatArray(maxAmount) { 2f }
    var energyNecessaryToMutate = FloatArray(maxAmount) { 1f }
    var isDividedInThisStage = BooleanArray(maxAmount)
    var isMutateInThisStage = BooleanArray(maxAmount)
    var cellType = ByteArray(maxAmount)
    var energy = FloatArray(maxAmount)
    var maxEnergy = FloatArray(maxAmount)
    var isNeural = BooleanArray(maxAmount)
    var neuronImpulseInput = FloatArray(maxAmount)
    var neuronImpulseOutput = FloatArray(maxAmount)
    var isOnEdge = BooleanArray(maxAmount)
    var degreeOfShortening = FloatArray(maxAmount) { 1f }
    var pheromoneType = IntArray(maxAmount) { -1 }
    var linkAmount = IntArray(maxAmount) { 0 }
    @Transient val organToIdToIndex = OrderedIntPairMap(maxAmount)
    var neuralConnections = Int2ObjectOpenHashMap<IntArrayList>()

    /**
     * Зигота-пустышка: стала зиготой делением или мутацией внутри тела, а не родилась
     * корнем организма. Действий генома не получает, ничего не растит и энергии не
     * копит — арена организма фиксирована при рождении и под новое тело на старом не
     * рассчитана. Корень отличается номером в геноме: у него всегда
     * [Zygote.ROOT_CELL_GENOME_ID], у клеток от деления — номер своего действия.
     */
    fun isDormantZygote(cellIndex: Int) =
        cellGenomeId[cellIndex] != Zygote.ROOT_CELL_GENOME_ID && cellList[cellType[cellIndex].toInt()] is Zygote

    /**
     * Инлайн-список соседей по физическим связям: MAX_LINKS_PER_CELL слотов на клетку,
     * подряд, начиная с cellIndex * MAX_LINKS_PER_CELL. Пустой слот = -1.
     *
     * Зачем это вообще нужно: проверка «связаны ли две клетки» делается в repulse на
     * каждое касание клетка-клетка, то есть миллионы раз за тик. Раньше это был
     * UnorderedIntPairMap поверх Long2IntOpenHashMap на 100k записей — таблица
     * ~1.5+ МБ, то есть больше любого L2, а обращение к ней это случайный пробинг:
     * почти гарантированный промах в L3/RAM (~200 циклов), плюс возможное линейное
     * пробирование по нескольким кэш-линиям. Одна такая проверка стоила дороже, чем
     * вся остальная математика касания.
     *
     * Слотов 16, но горячими являются только первые HOT_LINKS = 8: 8 int'ов это 32 байта,
     * и такой блок почти всегда попадает в одну кэш-линию целиком. Полные 64 байта дали бы
     * ровно размер линии, но не её выравнивание: данные массива в JVM начинаются после
     * хедера объекта, сам объект выровнен по 8/16 байт, а не по 64 — то есть 64-байтный
     * блок почти гарантированно лёг бы на границу и стоил двух промахов вместо одного.
     * Поэтому вторая половина списка читается только у клеток, у которых связей реально
     * больше восьми (см. areCellsLinked).
     *
     * Отсюда и ограничение симуляции: у клетки не может быть больше MAX_LINKS_PER_CELL
     * физических связей. Список хранится плотно (все живые соседи идут подряд от начала),
     * удаление делается swap-with-last — на плотность опирается и быстрый выход из скана,
     * и canAddCellLink.
     *
     * [linkAmount] ведётся параллельно этому списку и считает ТОЛЬКО физические связи:
     * инкремент в LinkEntity.addLink, декремент в LinkEntity.deleteLink.
     */
    var cellLinks = IntArray(maxAmount * MAX_LINKS_PER_CELL) { -1 }

    /**
     * Индексы связей, параллельно [cellLinks]: в слоте i лежит индекс той связи, которая
     * соединяет клетку с соседом cellLinks[i]. Оба массива всегда меняются вместе.
     *
     * Зачем отдельный массив, а не пара в одном: [cellLinks] сканируется в repulse на
     * каждое касание клеток, и весь смысл его раскладки в том, что первые HOT_LINKS слотов
     * лежат в ОДНОЙ кэш-линии. Если положить рядом ещё и индекс связи, на ту же выборку
     * понадобится две линии, и горячая проверка подорожает вдвое ради данных, которые ей
     * не нужны. Здесь наоборот — этот массив холодный: он читается только при разрыве
     * связи и при смерти клетки.
     *
     * Зачем он вообще: чтобы при смерти клетки можно было СРАЗУ снять все её связи. Раньше
     * это делалось лениво — processLink каждый тик проверял у каждой связи, живы ли обе
     * клетки, и по индексу клетки добраться до её связей было нельзя. Проверка стоила шесть
     * чтений из шести разных массивов на каждую связь каждый тик; теперь та же работа
     * делается один раз на смерть клетки и пропорциональна числу её связей, а не числу
     * связей в мире.
     */
    var cellLinkIds = IntArray(maxAmount * MAX_LINKS_PER_CELL) { -1 }

    /**
     * Горячая проверка из repulse.
     *
     * Первые HOT_LINKS слотов сканируются развёрнуто и без ранних выходов: они лежат в
     * одной кэш-линии, поэтому лишние загрузки бесплатны, а вот выход по первому -1
     * добавил бы плохо предсказуемую ветку (у клеток бывает и 2, и 6 связей) — промах
     * предсказателя дороже семи чтений из уже загруженной линии. Результаты сравнений
     * складываются через `or` без короткого замыкания, чтобы не появилось восемь
     * условных переходов.
     *
     * Дальше стоит ровно одна ветка: список плотный, поэтому непустой слот HOT_LINKS - 1
     * означает «связей девять или больше». У 99% клеток их 2-6, так что ветка
     * предсказывается почти идеально, и вторая кэш-линия в типичном случае не трогается
     * вообще — проверка стоит столько же, сколько при лимите в 8 связей.
     *
     * otherCellIndex всегда валидный индекс живой клетки (никогда -1), так что пустые
     * слоты со -1 никогда не дадут ложного совпадения.
     */
    fun areCellsLinked(cellIndex: Int, otherCellIndex: Int): Boolean {
        val links = cellLinks
        val base = cellIndex shl LINKS_SHIFT

        val foundInHotHalf = (links[base] == otherCellIndex) or
            (links[base + 1] == otherCellIndex) or
            (links[base + 2] == otherCellIndex) or
            (links[base + 3] == otherCellIndex) or
            (links[base + 4] == otherCellIndex) or
            (links[base + 5] == otherCellIndex) or
            (links[base + 6] == otherCellIndex) or
            (links[base + 7] == otherCellIndex)

        if (foundInHotHalf) return true

        // Список плотный: пустой последний слот первой половины значит, что связей меньше
        // девяти и вторая половина заведомо пустая. Не читаем её — экономим кэш-линию.
        if (links[base + HOT_LINKS - 1] == -1) return false

        return (links[base + 8] == otherCellIndex) or
            (links[base + 9] == otherCellIndex) or
            (links[base + 10] == otherCellIndex) or
            (links[base + 11] == otherCellIndex) or
            (links[base + 12] == otherCellIndex) or
            (links[base + 13] == otherCellIndex) or
            (links[base + 14] == otherCellIndex) or
            (links[base + 15] == otherCellIndex)
    }


    /** Есть ли у клетки свободный слот под ещё одну физическую связь. */
    fun canAddCellLink(cellIndex: Int) =
        cellLinks[(cellIndex shl LINKS_SHIFT) + MAX_LINKS_PER_CELL - 1] == -1

    /**
     * Добавляет соседа в первый свободный слот. false — слотов больше нет.
     * Вызывается только из однопоточной фазы применения команд.
     */
    fun addCellLink(cellIndex: Int, otherCellIndex: Int, linkIndex: Int): Boolean {
        val links = cellLinks
        val linkIds = cellLinkIds
        val base = cellIndex shl LINKS_SHIFT
        for (i in base until base + MAX_LINKS_PER_CELL) {
            if (links[i] == -1) {
                links[i] = otherCellIndex
                linkIds[i] = linkIndex
                return true
            }
        }
        return false
    }

    /**
     * Удаляет одно вхождение соседа, сохраняя плотность списка (swap with last).
     * Если соседа нет — no-op (связь могла быть уже снята вместе со смертью клетки).
     *
     * На плотность опирается и быстрый выход из скана в [areCellsLinked], и [canAddCellLink],
     * и обход в LinkEntity.detachAllLinks, поэтому оба массива двигаются синхронно.
     */
    fun removeCellLink(cellIndex: Int, otherCellIndex: Int) {
        val links = cellLinks
        val linkIds = cellLinkIds
        val base = cellIndex shl LINKS_SHIFT

        var slot = -1
        var last = -1
        for (i in base until base + MAX_LINKS_PER_CELL) {
            val neighbour = links[i]
            if (neighbour == -1) break
            if (neighbour == otherCellIndex && slot == -1) slot = i
            last = i
        }

        if (slot == -1) return

        links[slot] = links[last]
        links[last] = -1
        linkIds[slot] = linkIds[last]
        linkIds[last] = -1
    }

    fun clearCellLinks(cellIndex: Int) {
        val base = cellIndex shl LINKS_SHIFT
        cellLinks.fill(-1, base, base + MAX_LINKS_PER_CELL)
        cellLinkIds.fill(-1, base, base + MAX_LINKS_PER_CELL)
    }

    fun addNeuralConnection(cellIndex: Int, linkIndex: Int) {
        val list = neuralConnections.get(cellIndex) ?: IntArrayList(2).also {
            neuralConnections.put(cellIndex, it)
        }

        if (!list.contains(linkIndex)) {
            list.add(linkIndex)
        }
    }

    fun removeNeuralConnection(cellIndex: Int, linkIndex: Int) {
        val list = neuralConnections.get(cellIndex) ?: return
        list.rem(linkIndex)
    }

    //Neural entity
    var neuralIndexes = IntArray(maxAmount) { -1 }
    fun getNeuralGeneration(index: Int) = neuralEntity.getGeneration(neuralIndexes[index])
    fun getIsNeuronTransportable(index: Int) = neuralEntity.isNeuronTransportable[neuralIndexes[index]]
    fun setIsNeuronTransportable(index: Int, value: Boolean) { neuralEntity.isNeuronTransportable[neuralIndexes[index]] = value }
    fun getActivationFuncType(index: Int) = neuralEntity.activationFuncType[neuralIndexes[index]].toInt()
    fun setActivationFuncType(index: Int, value: Byte) { neuralEntity.activationFuncType[neuralIndexes[index]] = value }
    fun getA(index: Int) = neuralEntity.a[neuralIndexes[index]]
    fun setA(index: Int, value: Float) { neuralEntity.a[neuralIndexes[index]] = value }
    fun getB(index: Int) = neuralEntity.b[neuralIndexes[index]]
    fun setB(index: Int, value: Float) { neuralEntity.b[neuralIndexes[index]] = value }
    fun getC(index: Int) = neuralEntity.c[neuralIndexes[index]]
    fun setC(index: Int, value: Float) { neuralEntity.c[neuralIndexes[index]] = value }
    fun getDTime(index: Int) = neuralEntity.dTime[neuralIndexes[index]]
    fun setDTime(index: Int, value: Float) { neuralEntity.dTime[neuralIndexes[index]] = value}
    fun getRemember(index: Int) = neuralEntity.remember[neuralIndexes[index]]
    fun setRemember(index: Int, value: Float) { neuralEntity.remember[neuralIndexes[index]] = value }
    fun getIsSum(index: Int) = neuralEntity.isSum[neuralIndexes[index]]
    fun setIsSum(index: Int, value: Boolean) {neuralEntity.isSum.set(neuralIndexes[index], value)}
    fun getTickRed(index: Int) = neuralEntity.tickRed[neuralIndexes[index]]
    fun setTickRed(index: Int, value: Int) { neuralEntity.tickRed[neuralIndexes[index]] = value }
    fun getTickPain(index: Int) = neuralEntity.tickPain[neuralIndexes[index]]
    fun setTickPain(index: Int, value: Int) { neuralEntity.tickPain[neuralIndexes[index]] = value }
    fun getWeight(index: Int) = neuralEntity.weight[neuralIndexes[index]]
    fun setWeight(index: Int, value: Float) { neuralEntity.weight[neuralIndexes[index]] = value}

    fun deleteNeural(cellIndex: Int, neuralGeneration: Int? = null) {
        val neuralIndex = neuralIndexes[cellIndex]
        if (neuralIndex == -1) return

        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f
        isNeural[cellIndex] = false

        if (neuralEntity.isAlive[neuralIndex] && (neuralGeneration == null
                || neuralEntity.getGeneration(neuralIndex) == neuralGeneration)) {
            neuralEntity.deleteNeural(neuralIndex)
            neuralIndexes[cellIndex] = -1
        }
    }

    fun addNeural(
        index: Int,
        cellType: Int,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 0
    ) {
        neuronImpulseInput[index] = 0f
        neuronImpulseOutput[index] = 0f
        isNeural[index] = true
        neuralIndexes[index] = neuralEntity.addNeural(cellType, a, b, c, isSum, activationFuncType)
    }

    fun addCell(
        x: Float,
        y: Float,
        color: Int,
        radius: Float = 0.5f,
        cellGenomeId: Int = 0,
        cellType: Int,
        organIndex: Int,
        parentIndex: Int = -1,
        angleCos: Float = 1f,
        angleSin: Float = 0f,
        angleDiffCos: Float = 1f,
        angleDiffSin: Float = 0f,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 7,
        pheromoneType: Int = -1,
        specialModData: SpecialModData? = null
    ): Int {
        // Слот берётся из арены организма, если она у него есть.
        //
        // Исчерпание арены — это ошибка конфигурации, а не штатный режим: ёмкость
        // снята при запекании генома плюс OrganEntity.ARENA_HEADROOM_PERCENT.
        // Молча уйти в общий аллокатор нельзя — часть тела оказалась бы вне диапазона,
        // и обход организма по арене её просто не увидел бы.
        val arenaCellSlot = if (organEntity.hasArena(organIndex)) {
            val slot = organEntity.takeCellSlot(organIndex, cellGenomeId)
            if (slot == -1) {
                throw IllegalStateException(
                    "арена клеток организма $organIndex исчерпана " +
                        "(ёмкость ${organEntity.cellArenaCapacity[organIndex]}): " +
                        "тело выросло больше, чем под него зарезервировали — " +
                        "поднимите OrganEntity.ARENA_HEADROOM_PERCENT или пересохраните геном"
                )
            }
            slot
        } else -1

        val cellIndex =
            if (arenaCellSlot == -1) add() else addAt(arenaCellSlot, arenaOwner = organIndex)
        if (arenaCellSlot == -1) orphanCellCount++

        // Частица ложится по тому же смещению внутри своей арены, что и клетка внутри
        // своей: на этом держится particleIndexOfCell, то есть переход клетка -> частица
        // без чтения particleIndexes.
        val arenaParticleSlot =
            if (arenaCellSlot == -1) -1
            else organEntity.particleIndexOfCell(organIndex, cellIndex)

        particleIndexes[cellIndex] = particleEntity.addParticleAt(
            particleIndex = arenaParticleSlot,
            x = x,
            y = y,
            color = color,
            radius = radius,
            dragCoefficient = substrateSettings.data.viscosityOfTheEnvironment,
            effectOnContact = cellList[cellType].effectOnContact,
            isCollidable = cellList[cellType].isCollidable,
            cellStiffness = cellsSettings[cellType].cellStiffness,
            isCell = true,
            isSub = false,
            holderEntityIndex = cellIndex
        )
        //
        // Зигота ВСЕГДА начинает новую систему отсчёта, даже если появилась делением из
        // родительского организма. Иначе род навсегда остался бы привязан к точке спавна
        // прародителя: потомок наследует смещение относительно родителя, а не его реальное
        // положение, поэтому статические координаты всей линии остались бы возле исходной
        // точки, куда бы организмы ни расплылись. Все их связи свалились бы в одни и те же
        // статические чанки, и стадия связей упёрлась бы в один перегруженный слот.
        //
        // У остальных клеток смещение берётся по текущей реальной позиции родителя: он жив
        // и с момента постановки команды деления не двигался (движение — отдельная фаза).
        // Новую систему отсчёта начинает клетка БЕЗ РОДИТЕЛЯ, а не любая зигота.
        //
        // Организм может содержать несколько зигот: часть тела вырастает из зиготы, которая
        // просто поделилась внутри него (Stem, DivideManager — там parentIndex указывает на
        // делящуюся клетку). Такая зигота остаётся частью того же тела и обязана унаследовать
        // и карту, и якорь. Если дать ей свой якорь, её связи уедут в другой слот, а связь
        // между частями одного организма попадёт в два слота сразу — это и есть гонка.
        //
        // Корневые зиготы приходят без родителя: Producer передаёт parentIndex = -1,
        // UserCommandManager полагается на значение по умолчанию, тоже -1. Поэтому новый
        // организм получает собственный якорь в месте своего спавна, и связи расходятся
        // по слотам так же равномерно, как раньше.
        // Родитель мог умереть, пока команда ждала применения.
        //
        // ADD_CELL формируется в параллельной фазе, а применяется позже и по буферам
        // подряд: DELETE_CELL из буфера 1 выполнится раньше, чем ADD_CELL из буфера 3.
        // Тогда потомок начнёт СВОЮ систему отсчёта посреди чужого организма — якорь
        // разойдётся, и все его связи с телом будут отвергнуты барьером в addLink.
        // Клетка молча останется неприсоединённой, без всякой ошибки.
        //
        // Проверка нужна, чтобы понять, как часто это происходит на самом деле, прежде чем
        // решать, что с такой клеткой делать: не создавать вовсе или считать новым организмом.
        if (DEBUG_CHECKS && parentIndex != -1 && !isAlive[parentIndex]) {
            throw IllegalStateException(
                "клетка $cellIndex создаётся с МЁРТВЫМ родителем $parentIndex: " +
                    "она начнёт свою систему отсчёта (anchorY=$y) посреди организма, " +
                    "и её связи с телом будут отброшены"
            )
        }

        if (parentIndex != -1 && isAlive[parentIndex]) {
        } else {
        }

        this.cellGenomeId[cellIndex] = cellGenomeId
        cellActions[cellIndex] = null
        this.organIndex[cellIndex] = organIndex
        this.parentIndex[cellIndex] = parentIndex
        this.angleCos[cellIndex] = angleCos * angleDiffCos - angleSin * angleDiffSin
        this.angleSin[cellIndex] = angleSin * angleDiffCos + angleCos * angleDiffSin
        this.angleDirectedCos[cellIndex] = angleDiffCos
        this.angleDirectedSin[cellIndex] = angleDiffSin
        this.angleCompensationCos[cellIndex] = 1f
        this.angleCompensationSin[cellIndex] = 0f
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = false
        isMutateInThisStage[cellIndex] = false
        this.cellType[cellIndex] = cellType.toByte()
        energy[cellIndex] = 0.1f
        maxEnergy[cellIndex] = cellsSettings[cellType].maxEnergy
        isOnEdge[cellIndex] = true
        this.degreeOfShortening[cellIndex] = 1f
        this.pheromoneType[cellIndex] = pheromoneType
        linkAmount[cellIndex] = 0
        // Индексы переиспользуются через deadStack, поэтому слоты связей нужно
        // обязательно вычистить: иначе новая клетка унаследует соседей мёртвой.
        clearCellLinks(cellIndex)
        neuralConnections.remove(cellIndex)
        val cell = cellList[cellType]

        if (cell.isNeural) {
            addNeural(cellIndex, cellType, a, b, c, isSum, activationFuncType)
        } else {
            neuronImpulseInput[cellIndex] = 0f
            neuronImpulseOutput[cellIndex] = 0f
            isNeural[cellIndex] = false
            neuralIndexes[cellIndex] = -1
        }

        // Свои данные клетки приходят с командой (деление, см. DivideManager). Кто создаёт
        // клетку напрямую, без генома, — получает данные типа по умолчанию.
        specialEntity.addSpecial(cellIndex, specialModData ?: cell.createSpecialData(Cell.NO_GENOME_ACTION))

        // Ключ (organIndex, cellGenomeId) обязан быть уникальным: по нему деление и мутация
        // находят «клетку-соседа по чертежу генома». Если запись перетирается, значит две
        // РАЗНЫЕ клетки претендуют на один ключ, и поиск начнёт возвращать чужую — вплоть до
        // клетки другого организма, а это уже связь между особями и гонка на vx/vy.
        //
        // Главный подозреваемый — organIndex == -1: такие клетки лежат в карте в ОБЩЕМ для
        // всех безорганных клеток пространстве ключей, потому что ключ это
        // (organIndex shl 32) or genomeId. Раньше так жила зигота-сирота от продюсера; теперь
        // организм заводится до зиготы (OrganismSpawner), но проверка остаётся сторожем.
        //
        // Различаем два случая: перетирание записи ЖИВОЙ клетки (настоящий конфликт) и
        // мёртвой (просроченная запись, которую не убрал deleteCell) — лечатся они по-разному.
        if (DEBUG_CHECKS) {
            val previous = organToIdToIndex.get(organIndex, cellGenomeId)
            if (previous != -1 && previous != cellIndex) {
                throw IllegalStateException(
                    "конфликт ключа organToIdToIndex: organIndex=$organIndex " +
                        "genomeId=$cellGenomeId уже занят клеткой $previous " +
                        "(жива=${isAlive[previous]}, organIndex=${this.organIndex[previous]}, " +
                        "genomeId=${this.cellGenomeId[previous]}), новая клетка $cellIndex"
                )
            }
        }

        organToIdToIndex.put(organIndex, cellGenomeId, cellIndex)

        return cellIndex
    }

    /**
     * Проверка инвариантов арен. Вызывать под DEBUG_CHECKS раз в тик, из однопоточного места.
     *
     * ЗАЧЕМ
     * -----
     * Нарушение арены не падает и даже не портит физику сразу — оно просто выключает то,
     * ради чего арены заводились, и делает это молча. Клетка, оказавшаяся вне диапазона
     * своего организма, будет считаться как раньше и выглядеть совершенно нормально; когда
     * фазы начнут обходить организм ПО ДИАПАЗОНУ АРЕНЫ, она просто перестанет обсчитываться.
     * Поэтому инвариант проверяется явно и заранее, до того как на него начнут опираться.
     *
     * ЧТО ОЗНАЧАЕТ КАЖДОЕ ПАДЕНИЕ
     * ---------------------------
     *  - «вне арены» — клетка получила слот от общего аллокатора, хотя у организма арена
     *    есть. Значит на момент её addCell organIndex был ещё не проставлен: искать в
     *    порядке команд, кто создал клетку раньше, чем организм узнал свой индекс.
     *  - «частица разошлась с клеткой» — сломана параллельность арен, на которой держится
     *    particleIndexOfCell. Искать в addCell: слот клетки и слот частицы обязаны браться
     *    из одного смещения.
     */
    fun verifyArenaIntegrity() {
        for (i in 0 until aliveList.size) {
            val cellIndex = aliveList.getInt(i)

            // SpecialEntity индексируется индексом КЛЕТКИ, а не своим. Раньше это держалось
            // на том, что обе сущности растут в ногу, и разъехалось на аренах. Теперь связь
            // явная (SpecialEntity.addSpecial), и её стоит охранять: расхождение проявляется
            // не здесь, а через -1 в specialTypeIndexes у первого же глаза или хвоста.
            if (!specialEntity.isAlive[cellIndex]) {
                throw IllegalStateException(
                    "у живой клетки $cellIndex нет записи в SpecialEntity: " +
                        "индексы клеток и специальных частей разошлись"
                )
            }

            val organ = organIndex[cellIndex]
            if (!organEntity.hasArena(organ)) continue

            val from = organEntity.cellArenaBase[organ]
            val to = organEntity.cellArenaEnd(organ)
            if (cellIndex < from || cellIndex >= to) {
                throw IllegalStateException(
                    "клетка $cellIndex организма $organ вне его арены [$from, $to) — " +
                        "обход по диапазону арены её не увидит"
                )
            }

            val expectedParticle = organEntity.particleIndexOfCell(organ, cellIndex)
            if (particleIndexes[cellIndex] != expectedParticle) {
                throw IllegalStateException(
                    "клетка $cellIndex организма $organ: частица ${particleIndexes[cellIndex]}, " +
                        "а по параллельности арен должна быть $expectedParticle"
                )
            }
        }
    }

    /**
     * Пересчитывает «клетка на границе тела» по числу её физических связей и синхронно
     * решает, участвует ли её частица в пространственной сетке.
     *
     * ПОЧЕМУ ЧИСЛО СВЯЗЕЙ, А НЕ ГЕОМЕТРИЯ
     * -----------------------------------
     * В треугольной упаковке клетка, окружённая со всех сторон, имеет шесть соседей.
     * Меньше шести — значит хотя бы один сектор вокруг неё свободен, то есть снаружи до
     * неё можно дотянуться. Это чисто топологический признак: он не зависит ни от того,
     * как тело деформировано, ни от того, куда оно уплыло, и потому не требует
     * пересчёта при движении — только при изменении числа связей.
     *
     * ГРАНИЦА ПРИМЕНИМОСТИ
     * --------------------
     * Порог [EDGE_LINK_THRESHOLD] верен именно для треугольной решётки. Геном, растящий
     * тело по квадратной решётке (четыре соседа у внутренней клетки), при этом пороге
     * объявит внутренними вообще все клетки, и тело перестанет сталкиваться целиком.
     * Когда такие геномы появятся, порог придётся выводить из генома, а не держать
     * константой.
     *
     * ИНКРЕМЕНТАЛЬНОСТЬ
     * -----------------
     * Вызывается там, где linkAmount меняется — в addLink и deleteLink, — то есть
     * несколько раз за жизнь клетки, а не каждый тик. Ранний выход по неизменившемуся
     * флагу оставлен намеренно: смена состояния трогает чужой массив (частицы), и делать
     * это на каждой связи, когда ничего не поменялось, незачем.
     */
    fun refreshOnEdge(cellIndex: Int) {
        val onEdge = linkAmount[cellIndex] < EDGE_LINK_THRESHOLD
        if (isOnEdge[cellIndex] == onEdge) return

        isOnEdge[cellIndex] = onEdge

        val particleIndex = particleIndexes[cellIndex]
        if (particleIndex == -1) return
        particleEntity.isInGrid[particleIndex] = onEdge

        // Внутренней клетки в сетке не было, а перестройка (GridManager.rebuild) перебирает
        // только прошлый состав сетки и зарегистрированных новичков. Без регистрации клетка,
        // ставшая краевой, когда тело рвут, в сетку не попадала никогда: жива, рисуется, но
        // не сталкивается и не берётся курсором. Так падала проверка целостности сетки под
        // отладочным стресс-тестом «разогнать клетки» (клавиша D).
        //
        // Звать отсюда безопасно: addLink/deleteLink работают только при применении команд
        // мира, в одном потоке. Уход внутрь регистрации не требует: перестройка пропускает
        // частицы с isInGrid == false сама.
        if (onEdge) particleEntity.gridManager.registerParticle(particleIndex)
    }

    fun deleteCell(cellIndex: Int) {
        // Считается ДО delete: organIndex ещё не сброшен, иначе клетка из арены выглядела
        // бы сиротой и счётчик поехал бы в минус.
        if (!organEntity.hasArena(organIndex[cellIndex])) orphanCellCount--

        // Слот возвращается в арену СВОЕГО организма, иначе арена расходовалась бы по числу
        // когда-либо созданных клеток, а не живых.
        organEntity.releaseCellSlot(arenaOwnerOf(cellIndex), cellIndex)

        delete(cellIndex)

        organToIdToIndex.remove(organIndex[cellIndex], cellGenomeId[cellIndex])
        particleEntity.deleteParticle(particleIndexes[cellIndex])
        particleIndexes[cellIndex] = -1

        cellGenomeId[cellIndex] = -1
        cellActions[cellIndex] = null
        organIndex[cellIndex] = -1
        parentIndex[cellIndex] = -1
        this.angleCos[cellIndex] = 1f
        this.angleSin[cellIndex] = 0f
        this.angleDirectedCos[cellIndex] = 1f
        this.angleDirectedSin[cellIndex] = 0f
        this.angleCompensationCos[cellIndex] = 1f
        this.angleCompensationSin[cellIndex] = 0f
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = true
        isMutateInThisStage[cellIndex] = true
        this.cellType[cellIndex] = 0
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = 0f
        isNeural[cellIndex] = false
        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f
        isOnEdge[cellIndex] = true
        this.degreeOfShortening[cellIndex] = 1f
        pheromoneType[cellIndex] = -1
        linkAmount[cellIndex] = 0
        clearCellLinks(cellIndex)
        neuralConnections.remove(cellIndex)

        deleteNeural(cellIndex = cellIndex)

        specialEntity.deleteSpecial(cellIndex)
    }

    override fun persistFields(state: EntityState) {
        state.ints(::particleIndexes)
        state.ints(::cellGenomeId)
        state.ints(::organIndex)
        state.ints(::parentIndex)
        state.floats(::angleCos)
        state.floats(::angleSin)
        state.floats(::angleDirectedCos)
        state.floats(::angleDirectedSin)
        state.floats(::angleCompensationCos)
        state.floats(::angleCompensationSin)
        state.floats(::energyNecessaryToDivide)
        state.floats(::energyNecessaryToMutate)
        state.booleans(::isDividedInThisStage)
        state.booleans(::isMutateInThisStage)
        state.bytes(::cellType)
        state.floats(::energy)
        state.floats(::maxEnergy)
        state.booleans(::isNeural)
        state.floats(::neuronImpulseInput)
        state.floats(::neuronImpulseOutput)
        state.booleans(::isOnEdge)
        state.floats(::degreeOfShortening)
        state.ints(::pheromoneType)
        state.ints(::linkAmount)
        state.ints(::cellLinks, perSlot = MAX_LINKS_PER_CELL)
        state.ints(::cellLinkIds, perSlot = MAX_LINKS_PER_CELL)
        state.ints(::neuralIndexes)
        orphanCellCount = state.int("orphanCellCount", orphanCellCount)

        // Порядок внутри списка сохраняется: по нему, например, detachAllNeuralLinks снимает
        // связи умирающей клетки. Клетки — по возрастанию: порядок обхода хеш-таблицы
        // зависит от истории вставок, и без сортировки одинаковый мир давал бы разные файлы.
        state.packed(
            "neuralConnections",
            write = {
                val packed = IntArrayList()
                for (cellIndex in neuralConnections.keys.toIntArray().sorted()) {
                    val links = neuralConnections.get(cellIndex)
                    packed.add(cellIndex)
                    packed.add(links.size)
                    packed.addAll(links)
                }
                packed.toIntArray()
            },
            read = { packed ->
                var i = 0
                while (i < packed.size) {
                    val cellIndex = packed[i]
                    val size = packed[i + 1]
                    neuralConnections.put(cellIndex, IntArrayList(packed, i + 2, size))
                    i += 2 + size
                }
            }
        )
        // cellActions — ссылки внутрь генома, их сохраняет MapSave, см. cellActionStages.
    }

    /**
     * Действия клеток для сохранения мира: пары [клетка, стадия генома], из которой взято
     * её действие.
     *
     * Действие — ссылка внутрь генома, а не данные, поэтому хранится адрес: стадия плюс
     * cellGenomeId. Стадия ищется по ссылке, а не берётся текущая стадия организма: сразу
     * после смены стадии (justChangedStage) у клеток ещё лежат действия предыдущей — их
     * перезапишет только следующий тик.
     */
    /**
     * После загрузки мира: номера типов клеток из реестра того запуска, что сохранял мир,
     * в номера текущего. [newIndexByOld] — новый номер по старому (-1 — типа больше нет),
     * [savedKeys] — ключи по старым номерам, для сообщения об ошибке. Только живые клетки:
     * в мёртвых слотах тип никто не читает.
     */
    fun remapCellTypes(newIndexByOld: IntArray, savedKeys: List<String>) {
        if (newIndexByOld.withIndex().all { (old, new) -> old == new }) return
        for (i in 0 until aliveList.size) {
            val cellIndex = aliveList.getInt(i)
            val old = cellType[cellIndex].toInt()
            val new = newIndexByOld.getOrElse(old) { -1 }
            if (new == -1) throw UnknownCellTypeException(savedKeys.getOrElse(old) { "#$old" })
            cellType[cellIndex] = new.toByte()
        }
    }

    fun cellActionStages(genomes: List<Genome>): IntArray {
        val packed = IntArrayList()
        for (i in 0 until aliveList.size) {
            val cellIndex = aliveList.getInt(i)
            val action = cellActions[cellIndex] ?: continue
            val organ = organIndex[cellIndex]
            if (organ == -1) continue
            val stages = genomes.getOrNull(organEntity.genomeIndex[organ])?.genomeStageInstruction ?: continue

            // Почти всегда это текущая стадия или предыдущая, перебор всех — на всякий случай.
            val current = organEntity.stage[organ]
            val stage = (sequenceOf(current, current - 1) + stages.indices.asSequence())
                .firstOrNull { it in stages.indices && stages[it].cellActions[cellGenomeId[cellIndex]] === action }
                ?: continue
            packed.add(cellIndex)
            packed.add(stage)
        }
        return packed.toIntArray()
    }

    /** Обратное к [cellActionStages]. Геномы к этому моменту уже загружены и сопоставлены. */
    fun restoreCellActions(actionStages: IntArray, genomes: List<Genome>) {
        for (i in actionStages.indices step 2) {
            val cellIndex = actionStages[i]
            cellActions[cellIndex] = genomes[organEntity.genomeIndex[organIndex[cellIndex]]]
                .genomeStageInstruction[actionStages[i + 1]]
                .cellActions[cellGenomeId[cellIndex]]
        }
    }

    override fun restoreAfterLoad() {
        organToIdToIndex.clear()
        for (i in 0 until aliveList.size) {
            val cellIndex = aliveList.getInt(i)
            organToIdToIndex.put(organIndex[cellIndex], cellGenomeId[cellIndex], cellIndex)
        }
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        orphanCellCount = 0
        particleIndexes.clear(-1)
        cellGenomeId.clear(-1)
        cellActions.fill(null, 0, bound)
        organIndex.clear(-1)
        parentIndex.clear(-1)
        angleCos.clear(1f)
        angleSin.clear()
        angleDirectedCos.clear(1f)
        angleDirectedSin.clear()
        angleCompensationCos.clear(1f)
        angleCompensationSin.clear()
        energyNecessaryToDivide.clear(2f)
        energyNecessaryToMutate.clear(1f)
        isDividedInThisStage.clear(false)
        isMutateInThisStage.clear(false)
        cellType.clear()
        energy.clear()
        maxEnergy.clear()
        isNeural.clear(false)
        neuronImpulseInput.clear()
        neuronImpulseOutput.clear()
        neuralIndexes.clear()
        isOnEdge.clear(true)
        degreeOfShortening.clear(1f)
        pheromoneType.clear(-1)
        linkAmount.clear(0)
        cellLinks.fill(-1, 0, bound shl LINKS_SHIFT)
        cellLinkIds.fill(-1, 0, bound shl LINKS_SHIFT)
        neuralConnections.clear()
        organToIdToIndex.clear()
    }


    override fun onResize(oldMax: Int) {
        particleIndexes = particleIndexes.resize(-1)
        cellGenomeId = cellGenomeId.resize(-1)
        run {
            val old = cellActions
            cellActions = arrayOfNulls(maxAmount)
            System.arraycopy(old, 0, cellActions, 0, oldMax)
        }
        organIndex = organIndex.resize(-1)
        parentIndex = parentIndex.resize(-1)
        angleCos = angleCos.resize(1f)
        angleSin = angleSin.resize()
        angleDirectedCos = angleDirectedCos.resize(1f)
        angleDirectedSin = angleDirectedSin.resize()
        angleCompensationCos = angleCompensationCos.resize(1f)
        angleCompensationSin = angleCompensationSin.resize()
        energyNecessaryToDivide = energyNecessaryToDivide.resize(2f)
        energyNecessaryToMutate = energyNecessaryToMutate.resize(1f)
        isDividedInThisStage = isDividedInThisStage.resize(false)
        isMutateInThisStage = isMutateInThisStage.resize(false)
        cellType = cellType.resize()
        energy = energy.resize()
        maxEnergy = maxEnergy.resize()
        isNeural = isNeural.resize(false)
        neuronImpulseInput = neuronImpulseInput.resize()
        neuronImpulseOutput = neuronImpulseOutput.resize()
        neuralIndexes = neuralIndexes.resize()
        isOnEdge = isOnEdge.resize(true)
        degreeOfShortening = degreeOfShortening.resize(1f)
        pheromoneType = pheromoneType.resize(-1)
        linkAmount = linkAmount.resize(0)
        // Массив связей растёт не как остальные: на клетку в нём MAX_LINKS_PER_CELL
        // слотов, поэтому и размер, и копируемый диапазон умножаются на них же.
        run {
            val old = cellLinks
            cellLinks = IntArray(maxAmount shl LINKS_SHIFT) { -1 }
            System.arraycopy(old, 0, cellLinks, 0, oldMax shl LINKS_SHIFT)
        }
        run {
            val old = cellLinkIds
            cellLinkIds = IntArray(maxAmount shl LINKS_SHIFT) { -1 }
            System.arraycopy(old, 0, cellLinkIds, 0, oldMax shl LINKS_SHIFT)
        }
    }

    companion object {
        /**
         * Ограничение симуляции: столько физических связей максимум может быть у клетки.
         * Менять только на степень двойки и синхронно с LINKS_SHIFT (плюс развёрнутый
         * скан в areCellsLinked).
         */
        /**
         * Со скольких связей клетка считается ВНУТРЕННЕЙ (см. [refreshOnEdge]).
         *
         * Шесть — это полное окружение в треугольной упаковке. Значение подобрано под
         * геномы, которые растят тело именно такой решёткой; для других решёток его
         * придётся выводить из генома.
         */
        const val EDGE_LINK_THRESHOLD = 6

        const val MAX_LINKS_PER_CELL = 16

        /**
         * Сколько первых слотов считаются горячими: 8 int'ов = 32 байта, такой блок почти
         * всегда целиком лежит в одной кэш-линии. Остальные слоты — «холодный хвост» для
         * редких клеток с более чем восемью связями.
         */
        const val HOT_LINKS = 8

        /** log2(MAX_LINKS_PER_CELL): умножение на индекс базы делается сдвигом. */
        const val LINKS_SHIFT = 4
    }

}

