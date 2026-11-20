package io.github.some_example_name.old.features.guide.celllab

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.*
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.journal.*
import io.github.some_example_name.old.systems.genomics.OrganismSpawner
import io.github.some_example_name.old.systems.genomics.genome.*
import io.github.some_example_name.old.systems.simulation.ControllerKeys
import kotlin.math.sqrt

/** Сценарий задаёт окружение и входы; клетки, контакты, связи и рост считает игровой мир. */
internal class CellLabModel(val key: String) : Disposable {
    val world = CellLabWorld()
    val controls = CellLabCatalog.controls(key)
    private val values = LinkedHashMap<String, Float>().apply { controls.forEach { put(it.key, it.initial) } }
    var primary = -1
        private set
    var companion = -1
        private set
    var source = -1
        private set
    var kills = 0
        private set
    /** Сколько раз сработал Neuron справа на стенде синапса. */
    var postSpikes = 0
        private set
    var spawned = 0
        private set
    /** Клетки тел стенда мотонейрона: им не даём умереть от голода, изучают тут сигнал. */
    private var clusterCells = IntArray(0)
    /** Розетки стенда мотонейрона: [0] — MotorNeuron, [1..6] — Muscle по кругу от оси x (см. [rosette]). */
    var leftRosette = IntArray(0)
        private set
    var rightRosette = IntArray(0)
        private set
    var paused = false
        private set
    var grabbed = -1
        private set
    private var grabGeneration = -1
    var targetX = 0f
        private set
    var targetY = 0f
        private set
    // Четыре цели — фиксированные слоты (48 байт), помещаются в одну-две линии L1.
    private val preyIds = IntArray(4) { -1 }
    private val preyGenerations = IntArray(4)
    private val preyBirth = IntArray(4)
    private var spawnSerial = 0
    val tick get() = world.simulationData.tickCounter
    val c get() = world.cellEntity
    val p get() = world.particleEntity
    val primaryAlive get() = primary >= 0 && c.isAlive[primary]
    val powered = key in setOf("controller", "muscle", "excreta", "neuron", "suction_cup",
        "sticky", "chameleon", "eye", "touch_trigger", "synapse", "pheromone_emitter", "pheromone_sensor", "delay", "motor_neuron", "speed_sensor")

    init {
        require(key in CellLabCatalog.keys)
        setup()
        controls.forEach { configure(it.key, it.initial) }
        world.rebuildGrid()
    }

    fun value(key: String): Float = values[key] ?: 0f

    /** [motorDirX]/[motorDirY] — стрелка мотонейронов тела в позе покоя (волокно их мышц); ноль — без волокна. */
    private fun organ(types: List<String>, xs: FloatArray, ys: FloatArray, pairs: List<Int> = emptyList(),
                      triangles: List<Int> = emptyList(), motorDirX: Float = 0f, motorDirY: Float = 0f): IntArray {
        // Поза покоя для физики-библиотеки — те же координаты, по которым расставлены клетки.
        val areas = List(triangles.size / 3) { t ->
            val a = triangles[3 * t]; val b = triangles[3 * t + 1]; val d = triangles[3 * t + 2]
            (xs[b] - xs[a]) * (ys[d] - ys[a]) - (ys[b] - ys[a]) * (xs[d] - xs[a])
        }
        val layout = BakedLayout(types.indices.toList(), pairs, triangleSlots = triangles, triangleRestArea2 = areas,
            restX = xs.toList(), restY = ys.toList(),
            restRadius = List(types.size) { 0.5f }, restBone = types.map { it == "bone" })
            // Кластеры мотонейронов и волокна — как при запекании в редакторе (RCMSort).
            .withMotorClusters(BooleanArray(types.size) { types[it] == "muscle" },
                BooleanArray(types.size) { types[it] == "motor_neuron" },
                FloatArray(types.size) { motorDirX }, FloatArray(types.size) { motorDirY })
        val o = world.organEntity.addOrgan(0, 0)
        world.organEntity.allocateArenas(o, layout, 8)
        world.organEntity.alreadyGrownUp[o] = true
        world.organEntity.justChangedStage[o] = false
        val ids = IntArray(types.size)
        for (i in types.indices) {
            val type = world.cellList.indexOfKey(types[i])
            val cell = world.cellList[type]
            ids[i] = c.addCell(xs[i], ys[i], cell.defaultColor.toIntBits(), cellGenomeId = i,
                cellType = type, organIndex = o, activationFuncType = Formulas.requireIndex("linear").toByte())
            c.energy[ids[i]] = c.maxEnergy[ids[i]] * 0.5f
            cell.onStart(ids[i], 0, 0)
        }
        for (i in pairs.indices step 2) {
            val a = pairs[i]; val b = pairs[i + 1]
            val dx = xs[a] - xs[b]; val dy = ys[a] - ys[b]
            world.linkEntity.addLink(ids[a], ids[b], sqrt(dx * dx + dy * dy))
            // Опорное направление задаём до настроек стенда: иначе первый тик
            // примет уже повёрнутую клетку за исходную и добавит угол второй раз.
            if (c.parentIndex[ids[a]] == -1) world.linkEntity.reinitParentIndex(ids[a], ids[b])
            if (c.parentIndex[ids[b]] == -1) world.linkEntity.reinitParentIndex(ids[b], ids[a])
        }
        return ids
    }

