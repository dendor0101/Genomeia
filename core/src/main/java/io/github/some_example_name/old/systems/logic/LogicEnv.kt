package io.github.some_example_name.old.systems.logic

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.simulationData
import io.github.some_example_name.old.features.levels.LevelProgress
import io.github.some_example_name.old.features.levels.dialogs.PopUp
import io.github.some_example_name.old.features.simulation.SimulationScreen
import org.squiddev.cobalt.Constants
import org.squiddev.cobalt.LuaNumber
import org.squiddev.cobalt.LuaString
import org.squiddev.cobalt.ValueFactory.valueOf
import org.squiddev.cobalt.ValueFactory.varargsOf
import org.squiddev.cobalt.Varargs
import org.squiddev.cobalt.function.LibFunction
import org.squiddev.cobalt.function.LuaFunction
import java.util.MissingResourceException

/**
 * API уровня для Lua: имена функций, разбор аргументов, окна. Состояние и условия — в
 * [LevelRules]; машина Lua и песочница — в [EnvRun].
 */
class LogicEnv {
    val popups = com.badlogic.gdx.utils.Queue<PopUp>()
    var popupActive = false

    /**
     * Ожидание окна: popup() держит поток симуляции, пока игрок не закроет окно, а
     * onPopupClosed() его будит. Очередь трогает только поток рендера, поэтому счётчик
     * и очередь — разные вещи: счётчик под локом, очередь без (у неё один хозяин).
     *
     * Пока поток симуляции стоит, воркеры ParallelExecutor не крутятся вхолостую: после
     * короткого спина они засыпают (parkNanos с растущей паузой, PlatformTuning).
     */
    private val popupLock = Object()

    /** Сколько окон поставлено в очередь и ещё не закрыто. Под [popupLock]. */
    private var pendingPopups = 0

    /** Состояние уровня для экрана: цель, прогресс, метка, зона, провал. */
    val rules = LevelRules()

    private var cellsTriggerFired = false
    private var destinationTriggerFired = false

    val getCellsCount = native { _ ->
        valueOf(DISimulationContainer.simulationSystem.getStats().cells)
    }

    val world = native { args ->
        when (string(args, 0)) {
            "cells" -> valueOf(DISimulationContainer.simulationSystem.getStats().cells)
            "time" -> valueOf(simulationData.timeSimulation.toDouble())
            "tick" -> valueOf(simulationData.tickCounter)
            "pos" -> {
                val found = averagePos(simulationData.lastPlacedOrganIndex)
                varargsOf(valueOf(posX.toDouble() * found), valueOf(posY.toDouble() * found))
            }
            // Есть ли в организме игрока живая клетка этого типа. Нужно уровням-гайдам:
            // палитра прячет лишние клетки, но не мешает пройти уровень вообще без той,
            // которой он посвящён. Смотрим последний поставленный мышью организм: скриптовые
            // спавны (продюсер, враг уровня) не должны закрывать условие за игрока.
            "has_cell" -> valueOf(if (hasCell(simulationData.lastPlacedOrganIndex, string(args, 1))) 1 else 0)
            else -> Constants.NONE
        }
    }

    val win = native { _ ->
        if (simulationData.isLevel) LevelProgress.markCompleted(simulationData.level.id)
        rules.resumeAfterReading()
        // Мир уже закрыли (кнопка «назад» во время ожидания окна): closeWorld прерывает поток
        // симуляции. Поздний GoBack увёл бы игрока ещё на шаг назад, из редактора в меню.
        if (!Thread.currentThread().isInterrupted) Gdx.app.postRunnable {
            DIGameGlobalContainer.navigationCommandsManager.performCommand(GoBack)
        }
        Constants.NONE
    }

    /** Текст из бандла по ключу. Опечатка в ключе — сам ключ вместо текста, а не падение тика. */
    val bundle = native { args ->
        val key = string(args, 0)
        val text = try {
            DIGameGlobalContainer.bundle.get(key)
        } catch (e: MissingResourceException) {
            Gdx.app?.error(TAG, "в бандле нет ключа '$key'")
            key
        }
        luaUtf8(text)
    }

