package io.github.some_example_name.physics.lab.session

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.lab.inspection.trapCrashScan
import io.github.some_example_name.physics.lab.io.BodyFile
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.scenes.BenchScenes
import io.github.some_example_name.physics.lab.scenes.applyLabDamage
import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.BodySimulation
import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.updateMuscles
import kotlin.math.abs
import kotlin.math.sqrt

internal class DemoSession(internal val bodyPath: String,
    /**
     * Сколько копий тела класть на сцену и сколько свободных частиц добавлять.
     *
     * Параметрами, а не константами, ровно ради стенда мягкой решётки: там нужно ОДНО
     * тело и ни одной свободной частицы, иначе опыт мерит не решётку, а её
     * столкновение с копией самой себя.
     */
    internal val copies: Int = ORGANISMS,
    internal val freeParticles: Int = FREE_PARTICLES,
    /** Стенд мягкой решётки: доля связей, убиваемых при сбросе, и сид. См. applyLabDamage. */
    internal val labKillFraction: Double = 0.0,
    internal val labKillSeed: Long = 0L,
    /** Зафиксированные настройки численной модели и лабораторных режимов. */
    internal val physicsConfig: PhysicsConfig = DemoSettings.fromEnvironment(),
) {

    internal val state = BodyState(physicsConfig)
    internal val simulation = BodySimulation(state)
    // Только окно устанавливает эти обработчики. Безоконная сессия не знает Gdx.
    internal var onReset: (() -> Unit)? = null
    internal var onCamera: ((Double, Double) -> Unit)? = null
    internal var sampleInput: (() -> Unit)? = null
    internal var pressedMuscleMask = 0
    internal var hoveredMuscle = -1

    /** Автоматический ритмичный гребок (клавиша G). */
    internal var gait = false

    internal var gaitFrame = 0

    internal var simTime = 0.0

    /** Где был центр масс на сбросе. Дрейф считается от него. */
    internal var startCx = 0.0

    internal var startCy = 0.0

    // =================================================================
    //  ПОСТРОЕНИЕ ИЗ ФАЙЛА
    // =================================================================

    internal fun buildFromFile() { with(state) {
        simulation.load(BodyFile.load(bodyPath, copies = copies, freeParticles = freeParticles))

        if (!quiet) println("[DemoSession] " + body.describe())
        if (!quiet) println("[DemoSession] rigid bones = ${rigidBones.size}, degenerate clusters dropped = $degenerateBones" +
            ", largest = ${rigidBones.maxOfOrNull { it.size } ?: 0} cells (drag on it is that many times weaker)")
        if (!quiet) {
            var mc = 0
            for (i in 0 until n) if (muscleOf[i] >= 0) mc++
            var cm = 0
            for (c in 0 until conCount) if (conMuscle[c] >= 0) cm++
            var tm = 0
            for (t in 0 until triCount) if (triMuscle[t] >= 0) tm++
            println("[DemoSession] muscle cells = $mc (grown from ${body.muscleClusters.sumOf { it.size }}, edge grab $muscleTouch)" +
                ", contracting links = $cm, contracting triangles = $tm")
        }
    } }

    internal fun reset() { with(state) {
        simulation.reset()
        gaitFrame = 0
        hoveredMuscle = -1
        onReset?.invoke()
        simTime = 0.0
        startCx = comX()
        startCy = comY()
        applyLabDamage()
    } }

    /** Срабатывания потолка на момент захвата — строка DRAG показывает разницу. */
    internal var dragCapBase = 0

    /** Какую свободную частицу заряжать следующей. См. fireBullet. */
    internal var nextBullet = 0

    /**
     * Запустить свободную частицу справа налево на потолке скорости.
     *
     * Берутся по кругу уже существующие свободные частицы: заводить новые значило бы
     * менять n на ходу, а от n зависят все массивы решателя и разбиение на организмы.
     */
    /**
     * ТАРАН [O]: два самых крупных организма разгоняются НАВСТРЕЧУ друг другу.
     *
     * Пуля проверяет мембрану на одиночной лёгкой клетке, и этого мало: там масса
     * ничтожная, ткань даже не напрягается. Здесь на контур наваливается вся масса
     * второго тела, и это уже настоящее условие — самое тяжёлое столкновение,
     * какое в симуляции вообще возможно при действующем потолке скорости.
     *
     * Импульс задаётся КАЖДОЙ частице организма, а не одной: тело должно лететь
     * целиком, как брошенное, а не растягиваться за разогнанным краем.
     */
    // ================================================================
    //  СЦЕНЫ СТЕНДА ПРЯМО В ДЕМО: N выбрать, M запустить.
    //
    //  Зачем. Стенд считает числа без окна, но собирает топологию САМ: рассыпал тело,
    //  разложил клетки. В живой симуляции та же куча получается ИНАЧЕ — из разрушения,
    //  с пересборкой контура, смежности и списка исключений. Если стенд спокоен, а в
    //  демо та же сцена дребезжит, значит ломается не решатель контактов, а пересборка
    //  после разрушения. Ровно так уже находилась ошибка, из-за которой бывшие соседи
    //  переставали сталкиваться.
    //
    //  Расстановка берётся из BenchScenes, ОДНА на стенд и на демо: две копии молча
    //  разъехались бы, и картинка перестала бы отвечать за числа.
    // ================================================================
    internal var sceneIndex = 0

    internal val scenes = BenchScenes.all()

    /**
     * Один шаг модели: команды мышцам, их активации, потом физика.
     *
     * Вынесено из render() потому, что при ускорении повторять надо ВСЮ тройку, а не
     * только simulate(). Иначе gaitFrame и активации остались бы на частоте кадров,
     * гребок относительно физики замедлился бы в десять раз, и наблюдал бы ты совсем
     * другой режим, чем тот, который мерил стенд.
     */
    /** Разбор: сколько наносекунд занял последний тик целиком. */
    internal var dbgTickNs = 0L

    internal fun stepOnce() { with(state) {
        val tickT0 = System.nanoTime()
        // Цели мышц при воспроизведении берутся из журнала, а не из клавиатуры и
        // наведения: окна там нет, а записано ровно то, что получилось в игре.
        if (replay != null) replayTickInputs() else { sampleInput?.invoke(); updateMuscleTargets(); recordTickInputs() }
        recordMuscles()
        updateMuscles(DT)
        recordDrag()
        // Номер тика нужен разбору пробоя по одной клетке (CT_WATCH), иначе строки
        // трассы нельзя привязать к событию из журнала игрока.
        contacts?.tickNo = playTick
        simulate()
        simTime += DT
        logTick++
        playTick++
        if (playTick % PlayerLog.HASH_EVERY == 0) tickHash()
        dbgTickNs = System.nanoTime() - tickT0
    } }

    // ================================================================
    //  ЖУРНАЛ ИГРОКА — пишется ВСЕГДА, формат см. PlayerLog
    //
    //  Всё, что меняет физику, проходит через act(). Он же пишет строку в журнал, и
    //  он же исполняет строку при воспроизведении. Двух реализаций одного действия
    //  нет, поэтому и расходиться им не в чем: старая запись по L держала свою копию
    //  захвата и однажды забыла про вес shape matching.
    //
    //  Что в журнал НЕ попадает и почему это не нужно:
    //    - положение мыши без захвата. Физика читает курсор только у схваченной
    //      клетки (applyDragVelocity), а наведение на мышцу пишется результатом —
    //      строкой M;
    //    - пауза, скорость, ускорение, интерполяция, камера. Они меняют число тиков в
    //      секунду и картинку, но не сами тики.
    //
    //  Файл перезаписывается при каждом открытии окна. Без окна (стенды, разбор)
    //  create() не зовётся, и журнал не пишется вовсе.
    // ================================================================
    internal var plog: java.io.BufferedWriter? = null

    internal var plogMuscles = DoubleArray(0)

    internal var plogCurX = Double.NaN

    internal var plogCurY = Double.NaN

    /** Помеченные средней кнопкой клетки. Только для глаз и журнала. */
    internal val marks = LinkedHashSet<Int>()

    // --- воспроизведение без окна, см. PlayerReplay ---
    internal var replay: List<PlayerLog.Line>? = null

    internal var replayAt = 0

    internal var replayMuscle = DoubleArray(0)

    internal var replayHashOk = 0
        internal set

    internal var replayHashBad = 0
        internal set

    internal var replayFirstBadTick = -1
        internal set

    internal val currentTick: Int get() = with(state) { playTick }

    /**
     * ВОСПРОИЗВЕДЕНИЕ В ОКНЕ: тот же журнал, но видно, как всё происходит.
     *
     * ЗАЧЕМ ОТДЕЛЬНО ОТ replayRun. Тот крутит цикл до конца и ничего не рисует — он для
     * разбора числами. Окну нужен ШАГ: кадр отрисовки отдаёт столько тиков, сколько
     * набежало реального времени, и между ними картинка успевает обновиться. Физика при
     * этом та же до бита: последовательность stepOnce и порядок событий не меняются, а
     * скорость показа задаётся числом тиков за кадр, как и в обычном окне.
     *
     * Возвращает false, когда журнал кончился.
     */
    /** Журнал, который надо начать воспроизводить сразу после сборки тела. См. main. */
    internal var pendingReplay: List<PlayerLog.Line>? = null

    /** Во сколько раз быстрее показывать воспроизведение. См. main. */
    internal var pendingReplaySpeed: Double? = null

    internal val replayActive: Boolean get() = with(state) { replay != null }

    internal val replayTotal: Int get() = with(state) { replay?.lastOrNull()?.tick ?: 0 }

    // ================================================================
    //  ЗАПИСЬ ДЕЙСТВИЙ МЫШЬЮ [L] И ИХ ВОСПРОИЗВЕДЕНИЕ
    //
    //  Зачем. Часть неустойчивости видна только руками: пользователь тащит
    //  конкретный кластер, отпускает, держит — и тело начинает дёргаться.
    //  Описать это словами достаточно точно, чтобы воспроизвести в тесте, не
    //  выходит: результат зависит от того, ЗА КАКУЮ клетку взялись и по какой
    //  траектории вели. Лог снимает этот разрыв — записанное воспроизводится
    //  побитово, потому что симуляция детерминирована.
    //
    //  Запись НАЧИНАЕТСЯ СО СБРОСА, и это обязательно: без известного начального
    //  состояния воспроизвести нечего — тело к моменту нажатия уже прожило
    //  произвольную историю.
    //
    //  Формат построчный и разреженный: строка пишется только когда что-то
    //  изменилось, поэтому «держу мышь на месте десять секунд» это одна строка, а
    //  не триста. При воспроизведении последняя команда держится до следующей.
    //
    //      D <тик> <клетка> <x> <y>   тянуть эту клетку к точке
    //      U <тик>                     отпустить
    //      E <тик>                     конец записи
    // ================================================================
    internal var dragLog: StringBuilder? = null

    internal var logTick = 0

    internal var logLastId = -2

    internal var logLastX = 0.0

    internal var logLastY = 0.0

    /**
     * ЗАПИСЬ СОСТОЯНИЯ МЫШЦ. Без неё лог был неполон, и это уже подвело.
     *
     * Воспроизведение гоняло с выключенными мышцами, потому что в логе о них не
     * было ни слова. Записанное перетаскивание при этом сходилось к курсору
     * идеально, а руками тело улетало и не возвращалось — расхождение объяснялось
     * ровно тем, что при записи работал гребок, и тело уплывало СВОИМ ХОДОМ.
     *
     * Пишется строка вида «M тик 0:1.0,3:0.5» — только ненулевые цели и только
     * когда набор изменился. Молчащие мышцы не дают ни одной строки.
     */
    internal var logMuscles = ""

    /**
     * Кто сейчас командует мышцами. Источников три и они складываются по ИЛИ:
     * наведение мышью, удержание цифровой клавиши и автоматический гребок.
     */
    internal fun updateMuscleTargets() { with(state) {
        muscleTarget.fill(0.0)

        // Проверка верхней границы не лишняя: тело может не иметь мышц вовсе
        // (у текущей выгрузки muscle = 0), и тогда массив пуст.
        if (hoveredMuscle in muscleTarget.indices) muscleTarget[hoveredMuscle] = 1.0

        // Цифры 1..9 — держать соответствующую мышцу сокращённой.
        val keys = minOf(9, muscleTarget.size)
        for (m in 0 until keys) {
            if (pressedMuscleMask and (1 shl m) != 0) muscleTarget[m] = 1.0
        }
        // 0 — все сразу.
        if (pressedMuscleMask and (1 shl 9) != 0) muscleTarget.fill(1.0)

        // Автоматический гребок. Доля рабочей фазы — GAIT_DUTY, минимум один кадр:
        // при коротком периоде округление вниз может дать ноль, и гребка не будет вовсе.
        if (gait) {
            gaitFrame++
            val duty = (gaitPeriod * gaitDuty).toInt().coerceAtLeast(1)
            if (gaitFrame % gaitPeriod < duty) muscleTarget.fill(1.0)
        }
    } }

    /** Сам прогрев. Отдельно от потока, чтобы его можно было позвать из замера. */
    internal fun warmUpBlocking(ticks: Int) { with(state) {
        val t0 = System.nanoTime()
        try {
            val w = DemoSession(bodyPath, copies, freeParticles, labKillFraction, labKillSeed, physicsConfig)
            w.state.quiet = true
            w.replayBoot()
            // Через воспроизведение журнала, а не через обычный тик: тот опрашивает
            // клавиатуру, а прогрев идёт в чужом потоке и до появления окна.
            val script = listOf(
                PlayerLog.Line(1, "O", emptyList(), "1 O"),
                PlayerLog.Line(ticks, "END", emptyList(), "$ticks END"),
            )
            w.replayRun(script, onEvent = {}, onTick = {})
        } catch (e: Throwable) {
            println("[DemoSession] прогрев не удался: $e")
            return
        }
        if (!quiet) println("[DemoSession] прогрев разрушения: %d тиков за %.0f мс".format(
            ticks, (System.nanoTime() - t0) / 1e6))
    } }

    internal fun simulate() { simulation.step(); if (state.trapCrash > 0) trapCrashScan() }

}