    private fun single(type: String, x: Float, y: Float): Int = organ(listOf(type), floatArrayOf(x), floatArrayOf(y))[0]

    /**
     * Розетка стенда мотонейрона с центром ([cx], [cy]): клетка 0 — MotorNeuron со стрелкой
     * ([fx], [fy]), 1..6 — Muscle по кругу через 60° от оси x, шесть треугольников. [tail] —
     * ещё Leaf (7) и Muscle (8) цепочкой вправо от клетки 1; Leaf отодвинут так, чтобы не
     * перекрывать несвязанных соседей (расстояние ≥ 1).
     */
    private fun rosette(cx: Float, cy: Float, fx: Float, fy: Float, tail: Boolean): IntArray {
        val types = mutableListOf("motor_neuron"); repeat(6) { types += "muscle" }
        val xs = FloatArray(if (tail) 9 else 7); val ys = FloatArray(xs.size)
        xs[0] = cx; ys[0] = cy
        for (k in 1..6) {
            val a = (k - 1) * StrictMath.PI / 3
            xs[k] = (cx + ROSETTE_LINK * StrictMath.cos(a)).toFloat(); ys[k] = (cy + ROSETTE_LINK * StrictMath.sin(a)).toFloat()
        }
        val pairs = mutableListOf<Int>()
        for (k in 1..6) { pairs += 0; pairs += k }
        for (k in 1..6) { pairs += k; pairs += k % 6 + 1 }
        val triangles = mutableListOf<Int>()
        for (k in 1..6) { triangles += 0; triangles += k; triangles += k % 6 + 1 }
        if (tail) {
            types += "leaf"; types += "muscle"
            xs[7] = xs[1] + 0.61f; ys[7] = cy
            xs[8] = xs[7] + 0.61f; ys[8] = cy
            pairs += listOf(1, 7, 7, 8)
        }
        val ids = organ(types, xs, ys, pairs, triangles, fx, fy)
        // Стрелка мотонейрона — та же, что волокно. Родитель центра — клетка 1 (первая связь),
        // направление от него к центру — (-1, 0); стрелка задаётся поворотом к нему.
        val center = ids[0]
        c.angleDirectedCos[center] = -fx; c.angleDirectedSin[center] = -fy
        c.angleCos[center] = fx; c.angleSin[center] = fy
        return ids
    }

    /**
     * Розетка стенда мотонейрона ([rosette]): доля длины покоя вдоль оси x тела (клетки 1–4) или,
     * при [across], поперёк — между серединами пар 2–3 и 5–6. Мерится по самим клеткам: тело
     * может повернуться, доли — нет.
     */
    fun rosetteSpan(ids: IntArray, across: Boolean): Float {
        if (ids.size < 7 || (1..6).any { !c.isAlive[ids[it]] }) return 0f
        fun x(k: Int) = c.getX(ids[k])
        fun y(k: Int) = c.getY(ids[k])
        return if (!across) {
            val dx = x(1) - x(4); val dy = y(1) - y(4)
            sqrt(dx * dx + dy * dy) / (2f * ROSETTE_LINK)
        } else {
            val dx = (x(2) + x(3) - x(5) - x(6)) * 0.5f; val dy = (y(2) + y(3) - y(5) - y(6)) * 0.5f
            sqrt(dx * dx + dy * dy) / (2f * ROSETTE_LINK * ROSETTE_SIN60)
        }
    }

