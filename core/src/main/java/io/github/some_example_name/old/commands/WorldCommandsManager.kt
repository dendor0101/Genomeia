package io.github.some_example_name.old.commands

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.core.utils.OrderedIntPairMap
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.OrganismSpawner
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.collections.map
import kotlin.math.sqrt

class WorldCommandsManager(
    val gridManager: GridManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val specialEntity: SpecialEntity,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val substrateSettings: SubstrateSettings,
    val genomeManager: GenomeManager,
    val simulationData: SimulationData,
    val cellList: List<Cell>,
    val substancesEntity: SubstancesEntity,
    val userCommandManager: UserCommandManager? = null,
    val diContext: DIContext,
    val isEditor: Boolean
): WorldResizable, Disposable {

    val mapCellGenomeIdToIndex = Int2IntOpenHashMap().apply{
        defaultReturnValue(-1)
    }

    /**
     * Ключи порядка для буферов: стадии симуляции выставляют их, буферы запоминают, а
     * применение идёт слиянием по ним — см. WorkOrder. ThreadManager получает его из
     * контейнера.
     */
    val workOrder = WorkOrder(diContext.threadCount)

    /**
     * Свои данные клетки к команде буфера [bufferIndex] (ADD_CELL, ADD_SPECIAL_MOD_DATA):
     * объект кладётся в сам буфер (WorldCommandBuffer.pushObject), в команду — его номер.
     */
    fun pushSpecialModData(bufferIndex: Int, data: SpecialModData): Int =
        worldCommandBuffer[bufferIndex].pushObject(data)

    private fun specialModDataAt(bufferIndex: Int, index: Int): SpecialModData =
        worldCommandBuffer[bufferIndex].objectAt(index) as SpecialModData
    var worldCommandBuffer = newBuffers(1000)
    var worldCommandSecondBuffer = newBuffers(100)
    val worldCommandLastBuffer = WorldCommandBuffer(100)

    private var lastAddedCellIndexBuffer = IntArray(diContext.threadCount) { -1 }
    private val organIndexCellIdMapIndex = OrderedIntPairMap()



    fun executingCommandsFromTheWorld() {
        consumeInOrder(worldCommandBuffer) { threadId, type, ints, floats, booleans ->
                when (type) {
                    WorldCommandType.DIVIDE_ALIVE_CELL_ACTION_COUNTER -> {
                        organEntity.divideCounterThisStage[ints[0]]++
                    }
                    WorldCommandType.MUTATE_ALIVE_CELL_ACTION_COUNTER -> {
                        organEntity.mutateCounterThisStage[ints[0]]++
                    }
                    WorldCommandType.ADD_PARTICLE -> {
//                        particleEntity.addParticle(
//                            x = floats[0],
//                            y = floats[1],
//                            radius = floats[2],
//                            color = ints[0],
//                            isCell = false,
//                            holderEntityIndex = -1
//                        )
                    }
                    WorldCommandType.ADD_LINK -> {
                        // ints[0] == -1 означает "клетка, созданная предыдущей командой
                        // этого же буфера". Но ADD_CELL мог отказаться её создавать
                        // (морфогенез, если место уже занято), и тогда привязываться не
                        // к чему: раньше здесь подхватывался ОСТАТОК от прошлого деления,
                        // возможно вообще из другого тика, и связь уходила к посторонней
                        // клетке. См. сброс lastAddedCellIndexBuffer в ADD_CELL.
                        val cellIndex = if (ints[0] == -1) {
                            lastAddedCellIndexBuffer[threadId]
                        } else ints[0]

                        if (cellIndex == -1) return@consumeInOrder

                        val linkIndex = linkEntity.addLink(
                            cellIndex = cellIndex,
                            otherCellIndex = ints[1],
                            linksLength = floats[0],
                            cellGeneration = ints[2],
                            otherCellGeneration = ints[3],
                        )
                        // -1 значит, что связь не создалась: либо у одной из клеток кончились
                        // слоты (CellEntity.MAX_LINKS_PER_CELL), либо одна из них умерла
                        // (или её индекс переиспользовала другая клетка) раньше, чем команда
                        // дошла до применения.
                        if (linkIndex != -1) {
                        }
                    }

                    WorldCommandType.ADD_NEURAL_LINK -> {
                        // Та же ловушка, что и в ADD_LINK: клетки могло не быть создано.
                        val cellIndex = if (ints[0] == -1) {
                            lastAddedCellIndexBuffer[threadId]
                        } else ints[0]

                        if (cellIndex == -1) return@consumeInOrder

                        neuralLinkEntity.addNeuralLink(
                            cellIndex = cellIndex,
                            otherCellIndex = ints[1],
                            isLink1NeuralDirected = booleans[0],
                            color = ints[2],
                            cellGeneration = ints[3],
                            otherCellGeneration = ints[4]
                        )
                    }
                    WorldCommandType.DELETE_NEURAL_LINK -> {
                        neuralLinkEntity.deleteNeuralLink(linkIndex = ints[0], linkGeneration = ints[1])
                    }
                    WorldCommandType.DELETE_LINK -> {
                        val linkIndex = ints[0]
                        linkEntity.deleteLink(linkIndex, linkGeneration = ints[1])
                    }
                    WorldCommandType.ADD_CELL -> {
                        val isMorphogenesis = booleans[1]
                        val x = floats[0]
                        val y = floats[1]

                        val radius = floats[2]

                        var isDivide = true
                        var closestCells: IntArray? = null

                        // Родитель мог умереть, пока команда ждала применения.
                        //
                        // ADD_CELL формируется в параллельной фазе, а применяется позже и по
                        // буферам подряд: DELETE_CELL из буфера 1 выполнится раньше, чем
                        // ADD_CELL из буфера 3. Делить уже нечего — контекст деления исчез.
                        //
                        // Если всё-таки создать клетку, она возьмёт свой якорь вместо
                        // родительского (организм) и окажется новой системой
                        // отсчёта посреди чужого организма. Все её связи с телом отвергнет
                        // барьер в LinkEntity.addLink, и клетка молча повиснет отдельно.
                        //
                        // Одного isAlive мало: индексы клеток переиспользуются через deadStack,
                        // поэтому умерший и заново занятый слот снова "жив", но это уже другая
                        // клетка. Тогда потомок унаследовал бы ЧУЖУЮ карту тела и чужой якорь —
                        // и присоединился бы не к тому организму, причём совершенно молча.
                        // Отличает их поколение, снятое в момент постановки команды.
                        //
                        // Проверка стоит до морфогенеза: она заодно экономит collectParticles.
                        val parentCellIndex = ints[4]
                        val parentGeneration = ints[8]
                        if (parentCellIndex != -1 &&
                            !cellEntity.isAliveAndSameGen(parentCellIndex, parentGeneration)
                        ) {
                            isDivide = false
                        }

                        if (isDivide && isMorphogenesis) {
                            closestCells = gridManager.collectParticles(
                                gridX = x.toInt(),
                                gridY = y.toInt(),
                                radius = 1
                            )
                            //TODO сделать без алокаций
                            closestCells
                                .filter { particleEntity.isCell[it] }
                                .forEach {
                                    val dx = particleEntity.x[it] - x
                                    val dy = particleEntity.y[it] - y
                                    val squareDist = dx * dx + dy * dy
                                    if (squareDist < 0.36f) {
                                        isDivide = false
                                        return@forEach
                                    }
                                }
                        }
                        val cellType = ints[2]
                        val newCell = cellList[cellType]

                        if (isDivide && newCell is Zygote && parentCellIndex == -1 && !isEditor) {
                            // Зигота от продюсера (у неё нет родителя) — корень НОВОГО
                            // организма с геномом родителя. Сама клетка появится в последней
                            // фазе команд, уже в арене своего организма (OrganismSpawner).
                            //
                            // Зигота от деления по геному (родитель есть) сюда не попадает:
                            // она остаётся в теле пустышкой и ничего не растит
                            // (CellEntity.isDormantZygote). Новое тело на старом не помещается
                            // в арену организма — её размер фиксирован при рождении.
                            worldCommandLastBuffer.push(
                                type = WorldCommandType.SPAWN_ORGANISM,
                                ints = intArrayOf(organEntity.genomeIndex[ints[3]], ints[0]),
                                floats = floatArrayOf(x, y, radius, floats[3], floats[4], floats[5], floats[6])
                            )
                            lastAddedCellIndexBuffer[threadId] = -1
                        } else if (isDivide) {
                            val cellGenomeId = ints[1]
                            val parentOrganIndex = ints[3]
                            val parentIndex = ints[4]
                            // В редакторе организм один, и зигота — его часть.
                            val organIndex = if (newCell is Zygote && isEditor) 0 else parentOrganIndex

                            val specialModDataIndex = ints[7]

                            val cellIndex = cellEntity.addCell(
                                x = x,
                                y = y,
                                color = ints[0],
                                radius = radius,
                                cellGenomeId = cellGenomeId,
                                cellType = cellType,
                                organIndex = organIndex,
                                parentIndex = parentIndex,
                                angleCos = floats[3],
                                angleSin = floats[4],
                                angleDiffCos = floats[5],
                                angleDiffSin = floats[6],
                                a = floats[7],
                                b = floats[8],
                                c = floats[9],
                                isSum = booleans[0],
                                activationFuncType = ints[5].toByte(),
                                pheromoneType = ints[6],
                                specialModData = if (specialModDataIndex == -1) null else specialModDataAt(threadId, specialModDataIndex)
                            )
                            if (isEditor) {
                                mapCellGenomeIdToIndex.put(cellGenomeId, cellIndex)
                            }

                            //TODO сделать без алокаций
                            closestCells?.filter { particleEntity.isCell[it] }
                                ?.map { particleEntity.holderEntityIndex[it] }
                                // Связываться можно только со своим организмом.
                                //
                                // Это не косметика: слот связи назначается по СТАТИЧЕСКИМ
                                // organIndex, а он осмыслен
                                // сравнимы лишь внутри одного организма — у разных начала
                                // отсчёта не связаны. Связь между организмами дала бы
                                // произвольное статическое расстояние между её клетками,
                                // а с ним вернулась бы гонка на vx/vy в фазе связей.
                                ?.filter { cellEntity.organIndex[it] == cellEntity.organIndex[cellIndex] }
                                ?.forEach {
                                        val dx = cellEntity.getX(it) - x
                                        val dy = cellEntity.getY(it) - y
                                        val rSum = cellEntity.getRadius(it) + radius
                                        val squareDist = dx * dx + dy * dy
                                        if (rSum * rSum > squareDist) {
                                            val linkIndex = linkEntity.addLink(
                                                cellIndex = cellIndex,
                                                otherCellIndex = it,
                                                linksLength = sqrt(squareDist),
                                            )
                                            if (linkIndex != -1) {
                                            }
                                        }

                                }

                            val genomeIndex = organEntity.genomeIndex[parentOrganIndex]
                            newCell.onStart(cellIndex, -1, genomeIndex)

                            if (parentIndex != -1 && cellEntity.parentIndex[parentIndex] == -1) {
                                cellEntity.parentIndex[parentIndex] = cellIndex
                            }

                            lastAddedCellIndexBuffer[threadId] = cellIndex
                            organIndexCellIdMapIndex.put(parentOrganIndex, cellGenomeId, cellIndex)
                        } else {
                            // Клетка не создана — значит и «последней созданной» для
                            // следующих команд этого буфера нет.
                            //
                            // Без этого сброса ADD_LINK и ADD_NEURAL_LINK, идущие следом
                            // и ссылающиеся на неё через -1, подхватывали ОСТАТОК от
                            // прошлого деления: индекс живой, но совершенно посторонней
                            // клетки, возможно из другого тика и другого места мира.
                            // Связь при этом создавалась настоящая, считалась физикой и
                            // писала в vx/vy чужих частиц.
                            //
                            // Ловилось это проверкой статических координат: посторонняя
                            // клетка часто оказывалась из того же организма и физически
                            // рядом (то есть проходила проверку расстояния в addLink),
                            // но по карте тела стояла в другом месте.
                            lastAddedCellIndexBuffer[threadId] = -1
                        }
                    }
                    WorldCommandType.DECREMENT_DIVIDE_COUNTER -> {
                        organEntity.dividedTimes[ints[0]]--
                    }
                    WorldCommandType.DECREMENT_MUTATION_COUNTER -> {
                        organEntity.mutatedTimes[ints[0]]--
                    }
                    WorldCommandType.DELETE_CELL -> {
                        val cellIndex = ints[0]
                        val cellGeneration = ints[1]
                        if (cellEntity.getParticleIndex(cellIndex) == userCommandManager?.grabbedParticleIndex) {
                            userCommandManager.grabbedParticleIndex = -1
                            userCommandManager.isDragging = false
                        }

                        if (cellEntity.isAlive[cellIndex] && cellEntity.getGeneration(cellIndex) == cellGeneration) {
                            val x = cellEntity.getX(cellIndex)
                            val y = cellEntity.getY(cellIndex)
                            val newSubIndex = substancesEntity.addSubstance(
                                x = cellEntity.getX(cellIndex),
                                y = cellEntity.getY(cellIndex),
                                color = Color.RED.toIntBits(),
                                radius = (0.03f * cellEntity.energy[cellIndex]).coerceIn(0.1f, 0.5f),
                                subType = 0,
                            )
                            pheromoneEntity.addPheromone(x, y, emitterIndex = substancesEntity.particleIndex[newSubIndex], type = 0)
                            pheromoneEntity.addPheromone(x, y, emitterIndex = -1, type = 18, time = 0.3f)
                            organManager.cellDeleted(cellIndex)
                            // Строго до deleteCell: detachAllLinks читает список соседей
                            // умирающей клетки, а deleteCell его затирает. Здесь же
                            // выжившие соседи получают isOnEdge и сброс parentIndex —
                            // раньше это делал processLink, увидев мёртвую клетку.
                            linkEntity.detachAllLinks(cellIndex)
                            neuralLinkEntity.detachAllNeuralLinks(cellIndex)
                            cellEntity.deleteCell(cellIndex)
                            cellList[cellEntity.cellType[cellIndex].toInt()].onDie(cellIndex)
                        }
                    }
                    WorldCommandType.DELETE_NEURAL -> {
                        cellEntity.deleteNeural(cellIndex = ints[0], neuralGeneration = ints[1])
                    }
                    WorldCommandType.ADD_NEURAL -> {
                        // Мутация ставит команду в параллельной фазе, а клетку к этому
                        // моменту мог убить DELETE_CELL, применённый раньше в этом же
                        // проходе. Нейро-слот мёртвой клетки уже никто не освободит —
                        // он утёк бы навсегда.
                        if (!cellEntity.isAlive[ints[0]]) return@consumeInOrder
                        cellEntity.addNeural(
                            index = ints[0],
                            cellType = ints[1],
                            a = floats[0],
                            b = floats[1],
                            c = floats[2],
                            isSum = booleans[0],
                            activationFuncType = ints[2].toByte()
                        )
                    }
                    WorldCommandType.DELETE_ORGAN -> {
                        val organIndex = ints[0]
                        val organGeneration = ints[1]
                        //TODO DELETE_ORGAN
                        //TODO подумать все ли нормально будет с organIndexCellIdMapIndex
                    }
                    WorldCommandType.ADD_SUBSTANCE -> {
                        val newSubIndex = substancesEntity.addSubstance(
                            x = floats[0],
                            y = floats[1],
                            color = ints[0],
                            radius = floats[2],
                            subType = ints[1].toByte(),
                        )
                        pheromoneEntity.addPheromone(x = floats[0], y = floats[1], emitterIndex = substancesEntity.particleIndex[newSubIndex], type = 0)
                    }
                    WorldCommandType.DELETE_SUBSTANCE -> {
                        substancesEntity.deleteSubstance(subIndex = ints[0], subGeneration = ints[1])
                    }
                    WorldCommandType.ADD_PHEROMONE -> {
                        pheromoneEntity.addPheromone(x = floats[0], y = floats[1], emitterIndex = ints[0], type = ints[1])
                    }
                    WorldCommandType.DELETE_PHEROMONE -> {
                        pheromoneEntity.deletePheromone(pheromoneIndex = ints[0], pheromoneGeneration = ints[1])
                    }
                    WorldCommandType.MUTATE_ON_START -> {
                        val index = ints[0]
                        val threadId = ints[1]
                        val genomeIndex = ints[2]
                        // Клетку могли убить раньше в этом же проходе, как в ADD_NEURAL:
                        // onStart мёртвой клетки работал бы с уже освобождёнными слотами.
                        if (!cellEntity.isAlive[index]) return@consumeInOrder
                        val newCell = cellList[cellEntity.cellType[index].toInt()]
                        newCell.onStart(index, threadId, genomeIndex)
                    }
                    WorldCommandType.DELETE_SPECIAL_MOD_DATA -> {
                        // Клетка могла умереть раньше в этом проходе — слот всё равно освобождается
                        // (умерла она уже не клеткой с данными, и сама смерть его не тронула).
                        // Но если её индекс успели отдать новой клетке, specialTypeIndexes уже
                        // её — поколение клетки (растёт только при выдаче слота) это отличает.
                        val cellIndex = ints[0]
                        if (cellEntity.getGeneration(cellIndex) != ints[2]) return@consumeInOrder
                        specialEntity.deleteSpecialModData(cellIndex = cellIndex, modDataGeneration = ints[1])
                    }
                    WorldCommandType.ADD_SPECIAL_MOD_DATA -> {
                        // Как в ADD_NEURAL: клетку могли убить раньше в этом же проходе, а её
                        // индекс — отдать новой клетке. Поколение отличает одну от другой.
                        val cellIndex = ints[0]
                        if (!cellEntity.isAliveAndSameGen(cellIndex, ints[2])) return@consumeInOrder
                        specialEntity.addSpecialModData(cellIndex, specialModDataAt(threadId, ints[1]))
                    }
                    else -> {}
                }
            }

        consumeInOrder(worldCommandSecondBuffer) { _, type, ints, floats, booleans ->
                when (type) {
                    WorldCommandType.ADD_LINK_BY_ID -> {
                        val cellId = ints[0]
                        val otherCellId = ints[1]
                        val organIndex = ints[2]
                        val linksLength = floats[0]

                        val cellIndex = organIndexCellIdMapIndex.get(organIndex, cellId)
                        val otherCellIndex = organIndexCellIdMapIndex.get(organIndex, otherCellId)

                        if (cellIndex != -1 && otherCellIndex != -1 &&
                            !cellEntity.areCellsLinked(cellIndex, otherCellIndex)
                        ) {
                            val linkIndex = linkEntity.addLink(
                                cellIndex = cellIndex,
                                otherCellIndex = otherCellIndex,
                                linksLength = linksLength,
                            )
                            if (linkIndex != -1) {
                            }
                        }

                    }

                    WorldCommandType.ADD_NEURAL_LINK_BY_ID -> {
                        val cellId = ints[0]
                        val otherCellId = ints[1]
                        val organIndex = ints[2]
                        val isLink1NeuralDirected = booleans[0]

                        val cellIndex = organIndexCellIdMapIndex.get(organIndex, cellId)
                        val otherCellIndex = organIndexCellIdMapIndex.get(organIndex, otherCellId)

                        if (cellIndex != -1 && otherCellIndex != -1 && neuralLinkEntity.linkIndexMap.get(cellIndex, otherCellIndex) == -1) {
                            neuralLinkEntity.addNeuralLink(
                                cellIndex = cellIndex,
                                otherCellIndex = otherCellIndex,
                                isLink1NeuralDirected = isLink1NeuralDirected,
                                color = ints[3]
                            )
                        }
                    }

                    else -> {}
                }
            }

        organIndexCellIdMapIndex.clear()
    }

    /** Новые организмы от зигот, родившихся в мире (SPAWN_ORGANISM). */
    private val organismSpawner = OrganismSpawner(
        organEntity = organEntity,
        cellEntity = cellEntity,
        genomeManager = genomeManager,
        zygote = cellList.filterIsInstance<Zygote>().single()
    )

    fun executingLastCommandsFromTheWorld() {
        worldCommandLastBuffer.consume { type, ints, floats, booleans ->
            when (type) {
                WorldCommandType.SPAWN_ORGANISM -> {
                    organismSpawner.spawn(
                        genomeIndex = ints[0],
                        x = floats[0],
                        y = floats[1],
                        color = ints[1],
                        radius = floats[2],
                        angleCos = floats[3],
                        angleSin = floats[4],
                        angleDiffCos = floats[5],
                        angleDiffSin = floats[6]
                    )
                }

                else -> {}
            }
        }
    }

    override fun resize() {
        workOrder.resize(diContext.threadCount)
        worldCommandBuffer = newBuffers(1000)
        worldCommandSecondBuffer = newBuffers(100)
        lastAddedCellIndexBuffer = IntArray(diContext.threadCount) { -1 }
    }

    override fun dispose() {
    }

    private fun newBuffers(capacity: Int) =
        Array(diContext.threadCount) { index -> WorldCommandBuffer(capacity, bufferIndex = index, order = workOrder) }

    /** Позиции слияния по буферам — поле, а не локальный массив: слияние идёт каждый тик. */
    private var mergeCursors = IntArray(0)

    /**
     * Применить буферы в порядке ключей (WorkOrder): каждый буфер отсортирован, поэтому
     * это слияние. Берётся целиком пачка одной работы — команды подряд с одним ключом, —
     * так что выбор «чей ход» делается по разу на работу, а не на каждую команду. При
     * равных ключах (команды вне стадий в разных буферах) первым идёт меньший номер буфера.
     *
     * [processor] получает номер буфера: по нему живут per-buffer структуры применения
     * (lastAddedCellIndexBuffer, объекты команд в самом буфере).
     */
    private inline fun consumeInOrder(
        buffers: Array<WorldCommandBuffer>,
        processor: (threadId: Int, type: WorldCommandType, ints: IntArray, floats: FloatArray, booleans: BooleanArray) -> Unit
    ) {
        if (mergeCursors.size < buffers.size) mergeCursors = IntArray(buffers.size)
        val cursors = mergeCursors
        cursors.fill(0, 0, buffers.size)

        while (true) {
            var best = -1
            var bestKey = Long.MAX_VALUE
            for (b in buffers.indices) {
                val cursor = cursors[b]
                if (cursor < buffers[b].size) {
                    val key = buffers[b].orderKeys[cursor]
                    if (best == -1 || key < bestKey) {
                        best = b
                        bestKey = key
                    }
                }
            }
            if (best == -1) break

            val buffer = buffers[best]
            val from = cursors[best]
            var to = from + 1
            while (to < buffer.size && buffer.orderKeys[to] == bestKey) to++
            cursors[best] = to
            buffer.consumeRange(from, to) { type, ints, floats, booleans -> processor(best, type, ints, floats, booleans) }
        }
        buffers.forEach { it.clear() }
    }
}
