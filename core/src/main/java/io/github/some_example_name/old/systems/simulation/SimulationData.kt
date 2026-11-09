package io.github.some_example_name.old.systems.simulation

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
     * Отладочный стресс-тест жизненного цикла клеток: пока клавиша зажата, каждый тик
     * несколько случайных клеток умирают (A) или получают предельную скорость и улетают,
     * рвя свои связи (D).
     *
     * Нужен, чтобы не воспроизводить руками сценарии, на которых всплывают гонки и битые
     * ссылки в связях: они возникают именно при активной смерти клеток и разрыве связей,
     * а руками такое повторять долго и ненадёжно.
     *
     * Выставляет поток симуляции из снимка клавиш контроллера (A и D), в начале тика —
     * поэтому стресс попадает в журнал сессии и повторяется при повторе бага.
     *
     * Работает только при DEBUG_CHECKS: в обычной сборке флаги никем не выставляются.
     */
    var debugKillCells = false
    var debugFlingCells = false

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
    }

}