    private fun setup() {
        // У связанных клеток радиус 0.5, исходная длина связи 0.55: компактное тело,
        // как в редакторе. Свободные цели стоят дальше, чтобы сразу не расталкиваться.
        when (key) {
            "muscle" -> {
                // Треугольником, а не цепочкой: мышца сокращает только связи внутри своего кольца
                // (соседи по треугольнику, BodyState.cellScaleEff), в цепочке длины не менялись бы.
                val ids = organ(listOf("muscle", "leaf", "leaf"), floatArrayOf(4.0f, 3.725f, 4.275f),
                    floatArrayOf(4.476f, 4.0f, 4.0f), listOf(0, 1, 0, 2, 1, 2), triangles = listOf(0, 1, 2))
                primary = ids[0]; companion = ids[1]; source = ids[2]
            }
            "bone" -> {
                val ids = organ(listOf("bone", "bone", "bone"), floatArrayOf(4.0f, 3.45f, 4.55f),
                    floatArrayOf(4.0f, 4.0f, 4.0f), listOf(0, 1, 0, 2))
                primary = ids[0]; companion = ids[1]; source = ids[2]
                organ(listOf("fat", "fat", "fat"), floatArrayOf(3.45f, 4.0f, 4.55f),
                    floatArrayOf(2.5f, 2.5f, 2.5f), listOf(0, 1, 1, 2))
            }
            "fat", "punisher", "producer", "tail", "speed_sensor" -> {
                val ids = organ(listOf(key, "leaf"), floatArrayOf(4.0f, 3.45f), floatArrayOf(4.0f, 4.0f), listOf(0, 1))
                primary = ids[0]; companion = ids[1]
                if (key == "producer") world.organEntity.genomeIndex[c.organIndex[primary]] = growthGenome()
            }
            "controller" -> {
                val ids = organ(listOf("controller", "tail", "leaf"),
                    floatArrayOf(4f, 4.55f, 3.45f), floatArrayOf(4f, 4f, 4f), listOf(0, 1, 0, 2))
                primary = ids[0]; companion = ids[1]; source = ids[2]
                world.neuralLinkEntity.addNeuralLink(primary, companion, false, Color.CYAN.toIntBits())
            }
            "pumper" -> {
                val ids = organ(listOf("pumper", "fat"), floatArrayOf(4f, 3.45f),
                    floatArrayOf(4f, 4f), listOf(0, 1))
                primary = ids[0]; source = ids[1]
                c.energy[primary] = 0.1f; c.energy[source] = 0.2f
                spawnPumperDonor()
            }
            "eye" -> {
                primary = single(key, 2.0f, 4.0f)
                val colors = intArrayOf(Color.RED.toIntBits(), Color.GREEN.toIntBits(), Color.BLUE.toIntBits())
                for (i in 0..2) {
                    val target = single("chameleon", 5.0f, 2.5f + i * 1.5f)
                    world.specialEntity.setSpecialData(target, ChameleonData(1 shl i))
                    c.setColor(target, colors[i])
                    c.setB(target, 1f)
                    c.setA(target, 0f)
                }
            }
            "touch_trigger", "sticky" -> {
                primary = single(key, 3.5f, 4.0f)
                companion = single("fat", 4.5f, 4.0f)
                c.energy[companion] = c.maxEnergy[companion]
                c.energy[primary] = 0.5f
            }
            "pheromone_emitter", "pheromone_sensor" -> {
                source = single("pheromone_emitter", 3.0f, 4.0f)
                companion = single("pheromone_sensor", 4.0f, 4.0f)
                primary = if (key == "pheromone_emitter") source else companion
            }
            "synapse" -> {
                val ids = organ(listOf("synapse", "neuron", "neuron"), floatArrayOf(4.0f, 2.9f, 5.1f), floatArrayOf(4.0f, 4.0f, 4.0f))
                primary = ids[0]; source = ids[1]; companion = ids[2]
                world.neuralLinkEntity.addNeuralLink(source, primary, false, Color.CYAN.toIntBits())
                world.neuralLinkEntity.addNeuralLink(primary, companion, false, Color.CYAN.toIntBits())
                world.specialEntity.synapseState(primary)!!.weight = 0.5f
                // Справа — спайковый нейрон: копит вклады и срабатывает по порогу. Порог выше
                // начального веса: пока синапс не научился, вход сам нейрон не будит.
                c.setActivationFuncType(companion, Formulas.requireIndex("lif").toByte())
                c.setA(companion, SYNAPSE_LEAK)
                c.setB(companion, SYNAPSE_THRESHOLD)
                c.setC(companion, 1f)
            }
            "delay" -> {
                // Neuron слева -> Delay -> Neuron справа: вход и выход видны на соседях.
                val ids = organ(listOf("neuron", "delay", "neuron"), floatArrayOf(2.9f, 4.0f, 5.1f), floatArrayOf(4.0f, 4.0f, 4.0f))
                source = ids[0]; primary = ids[1]; companion = ids[2]
                world.neuralLinkEntity.addNeuralLink(source, primary, false, Color.CYAN.toIntBits())
                world.neuralLinkEntity.addNeuralLink(primary, companion, false, Color.CYAN.toIntBits())
            }
            "motor_neuron" -> {
                // Два тела-розетки: MotorNeuron в центре, шесть Muscle вокруг и ни одной
                // нейросвязи — сигнал мышцам раздаёт кластер. Стрелка (волокно) у левого вдоль
                // оси x, у правого вдоль y: при одном сигнале левое сжимается в ширину, правое в
                // высоту. У правого справа ещё Leaf и за ним Muscle: кластер через Leaf не идёт,
                // эта Muscle не слушается.
                leftRosette = rosette(2.2f, 4.0f, 1f, 0f, tail = false)
                rightRosette = rosette(4.6f, 4.0f, 0f, 1f, tail = true)
                primary = leftRosette[0]; companion = rightRosette[0]; source = rightRosette[8]
                clusterCells = leftRosette + rightRosette
            }
            "zygote" -> {
                val genome = growthGenome()
                primary = OrganismSpawner(world.organEntity, c, world.genomeManager, world.zygote)
                    .spawn(genome, 4.0f, 4.0f, world.zygote.defaultColor.toIntBits(), 0.5f, 1f, 0f)
            }
            else -> primary = single(key, 4.0f, 4.0f)
        }
    }

