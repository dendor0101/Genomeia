package io.github.some_example_name.physics.lab.window

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.FitViewport
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.countInverted
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.EDGE_PICK_FACTOR
import io.github.some_example_name.physics.lab.session.FAST_BUDGET_MS
import io.github.some_example_name.physics.lab.session.FAST_MAX_STEPS
import io.github.some_example_name.physics.lab.session.MAX_CATCHUP
import io.github.some_example_name.physics.lab.session.ORGANISMS
import io.github.some_example_name.physics.lab.session.PICK_FACTOR
import io.github.some_example_name.physics.lab.session.VIEW_HEIGHT
import io.github.some_example_name.physics.lab.session.VIEW_WIDTH
import io.github.some_example_name.physics.lab.session.WARMUP_ON_START
import io.github.some_example_name.physics.lab.session.WARMUP_TICKS
import io.github.some_example_name.physics.lab.session.closePlayerLog
import io.github.some_example_name.physics.lab.session.openPlayerLog
import io.github.some_example_name.physics.lab.session.replayLoad
import io.github.some_example_name.physics.lab.session.replayStepOne
import io.github.some_example_name.render.demo.*
import kotlin.math.abs
import kotlin.math.sqrt

internal class DemoWindow(val session: DemoSession) {
    val state get() = session.state
    internal val interpolation = DemoInterpolation(state)
    internal var interpMode: Int
        get() = interpolation.mode
        set(value) { interpolation.mode = value }
    internal fun prepareLerp() = interpolation.prepare(renderAlpha)
    internal fun fx(i: Int) = interpolation.x(i)
    internal fun fy(i: Int) = interpolation.y(i)
    internal var paused = false

    internal var viewMode = 0

    // --- ускорение симуляции и наблюдение за дрейфом ---
    internal var fastForward = false

    /** Сколько шагов физики уложилось в кадр. Сглаженное — мгновенное слишком дёргается. */
    internal var stepsAvg = 1f

    /**
     * СИМУЛИРОВАННОЕ время, а не настоящее. При ускорении они расходятся в разы, и
     * смотреть на дрейф надо именно по этому: «уехал на столько-то за 200 секунд
     * своей жизни» — величина, воспроизводимая между запусками и сравнимая с цифрами
     * из PhysicsAudit. Настоящее время наблюдения не значит ничего.
     */
    /** Долг нерасходованного реального времени. См. аккумулятор в render(). */
    internal var accumulator = 0.0

    /** Доля тика, уже покрытая реальным временем. 0 — начало тика, 1 — конец. */
    internal var renderAlpha = 0.0

    internal val controls = SimControls()

    internal lateinit var renderer: DemoRenderer
    internal val shapes get() = renderer.canvas
    internal val hud get() = renderer.text

    internal lateinit var viewport: FitViewport

    internal lateinit var camera: OrthographicCamera

    internal lateinit var hudCamera: OrthographicCamera

    internal val tmp = Vector3()

    internal val tmpColor = Color()

    internal val tmpColor2 = Color()

    internal var hoverId = -1

    internal var wasTouched = false

    internal var pickRadius = 0.05f

    internal var edgePickRadius = 0.03f

    /** Скорость, на которую вернуться из сверхзамедления. См. клавишу [. */
    internal var slowmoWas = 0.0

    /** Режим интерполяции до включения сверхзамедления. */
    internal var slowmoInterpWas = 1

    internal var wasMiddle = false

    // =================================================================
    //  ЖИЗНЕННЫЙ ЦИКЛ И ОТРИСОВКА
    // =================================================================