    val lose = native { args ->
        rules.fail(string(args, 0).ifEmpty { LevelRules.FAIL_DEFAULT_KEY })
        Constants.NONE
    }

    val popup = native { args ->
        val text = string(args, 0)
        // Пустой заголовок PopUp заменит своим из бандла (в потоке рендера, где есть Gdx).
        val title = string(args, 1)
        if (bool(args, 2)) rules.requestReadingPause()
        // Очередь трогает только поток рендера (postRunnable и pumpPopups): сцену нельзя
        // дёргать из симуляции. Счётчик — под локом, его читает ждущий поток симуляции.
        synchronized(popupLock) { pendingPopups++ }
        Gdx.app.postRunnable { popups.addLast(PopUp(title, text)) }
        // Пока окно не закрыто, следующая строка скрипта не выполняется: поток симуляции
        // стоит, тики не идут. При повторе .bug ждать некого — окно там никто не закроет.
        val simulation = DISimulationContainer.simulationSystem
        if (!BugReplay.isActive && simulation.isSimulationThread()) {
            simulation.waitOutsideTick { awaitPopupsClosed() }
        }
        Constants.NONE
    }

    val objective = native { args ->
        rules.setObjective(string(args, 0))
        Constants.NONE
    }

    val progress = native { args ->
        rules.setProgress(number(args, 0).toInt(), number(args, 1).toInt())
        Constants.NONE
    }

    val marker = native { args ->
        if ((args.count() == 0)) rules.clearMarker() else rules.setMarker(number(args, 0), number(args, 1))
        Constants.NONE
    }

    val zone = native { args ->
        if ((args.count() == 0)) rules.clearZone() else rules.setZone(number(args, 0), number(args, 1), number(args, 2))
        Constants.NONE
    }

    val timeLimit = native { args ->
        rules.setTimeLimit(number(args, 0))
        Constants.NONE
    }

    /**
     * Разрешить или запретить перетаскивание организма. По умолчанию в уровне запрещено:
     * иначе тело перетаскивают к финишу и уровень проходится без игры.
     */
    val setMovable = native { args ->
        simulationData.isMovable = bool(args, 0)
        Constants.NONE
    }

    val hint = native { args ->
        val text = string(args, 0)
        if (rules.hintOnce(text)) {
            // Подсказка тоже считается: если следом скрипт ждёт окно, оно не должно
            // «отпустить» ожидание, пока открыта ещё и подсказка.
            synchronized(popupLock) { pendingPopups++ }
            Gdx.app.postRunnable {
                popups.addLast(PopUp(DIGameGlobalContainer.bundle.get("level.hint"), text))
            }
        }
        Constants.NONE
    }

    val onCells = native { args ->
        val fn = function(args, 1)
        if (fn != null) rules.setOnCells(number(args, 0).toInt(), fn)
        Constants.NONE
    }

    val onDestination = native { args ->
        function(args, 0)?.let { rules.setOnDestination(it) }
        Constants.NONE
    }

    val onTimeout = native { args ->
        function(args, 0)?.let { rules.setOnTimeout(it) }
        Constants.NONE
    }

    val afterTicks = native { args ->
        val fn = function(args, 1)
        if (fn != null) rules.afterTicks(number(args, 0).toInt(), fn)
        Constants.NONE
    }

    val spawnFood = native { args ->
        val x = number(args, 0)
        val y = number(args, 1)
        val radius = if (args.count() > 2) number(args, 2) else DEFAULT_FOOD_RADIUS
        if (x > 0f && x < DISimulationContainer.gridWidth && y > 0f && y < DISimulationContainer.gridHeight) {
            // Сюда, а не в общий буфер: скрипт зовётся в начале тика, до параллельных
            // стадий, и его команды должны лечь после них — порядок не зависит от воркеров.
            DISimulationContainer.worldCommandsManager.worldCommandLastBuffer.push(
                type = WorldCommandType.ADD_SUBSTANCE,
                ints = intArrayOf(Color.RED.toIntBits(), 0),
                floats = floatArrayOf(x, y, radius)
            )
        }
        Constants.NONE
    }

