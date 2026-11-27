package io.github.some_example_name.old.systems.logic

import ai.tenum.lua.runtime.LuaBoolean
import ai.tenum.lua.runtime.LuaFunction
import ai.tenum.lua.runtime.LuaNativeFunction
import ai.tenum.lua.runtime.LuaNumber
import ai.tenum.lua.runtime.LuaString
import ai.tenum.lua.runtime.LuaValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.ObjectMap
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.simulationData
import io.github.some_example_name.old.features.levels.dialogs.PopUp
import io.github.some_example_name.old.features.simulation.SimulationScreen


class LogicEnv {
    val popups = com.badlogic.gdx.utils.Queue<PopUp>()
    var popupActive = false

    /**
     * Ожидание окна: popup() держит поток симуляции, пока игрок не закроет окно, а
     * onPopupClosed() его будит. Очередь трогает только поток рендера, поэтому счётчик
     * и очередь — разные вещи: счётчик под локом, очередь без (у неё один хозяин).
     */
    private val popupLock = Object()

    /** Сколько окон поставлено в очередь и ещё не закрыто. Под [popupLock]. */
    private var pendingPopups = 0

    //Состояние уровня для экрана: цель, прогресс, метка, зона, провал.
    val rules = LevelRules()

    private var cellsTriggerFired = false
    private var destinationTriggerFired = false

    val getCellsCount = LuaNativeFunction {
        listOf(LuaNumber.of(DISimulationContainer.simulationSystem.getStats().cells))
    }

    val world = LuaNativeFunction {
        args -> val name = string(args, 0)
        when (name) {
            "cells" -> {
                listOf(LuaNumber.of(DISimulationContainer.simulationSystem.getStats().cells))
            }
            "time" -> {
                listOf(LuaNumber.of(simulationData.timeSimulation.toDouble()))
            }
            "tick" -> {
                    listOf(LuaNumber.of(simulationData.tickCounter))
            }
            "pos" -> {
                val (posX, posY) = getAveragePos() ?: (0f to 0f)
                listOf(LuaNumber.of(posX.toDouble()), LuaNumber.of(posY.toDouble()))
            }
            // Есть ли в организме игрока живая клетка этого типа. Нужно уровням-гайдам:
            // палитра прячет лишние клетки, но не мешает пройти уровень вообще без той,
            // которой он посвящён. Смотрим последний поставленный мышью организм: скриптовые
            // спавны (продюсер, враг уровня) не должны закрывать условие за игрока.
            "has_cell" -> {
                val key = string(args, 1)
                val typeIndex = DISimulationContainer.cellList.indexOfKey(key)
                val organIndex = simulationData.lastPlacedOrganIndex
                var found = 0
                if (typeIndex >= 0 && organIndex >= 0) {
                    val cells = DISimulationContainer.cellEntity
                    val alive = cells.aliveList
                    for (i in 0 until alive.size) {
                        val cellIndex = alive.getInt(i)
                        if (cells.organIndex[cellIndex] == organIndex &&
                            cells.cellType[cellIndex].toInt() == typeIndex
                        ) {
                            found = 1
                            break
                        }
                    }
                }
                listOf(LuaNumber.of(found))
            }
            else -> listOf()
        }
    }

    val win = LuaNativeFunction {//при isLevel у каждой map есть свой id, при win мы отображаем popup а затем засчитываем json файл с levelam'и как пройденный
        if (DISimulationContainer.simulationData.isGuide) {
            val file = Gdx.files.local("campaign/levels/levels.json")
            val json = Json()

            // 2. Читаем существующий файл или создаем новую карту, если файла нет
            val settings: ObjectMap<String, Boolean> = if (file.exists()) {
                json.fromJson(ObjectMap::class.java, java.lang.Boolean::class.java, file)
                    as ObjectMap<String, Boolean>
            } else {
                ObjectMap()
            }

            settings.put(DISimulationContainer.simulationData.level.id.toString(), true)
            file.writeString(json.prettyPrint(settings), false)
        }

        rules.resumeAfterReading()
        // Экран уже закрыли (кнопка «назад» или dispose во время ожидания окна): поздний
        // GoBack увёл бы игрока ещё на шаг назад, например из редактора в меню.
        if (!simulationData.isFinish) Gdx.app.postRunnable {
            DIGameGlobalContainer.navigationCommandsManager.performCommand(GoBack)
        }

        listOf()
    }

