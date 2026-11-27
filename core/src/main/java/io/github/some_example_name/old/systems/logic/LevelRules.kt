package io.github.some_example_name.old.systems.logic

import ai.tenum.lua.runtime.LuaFunction
import ai.tenum.lua.runtime.LuaNumber
import ai.tenum.lua.runtime.LuaValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.RandomXS128

/**
 * Правила уровня, которые задаёт Lua-скрипт: цель, прогресс, метка и зона на карте,
 * лимит времени, провал и события («когда клеток станет N», «когда дойдём до точки»).
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ [LogicEnv]
 * ---------------------------
 * LogicEnv — это API для Lua: имена функций и разбор аргументов. Здесь лежит только
 * состояние и условия. Экран читает отсюда HUD, метку, зону и повод для окна провала,
 * а двигают их вызовы из Lua. Так экран не разбирает Lua-аргументы, а скрипт не знает
 * про UI.
 *
 * ПОТОКИ
 * ------
 * Пишется в потоке симуляции, читается экраном из потока рендера. Поэтому [hud] и
 * [failReason] — @Volatile, а hud заменяется целиком (иммутабельный снимок): экран
 * видит либо старое состояние, либо новое, но не половину.
 *
 * ВРЕМЯ
 * -----
 * Тики считает сам класс: [step] зовётся ровно раз за тик, пока идёт уровень. Лимит
 * задаётся в секундах, а хранится в тиках — по 1/60 с, как DELTA_SIM_TICK_TIME. Так
 * условие не зависит от того, сколько кадров успел нарисовать экран.
 */
class LevelRules {

    /** Что показать на экране. Заменяется целиком — см. «ПОТОКИ». */
    @Volatile
    var hud = LevelHud()
        private set

    /**
     * Почему уровень провален; null — ещё играем. Экран показывает окно и стирает.
     * Текст из Lua (`lose("...")`) или ключ бандла [FAIL_DEFAULT_KEY] / [FAIL_TIMEOUT_KEY]:
     * бандл живёт в Gdx, а правила крутятся в потоке симуляции и в тестах без Gdx, поэтому
     * ключ в текст переводит экран.
     */
    @Volatile
    var failReason: String? = null
        private set

    /**
     * Скрипт просит паузу, пока игрок читает окно (`popup(..., true)`). Ставит и снимает
     * экран через обычную команду паузы, а не сама симуляция: пауза должна попасть в журнал.
     */
    @Volatile
    var readingPause = false
        private set

    /** Лимит уровня в тиках; 0 — без лимита. */
    private var timeLimitTicks = 0

    private var tick = 0L
    private var cellsThreshold = -1
    private var cellsFired = false
    private var destinationFired = false
    private var timeoutFired = false

    private var onCells: LuaFunction? = null
    private var onDestination: LuaFunction? = null
    private var onTimeout: LuaFunction? = null
    private val afterTicks = ArrayList<AfterTick>()

    /** Показанные подсказки: hint() не должен всплывать повторно. */
    private val shownHints = HashSet<String>()

    /**
     * Случайность уровня — только отсюда. Свой генератор, а не MathUtils.random: сид
     * приходит из SessionStarted, поэтому повтор .bug видит те же числа.
     */
    private val random = RandomXS128()

    // --- состояние, которое ставят функции Lua -------------------------------------

    fun reset() {
        hud = LevelHud()
        failReason = null
        readingPause = false
        timeLimitTicks = 0
        tick = 0
        cellsThreshold = -1
        cellsFired = false
        destinationFired = false
        timeoutFired = false
        onCells = null
        onDestination = null
        onTimeout = null
        afterTicks.clear()
        shownHints.clear()
    }

    /** Сид генератора случайных чисел уровня — тот же, что у сессии мира. */
    fun seed(value: Long) = random.setSeed(value)

    fun setObjective(text: String) {
        hud = hud.copy(objective = text)
    }

    fun setProgress(current: Int, max: Int) {
        hud = hud.copy(progress = current, progressMax = max)
    }

    fun setMarker(x: Float, y: Float) {
        hud = hud.copy(markerX = x, markerY = y, hasMarker = true)
    }

    fun clearMarker() {
        hud = hud.copy(hasMarker = false)
    }

    fun setZone(x: Float, y: Float, radius: Float) {
        hud = hud.copy(zoneX = x, zoneY = y, zoneRadius = radius, hasZone = true)
    }

    fun clearZone() {
        hud = hud.copy(hasZone = false)
    }

