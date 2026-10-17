package io.github.some_example_name.physics.lab.window

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.FitViewport
import io.github.some_example_name.physics.BEND_LEVELS
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.GROUND_Y
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.MEDIUM_DRAG
import io.github.some_example_name.physics.NORMAL_DRAG
import io.github.some_example_name.physics.SOFT_COMPLIANCE
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.VISCOSITY
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.FAST_BUDGET_MS
import io.github.some_example_name.physics.lab.session.act
import io.github.some_example_name.physics.bendCompliance
import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.triTorn
import io.github.some_example_name.render.demo.*
import kotlin.math.abs
import kotlin.math.sqrt

internal fun DemoWindow.drawBody() { with(session) { with(state) {
        renderer.beginFrame(BG, camera.combined)

        shapes.begin(filled = true)
        // Заливка идёт ПО ТРЕУГОЛЬНИКАМ: у гексагональной топологии нет ячеек, из которых
        // можно было бы собрать контур, а треугольники ограничения площади её и покрывают.
        for (t in 0 until triCount) {
            val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
            val m = triMuscle[t]
            val act = if (m >= 0) muscleActivation[m] else 0.0
            // ТРЕУГОЛЬНИК НА КРАЮ МЫШЦЫ КРАСИТСЯ ЧАСТИЧНО — по доле мышечных вершин.
            //
            // Площадь сокращает только тот, у которого все три вершины в одном кластере и
            // ни одной кости, см. muscleOfTri. Остальные, кто мышцу задевает, — это либо
            // следующее кольцо за выросшим кластером (см. MUSCLE_TOUCH), либо треугольник
            // с костью в углу: его клетки мышечные и связи сокращаются, а площадь нет.
            // Без этой заливки край мышцы выглядел бы обычной тканью, хотя в редакторе
            // там мышца, — из-за чего и пришёл вопрос, почему мышца у кости не мышца.
            val mv = if (m >= 0) -1 else {
                var k = 0; var any = -1
                if (muscleOf[i0] >= 0) { k++; any = muscleOf[i0] }
                if (muscleOf[i1] >= 0) { k++; any = muscleOf[i1] }
                if (muscleOf[i2] >= 0) { k++; any = muscleOf[i2] }
                if (k == 0) -1 else any
            }
            shapes.color = when {
                triInverted[t] -> INVERTED_FILL
                triTorn(t) -> TEAR_FILL
                boneOf[i0] != -1 && boneOf[i0] == boneOf[i1] && boneOf[i0] == boneOf[i2] ->
                    if (bonesRigid) BONE_FILL else BONE_FILL_OFF
                m >= 0 -> if (act > 0.002) tmpColor.set(MUSCLE_IDLE).lerp(MUSCLE_FILL, act.toFloat()) else MUSCLE_IDLE
                mv >= 0 -> {
                    val a2 = muscleActivation[mv]
                    var share = 0
                    if (muscleOf[i0] == mv) share++
                    if (muscleOf[i1] == mv) share++
                    if (muscleOf[i2] == mv) share++
                    tmpColor2.set(MUSCLE_IDLE).lerp(MUSCLE_FILL, a2.toFloat())
                    tmpColor.set(SOFT_FILL).lerp(tmpColor2, share / 3f)
                }
                else -> SOFT_FILL
            }
            shapes.triangle(fx(i0), fy(i0), fx(i1), fy(i1), fx(i2), fy(i2))
        }
        // Кости заливаются ещё и по своим связям: у мелких костей треугольников может
        // не быть вовсе, и без этого они на картинке пропадут.
        if (viewMode == 1) {
            for (i in 0 until n) {
                if (boneOf[i] == -1) continue
                shapes.color = if (bonesRigid) BONE_FILL else BONE_FILL_OFF
                shapes.circle(fx(i), fy(i), body.radius[i] * 0.9f, 10)
            }
        }
        shapes.color = ACTIVE
        if (hoverId >= 0) shapes.circle(fx(hoverId), fy(hoverId), body.meanLinkLength * 0.5f, 14)
        shapes.color = DOT
        // Точки рисуются СВОИМ радиусом, а не общим: иначе разница масс между клетками
        // не видна глазами, а она теперь влияет на физику.
        for (i in 0 until n) if (!cellDead[i]) shapes.circle(fx(i), fy(i), body.radius[i] * 0.22f, 6)

        // РЕЖИМ 2: НАСТОЯЩИЕ РАДИУСЫ КЛЕТОК.
        //
        // В обычном режиме клетка рисуется точкой в 22% своего радиуса — это метка
        // положения, а не размер. Из-за этого перекрытия соседей не видно вовсе,
        // хотя в физике оно есть: при радиусе 0.5 и шаге решётки 0.6 соседи
        // перекрываются на 40%.
        //
        // Здесь же рисуются ТРИ радиуса сразу, и разница между ними существенна:
        //
        //   белый    радиус клетки из файла. Из него берётся МАССА (пропорционально
        //            площади) и он же задаёт размер, который рисует редактор.
        //   голубой  радиус КОНТАКТА, он же мембрана. Берётся из длины граничных
        //            рёбер, а не из радиуса клетки, потому что сшивание контура
        //            зависит от расстояния между соседями, а не от того, какой
        //            размер нарисовал игрок.
        //
        // Голубой ЗАМЕТНО МЕНЬШЕ белого, и это осознанно: контакт срабатывает
        // раньше, чем сходятся нарисованные круги. Иначе несвязанные граничные
        // клетки попадали бы в вечный ложный контакт — запас между «сшить контур»
        // и «не задеть лишнего» на этом теле всего 1.6x.
        if (viewMode == 2) {
            val ctR = contacts
            shapes.color = DOT
            for (i in 0 until n) if (!cellDead[i]) shapes.circle(fx(i), fy(i), body.radius[i], 14)
            if (ctR != null) {
                shapes.color = CONTACT_COLOR
                for (i in 0 until n) {
                    val r = ctR.contactRadiusOf(i)
                    if (r > 0.0) shapes.circle(fx(i), fy(i), r.toFloat(), 14)
                }
            }
        }
        shapes.end()

        shapes.begin(filled = true)
        shapes.color = GROUND_COLOR
        shapes.rectLine(-1f, GROUND_Y.toFloat(), 5f, GROUND_Y.toFloat(), 0.006f)
        shapes.color = LINK
        for (c in 0 until conCount) {
            val i = conA[c]; val j = conB[c]
            shapes.rectLine(fx(i), fy(i), fx(j), fy(j), 0.0015f)
        }

        // КОНТАКТНЫЕ ЧАСТИЦЫ. Рисуются кружком СВОЕГО радиуса, умноженного на
        // CONTACT_SCALE, — то есть ровно тем кругом, которым клетка и сталкивается.
        // Так видно настоящую геометрию контакта, а не приблизительную метку: если
        // круги налезли друг на друга, значит контакт обязан был сработать.
        // Кости сюда попадают наравне с мягкой тканью: граница у них общая.
        val ctDraw = contacts
        if (contactsOn && ctDraw != null) {
            shapes.color = CONTACT_COLOR
            for (e in 0 until boundCount) {
                for (v in intArrayOf(boundA[e], boundB[e])) {
                    shapes.circle(fx(v), fy(v), ctDraw.contactRadiusOf(v).toFloat(), 12)
                }
            }
            // Свободные частицы рисуем отдельно: они не лежат ни на одном граничном
            // ребре, и цикл выше их просто не видит.
            for (i in 0 until n) {
                if (!isFree[i] || cellDead[i]) continue
                shapes.circle(fx(i), fy(i), ctDraw.contactRadiusOf(i).toFloat(), 12)
            }
        }

        // Граница: то, что реально омывается средой и создаёт тягу.
        shapes.color = BOUNDARY_COLOR
        for (e in 0 until boundCount) {
            val i = boundA[e]; val j = boundB[e]
            shapes.rectLine(fx(i), fy(i), fx(j), fy(j), 0.005f)
        }

        // Пары изгиба — только в отладочном режиме и только когда стадия включена:
        // иначе они закрывают контур сплошной сеткой и мешают смотреть на само тело.
        if (viewMode == 1 && bendLevel > 0) {
            shapes.color = BEND_COLOR
            for (c in 0 until bendCount) {
                val i = bendA[c]; val j = bendB[c]
                shapes.rectLine(fx(i), fy(i), fx(j), fy(j), 0.002f)
            }
        }
        shapes.end()

        // Метки средней кнопкой — поверх всего, двойным кольцом, чтобы не спутать с
        // подсветкой под курсором.
        if (marks.isNotEmpty()) {
            shapes.begin(filled = false)
            shapes.color = MARK_COLOR
            val r = body.meanLinkLength * 0.6f
            for (i in marks) {
                shapes.circle(fx(i), fy(i), r, 20)
                shapes.circle(fx(i), fy(i), r * 1.25f, 20)
            }
            shapes.end()
        }

} } }

