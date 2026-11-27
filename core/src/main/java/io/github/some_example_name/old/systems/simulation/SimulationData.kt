package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.features.levels.editor.LevelInfo
import it.unimi.dsi.fastutil.ints.IntArrayList

class SimulationData {
    var isRestart = false
    var isFinish = false
    var tickCounter = 0
    var timeSimulation = 0f

    var isLevel = false //level-игровой уровень с lua логикой и конечной целью. при false-обычная симуляция.
    var isGuide = false

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
//    var hasLua = false
//    var hasDestPoint = false //точка куда игрок должен добраться
//    var cellsNeed = -1 //сколько клеток нужно для победы
    lateinit var level: LevelInfo

    //Пока вынес все глобальные переменные сюда, но кажется это все не совсем к месту
    var currentGenomeIndex = 0

    var isPlay = true
    var maxSpeed = false

    var targetUPS: Int = 60
        set(value) {
            field = value.coerceIn(1, 1000)
        }
    var ups = 60
    var selectedCellIndex = -1

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

    fun clear() {
        isRestart = false
        isFinish = false
        tickCounter = 0
        timeSimulation = 0f
        selectedCellIndex = -1
        lastPlacedOrganIndex = -1
        scriptedOrganIndices.clear()
        // Пауза — состояние ЭКРАНА, а не мира: её ставит окно уровня или диалог провала,
        // и уходя из симуляции его никто не снимает. Без сброса следующий мир (например,
        // уровень, запущенный из редактора после выигранного/проваленного) открывался
        // стоящим на тике 0: поток обновления видит isPlay=false и просто спит.
        // clear() зовётся при сборке каждого нового мира — это и есть точка «с чистого листа».
        isPlay = true
    }


    fun resetLevel() {
        isLevel = false
        isGuide = false
        isMovable = true
        scriptedOrganIndices.clear()
    }

}