    internal fun create(logPath: String = PlayerLog.FILE, warmUp: Boolean = true) { with(session) { with(state) {
        renderer = DemoRenderer()
        simulation.observer = interpolation
        camera = OrthographicCamera()
        viewport = FitViewport(VIEW_WIDTH, VIEW_HEIGHT, camera)
        hudCamera = OrthographicCamera()

        // Зум колесом. Только он и требует InputProcessor: всё остальное опрашивается
        // напрямую, а прокрутка приходит событием и опросом её не поймать.
        Gdx.input.inputProcessor = object : com.badlogic.gdx.InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                // Мир под курсором запоминается ДО зума и возвращается ПОСЛЕ — так
                // приближение идёт к точке под мышью, а не к центру экрана.
                tmp.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
                viewport.unproject(tmp)
                val wx = tmp.x
                val wy = tmp.y

                camera.zoom *= if (amountY > 0) 1.15f else 1f / 1.15f
                camera.zoom = camera.zoom.coerceIn(0.05f, 8f)
                camera.update()

                tmp.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
                viewport.unproject(tmp)
                camera.position.x += wx - tmp.x
                camera.position.y += wy - tmp.y
                camera.update()
                return true
            }
        }

        onReset = {
            hoverId = -1
            interpolation.invalidate()
            accumulator = 0.0
            stepsAvg = 1f
            camera.zoom = ORGANISMS.toFloat()
            camera.position.set(viewCenterX(), 0.85f, 0f)
            camera.update()
        }
        onCamera = { x, y -> camera.position.set(x.toFloat(), y.toFloat(), 0f); camera.update() }
        sampleInput = { sampleMuscleKeys() }
        buildFromFile()
        pickRadius = body.meanLinkLength * PICK_FACTOR
        edgePickRadius = body.meanLinkLength * EDGE_PICK_FACTOR
        if (warmUp) startWarmUp()
        // ВОСПРОИЗВЕДЕНИЕ НАЧИНАЕТСЯ С ТОГО ЖЕ СОСТОЯНИЯ, что и запись: reset ставит позы
        // покоя, как replayBoot у разбора без окна. Без него тело стартовало бы из
        // прогретого состояния, и суммы разошлись бы с первой же проверки.
        pendingReplay?.let {
            reset(); replayLoad(it); pendingReplay = null
            pendingReplaySpeed?.let { v -> controls.timeScale = v }
        }
        reset()
        openPlayerLog(logPath)
    } } }

    internal fun resize(width: Int, height: Int) { with(session) { with(state) {
        viewport.update(width, height)
        // Позиция ставится ТОЛЬКО в первый раз: иначе изменение размера окна сбрасывало
        // бы панораму и зум, которые пользователь только что настроил.
        if (!cameraPlaced) {
            camera.position.set(viewCenterX(), 0.85f, 0f)
            cameraPlaced = true
        }
        camera.update()
        hudCamera.setToOrtho(false, width.toFloat(), height.toFloat())
    } } }

    internal var cameraPlaced = false

    /** Центр кадра: середина между копиями, иначе вторая уезжает за край. */
    internal fun viewCenterX(): Float { with(session) { with(state) {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            val v = body.x[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return (lo + hi) * 0.5f
    } } }

    internal fun render(frameSeconds: Double = Gdx.graphics.deltaTime.toDouble()) { with(session) { with(state) {
        // ПРИ ВОСПРОИЗВЕДЕНИИ ЖИВОЙ ВВОД НЕ ЧИТАЕТСЯ. Иначе случайное нажатие подмешает
        // в журнал событие, которого там не было, и воспроизведение перестанет быть
        // воспроизведением. Окно закрывается крестиком, как обычно.
        if (replayActive) handleViewInput() else handleInput()

        // Мышцы обновляются ВНУТРИ паузы-гварда вместе с физикой. Иначе на паузе
        // активации продолжали бы ехать к цели, длины покоя менялись бы без решателя,
        // и при снятии паузы тело получало бы разом накопленную ошибку — рывок.
        if (!paused) {
            var steps = 0
            if (!fastForward) {
                // ФИКСИРОВАННЫЙ ШАГ С АККУМУЛЯТОРОМ.
                //
                // Раньше здесь был ровно один шаг на кадр отрисовки, и DT работал как
                // «предполагаемая длительность кадра». Пока DT совпадал с частотой
                // экрана, это сходилось; после перехода на 60 UPS при отрисовке в 144
                // кадра симуляция пошла бы в 2.4 раза быстрее реального времени, и
                // счётчик simTime врал бы ровно во столько же.
                //
                // DT НЕ ТРОГАЕТСЯ НИКОГДА: детерминизм требует фиксированного шага, а
                // скорость проигрывания меняется числом тиков, а не их длиной.
                // timeScale умножает РЕАЛЬНОЕ время, а не DT. Значит меняется число
                // тиков в секунду, а не их длина: на 0.5x получаются побитово те же
                // состояния, просто показанные на большем числе кадров.
                accumulator += frameSeconds * controls.timeScale
                // Потолок на догон. Без него после подвисания (перетаскивание окна,
                // сборка мусора) накопленный долг отрабатывается разом, и тело получает
                // пачку шагов подряд — выглядит как взрыв.
                if (accumulator > MAX_CATCHUP) accumulator = MAX_CATCHUP
                while (accumulator >= DT) {
                    if (replayActive) { if (!replayStepOne()) { accumulator = 0.0; break } }
                    else stepOnce()
                    accumulator -= DT
                    steps++
                }
            } else {
                // Крутим шаги, пока не выйдет бюджет. Проверка ПОСЛЕ шага, а не до:
                // хотя бы один шаг за кадр обязан пройти, иначе при просроченном кадре
                // симуляция встанет совсем и это будет похоже на зависание.
                val deadline = System.nanoTime() + FAST_BUDGET_MS * 1_000_000L
                do {
                    if (replayActive) { if (!replayStepOne()) break } else stepOnce()
                    steps++
                } while (System.nanoTime() < deadline && steps < FAST_MAX_STEPS)
                // В ускорении долг не копим: реальное время тут ни при чём.
                accumulator = 0.0
            }
            // Доля тика, уже покрытая реальным временем: по ней fx()/fy() выбирают точку
            // в истории подшагов. В ускорении интерполировать нечего — там за кадр
            // проходит много тиков, показываем последнее состояние.
            renderAlpha = if (fastForward) 1.0 else (accumulator / DT).coerceIn(0.0, 1.0)

            // Сглаживание, иначе число в HUD прыгает и его невозможно прочесть.
            stepsAvg += (steps - stepsAvg) * 0.1f
        }
        prepareLerp()
        countInverted()

        drawBody()
        drawHud()
    } } }

    /**
     * ПРОГРЕВ КОДА РАЗРУШЕНИЯ ПРИ ЗАПУСКЕ.
     *
     * ЗАЧЕМ. Первое же столкновение в игре заметно подлагивает, и это не работа, а
     * компиляция: пути разрыва, пересборки и контактов до первого удара не исполнялись ни
     * разу и идут в интерпретаторе. Замер на журнале 12:17 (тик первого тарана): в холодном
     * процессе 78–107 мс, в прогретом — 22–28 мс при том же объёме работы.
     *
     * КАК. Отдельная копия тела в фоновом потоке получает таран и несколько тиков живёт с
     * разрушением. Общего состояния с игрой у неё нет вовсе — только скомпилированный код,
     * а он в JVM общий на процесс. Поэтому на результат симуляции прогрев не влияет:
     * контрольные суммы шести журналов игрока после него те же.
     *
     * Поток фоновый и с низким приоритетом: он не должен отбирать время у отрисовки.
     */
    internal fun startWarmUp() { with(session) { with(state) {
        if (!WARMUP_ON_START) return
        val t = Thread({ warmUpBlocking(WARMUP_TICKS) }, "warmup")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    } } }

    internal fun dispose() { with(session) { with(state) {
        closePlayerLog()
        renderer.dispose()
    } } }

}