    val bundle = LuaNativeFunction {
        args -> val name = string(args, 0)

        listOf(LuaString.of(DIGameGlobalContainer.bundle.get(name) ?: ""))
    }


    val lose = LuaNativeFunction { args ->
        rules.fail(string(args, 0).ifEmpty { "Уровень провален" })
        listOf()
    }

    val popup = LuaNativeFunction { args ->
        val text = string(args, 0)
        val title = string(args, 1).ifEmpty { "title" }
        val pause = bool(args, 2)
        if (pause) rules.requestReadingPause()
        // Очередь трогает только поток рендера (postRunnable и pumpPopups): сцену нельзя
        // дёргать из симуляции. Счётчик — под локом, его читает ждущий поток симуляции.
        synchronized(popupLock) { pendingPopups++ }
        Gdx.app.postRunnable {
            popups.addLast(PopUp(title, text))
        }
        // Пока окно не закрыто, следующая строка скрипта не выполняется: поток симуляции
        // стоит, тики не идут. При повторе .bug ждать некого — окно там никто не закроет;
        // в редакторе (LogicUI) run() зовётся с потока рендера, и ожидание заморозило бы
        // игру целиком.
        if (!BugReplay.isActive && DISimulationContainer.simulationSystem.isSimulationThread()) {
            awaitPopupsClosed()
        }
        listOf()
    }

    val objective = LuaNativeFunction { args ->
        rules.setObjective(string(args, 0))
        listOf()
    }

    val progress = LuaNativeFunction { args ->
        rules.setProgress(number(args, 0).toInt(), number(args, 1).toInt())
        listOf()
    }

    val marker = LuaNativeFunction { args ->
        if (args.isEmpty()) rules.clearMarker() else rules.setMarker(number(args, 0), number(args, 1))
        listOf()
    }

    val zone = LuaNativeFunction { args ->
        if (args.isEmpty()) {
            rules.clearZone()
        } else {
            rules.setZone(number(args, 0), number(args, 1), number(args, 2))
        }
        listOf()
    }

    val timeLimit = LuaNativeFunction { args ->
        rules.setTimeLimit(number(args, 0))
        listOf()
    }

    /**
     * Разрешить или запретить перетаскивание организма. По умолчанию в уровне запрещено:
     * иначе тело перетаскивают к финишу и уровень проходится без игры.
     */
    val setMovable = LuaNativeFunction { args ->
        simulationData.isMovable = bool(args, 0)
        listOf()
    }

    val hint = LuaNativeFunction { args ->
        val text = string(args, 0)
        if (rules.hintOnce(text)) {
            // Подсказка тоже считается: если следом скрипт ждёт окно, оно не должно
            // «отпустить» ожидание, пока открыта ещё и подсказка.
            synchronized(popupLock) { pendingPopups++ }
            Gdx.app.postRunnable {
                popups.addLast(PopUp("Подсказка", text))
            }
        }
        listOf()
    }

    val onCells = LuaNativeFunction { args ->
        val fn = function(args, 1)
        if (fn != null) rules.setOnCells(number(args, 0).toInt(), fn)
        listOf()
    }

    val onDestination = LuaNativeFunction { args ->
        function(args, 0)?.let { rules.setOnDestination(it) }
        listOf()
    }

    val onTimeout = LuaNativeFunction { args ->
        function(args, 0)?.let { rules.setOnTimeout(it) }
        listOf()
    }