    /** Небольшой настоящий геном: зигота делится, затем превращается в лист. */
    private fun growthGenome(): Int = world.genomeManager.addSavedGenome(Genome(
        version = 1, name = "guide-two-leaves", cellsAmount = 2, linksAmount = 1, neuralLinksPeak = 0,
        sortedGraph = BakedLayout(listOf(0, 1), listOf(0, 1), restX = listOf(0f, 0.55f), restY = listOf(0f, 0f),
            restRadius = listOf(0.5f, 0.5f), restBone = listOf(false, false)),
        stageInstruction = listOf(
            GenomeStage(mapOf(0 to CellAction(divide = Action(id = 1, cellType = "leaf", angle = 0f,
                physicalLink = mapOf(0 to LinkData(length = 0.55f)))))),
            GenomeStage(mapOf(0 to CellAction(mutate = Action(cellType = "leaf"))))
        )
    ))

    fun apply(input: CellLabInput) {
        when (input.action) {
            "set" -> {
                val control = controls.firstOrNull { it.key == input.key } ?: return
                if (!input.value.isFinite()) return
                val v = input.value.coerceIn(control.min, control.max)
                values[input.key] = v
                configure(input.key, v)
            }
            "keyboard" -> {
                val mask = input.value.toInt()
                for (i in world.simulationData.controllerKeys.indices)
                    world.simulationData.controllerKeys[i] = mask and (1 shl i) != 0
            }
            "pause" -> paused = input.value != 0f
            "grab" -> {
                grabbed = nearest(input.x, input.y)
                grabGeneration = if (grabbed >= 0) p.getGeneration(grabbed) else -1
                targetX = input.x.coerceIn(0.5f, CellLabWorld.SIZE - 0.5f); targetY = input.y.coerceIn(0.5f, CellLabWorld.SIZE - 0.5f)
            }
            "drag" -> if (input.x.isFinite() && input.y.isFinite()) {
                targetX = input.x.coerceIn(0.5f, CellLabWorld.SIZE - 0.5f); targetY = input.y.coerceIn(0.5f, CellLabWorld.SIZE - 0.5f)
            }
            "release" -> grabbed = -1
            "drain" -> if (primaryAlive && (key == "leaf" || key == "fat")) c.energy[primary] = 0f
            "spawn" -> spawn()
        }
    }

