package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.ScreenCell

class SimulationData {
    var isRestart = false
    var tickCounter = 0
    var timeSimulation = 0f

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
    }

}