    fun setTimeLimit(seconds: Float) {
        timeLimitTicks = (seconds * TICKS_PER_SECOND).toInt().coerceAtLeast(1)
    }

    fun setOnCells(cells: Int, fn: LuaFunction) {
        cellsThreshold = cells
        onCells = fn
    }

    fun setOnDestination(fn: LuaFunction) {
        onDestination = fn
    }

    fun setOnTimeout(fn: LuaFunction) {
        onTimeout = fn
    }

    fun afterTicks(ticks: Int, fn: LuaFunction) {
        afterTicks.add(AfterTick(tick + ticks.coerceAtLeast(1), fn))
    }

    /** Подсказку показываем один раз; false — эту уже показывали. */
    fun hintOnce(text: String): Boolean = shownHints.add(text)

    fun requestReadingPause() {
        readingPause = true
    }

    fun resumeAfterReading() {
        readingPause = false
    }

    /** Уровень провален. Повторный fail() не перебивает первый повод. */
    fun fail(reason: String) {
        if (failReason == null) failReason = reason
    }

    /** Экран показал окно провала и больше не должен его переоткрывать. */
    fun clearFail() {
        failReason = null
    }

    fun randomFloat(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

    fun randomInt(from: Int, to: Int): Int =
        if (to <= from) from else from + random.nextInt(to - from + 1)

    // --- то, что зовётся каждый тик ------------------------------------------------

    /** События по тикам, по числу клеток и по лимиту времени. Ровно раз за тик уровня. */
    fun step(cells: Int) {
        tick++

        if (afterTicks.isNotEmpty()) fireAfterTicks()

        if (!cellsFired && cellsThreshold >= 0 && cells >= cellsThreshold) {
            cellsFired = true
            onCells?.let { call(it, LuaNumber.of(cells)) }
        }

        if (!timeoutFired && timeLimitTicks > 0 && tick >= timeLimitTicks) {
            timeoutFired = true
            // Без своего обработчика — обычный провал: иначе лимит времени ничего не делает.
            if (onTimeout != null) call(onTimeout!!) else fail(FAIL_TIMEOUT_KEY)
        }
    }

    /** Организм дошёл до точки назначения уровня. Стреляет один раз. */
    fun destinationReached() {
        if (destinationFired) return
        destinationFired = true
        onDestination?.let { call(it) }
    }

    private fun fireAfterTicks() {
        // Список маленький (уровни ставят единицы отложенных событий), поэтому без
        // итераторов и без удаления в обход индексов.
        var i = 0
        while (i < afterTicks.size) {
            val event = afterTicks[i]
            if (event.at <= tick) {
                afterTicks.removeAt(i)
                call(event.fn)
            } else {
                i++
            }
        }
    }

    /**
     * Вызов Lua из потока симуляции. Ошибку скрипта не пробрасываем в тик: иначе один
     * кривой уровень валит мир, и повтор .bug упирается в чужую строку Lua.
     */
    private fun call(fn: LuaFunction, vararg args: LuaValue<*>) {
        try {
            fn.call(args.toList())
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "ошибка в скрипте уровня", e)
        }
    }

    private class AfterTick(val at: Long, val fn: LuaFunction)

    companion object {
        private const val TAG = "LevelRules"

        /** DELTA_SIM_TICK_TIME = 1/60 с, см. SimulationSystem. */
        private const val TICKS_PER_SECOND = 60

        /** Ключ бандла: `lose()` без текста. См. [failReason]. */
        const val FAIL_DEFAULT_KEY = "level.failed"

        /** Ключ бандла: вышел лимит времени, а своего `onTimeout` у уровня нет. */
        const val FAIL_TIMEOUT_KEY = "level.timeUp"
    }
}

/**
 * Снимок того, что уровень просит показать. Иммутабельный — чтобы поток рендера не
 * прочитал половину обновления. `progress < 0` — прогресса нет, `hasMarker`/`hasZone` —
 * рисовать ли метку и круг допуска.
 */
data class LevelHud(
    val objective: String = "",
    val progress: Int = -1,
    val progressMax: Int = -1,
    val markerX: Float = 0f,
    val markerY: Float = 0f,
    val hasMarker: Boolean = false,
    val zoneX: Float = 0f,
    val zoneY: Float = 0f,
    val zoneRadius: Float = 0f,
    val hasZone: Boolean = false,
    /** Зона старта из LevelInfo: её ставит экран, а не Lua, — чтобы игрок видел, куда ставить. */
    val spawnX: Float = 0f,
    val spawnY: Float = 0f,
    val spawnRadius: Float = 0f,
    val hasSpawnZone: Boolean = false
)