    private fun configure(name: String, value: Float) {
        if (!primaryAlive) return
        when (name) {
            "light" -> world.substrateSettings.data.amountOfSolarEnergy = value
            "channels", "range" -> if (key == "eye") {
                world.specialEntity.setSpecialData(primary, EyeData(value("channels").toInt(), value("range")))
            } else if (key == "chameleon") world.specialEntity.setSpecialData(primary, ChameleonData(value.toInt()))
            "angle" -> {
                val angle = value.toDouble() * StrictMath.PI / 180.0
                val cos = StrictMath.cos(angle).toFloat()
                val sin = StrictMath.sin(angle).toFloat()
                c.angleDirectedCos[primary] = cos
                c.angleDirectedSin[primary] = sin
                val parent = c.parentIndex[primary]
                // Направление связанной клетки каждый тик вычисляется из угла относительно
                // тела. Одно изменение angleCos/angleSin терялось на следующей стадии связей.
                if (parent >= 0 && c.isAlive[parent]) world.cellSystem.processCellAngle(primary, parent)
                else { c.angleCos[primary] = cos; c.angleSin[primary] = sin }
            }
            "formula" -> {
                val formula = FORMULAS[value.toInt()]
                c.setActivationFuncType(primary, Formulas.requireIndex(formula).toByte())
                c.setC(primary, if (formula == "sin" || formula == "step_le") 1f else 0f)
            }
            "a" -> c.setA(primary, value)
            "b" -> c.setB(primary, value)
            "key" -> world.specialEntity.setSpecialData(primary, ControllerData(KEYS[value.toInt()]))
            // Новые параметры — новый пустой буфер, как при мутации клетки.
            "ticks", "binary" -> world.specialEntity.setSpecialData(primary,
                DelayState.of(DelayData(value("ticks").toInt(), value("binary") > 0f)))
            "learning" -> c.setB(primary, value)
            "depression" -> c.setC(primary, value)
            "decay" -> c.setA(primary, value)
            "inhibitory" -> world.specialEntity.synapseState(primary)?.inhibitory = value > 0f
            "emitterType" -> c.pheromoneType[source] = value.toInt()
            "sensorType" -> c.pheromoneType[companion] = value.toInt()
        }
    }

