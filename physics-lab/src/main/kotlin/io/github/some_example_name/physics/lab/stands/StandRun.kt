package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.PhysicsWorkers

/**
 * ОДИН ПРОГОН СЦЕНЫ: мир, собранный сценой, и монитор правил над ним. Общий для окна и
 * пакетного прогона: окно шагает по тику на кадр, пакет — до конца сразу.
 *
 * Сцена собирается и шагает в своей вариации (Variation.use): фигуры в это время отдают позу
 * и радиусы этой вариации — и при расстановке тел, и в действиях сценария по тикам.
 *
 * Падение решателя (исключение в шаге мира) — тоже итог: прогон останавливается, сцена
 * считается проваленной, текст исключения остаётся в [crash].
 */
class StandRun(val scenario: Scenario, workers: PhysicsWorkers? = null, forceParallel: Boolean = false, frame: Frame = STAND_FRAME,
               /** Число подшагов эталонного решателя; 0 — быстрый (StandWorld.reference). */
               reference: Int = 0,
               /** Остановить прогон на этом тике: эталон идёт ровно столько, сколько шёл быстрый (тот мог уснуть раньше срока). */
               private val stopAt: Int = Int.MAX_VALUE) {
    val w = StandWorld(workers, forceParallel, frame, reference)
    val monitor: RuleMonitor
    /** Об кого и как гибнут клетки (KillCauses). У прогона с воркерами и у эталона счёт пуст. */
    val kills = KillCauses(w)
    private val trackKills = workers == null && reference == 0
    var crash: Throwable? = null
        private set

    init {
        Variation.use(scenario.variation) { scenario.setup(w); w.defaultPremise() }
        monitor = RuleMonitor(w, scenario.limits, scenario.ticks)
        monitor.begin()
    }

    val tick: Int get() = w.tick
    /**
     * Сцена закончилась раньше срока: всё уснуло, а сценарию, росту и мыши больше нечего делать.
     * Спящий мир не меняется, поэтому досчитывать его до конца незачем — длительность сцены
     * только верхняя граница, взятая с запасом.
     */
    val settledEarly: Boolean get() = crash == null && w.tick < scenario.ticks && monitor.sleepTick >= 0 && w.tick > w.scriptEnd && !w.busy

    val done: Boolean get() = w.tick >= minOf(scenario.ticks, stopAt) || crash != null || settledEarly
    val passed: Boolean get() = crash == null && monitor.passed

    fun step() {
        if (done) return
        try {
            Variation.use(scenario.variation) {
                if (trackKills) kills.attach()
                w.step()
                monitor.afterTick()
            }
        } catch (t: Throwable) {
            crash = t
        }
        if (done) Variation.use(scenario.variation) { monitor.finish() }
    }

    /** Шаг мира после конца сцены (свободный ход в окне): правила уже не считаются. */
    fun stepFree() { Variation.use(scenario.variation) { w.step() } }

    /** Действие над миром сцены в её вариации — для окна: захват клетки мышью, чтение проб. */
    fun <T> inVariation(block: () -> T): T = Variation.use(scenario.variation, block)

    fun runAll(): StandRun { while (!done) step(); return this }
}
