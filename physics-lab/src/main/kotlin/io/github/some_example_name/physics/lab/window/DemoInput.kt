package io.github.some_example_name.physics.lab.window

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.FitViewport
import io.github.some_example_name.physics.BEND_LEVELS
import io.github.some_example_name.physics.lab.scenes.LabControl
import io.github.some_example_name.physics.lab.session.SLOWMO_SCALE
import io.github.some_example_name.physics.lab.session.act
import io.github.some_example_name.physics.lab.session.toggleDragLog
import io.github.some_example_name.physics.lab.session.toggleMark
import kotlin.math.abs
import kotlin.math.sqrt

// =================================================================
//  ВВОД
// =================================================================

internal fun DemoWindow.handleInput() { with(session) { with(state) {
    tmp.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
    viewport.unproject(tmp)
    mouseX = tmp.x.toDouble(); mouseY = tmp.y.toDouble()

    // Диалог забирает мышь ПЕРВЫМ. Иначе клик по ползунку, который висит поверх
    // тела, одновременно хватал бы вершину под ним.
    val uiTookMouse = controls.handleInput(Gdx.graphics.width, Gdx.graphics.height)
    if (uiTookMouse) {
        if (dragId >= 0) act('U')
        wasTouched = true
    }

    // Панорама правой кнопкой. ЛЕВАЯ остаётся за перетаскиванием вершины, СРЕДНЯЯ —
    // за пометкой клеток, поэтому ниже проверяется именно кнопка, а не isTouched:
    // тот истинен для любой кнопки и панорама воровала бы захват.
    if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
        // Сколько мира приходится на пиксель — зависит от зума, поэтому считается
        // каждый кадр, иначе на приближении панорама была бы рывками.
        val unitsPerPixel = viewport.worldWidth * camera.zoom / viewport.screenWidth
        camera.position.x -= Gdx.input.deltaX * unitsPerPixel
        camera.position.y += Gdx.input.deltaY * unitsPerPixel   // экранный Y смотрит вниз
        camera.update()
    }

    val touched = Gdx.input.isButtonPressed(Input.Buttons.LEFT) && !uiTookMouse
    if (touched && !wasTouched) {
        val i = nearestParticle()
        if (i >= 0) act('D', i)
    }
    if (!touched && wasTouched && dragId >= 0) act('U')
    wasTouched = touched

    // ПОМЕТКА СРЕДНЕЙ КНОПКОЙ: клетка, которую игрок считает проблемной. Повторный
    // клик снимает. На физику не влияет, только ложится в журнал с номером тика.
    val middle = Gdx.input.isButtonPressed(Input.Buttons.MIDDLE) && !uiTookMouse
    if (middle && !wasMiddle) {
        val i = nearestParticle()
        if (i >= 0) toggleMark(i, fx(i), fy(i), paused, controls.timeScale, renderAlpha, interpMode)
    }
    wasMiddle = middle

    hoverId = if (dragId >= 0) dragId else nearestParticle()
    hoveredMuscle = muscleUnderMouse()

    if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) paused = !paused
    if (Gdx.input.isKeyJustPressed(Input.Keys.R)) act('R')
    if (Gdx.input.isKeyJustPressed(Input.Keys.B)) act('B', if (bonesRigid) 0 else 1)
    if (Gdx.input.isKeyJustPressed(Input.Keys.C)) viewMode = (viewMode + 1) % 3
    if (Gdx.input.isKeyJustPressed(Input.Keys.G)) act('G', if (gait) 0 else 1)
    if (Gdx.input.isKeyJustPressed(Input.Keys.F)) fastForward = !fastForward
    if (Gdx.input.isKeyJustPressed(Input.Keys.E)) act('E', (bendLevel + 1) % BEND_LEVELS.size)
    if (Gdx.input.isKeyJustPressed(Input.Keys.T)) controls.toggle()
    if (Gdx.input.isKeyJustPressed(Input.Keys.Y)) controls.reset()
    if (Gdx.input.isKeyJustPressed(Input.Keys.I)) interpMode = (interpMode + 1) % 3
    if (Gdx.input.isKeyJustPressed(Input.Keys.K)) act('K', if (contactsOn) 0 else 1)
    if (Gdx.input.isKeyJustPressed(Input.Keys.P)) act('P')
    if (Gdx.input.isKeyJustPressed(Input.Keys.O)) act('O')
    // СВЕРХЗАМЕДЛЕНИЕ НА КЛАВИШЕ, А НЕ НА ПОЛЗУНКЕ.
    //
    // Ползунок квадратичный (value = 10 * t^2), и 0.001 попадает на 1% дорожки —
    // мышью такое не поймать. А нужно оно именно тогда, когда разглядываешь, как
    // клетка входит в ткань, и ставишь на неё метку: на реальной скорости вход
    // занимает один-два подшага из шестнадцати.
    //
    // Клавиша переключает: первое нажатие ставит SLOWMO_SCALE, второе возвращает
    // прежнюю скорость, а не единицу — чтобы не терять то, что было выставлено.
    if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT_BRACKET)) {
        if (controls.timeScale > SLOWMO_SCALE * 1.5) {
            slowmoWas = controls.timeScale
            slowmoInterpWas = interpMode
            controls.timeScale = SLOWMO_SCALE
            // ИСТОРИЯ ПОДШАГОВ ВКЛЮЧАЕТСЯ ВМЕСТЕ С ЗАМЕДЛЕНИЕМ. Иначе на тик
            // приходятся сотни кадров, а показывать между ними нечего: картинка
            // стоит, потом прыгает на целый тик. Со историей видно каждый из
            // шестнадцати подшагов — а вход клетки в ткань занимает один-два.
            interpMode = 2
        } else {
            controls.timeScale = if (slowmoWas > 0.0) slowmoWas else 1.0
            interpMode = slowmoInterpWas
        }
    }
    if (Gdx.input.isKeyJustPressed(Input.Keys.L)) {
        // Старая запись начинается со сброса — сброс идёт через журнал игрока.
        if (dragLog == null) act('R')
        toggleDragLog()
    }
    if (Gdx.input.isKeyJustPressed(Input.Keys.X)) act('X', if (tearingOn) 0 else 1)
    // J просит у стенда мягкой решётки новый сид разрушения. В обычном демо
    // окно просто закроется — стенда, который откроет следующее, там нет.
    if (Gdx.input.isKeyJustPressed(Input.Keys.J)) {
        LabControl.restartRequested = true
        Gdx.app.exit()
    }
    if (Gdx.input.isKeyJustPressed(Input.Keys.N)) act('N', (sceneIndex + 1) % scenes.size)
    if (Gdx.input.isKeyJustPressed(Input.Keys.M)) act('S')
    // ESC сначала закрывает диалог и только потом выходит: иначе из него не выйти
    // иначе как повторным T, а рефлекс у всех один.
    if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
        if (controls.isOpen) controls.close() else Gdx.app.exit()
    }
} } }