    fun step() {
        if (paused) return
        val next = tick + 1
        val selected = ControllerKeys.indexOf(KEYS[value("key").toInt()])
        val hardwarePressed = world.simulationData.controllerKeys[selected]
        if (primaryAlive) {
            if (powered) c.energy[primary] = c.maxEnergy[primary]
            when (key) {
                "tail", "muscle", "neuron", "suction_cup", "sticky", "chameleon", "excreta" ->
                    c.neuronImpulseInput[primary] = value("signal")
                "motor_neuron" -> {
                    // Один сигнал обоим мотонейронам: тела различаются только стрелкой.
                    c.neuronImpulseInput[primary] = value("signal")
                    if (c.isAlive[companion]) c.neuronImpulseInput[companion] = value("signal")
                    for (cell in clusterCells) if (c.isAlive[cell]) c.energy[cell] = c.maxEnergy[cell]
                }
                "producer" -> if (world.organEntity.aliveList.size < MAX_CELLS / 2) c.neuronImpulseInput[primary] = value("signal")
                "controller" -> {
                    world.simulationData.controllerKeys[selected] = hardwarePressed || value("pressed") > 0f
                }
                "synapse" -> if (c.isAlive[source] && c.isAlive[companion]) {
                    // Вход и учитель — по одному фронту за цикл; задержка меняет порядок спайков.
                    val phase = next % 120
                    c.neuronImpulseInput[source] = if (phase == 40) 1f else 0f
                    val teach = value("teacher") > 0f && phase == 40 + value("delay").toInt()
                    c.neuronImpulseInput[companion] = if (teach) 1f else 0f
                    c.energy[source] = c.maxEnergy[source]; c.energy[companion] = c.maxEnergy[companion]
                }
                "pheromone_emitter", "pheromone_sensor" -> c.neuronImpulseInput[source] = value("signal")
                "delay" -> if (c.isAlive[source] && c.isAlive[companion]) {
                    c.neuronImpulseInput[source] = delayWave(next)
                    c.energy[source] = c.maxEnergy[source]; c.energy[companion] = c.maxEnergy[companion]
                }
            }
        }
        if (key == "eye") for (i in 0..c.lastId) if (c.isAlive[i]) c.energy[i] = c.maxEnergy[i]
        if (value("feed") > 0f && next % 180 == 0) spawn()
        applyDrag()
        world.tick()
        if (key == "synapse" && companion >= 0 && c.isAlive[companion] && c.neuronImpulseOutput[companion] >= 1f) postSpikes++
        world.simulationData.controllerKeys[selected] = hardwarePressed
        for (slot in preyIds.indices) {
            val prey = preyIds[slot]
            if (prey >= 0 && (!c.isAlive[prey] || c.getGeneration(prey) != preyGenerations[slot])) {
                kills++; preyIds[slot] = -1
            }
        }
        // Еда от убитых клеток/выделителя не заполняет стенд за долгую сессию.
        val food = world.substancesEntity
        val foodChanged = food.aliveList.size > MAX_FOOD
        while (food.aliveList.size > MAX_FOOD) {
            val i = food.aliveList.getInt(0)
            food.deleteSubstance(i, food.getGeneration(i))
        }
        if (foodChanged) world.rebuildGrid()
    }

    /**
     * Волна источника для Delay: короткий импульс раз в [DELAY_PERIOD] тиков или синус от 0
     * до 1 того же периода. На синусе видно, что двоичный режим режет сигнал до 0 и 1.
     * StrictMath — стенд обязан повторяться побитово, как мир.
     */
    private fun delayWave(tick: Int): Float {
        val phase = tick % DELAY_PERIOD
        return if (value("wave") > 0f) {
            (0.5 + 0.5 * StrictMath.sin(2.0 * StrictMath.PI * phase / DELAY_PERIOD)).toFloat()
        } else if (phase < DELAY_PULSE) 1f else 0f
    }

    val canSpawn: Boolean get() = when (key) {
        "sucker" -> primaryAlive && world.substancesEntity.aliveList.size < MAX_FOOD
        "punisher" -> primaryAlive && c.aliveList.size < MAX_CELLS
        "pumper" -> primaryAlive && (companion < 0 || !c.isAlive[companion])
        else -> false
    }

    private fun spawnPumperDonor() {
        companion = single("fat", 5f, 4f)
        c.energy[companion] = 1.5f
        spawned++
    }

    /** Фиксированный обход точек даёт разброс без генератора случайных чисел.
     * Выбираем свободное место вдали от изучаемой клетки, поэтому еду надо собрать самому. */
    private fun spawnPoint(): Int {
        for (offset in 0 until 16) {
            val point = ((spawnSerial + offset) * 7) % 16
            val x = 1f + point % 4 * 2f
            val y = 1f + point / 4 * 2f
            val dx = x - c.getX(primary); val dy = y - c.getY(primary)
            if (dx * dx + dy * dy < 4f) continue
            if (nearest(x, y) >= 0) continue
            spawnSerial += offset + 1
            return point
        }
        return -1
    }

