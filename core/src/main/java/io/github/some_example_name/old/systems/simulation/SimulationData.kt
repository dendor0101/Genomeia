package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.ScreenCell
import io.github.some_example_name.old.features.levels.editor.LevelInfo
import it.unimi.dsi.fastutil.ints.IntArrayList

class SimulationData {
    var isRestart = false
    var tickCounter = 0
    var timeSimulation = 0f

    /** Мир — уровень (Lua-скрипт, цель, зона старта, [level]); false — обычная симуляция. */
    var isLevel = false

    /**
     * Организмы, которых поставил сам уровень (LevelInfo.scriptedOrganisms).
     *
     * Нужны проверке terrain: в уровне с врагом/скриптовым организмом финиш считается
     * по нему, а не по телу игрока. Список наполняет WorldCommandsManager в момент
     * SPAWN_ORGANISM и входит в StateHash, чтобы повтор видел ту же цель.
     */
    val scriptedOrganIndices = IntArrayList()

    /**
     * Можно ли двигать организм мышью. В обычном мире — да, в уровне — нет: иначе тело
     * перетаскивают прямо к финишу. Скрипт уровня снимает запрет вызовом setMovable(true).
     * Ставится заново в начале каждой сессии (SimulationSystem.startSession), поэтому
     * рестарт уровня не наследует разрешение от прошлого захода.
     */
    var isMovable = true

    /** Уровень мира; читать только при [isLevel] — ставит NavigationCommandsManager.applyLevel. */
    lateinit var level: LevelInfo

    //Пока вынес все глобальные переменные сюда, но кажется это все не совсем к месту
    var currentGenomeIndex = 0

    var isPlay = true
    var maxSpeed = false

    /**
     * Тиков в секунду по умолчанию. 30: тик игры — один шаг мира физики длиной DT = 1/30 с
     * (physics/bridge/PhysicsUnits), так мир идёт в реальном времени, как стенд физики.
     */
    var targetUPS: Int = 30
        set(value) {
            field = value.coerceIn(1, 1000)
        }
    var ups = 60
    var selectedCellIndex = -1
    /** Только для подсветки: освободившийся слот не должен подсветить нового жильца. */
    var selectedFrameCell: ScreenCell? = null

    /**
     * Организм, который игрок поставил мышью последним, -1 — ещё не ставил.
     *
     * По нему считается средняя точка для финиша уровня: иначе центр тела сдвигали бы
     * чужие клетки (старые организмы, деления, скриптовые спавны), и цель засчитывалась
     * бы не там, где игрок водит своё тело.
     */
    var lastPlacedOrganIndex = -1

    var showControllerKeys = false
    /** Что нажато сейчас. Пишет поток рендера в любой момент, читать в симуляции нельзя. */
    val controllerKeyTouched = BooleanArray(ControllerKeys.COUNT)

    /**
     * Клавиши, которые видит симуляция: снимок [controllerKeyTouched] в начале тика.
     * Весь тик одинаковые для всех клеток (рендер не переключит их посреди фазы), и ровно
     * их пишет в журнал сессии и подставляет повтор бага (SessionJournal / BugReplay).
     */
    val controllerKeys = BooleanArray(ControllerKeys.COUNT)

    /**
     * Стресс жизненного цикла для проверок, см. PlayerCommand.SetStress: раз во сколько тиков
     * убить клетку, отбросить клетку, посадить организм; 0 — выключено. Состояние мира: в
     * хэше (SimulationSystem.hashState), сбрасывается с началом сессии.
     */
    var stressKillEvery = 0
    var stressFlingEvery = 0
    var stressSpawnEvery = 0

    /**
     * Отладка физики, см. PlayerCommand.SetPhysicsDebug (галочки отладочного режима): хвосты не
     * толкают (Tail) — виден собственный дрейф тела; глубокое проникновение частиц не убивает
     * (PhysicsWorld.deepKillOn). Состояние мира: в хэше, сбрасывается с началом сессии.
     */
    var debugNoTailThrust = false
    var debugNoDeepKill = false

    fun clear() {
        isRestart = false
        tickCounter = 0
        timeSimulation = 0f
        selectedCellIndex = -1
        selectedFrameCell = null
        lastPlacedOrganIndex = -1
        scriptedOrganIndices.clear()
        // Пауза — состояние ЭКРАНА, а не мира: её ставит окно уровня или диалог провала,
        // и уходя из симуляции его никто не снимает. Без сброса следующий мир (например,
        // уровень, запущенный из редактора после выигранного/проваленного) открывался
        // стоящим на тике 0: поток обновления видит isPlay=false и просто спит.
        // clear() зовётся при сборке каждого нового мира — это и есть точка «с чистого листа».
        // Только в уровне: обычный мир ведёт себя ровно как до PR 38 (рестарт на паузе
        // остаётся на паузе, BugReplay сам решает, когда мир стоит). isLevel ставится до
        // сборки мира (applyLevel), поэтому здесь он уже про новый мир.
        if (isLevel) isPlay = true
    }


    fun resetLevel() {
        isLevel = false
        isMovable = true
        scriptedOrganIndices.clear()
    }

}
