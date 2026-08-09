package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.SpecialModData
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.simulation.SimulationData
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class CellEntity(
    @Transient val cellsStartMaxAmount: Int = 1
) : Entity(cellsStartMaxAmount) {

    // Transient-зависимости, инициализируются через конструктор или loadEntity()
    @Transient lateinit var particleEntity: ParticleEntity
    @Transient lateinit var simulationData: SimulationData
    @Transient lateinit var substrateSettings: SubstrateSettings
    @Transient lateinit var neuralEntity: NeuralEntity
    @Transient lateinit var specialEntity: SpecialEntity
    @Transient lateinit var cellList: List<Cell>

    // Вторичный конструктор для обычного создания
    constructor(
        cellsStartMaxAmount: Int,
        particleEntity: ParticleEntity,
        simulationData: SimulationData,
        substrateSettings: SubstrateSettings,
        neuralEntity: NeuralEntity,
        specialEntity: SpecialEntity,
        cellList: List<Cell>
    ) : this(cellsStartMaxAmount) {
        loadEntity(particleEntity, simulationData, substrateSettings, neuralEntity, specialEntity, cellList)
    }

    //Particle entity
    @ProtoNumber(1) var particleIndexes = IntArray(maxAmount) { -1 }
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

    @ProtoNumber(2) var cellGenomeId = IntArray(maxAmount) { -1 }
    @Transient var cellActions: Array<CellAction?> = arrayOfNulls(maxAmount)
    @ProtoNumber(4) var organIndex = IntArray(maxAmount) { -1 }
    @ProtoNumber(5) var parentIndex = IntArray(maxAmount) { -1 }
    @ProtoNumber(6)  var angleCos = FloatArray(maxAmount)
    @ProtoNumber(7) var angleSin = FloatArray(maxAmount)
    @ProtoNumber(8) var angleDirectedCos = FloatArray(maxAmount)
    @ProtoNumber(9) var angleDirectedSin = FloatArray(maxAmount)
    @ProtoNumber(10) var angleCompensationCos = FloatArray(maxAmount)
    @ProtoNumber(11) var angleCompensationSin = FloatArray(maxAmount)
    @ProtoNumber(12) var energyNecessaryToDivide = FloatArray(maxAmount) { 2f }
    @ProtoNumber(13) var energyNecessaryToMutate = FloatArray(maxAmount) { 1f }
    @ProtoNumber(14) var isDividedInThisStage = BooleanArray(maxAmount)
    @ProtoNumber(15) var isMutateInThisStage = BooleanArray(maxAmount)
    @ProtoNumber(16) var cellType = ByteArray(maxAmount)
    @ProtoNumber(17) var energy = FloatArray(maxAmount)
    @ProtoNumber(18) var maxEnergy = FloatArray(maxAmount)
    @ProtoNumber(19) var isNeural = BooleanArray(maxAmount)
    @ProtoNumber(20) var neuronImpulseInput = FloatArray(maxAmount)
    @ProtoNumber(21) var neuronImpulseOutput = FloatArray(maxAmount)
    @ProtoNumber(22) var isOnEdge = BooleanArray(maxAmount)
    @ProtoNumber(23) var degreeOfShortening = FloatArray(maxAmount) { 1f }
    @ProtoNumber(24) var pheromoneType = IntArray(maxAmount) { -1 }
    @ProtoNumber(25) var linkAmount = IntArray(maxAmount) { 0 }
    @ProtoNumber(26) var command = ByteArray(maxAmount) { -1 }
    @Transient var neuralConnections = Int2ObjectOpenHashMap<IntArrayList>()

    @ProtoNumber(27) var neuralConnectionsData: Map<Int, List<Int>> = emptyMap()

    /**
     * Индексы клеток, у которых сейчас есть действие генома.
     *
     * Сам CellAction сериализовать нельзя: DivideManager читает у него
     * physicalLinkMirroredForCell — @Transient-поле, которое досчитывается только при разборе
     * генома. Копия из архива пришла бы с пустым мостом связей, поэтому храним лишь список
     * клеток, а сам объект берём из генома в restoreCellActions().
     */
    @ProtoNumber(3) var cellActionIndexes: List<Int> = emptyList()

    fun addNeuralConnection(cellIndex: Int, targetNeuralIndex: Int) {
        val list = neuralConnections[cellIndex] ?: IntArrayList(4).also {
            neuralConnections[cellIndex] = it
        }

        if (!list.contains(targetNeuralIndex)) {
            list.add(targetNeuralIndex)
        }
    }

    fun removeNeuralConnection(cellIndex: Int, targetNeuralIndex: Int) {
        val list = neuralConnections.get(cellIndex) ?: return
        list.rem(targetNeuralIndex)
    }

    //Neural entity
    @ProtoNumber(28) var neuralIndexes = IntArray(maxAmount) { -1 }
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
    fun setIsSum(index: Int, value: Boolean) { neuralEntity.isSum.set(neuralIndexes[index], value) }
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
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 4.25f,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 7,
        speed: Float = 0f,
        pheromoneType: Int = -1,
        specialModData: SpecialModData? = null
    ): Int {
        val cellIndex = add()

        particleIndexes[cellIndex] = particleEntity.addParticle(
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
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = cellsSettings[cellType].maxEnergy
        isOnEdge[cellIndex] = true
        this.degreeOfShortening[cellIndex] = 1f
        this.pheromoneType[cellIndex] = pheromoneType
        linkAmount[cellIndex] = 0
        command[cellIndex] = -1
        val cell = cellList[cellType]
        if (cell.doesNeedNeuralConnections) {
            neuralConnections.put(cellIndex, IntArrayList(4))
        }

        if (cell.isNeural) {
            addNeural(cellIndex, cellType, a, b, c, isSum, activationFuncType)
        } else {
            neuronImpulseInput[cellIndex] = 0f
            neuronImpulseOutput[cellIndex] = 0f
            isNeural[cellIndex] = false
            neuralIndexes[cellIndex] = -1
        }

        specialEntity.addSpecial(
            cell = cell,
            colorDifferentiation = colorDifferentiation,
            visibilityRange = visibilityRange,
            speed = speed,
            specialModData = specialModData
        )

        return cellIndex
    }

    fun deleteCell(cellIndex: Int) {
        delete(cellIndex)

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
        val ct = cellType[cellIndex]
        val cell = cellList[ct.toInt()]
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
        command[cellIndex] = -1
        neuralConnections.remove(cellIndex)

        deleteNeural(cellIndex = cellIndex)

        specialEntity.delete(cell = cell, cellIndex = cellIndex)
    }

    fun serializeEntity() {
        super.saveSerialize()
        neuralConnectionsData = neuralConnections
            .mapValues { it.value.toList() }
            .toMap()

        cellActionIndexes = cellActions.mapIndexedNotNull { index, action ->
            index.takeIf { action != null }
        }
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
        rebuildTransient()
    }

    fun copyFrom(other: CellEntity) {
        copyBaseFrom(other)

        copyInto(other.particleIndexes, particleIndexes)
        copyInto(other.cellGenomeId, cellGenomeId)
        copyInto(other.organIndex, organIndex)
        copyInto(other.parentIndex, parentIndex)
        copyInto(other.angleCos, angleCos)
        copyInto(other.angleSin, angleSin)
        copyInto(other.angleDirectedCos, angleDirectedCos)
        copyInto(other.angleDirectedSin, angleDirectedSin)
        copyInto(other.angleCompensationCos, angleCompensationCos)
        copyInto(other.angleCompensationSin, angleCompensationSin)
        copyInto(other.energyNecessaryToDivide, energyNecessaryToDivide)
        copyInto(other.energyNecessaryToMutate, energyNecessaryToMutate)
        copyInto(other.isDividedInThisStage, isDividedInThisStage)
        copyInto(other.isMutateInThisStage, isMutateInThisStage)
        copyInto(other.cellType, cellType)
        copyInto(other.energy, energy)
        copyInto(other.maxEnergy, maxEnergy)
        copyInto(other.isNeural, isNeural)
        copyInto(other.neuronImpulseInput, neuronImpulseInput)
        copyInto(other.neuronImpulseOutput, neuronImpulseOutput)
        copyInto(other.isOnEdge, isOnEdge)
        copyInto(other.degreeOfShortening, degreeOfShortening)
        copyInto(other.pheromoneType, pheromoneType)
        copyInto(other.linkAmount, linkAmount)
        copyInto(other.command, command)
        copyInto(other.neuralIndexes, neuralIndexes)

        neuralConnectionsData = other.neuralConnectionsData
        cellActionIndexes = other.cellActionIndexes
        rebuildTransient()
    }

    private fun rebuildTransient() {
        neuralConnections.clear()
        for ((key, list) in neuralConnectionsData) {
            neuralConnections[key] = IntArrayList(list)
        }
        cellActions = arrayOfNulls(maxAmount)
    }

    /**
     * Возвращает клеткам ссылки на действия генома — те же объекты, что лежат в
     * genomeManager.genomes, а не их копии (см. [cellActionIndexes]).
     *
     * Вызывать после того, как загружены organEntity и геномы.
     */
    fun restoreCellActions(organEntity: OrganEntity, genomes: List<Genome>) {
        cellActions = arrayOfNulls(maxAmount)

        for (cellIndex in cellActionIndexes) {
            if (cellIndex !in cellActions.indices || !isAlive[cellIndex]) continue

            val organIndex = organIndex[cellIndex]
            if (organIndex < 0 || organIndex >= organEntity.maxAmount || !organEntity.isAlive[organIndex]) continue

            val genome = genomes.getOrNull(organEntity.genomeIndex[organIndex]) ?: continue
            val stage = genome.genomeStageInstruction.getOrNull(organEntity.stage[organIndex]) ?: continue

            cellActions[cellIndex] = stage.cellActions[cellGenomeId[cellIndex]]
        }
    }

    fun loadEntity(
        particleEntity: ParticleEntity,
        simulationData: SimulationData,
        substrateSettings: SubstrateSettings,
        neuralEntity: NeuralEntity,
        specialEntity: SpecialEntity,
        cellList: List<Cell>
    ) {
        this.particleEntity = particleEntity
        this.simulationData = simulationData
        this.substrateSettings = substrateSettings
        this.neuralEntity = neuralEntity
        this.specialEntity = specialEntity
        this.cellList = cellList
    }

    override fun onCopy() {}

    override fun onPaste() {}

    override fun onClear(bound: Int) {
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
        // Дефолт -1, как у инициализатора поля и deleteNeural: 0 — валидный индекс в neuralEntity
        neuralIndexes.clear(-1)
        isOnEdge.clear(true)
        degreeOfShortening.clear(1f)
        pheromoneType.clear(-1)
        linkAmount.clear(0)
        command.clear(-1)
        neuralConnections.clear()
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
        neuralIndexes = neuralIndexes.resize(-1)
        isOnEdge = isOnEdge.resize(true)
        degreeOfShortening = degreeOfShortening.resize(1f)
        pheromoneType = pheromoneType.resize(-1)
        linkAmount = linkAmount.resize(0)
        command = command.resize(-1)
    }
}