    val afterTicks = LuaNativeFunction { args ->
        val fn = function(args, 1)
        if (fn != null) rules.afterTicks(number(args, 0).toInt(), fn)
        listOf()
    }

    val spawnFood = LuaNativeFunction { args ->
        val x = number(args, 0)
        val y = number(args, 1)
        val radius = if (args.size > 2) number(args, 2) else DEFAULT_FOOD_RADIUS
        if (x > 0f && x < DISimulationContainer.gridWidth && y > 0f && y < DISimulationContainer.gridHeight) {
            DISimulationContainer.worldCommandsManager.worldCommandLastBuffer.push(
                type = WorldCommandType.ADD_SUBSTANCE,
                ints = intArrayOf(Color.RED.toIntBits(), 0),
                floats = floatArrayOf(x, y, radius)
            )
        }
        listOf()
    }

    val random = LuaNativeFunction { args ->
        val result = when (args.size) {
            0 -> rules.randomFloat(0f, 1f)
            1 -> rules.randomFloat(0f, number(args, 0))
            else -> rules.randomFloat(number(args, 0), number(args, 1))
        }
        listOf(LuaNumber.of(result.toDouble()))
    }

    val log = LuaNativeFunction { args ->
        Gdx.app.log("Level", string(args, 0))
        listOf()
    }

    fun execute() {
        if (!simulationData.isLevel) return

        val cells = DISimulationContainer.simulationSystem.getStats().cells
        rules.step(cells)

        val level = simulationData.level
        if (!cellsTriggerFired && level.cellsNeed > 0 && cells >= level.cellsNeed) {
            cellsTriggerFired = true
            DIGameGlobalContainer.luaEnv.cells()
        }
        if (!destinationTriggerFired && level.needDestination && destinationReached()) {
            destinationTriggerFired = true
            rules.destinationReached()
            DIGameGlobalContainer.luaEnv.terrain()
        }
    }

    fun onPopupClosed() {
        popupActive = false
        rules.resumeAfterReading()
        synchronized(popupLock) {
            if (pendingPopups > 0) pendingPopups--
            popupLock.notifyAll()
        }
    }