    /** random(), random(b), random(a, b) — дробное, из генератора уровня с сидом сессии. */
    val random = native { args ->
        val result = when (args.count()) {
            0 -> rules.randomFloat(0f, 1f)
            1 -> rules.randomFloat(0f, number(args, 0))
            else -> rules.randomFloat(number(args, 0), number(args, 1))
        }
        valueOf(result.toDouble())
    }

    /**
     * math.random с правилами Lua — () дробное из [0, 1), (m) целое из [1, m], (m, n)
     * целое из [m, n], — но из того же генератора уровня, что и [random]: у Cobalt он сеется
     * от часов, и повтор .bug выдавал бы другие числа.
     */
    val mathRandom = native { args ->
        when (args.count()) {
            0 -> valueOf(rules.randomFloat(0f, 1f).toDouble())
            1 -> valueOf(rules.randomInt(1, integer(args, 0)))
            else -> valueOf(rules.randomInt(integer(args, 0), integer(args, 1)))
        }
    }

    val log = native { args ->
        Gdx.app?.log("Level", string(args, 0))
        Constants.NONE
    }

    /**
     * Ровно раз за тик уровня: события правил, затем cells()/terrain() скрипта, когда
     * выполнилось условие уровня. [callFunction] — вызов функции скрипта по имени (EnvRun).
     */
    fun execute(callFunction: (String) -> Unit) {
        if (!simulationData.isLevel) return

        val cells = DISimulationContainer.simulationSystem.getStats().cells
        rules.step(cells)

        val level = simulationData.level
        if (!cellsTriggerFired && level.cellsNeed > 0 && cells >= level.cellsNeed) {
            cellsTriggerFired = true
            callFunction("cells")
        }
        if (!destinationTriggerFired && level.needDestination && destinationReached()) {
            destinationTriggerFired = true
            rules.destinationReached()
            callFunction("terrain")
        }
    }

    /** Ошибка скрипта — в лог; уровень идёт дальше. */
    fun reportScriptError(e: Exception) {
        Gdx.app?.error(TAG, "ошибка в скрипте уровня", e)
    }