/**
 * Ближайшая вершина к курсору — по НАРИСОВАННЫМ позициям, а не по сырым px/py.
 *
 * Пока интерполяции не было, разницы не существовало. С ней сырая позиция — это
 * конец последнего тика, а на экране тело стоит где-то МЕЖДУ состояниями. На
 * обычной скорости расхождение меньше кадра и незаметно, а на замедлении
 * renderAlpha подолгу держится далеко от единицы, и подсветка уезжает от клетки,
 * в которую целишься. Для отладки это критично: наводишься на одну клетку, а
 * подсвечивается соседняя.
 *
 * Выбор идёт по тому, что пользователь ВИДЕЛ, — это верно и по существу.
 * Стоимость нулевая: fx() это пара умножений, а проход и так был O(n).
 *
 * ФИЗИКУ ЭТО НЕ ТРОГАЕТ. solveDrag по-прежнему тянет px/py к курсору в физическом
 * пространстве; интерполяция живёт только в отрисовке и в выборе.
 */
internal fun DemoWindow.nearestParticle(): Int { with(session) { with(state) {
    var best = -1
    var bestD2 = (pickRadius * pickRadius).toDouble()
    for (i in 0 until n) {
        // Мёртвую не схватить и не пометить: на экране её нет.
        if (cellDead[i]) continue
        val dx = fx(i) - mouseX; val dy = fy(i) - mouseY
        val d2 = dx * dx + dy * dy
        if (d2 < bestD2) { bestD2 = d2; best = i }
    }
    return best
} } }

internal fun DemoWindow.muscleUnderMouse(): Int { with(session) { with(state) {
    var best = -1
    var bestD2 = (edgePickRadius * edgePickRadius).toDouble()
    for (c in 0 until conCount) {
        val m = conMuscle[c]
        if (m < 0) continue
        // Тоже по нарисованным позициям — см. nearestParticle().
        val i = conA[c]; val j = conB[c]
        val xi = fx(i); val yi = fy(i)
        val ex = fx(j) - xi; val ey = fy(j) - yi
        val len2 = ex * ex + ey * ey
        var t = if (len2 < 1e-18) 0.0 else ((mouseX - xi) * ex + (mouseY - yi) * ey) / len2
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val dx = mouseX - (xi + ex * t); val dy = mouseY - (yi + ey * t)
        val d2 = dx * dx + dy * dy
        if (d2 < bestD2) { bestD2 = d2; best = m }
    }
    return best
} } }

/**
 * ВВОД, РАЗРЕШЁННЫЙ ПРИ ВОСПРОИЗВЕДЕНИИ: только то, что меняет ПОКАЗ, но не физику.
 *
 * Пауза, скорость и режим интерполяции не вмешиваются в решатель: timeScale меняет
 * число тиков в секунду, а не их длину, а интерполяция вообще только рисует. Значит
 * воспроизведение остаётся побитово тем же, а смотреть его становится возможно: без
 * этого запись на 247 тиков пролетает за восемь секунд, а вход клетки в ткань
 * занимает один-два подшага.
 *
 * Всё, что подмешало бы в журнал событие (мышцы, таран, захват, сброс), здесь
 * намеренно не читается.
 */
internal fun DemoWindow.handleViewInput() { with(session) { with(state) {
    if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) paused = !paused
    if (Gdx.input.isKeyJustPressed(Input.Keys.T)) controls.toggle()
    if (Gdx.input.isKeyJustPressed(Input.Keys.I)) interpMode = (interpMode + 1) % 3
    if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT_BRACKET)) {
        if (controls.timeScale > SLOWMO_SCALE * 1.5) {
            slowmoWas = controls.timeScale
            slowmoInterpWas = interpMode
            controls.timeScale = SLOWMO_SCALE
            interpMode = 2
        } else {
            controls.timeScale = if (slowmoWas > 0.0) slowmoWas else 1.0
            interpMode = slowmoInterpWas
        }
    }
    if (Gdx.input.isKeyJustPressed(Input.Keys.Y)) controls.reset()
    controls.handleInput(Gdx.graphics.width, Gdx.graphics.height)
} } }

internal fun DemoWindow.sampleMuscleKeys() {
    var mask = 0
    for (m in 0 until minOf(9, state.muscleTarget.size)) {
        if (Gdx.input.isKeyPressed(Input.Keys.NUM_1 + m)) mask = mask or (1 shl m)
    }
    if (Gdx.input.isKeyPressed(Input.Keys.NUM_0)) mask = mask or (1 shl 9)
    session.pressedMuscleMask = mask
}