    /**
     * Ждать, пока закроют все окна, поставленные до этого места скрипта.
     *
     * Слайс ожидания, а не вечное wait(): экран могут закрыть кнопкой «назад», и тогда
     * dispose() ставит isFinish и прерывает поток. Без проверки флага ожидание пережило
     * бы экран, а без выхода по InterruptedException join() в stopUpdateThread ждал бы
     * целую секунду.
     */
    private fun awaitPopupsClosed() {
        synchronized(popupLock) {
            while (pendingPopups > 0) {
                if (DISimulationContainer.simulationData.isFinish) return
                try {
                    popupLock.wait(POPUP_WAIT_SLICE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * Новый экран симуляции — очередь окон с чистого листа.
     *
     * Иначе окно, поставленное в прошлом мире прямо перед победой (например, окно
     * «Победа» перед win), всплывало первым уже в следующем запуске уровня. Зовётся
     * только с потока рендера, до старта потока симуляции.
     */
    fun resetPopups() {
        popups.clear()
        popupActive = false
        synchronized(popupLock) {
            pendingPopups = 0
            popupLock.notifyAll()
        }
    }

    fun pumpPopups() {
        if (!popupActive && !popups.isEmpty && DIGameGlobalContainer.game.screen is SimulationScreen) {
            popups.get(0).show((DIGameGlobalContainer.game.screen as SimulationScreen).getStage())
            popups.removeFirst()
            popupActive = true
        }
    }

    fun resetTriggers() {
        cellsTriggerFired = false
        destinationTriggerFired = false
    }

    private fun destinationReached(): Boolean {
        // В уровне с организмами, которых ставит сам уровень (враг, скриптовый пловец),
        // финиш считается по ним. Игрок в такой цели не участвует: иначе достаточно было
        // бы довести своё тело до финиша, не разыгрывая сценарий.
        // Смотрим на список из уровня, а не на уже заспавненные индексы: до конца первого
        // тика индексов ещё нет, и проверка не должна успеть засчитать финиш по игроку.
        if (simulationData.level.scriptedOrganisms.isNotEmpty()) {
            return scriptedDestinationReached()
        }

        val (sx, sy) = getAveragePos() ?: return false
        val dx = simulationData.level.destination.first - sx
        val dy = simulationData.level.destination.second - sy
        return dx * dx + dy * dy <= DESTINATION_RADIUS * DESTINATION_RADIUS
    }

    /**
     * Дошёл ли хотя бы один организм, поставленный уровнем, до финиша.
     *
     * Считаем каждый организм отдельно, а не среднюю всех: если врагов два, средняя
     * точка может оказаться между ними и никогда не попасть в радиус. Аллокаций нет,
     * организмов уровня обычно один-два, но код не завязан на это число.
     */
    private fun scriptedDestinationReached(): Boolean {
        val cell = DISimulationContainer.cellEntity
        val particle = DISimulationContainer.particleEntity
        val alive = cell.aliveList
        val scripted = simulationData.scriptedOrganIndices
        val destX = simulationData.level.destination.first
        val destY = simulationData.level.destination.second
        val radiusSquared = DESTINATION_RADIUS * DESTINATION_RADIUS

        for (s in 0 until scripted.size) {
            val organ = scripted.getInt(s)
            var sx = 0f
            var sy = 0f
            var count = 0
            for (i in 0 until alive.size) {
                val cellIndex = alive.getInt(i)
                if (cell.organIndex[cellIndex] != organ) continue
                val particleIndex = cell.particleIndexes[cellIndex]
                if (particleIndex < 0) continue
                sx += particle.x[particleIndex]
                sy += particle.y[particleIndex]
                count++
            }
            if (count == 0) continue
            val dx = destX - sx / count
            val dy = destY - sy / count
            if (dx * dx + dy * dy <= radiusSquared) return true
        }
        return false
    }

    private fun getAveragePos(): Pair<Float, Float>? {
        val cell = DISimulationContainer.cellEntity
        val particle = DISimulationContainer.particleEntity
        val alive = cell.aliveList
        // Считаем по организму, который игрок поставил последним: если он один - это тело
        // целиком, а если игрок успел поставить несколько - двигаться к финишу должно
        // именно последнее. -1 — мышью ещё не ставили (скриптовый спавн), тогда как раньше
        // берём все клетки, чтобы старые уровни не сломались.
        val organ = simulationData.lastPlacedOrganIndex

        var sx = 0f
        var sy = 0f
        var count = 0
        for (i in 0 until alive.size) {
            val cellIndex = alive.getInt(i)
            if (organ >= 0 && cell.organIndex[cellIndex] != organ) continue
            val particleIndex = cell.particleIndexes[cellIndex]
            if (particleIndex < 0) continue
            sx += particle.x[particleIndex]
            sy += particle.y[particleIndex]
            count++
        }
        if (count == 0) return null

        return sx/count to sy/count
    }


    private fun string(args: List<LuaValue<*>>, index: Int): String =
        (args.getOrNull(index) as? LuaString)?.value ?: ""

    private fun number(args: List<LuaValue<*>>, index: Int): Float =
        (args.getOrNull(index) as? LuaNumber)?.toDouble()?.toFloat() ?: 0f

    private fun bool(args: List<LuaValue<*>>, index: Int): Boolean =
        (args.getOrNull(index) as? LuaBoolean)?.value == true

    private fun function(args: List<LuaValue<*>>, index: Int): LuaFunction? =
        args.getOrNull(index) as? LuaFunction

    private companion object {
        const val DESTINATION_RADIUS = 7f

        const val DEFAULT_FOOD_RADIUS = 0.3f

        /** Раз в столько миллисекунд ждущее окно проверяет, не закрыли ли экран. */
        const val POPUP_WAIT_SLICE_MS = 100L
    }
}