    /** Скрипт оборван пределом инструкций: уровень провален с объяснением, а не завис. */
    fun reportScriptTooLong() {
        Gdx.app?.error(TAG, "скрипт уровня превысил предел инструкций (${EnvRun.INSTRUCTION_BUDGET}) и остановлен")
        rules.fail(LevelRules.FAIL_SCRIPT_KEY)
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
     * Слайс ожидания, а не вечное wait(): экран могут закрыть кнопкой «назад», тогда
     * closeWorld прерывает поток. Флаг прерывания оставляем стоять: по нему win() узнаёт,
     * что уходить назад уже не нужно.
     */
    private fun awaitPopupsClosed() {
        synchronized(popupLock) {
            while (pendingPopups > 0) {
                if (Thread.currentThread().isInterrupted) return
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
        val screen = DIGameGlobalContainer.game.screen as? SimulationScreen ?: return
        if (!popupActive && !popups.isEmpty) {
            popups.removeFirst().show(screen.getStage())
            popupActive = true
        }
    }

    fun resetTriggers() {
        cellsTriggerFired = false
        destinationTriggerFired = false
    }

    private fun destinationReached(): Boolean {
        val level = simulationData.level
        val radiusSquared = DESTINATION_RADIUS * DESTINATION_RADIUS
        // В уровне с организмами, которых ставит сам уровень (враг, скриптовый пловец),
        // финиш считается по ним, по каждому отдельно: средняя точка двух врагов могла бы
        // никогда не попасть в радиус. Смотрим на список из уровня, а не на уже заспавненные
        // индексы: до конца первого тика индексов ещё нет, и финиш не должен засчитаться по игроку.
        if (level.scriptedOrganisms.isNotEmpty()) {
            val scripted = simulationData.scriptedOrganIndices
            for (s in 0 until scripted.size) {
                if (averagePos(scripted.getInt(s)) == 0) continue
                if (closeTo(level.destination.first, level.destination.second, radiusSquared)) return true
            }
            return false
        }
        if (averagePos(simulationData.lastPlacedOrganIndex) == 0) return false
        return closeTo(level.destination.first, level.destination.second, radiusSquared)
    }

    private fun closeTo(x: Float, y: Float, radiusSquared: Float): Boolean {
        val dx = x - posX
        val dy = y - posY
        return dx * dx + dy * dy <= radiusSquared
    }

    /** Итог [averagePos]: средняя точка, без пары и без аллокаций — проверка финиша каждый тик. */
    private var posX = 0f
    private var posY = 0f

    /**
     * Средняя точка живых клеток организма в [posX]/[posY]; 1 — нашлась, 0 — клеток нет.
     *
     * По арене организма, а не по всему миру: клетки организма лежат в его диапазоне
     * индексов (OrganEntity.cellArenaBase…cellArenaEnd), и проверка финиша, которая идёт
     * каждый тик, стоит столько, сколько клеток в одном теле, а не в мире из 100 тысяч.
     * [organ] < 0 — мышью ещё не ставили (тело пришло тестом генома): тогда, как и
     * раньше, все клетки мира — в уровне их единицы.
     */
    private fun averagePos(organ: Int): Int {
        val cells = DISimulationContainer.cellEntity
        val organs = DISimulationContainer.organEntity
        var sx = 0f
        var sy = 0f
        var count = 0
        if (organ >= 0) {
            if (!organs.hasArena(organ)) return 0
            val isAlive = cells.isAlive
            val organIndex = cells.organIndex
            for (i in organs.cellArenaBase[organ] until organs.cellArenaEnd(organ)) {
                if (!isAlive[i] || organIndex[i] != organ) continue
                sx += cells.getX(i)
                sy += cells.getY(i)
                count++
            }
        } else {
            val alive = cells.aliveList
            for (k in 0 until alive.size) {
                val i = alive.getInt(k)
                sx += cells.getX(i)
                sy += cells.getY(i)
                count++
            }
        }
        if (count == 0) return 0
        posX = sx / count
        posY = sy / count
        return 1
    }

    /** Есть ли у организма живая клетка типа [key] — тоже по его арене, см. [averagePos]. */
    private fun hasCell(organ: Int, key: String): Boolean {
        val typeIndex = DISimulationContainer.cellList.indexOfKey(key)
        val organs = DISimulationContainer.organEntity
        if (typeIndex < 0 || organ < 0 || !organs.hasArena(organ)) return false
        val cells = DISimulationContainer.cellEntity
        for (i in organs.cellArenaBase[organ] until organs.cellArenaEnd(organ)) {
            if (cells.isAlive[i] && cells.organIndex[i] == organ && cells.cellType[i].toInt() == typeIndex) return true
        }
        return false
    }

    // Аргументы — по номеру с нуля, как в API выше; Varargs в Cobalt считает с единицы, а
    // за концом отдаёт nil. Типы строгие, как и раньше: число вместо строки — пустая строка.

    private fun string(args: Varargs, index: Int): String =
        (args.arg(index + 1) as? LuaString)?.utf8() ?: ""

    private fun number(args: Varargs, index: Int): Float =
        (args.arg(index + 1) as? LuaNumber)?.toDouble()?.toFloat() ?: 0f

    private fun integer(args: Varargs, index: Int): Int =
        (args.arg(index + 1) as? LuaNumber)?.toDouble()?.toInt() ?: 0

    private fun bool(args: Varargs, index: Int): Boolean =
        args.arg(index + 1) == Constants.TRUE

    private fun function(args: Varargs, index: Int): LuaFunction? =
        args.arg(index + 1) as? LuaFunction

    /** Нативная функция для Lua: тело получает аргументы и отдаёт результаты. */
    private inline fun native(crossinline body: (Varargs) -> Varargs): LuaFunction =
        LibFunction.createV { _, args -> body(args) }

    private companion object {
        const val TAG = "Level"

        const val DESTINATION_RADIUS = 7f

        const val DEFAULT_FOOD_RADIUS = 0.3f

        /** Раз в столько миллисекунд ждущее окно проверяет, не закрыли ли экран. */
        const val POPUP_WAIT_SLICE_MS = 100L
    }
}