    private fun spawn() {
        if (!canSpawn) return
        if (key == "pumper") {
            spawnPumperDonor()
        } else {
            val point = spawnPoint()
            if (point < 0) return
            val x = 1f + point % 4 * 2f
            val y = 1f + point / 4 * 2f
            if (key == "sucker") {
                world.substancesEntity.addSubstance(x, y, Color.ORANGE.toIntBits(), 0.22f, 0)
            } else {
                var slot = preyIds.indexOf(-1)
                if (slot == -1) {
                    slot = preyBirth.indices.minBy { preyBirth[it] }
                    world.worldCommandsManager.worldCommandBuffer[0].push(
                        WorldCommandType.DELETE_CELL, preyIds[slot], preyGenerations[slot])
                    world.worldCommandsManager.executingCommandsFromTheWorld()
                    world.organEntity.deleteEmptiedOrgans()
                }
                val prey = single(PREY_TYPES[value("prey").toInt()], x, y)
                preyIds[slot] = prey
                preyGenerations[slot] = c.getGeneration(prey)
                preyBirth[slot] = tick
            }
            spawned++
        }
        world.rebuildGrid()
    }

    fun nearest(x: Float, y: Float): Int {
        if (!x.isFinite() || !y.isFinite()) return -1
        var nearest = -1; var best = 0.85f * 0.85f
        for (i in 0..p.lastId) if (p.isAlive[i]) {
            val dx = p.x(i) - x; val dy = p.y(i) - y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; nearest = i }
        }
        return nearest
    }

    private fun applyDrag() {
        val i = grabbed
        if (i < 0) return
        if (!p.isAlive[i] || p.getGeneration(i) != grabGeneration) { grabbed = -1; return }
        val dx = targetX - p.x(i); val dy = targetY - p.y(i)
        val scale = 0.003f / sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        // Мягкая тяга с гашением разгона: клетка не пролетает через точку захвата.
        // Сопротивление SuctionCup и связей по-прежнему считает обычная физика.
        p.addVelocity(i, dx * scale - p.vx(i) * 0.3f, dy * scale - p.vy(i) * 0.3f)
    }

    fun stateHash(): Long = StateHash.compute(world.entityList, simulation = { h ->
        var v = Fnv.word(h, tick)
        v = Fnv.word(v, world.simulationData.timeSimulation.toRawBits())
        v = Fnv.word(v, primary); v = Fnv.word(v, companion); v = Fnv.word(v, source)
        for (i in preyIds.indices) {
            v = Fnv.word(v, preyIds[i]); v = Fnv.word(v, preyGenerations[i]); v = Fnv.word(v, preyBirth[i])
        }
        v = Fnv.word(v, kills); v = Fnv.word(v, spawned); v = Fnv.word(v, spawnSerial); v = Fnv.word(v, postSpikes)
        for (on in world.simulationData.controllerKeys) v = Fnv.word(v, if (on) 1 else 0)
        v
    }, input = { h ->
        var v = Fnv.word(h, if (paused) 1 else 0)
        v = Fnv.word(v, grabbed); v = Fnv.word(v, grabGeneration)
        v = Fnv.word(v, targetX.toRawBits()); v = Fnv.word(v, targetY.toRawBits())
        for (control in controls) v = Fnv.word(v, value(control.key).toRawBits())
        v
    }, grid = world.gridManager::hashOrder, physics = world.worldPhysics::hash).hash

    override fun dispose() = world.dispose()

    companion object {
        const val MAX_CELLS = 32
        const val MAX_FOOD = 24
        val PREY_TYPES = arrayOf("fat", "bone")
        val FORMULAS = arrayOf("linear", "sigmoid", "step_le", "sin", "lif")

        /** Neuron справа на стенде синапса: доля накопленного, теряемая за тик, и порог. */
        const val SYNAPSE_LEAK = 0.2f
        const val SYNAPSE_THRESHOLD = 0.6f
        val KEYS = arrayOf("W", "A", "S", "D")
        const val DELAY_PERIOD = 90
        const val DELAY_PULSE = 10
        /** Розетка стенда мотонейрона: связь центра с кругом, как у компактного тела гайда. */
        const val ROSETTE_LINK = 0.55f
        const val ROSETTE_SIN60 = 0.8660254f
    }
}