internal fun DemoWindow.drawHud() { with(session) { with(state) {
    hud.begin(hudCamera.combined)
    var y = Gdx.graphics.height - 14f
    val line = 18f

    hud.color = HUD_TEXT
    hud.text("REAL BODY FROM GENOME EDITOR  -  XPBD + shape matching", 16f, y); y -= line * 1.4f

    hud.color = HUD_MUTED
    // conCount это МЯГКИЕ связи: внутрикостных в нём намеренно нет. У тела
    // целиком из кости здесь ноль, и это не ошибка выгрузки.
    hud.text("cells = $n   soft links = $conCount   area triangles = ${triCount}" +
        "   (%.2f per cell)".format(triCount.toFloat() / n), 16f, y); y -= line
    hud.text("substeps = $SUBSTEPS   soft compliance = $SOFT_COMPLIANCE   viscosity = $VISCOSITY /s", 16f, y); y -= line
    hud.text("medium drag = $MEDIUM_DRAG /s (isotropic, no thrust)   normal drag = $NORMAL_DRAG on $boundCount boundary edges", 16f, y); y -= line

    hud.color = if (degenerateBones > 0 || boneCapHits > 0) HUD_WARN else HUD_MUTED
    hud.text("bones: ${rigidBones.size} rigid clusters" +
        "   ${degenerateBones} single-cell IGNORED   step cap hits = $boneCapHits", 16f, y); y -= line

    hud.color = HUD_MUTED
    hud.text("muscles: ${body.muscleClusters.size} clusters   hovered = $hoveredMuscle", 16f, y); y -= line

    hud.color = if (invertedPeak > 0) INVERTED_FILL else HUD_MUTED
    hud.text("INVERTED triangles: now = $invertedNow   peak = $invertedPeak", 16f, y); y -= line
    // ПРОНИКНОВЕНИЕ ВИДНО СРАЗУ, А НЕ ПОСЛЕ РАЗБОРА. Обход на проникновение всё равно
    // идёт каждый тик — он нужен возврату обломков, — так что строка почти бесплатна.
    // Падения в окне намеренно нет (см. trapCrash): оно убило бы сеанс в момент
    // наблюдения и унесло журнал, а журнал потом воспроизводится побитово.
    if (replayActive || replayHashOk + replayHashBad > 0) {
        hud.color = if (replayHashBad > 0) Color.valueOf("FF6B6BFF") else HUD_MUTED
        hud.text("REPLAY: tick $playTick / $replayTotal   checksums ok = $replayHashOk" +
            (if (replayHashBad > 0) ", MISMATCH from tick $replayFirstBadTick" else ", none failed") +
            (if (replayActive) "" else "   (log finished)"), 16f, y); y -= line
        hud.color = HUD_TEXT
    }
    hud.text("inside foreign tissue: now = $dbgTrapNow (folds $dbgTrapFold)" +
        "   membrane pierced = $piercedCount" +
        (if (trapCrash > 0) "   CRASH ON" else ""), 16f, y); y -= line

    // --- наблюдение за дрейфом ---
    //
    // Дрейф показывается В КЛЕТКАХ (в средних длинах связи), а не в мировых единицах:
    // «уехал на 0.04» само по себе ни о чём не говорит, а «уехал на одну клетку»
    // сразу понятно. Импульс рядом нужен, чтобы отличить две разные вещи: тело ещё
    // едет по инерции (|P| большой, дрейф растёт) или уже стоит, а уехало раньше
    // (|P| около нуля при выросшем дрейфе). Это ровно то различие, на котором в
    // PhysicsAudit держится вся проверка честности.
    val ddx = comX() - startCx
    val ddy = comY() - startCy
    val drift = sqrt(ddx * ddx + ddy * ddy)
    var pxSum = 0.0; var pySum = 0.0
    for (i in 0 until n) { pxSum += vx[i]; pySum += vy[i] }
    val momentum = sqrt(pxSum * pxSum + pySum * pySum)
    hud.color = HUD_TEXT
    hud.text("sim time = %.1f s   drift = %.4f (%.2f cells)   |P| = %.3f"
        .format(simTime, drift, drift / body.meanLinkLength, momentum), 16f, y); y -= line

    // ДИАГНОСТИКА ТЯГИ ПРЯМО В КАДРЕ.
    //
    // Вынесено сюда после двух записей, которые воспроизводились как исправные,
    // хотя руками симптом был. Догадки кончились: пусть те же самые величины
    // видно в тот момент, когда всё ломается, а не только в разборе лога.
    //
    //   cell->cur   отставание схваченной клетки от курсора, в связях. Тяга
    //               исправна, когда оно идёт к нулю.
    //   body->cur   расстояние от ЦЕНТРА МАСС организма до курсора. Оно НЕ
    //               обязано быть нулём: схваченная клетка может стоять на
    //               курсоре, а центр тела законно висеть в стороне. Важен
    //               РОСТ этого числа, а не само значение.
    //   cap         срабатывания потолка скорости. Он правит частицы по одной
    //               и импульс не сохраняет — любое ненулевое число здесь
    //               означает движение из ниоткуда.
    if (dragId >= 0) {
        val gx = px[dragId] - mouseX
        val gy = py[dragId] - mouseY
        val cellGap = sqrt(gx * gx + gy * gy) / body.meanLinkLength
        val o = organismOf[dragId]
        var mm = 0.0; var cxx = 0.0; var cyy = 0.0
        for (q in 0 until n) {
            if (organismOf[q] != o || invMass[q] <= 0.0) continue
            val w = 1.0 / invMass[q]
            mm += w; cxx += w * px[q]; cyy += w * py[q]
        }
        val bodyGap = if (mm > 0.0) {
            val bx = cxx / mm - mouseX; val by = cyy / mm - mouseY
            sqrt(bx * bx + by * by) / body.meanLinkLength
        } else 0.0
        // И пик, и потолок берутся ОТ МОМЕНТА ЗАХВАТА, а не от сброса.
        val capHere = speedCapHits - dragCapBase
        val vPeak = sqrt(peakSpeed2) * DT / body.meanLinkLength
        hud.color = if (capHere > 0) INVERTED_FILL else HUD_TEXT
        hud.text(("DRAG  cell->cur = %.2f   body->cur = %.2f   peak = %.2f of %.1f   cap = %d")
            .format(cellGap, bodyGap, vPeak, MAX_SPEED_CELLS_PER_TICK, capHere), 16f, y)
        hud.color = HUD_TEXT
        y -= line
    }

    // Изгиб контура: показывается всегда, потому что он МЕНЯЕТ ФИЗИКУ и по картинке
    // это не всегда очевидно — тело просто становится упрямее.
    // Контакты: число сработавших, обрезок CCD и обоих потолков. Потолки в норме
    // должны быть нулями — ненулевое значит удар или что-то пошло не так.
    hud.color = if (contactsOn) HUD_TEXT else HUD_MUTED
    val ct = contacts
    hud.text(if (contactsOn)
        "CONTACTS [K] on   now = %d   ccd clamps = %d   link cap = %d   speed cap = %d   torn = %d   TEAR[X] %s, killed = %d"
            .format(ct?.lastContacts ?: 0, ct?.lastToiClamps ?: 0, linkCapHits, speedCapHits,
                linkTorn.count { it },
                if (tearingOn) "on" else "OFF", tornTotal)
    else "K -- self-contact (OFF)", 16f, y); y -= line

    hud.color = if (bendLevel > 0) HUD_WARN else HUD_MUTED
    hud.text(if (bendLevel > 0)
        "CONTOUR BEND [E] = %.0e on %d pairs  -- thrust cost, see BEND_LEVELS"
            .format(bendCompliance(), bendCount)
    else "E -- contour bend (OFF, %d pairs available)".format(bendCount), 16f, y); y -= line

    hud.color = if (controls.timeScale != 1.0) HUD_WARN else HUD_MUTED
    hud.text("TIME SCALE [T] = x%.2f   INTERP [I] = %s".format(controls.timeScale,
        when (interpMode) {
            0 -> "off (raw physics state)"
            1 -> "tick bounds (2 snapshots/tick)"
            else -> "by substeps (%d snapshots/tick)".format(SUBSTEPS + 1)
        }), 16f, y); y -= line

    hud.color = if (fastForward) HUD_WARN else HUD_MUTED
    hud.text(if (fastForward)
        "FAST FORWARD [F] -- x%.0f  (%.0f steps/frame, budget %d ms)"
            .format(stepsAvg, stepsAvg, FAST_BUDGET_MS)
    else "F -- fast forward (real time now)", 16f, y); y -= line * 1.4f

    hud.color = HUD_MUTED
    hud.text("LMB drag   HOVER a muscle edge   1..9 hold a muscle   0 hold ALL   " +
        "G auto-gait" + if (gait) " [ON, period $gaitPeriod]" else "", 16f, y); y -= line
    hud.color = HUD_TEXT
    hud.text("СЦЕНА [N выбрать, M запустить]: %d/%d  %s".format(
        sceneIndex + 1, scenes.size, scenes[sceneIndex].name), 16f, y); y -= line
    hud.text("SPACE pause   F fast   E bend   R reset   B bones   C view (норма/кости/радиусы)   P bullet   O slam   L rec   X tear   RMB pan   MMB mark   wheel zoom",
        16f, y); y -= line
    hud.color = if (marks.isNotEmpty()) MARK_COLOR else HUD_MUTED
    hud.text("LOG %s  tick %d   MARKS [MMB] = %s".format(
        if (plog != null) PlayerLog.FILE else "OFF", playTick,
        if (marks.isEmpty()) "none" else marks.joinToString(" ") { "#$it" }), 16f, y); y -= line
    if (paused) { hud.color = HUD_WARN; hud.text("PAUSED", 16f, y) }
    hud.end()

    // Диалог рисуется ПОСЛЕДНИМ, поверх всего: он и логически сверху, и свои
    // begin/end у него собственные — рисовать его внутри чужой пары нельзя.
    renderer.drawControls(controls, hudCamera.combined, Gdx.graphics.width)
} } }
