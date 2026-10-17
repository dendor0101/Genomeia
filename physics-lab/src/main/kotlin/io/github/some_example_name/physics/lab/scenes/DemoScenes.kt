package io.github.some_example_name.physics.lab.scenes

import io.github.some_example_name.physics.CONTACT_SCALE
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.lab.session.BULLET_SPEED_FRACTION
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.killEdgeNow
import io.github.some_example_name.physics.rebuildAfterTear
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * РАЗРУШЕНИЕ ДЛЯ СТЕНДА: убить долю связей случайно, но ТЕМ ЖЕ ПУТЁМ, что в игре.
 *
 * Зачем не проще. Сначала я убивал связи прямо в файле тела, и это оказалось
 * неверно по существу: движок тогда считает, что такие пары НИКОГДА не были
 * связаны. А исключение из контактов даётся ровно таким парам — перекрытым в позе
 * покоя и никогда не связанным, — иначе соседи на 0.6 связи при контактном
 * диаметре 1.0 были бы в вечном контакте и тело распирало бы само себя. В итоге
 * ни одна пара по краям дыр не сталкивалась вовсе, что сразу и было замечено
 * глазами.
 *
 * Здесь связи умирают ПОСЛЕ загрузки целого тела и после того, как список
 * «когда-либо связанных» уже снят с целого графа. Тогда такие пары от исключения
 * защищены и сталкиваются, ровно как после настоящего разрыва.
 *
 * Зовётся из reset, поэтому клавиша сброса не возвращает целую решётку молча.
 */
internal fun DemoSession.applyLabDamage() { with(state) {
    if (labKillFraction <= 0.0 || conCount == 0) return
    val rnd = java.util.Random(labKillSeed)
    val before = conCount
    val target = (conCount * labKillFraction).toInt()
    var killed = 0
    var guard = 0
    while (killed < target && guard < conCount * 64) {
        guard++
        val c = rnd.nextInt(conCount)
        if (conDead[c]) continue
        conDead[c] = true; killEdgeNow(conEdge[c]); killed++
    }
    tearsPending = true
    rebuildAfterTear()
    if (!quiet) println("[SoftGridLab] убито связей $killed из $before, осталось $conCount, тел $organismCount, сид $labKillSeed")
} }

internal fun DemoSession.sceneCtx() = with(state) { SceneCtx(
    n = n, px = px, py = py, prevX = prevX, prevY = prevY, vx = vx, vy = vy,
    organismOfCell = { i -> organismOf[i] },
    organismSizeOf = { o -> organismSize[o] },
    organismCount = { organismCount },
    isFreeOf = { i -> isFree[i] },
    invMass = invMass,
    contactRadiusOf = { i -> contacts?.contactRadiusOf(i) ?: (radius[i] * CONTACT_SCALE) },
    meanLink = body.meanLinkLength.toDouble(),
    dt = DT,
    vmax = MAX_SPEED_CELLS_PER_TICK,
    shatter = { o ->
        for (c in 0 until conCount) if (organismOf[conA[c]] == o) { conDead[c] = true; killEdgeNow(conEdge[c]) }
        tearsPending = true
        simulate()
    },
    step = { stepOnce() },
    stepContract = { gait = true; stepOnce() },
    inBoneOf = { i -> boneOf[i] >= 0 },
    focus = { x, y ->
        onCamera?.invoke(x, y)
    },
) }

/** Поставить выбранную сцену. Сброс обязателен: сцены стартуют от целых тел. */
internal fun DemoSession.runScene() { with(state) {
    reset()
    tearingOn = true
    scenes[sceneIndex].setup(sceneCtx(), 0.0, 0.0)
    // Гребок гасится ПОСЛЕ расстановки. Сцена с оторванным куском включает его
    // сама на своё окно, и без этой строки он оставался бы включённым дальше —
    // тогда глазами видно одно, а стенд считает другое.
    gait = false; gaitFrame = 0
} }

internal fun DemoSession.slamOrganisms() { with(state) {
    // ЗАХВАТ ОТПУСКАЕТСЯ. Держать клетку и одновременно бить тараном —
    // противоречивая пара действий: таран НАЗНАЧАЕТ скорость всем частицам, а
    // рука тут же начинает с ней бороться. Замер: пик 9.32 при потолке 8.0 и
    // 136 срабатываний clampSpeed, хотя сам таран на кирпиче проходит чисто.
    if (dragId >= 0) { matchWeight[dragId] = 1.0; dragId = -1 }
    // Два самых крупных организма — это тела; свободные частицы отсеиваются
    // сами, они размером в одну клетку.
    val order = (0 until organismCount).sortedByDescending { organismSize[it] }
    if (order.size < 2) return
    val a = order[0]; val b = order[1]
    if (organismSize[b] < 2) return

    var ax = 0.0; var ay = 0.0; var an = 0
    var bx = 0.0; var by = 0.0; var bn = 0
    for (i in 0 until n) {
        when (organismOf[i]) {
            a -> { ax += px[i]; ay += py[i]; an++ }
            b -> { bx += px[i]; by += py[i]; bn++ }
        }
    }
    if (an == 0 || bn == 0) return
    ax /= an; ay /= an; bx /= bn; by /= bn

    var dx = bx - ax; var dy = by - ay
    val d = sqrt(dx * dx + dy * dy)
    if (d < 1e-12) return
    dx /= d; dy /= d

    val speed = BULLET_SPEED_FRACTION * MAX_SPEED_CELLS_PER_TICK * body.meanLinkLength / DT
    for (i in 0 until n) {
        when (organismOf[i]) {
            a -> { vx[i] = dx * speed; vy[i] = dy * speed }
            b -> { vx[i] = -dx * speed; vy[i] = -dy * speed }
        }
    }
    if (!quiet) println("[DemoSession] таран: организмы $a и $b, по " +
        "%.1f клеток/тик навстречу".format(speed * DT / body.meanLinkLength))
} }

internal fun DemoSession.fireBullet() { with(state) {
    val ids = (0 until n).filter { isFree[it] }
    if (ids.isEmpty()) return
    val i = ids[nextBullet % ids.size]
    nextBullet++

    // Справа от всего, что есть на сцене, на высоте середины тел.
    var maxX = -Double.MAX_VALUE
    var sumY = 0.0; var cnt = 0
    for (k in 0 until n) {
        if (isFree[k]) continue
        if (px[k] > maxX) maxX = px[k]
        sumY += py[k]; cnt++
    }
    if (cnt == 0) return
    val speed = BULLET_SPEED_FRACTION * MAX_SPEED_CELLS_PER_TICK * body.meanLinkLength / DT
    px[i] = maxX + body.meanLinkLength * 8.0
    py[i] = sumY / cnt
    prevX[i] = px[i]; prevY[i] = py[i]
    vx[i] = -speed; vy[i] = 0.0
    println("[DemoSession] пуля: клетка #$i, скорость " +
        "%.1f клеток/тик".format(speed * DT / body.meanLinkLength))
} }
