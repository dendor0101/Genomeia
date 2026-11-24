package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.DT
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * СЕМЕЙСТВО «ГЕНОМЫ: РОСТ НА ХОДУ». Автор 20.11: «добавь стенды где у генома есть постоянная скорость и он растет в
 * движении, верчении, в момент столкновений разных объектов». Тело генома растёт из зиготы по стадиям своего генома,
 * стадия за четыре тика, а привод ([drive]) всё время роста держит его ход — скорость центра масс и (или) вращение
 * вокруг него, как у организма, который плывёт, пока растёт. По пути — стена рельефа, лежащее тело, россыпь частиц или
 * такое же растущее тело навстречу.
 *
 * Главное здесь сверх общих правил — «клетки рождаются на своём месте» (Rule.BIRTH, BirthLog): на ходу клетка
 * рождается в движущемся родителе и встаёт по позе сдвинутого, повёрнутого и примятого ударом тела.
 *
 * Тела — те же, что растут у стены (gen-grow/…/wall-p4): от 8 до 1500 клеток и рост не дольше GROWTH_TICKS_MAX;
 * встреча двух растущих тел — у тел от 20 до 400 клеток, как gen-grow/…/twins-p4. Вариаций нет: у тела генома свои
 * длины связей и радиусы.
 */

/** Ход привода, связей за тик: вдвое быстрее «лёгкого касания» TAP и вдвое медленнее «толчка» BUMP — оба удара ничего рвать не вправе. */
private const val DRIVE_SPEED = 0.3

/** Скорость края взрослого тела во вращении, связей за тик; угловая — не быстрее DRIVE_SPIN_MAX радиана за тик (у малых тел). */
private const val DRIVE_RIM = 0.3
private const val DRIVE_SPIN_MAX = 0.05

/**
 * Тяга привода: за тик он меняет скорость тела не больше чем на столько связей за тик (вращение — на столько же на
 * радиусе инерции). Это тяга, а не жёсткая привязка к ходу: упёршись в стену, тело давит на неё с этой силой, а не
 * получает каждый тик удар на полном ходу. Среда на ходу DRIVE_SPEED тормозит тело примерно на 0.003 связи за тик за тик
 * (гасит скорость в e раз примерно за сто тиков, CatalogKit.settle) — тяга перекрывает её в несколько раз.
 */
private const val DRIVE_ACCEL = 0.02

/** Стадия генома за столько тиков — как у роста у стены и роста при работающих мышцах. */
private const val STAGE_PERIOD = 4

/** Дольше этого рост в сцену не ставится (как CatalogGenomes.GROWTH_TICKS_MAX). */
private const val GROWTH_TICKS_MAX = 5000

/** Движение тела целиком: центр масс живых клеток, его скорость, угловая скорость вокруг него и радиус инерции — в единицах мира физики. */
private class Motion(val mass: Double, val cx: Double, val cy: Double, val ux: Double, val uy: Double, val omega: Double, val gyration: Double)

private fun StandWorld.motionOf(b: StandBody): Motion? {
    val st = world.state
    val first = b.first
    var m = 0.0; var cx = 0.0; var cy = 0.0; var px = 0.0; var py = 0.0
    for (k in 0 until b.count) {
        val p = first + k
        if (st.cellDead[p] || st.cellUnborn[p] || st.restInvMass[p] == 0.0f) continue
        val w = 1.0 / st.restInvMass[p]
        m += w; cx += w * st.px[p]; cy += w * st.py[p]; px += w * st.vx[p]; py += w * st.vy[p]
    }
    if (m == 0.0) return null
    cx /= m; cy /= m
    val ux = px / m; val uy = py / m
    var lz = 0.0; var inertia = 0.0
    for (k in 0 until b.count) {
        val p = first + k
        if (st.cellDead[p] || st.cellUnborn[p] || st.restInvMass[p] == 0.0f) continue
        val w = 1.0 / st.restInvMass[p]
        val rx = st.px[p] - cx; val ry = st.py[p] - cy
        lz += w * (rx * (st.vy[p] - uy) - ry * (st.vx[p] - ux))
        inertia += w * (rx * rx + ry * ry)
    }
    return Motion(m, cx, cy, ux, uy, if (inertia > 0.0) lz / inertia else 0.0, sqrt(inertia / m))
}

/**
 * ПРИВОД: шаг скорости центра масс живых клеток тела [b] к ([vx], [vy]) связей за тик и угловой скорости вокруг него к
 * [spin] радиан за тик (против часовой стрелки), не больше DRIVE_ACCEL за тик. Прибавка — движение тела целиком (сдвиг
 * и поворот): деформацию ткани, удары и среду она не трогает. Внесённая энергия идёт в запас сцены. Оси и знак
 * вращения — сцены (рамка вариации), как у [StandWorld.kick].
 */
internal fun StandWorld.drive(b: StandBody, vx: Double, vy: Double, spin: Double) {
    val mo = motionOf(b) ?: return
    val tx = linksPerTick(if (frame.still) vx else frame.dx(vx, vy))
    val ty = linksPerTick(if (frame.still) vy else frame.dy(vx, vy))
    val amax = linksPerTick(DRIVE_ACCEL)
    var dvx = tx - mo.ux; var dvy = ty - mo.uy
    val dv = sqrt(dvx * dvx + dvy * dvy)
    if (dv > amax) { dvx *= amax / dv; dvy *= amax / dv }
    val wmax = if (mo.gyration > 0.0) amax / mo.gyration else 0.0
    val dw = (frame.spin * spin / DT - mo.omega).coerceIn(-wmax, wmax)
    if (dvx == 0.0 && dvy == 0.0 && dw == 0.0) return
    val st = world.state
    val first = b.first
    var before = 0.0; var after = 0.0
    for (k in 0 until b.count) {
        val p = first + k
        if (st.cellDead[p] || st.cellUnborn[p] || st.restInvMass[p] == 0.0f) continue
        val w = 1.0 / st.restInvMass[p]
        val ox = st.vx[p]; val oy = st.vy[p]
        val nx = ox + dvx - dw * (st.py[p] - mo.cy)
        val ny = oy + dvy + dw * (st.px[p] - mo.cx)
        before += 0.5 * w * (ox * ox + oy * oy); after += 0.5 * w * (nx * nx + ny * ny)
        world.setVelocity(p, nx.toFloat(), ny.toFloat())
    }
    world.wake(first)
    addEnergy(after - before)
}

/** Тело обязано провернуться хотя бы на [radians]: сумма модуля его угловой скорости по тикам. */
internal fun StandWorld.mustSpin(b: StandBody, radians: Double, text: String) {
    var turned = 0.0
    must(text) { turned += abs(motionOf(b)?.omega ?: 0.0) * DT; turned >= radians }
}

/** Угловая скорость, при которой край взрослого тела [s] идёт со скоростью DRIVE_RIM связей за тик (радиан за тик). */
private fun spinRate(s: ShapeSpec): Double {
    var mx = 0.0; var my = 0.0
    for (k in 0 until s.count) { mx += s.x[k]; my += s.y[k] }
    mx /= s.count; my /= s.count
    var r = 0.0
    for (k in 0 until s.count) r = maxOf(r, Math.hypot(s.x[k] - mx, s.y[k] - my))
    return if (r <= 0.0) DRIVE_SPIN_MAX else minOf(DRIVE_SPIN_MAX, DRIVE_RIM * GAME_LINK / r)
}

internal fun genomeMotionFamily(): List<Scenario> {
    val family = Family("34. Геномы: рост на ходу")
    val wall = terrainLayouts().first { it.id == "wall" }
    val one = Variation.ONLY_BASE
    for (e in GenomeBodies.entries) {
        if (e.cells !in 8 until 1500 || e.stages * STAGE_PERIOD > GROWTH_TICKS_MAX) continue
        val id = "gen-move/${e.slug}"
        val name = "«${e.title}» (${e.cells} кл.)"
        val about = "Тело генома «${e.title}» (${e.cells} клеток) растёт из зиготы по стадиям своего генома (${e.stages} стадий), стадия за четыре тика."
        val grow = e.stages * STAGE_PERIOD
        val born = "Клетки рождаются у своего тела и без дальних связей. Ни разрывов, ни гибели клеток; тело вырастает целиком."
        // Путь привода за весь рост, в единицах игры: на середине роста тело проходит половину.
        val path = DRIVE_SPEED * GAME_LINK * grow
        fun grower(w: StandWorld, s: ShapeSpec, x: Double = 0.0, angle: Double = 0.0) =
            w.growing(s, x, 0.0, angle, seed = s.genome!!.zygote, period = STAGE_PERIOD, batch = 0)

        family.add("$id/drift-p4", "$name: рост на ходу",
            "$about Всё время роста привод держит ход вправо, $DRIVE_SPEED связи за тик; после роста отпускает.",
            "$born После роста тело тормозится средой, возвращает форму и засыпает.", grow + settle(DRIVE_SPEED), GROW, one) {
            val g = grower(this, GenomeBodies.shape(e.slug))
            kick(g, DRIVE_SPEED, 0.0)
            during(0, grow) { drive(g, DRIVE_SPEED, 0.0, 0.0) }
            mustMove(g, 0.5 * DRIVE_SPEED * grow, "растущее тело прошло хотя бы половину пути привода")
        }

        family.add("$id/spin-p4", "$name: рост во вращении",
            "$about Всё время роста привод вращает тело против часовой стрелки: край взрослого тела шёл бы со скоростью $DRIVE_RIM связи за тик; после роста отпускает.",
            "$born После роста вращение гаснет, тело возвращает форму и засыпает.", grow + settle(DRIVE_RIM), GROW, one) {
            val s = GenomeBodies.shape(e.slug)
            val g = grower(this, s)
            val w = spinRate(s)
            kick(g, 0.0, 0.0, w)
            during(0, grow) { drive(g, 0.0, 0.0, w) }
            mustSpin(g, minOf(Math.PI / 2, 0.5 * w * grow), "растущее тело провернулось")
        }

        family.add("$id/drift-spin-p4", "$name: рост на ходу и во вращении",
            "$about Всё время роста привод держит ход вправо, $DRIVE_SPEED связи за тик, и вращает тело против часовой стрелки (край взрослого — $DRIVE_RIM связи за тик); после роста отпускает.",
            "$born После роста тело тормозится средой, возвращает форму и засыпает.", grow + settle(DRIVE_SPEED), GROW, one) {
            val s = GenomeBodies.shape(e.slug)
            val g = grower(this, s)
            val w = spinRate(s)
            kick(g, DRIVE_SPEED, 0.0, w)
            during(0, grow) { drive(g, DRIVE_SPEED, 0.0, w) }
            mustMove(g, 0.5 * DRIVE_SPEED * grow, "растущее тело прошло хотя бы половину пути привода")
            mustSpin(g, minOf(Math.PI / 2, 0.5 * w * grow), "растущее тело провернулось")
        }

        family.add("$id/wall-p4", "$name: рост на ходу в стену",
            "$about Привод держит ход вправо, $DRIVE_SPEED связи за тик, и всё время роста упирает тело в стену рельефа, до которой оно доходит к середине роста.",
            "Новорождённая ткань не входит в рельеф и не рождается в нём. $born После роста привод отпускает, всё замирает и засыпает.",
            grow + settle(DRIVE_SPEED), GROW_TIGHT, one) {
            val s = GenomeBodies.shape(e.slug)
            place(wall, maxOf(0.9, 0.5 * path + 0.4 * minOf(s.halfWidth, s.halfHeight)))
            val g = grower(this, s)
            kick(g, DRIVE_SPEED, 0.0)
            during(0, grow) { drive(g, DRIVE_SPEED, 0.0, 0.0) }
            mustTouchTerrain(g, "растущее тело дошло до стены")
        }

        family.add("$id/block-p4", "$name: рост на ходу, толкая лежащее тело",
            "$about Привод держит ход вправо, $DRIVE_SPEED связи за тик; к середине роста тело упирается в лежащий квадрат 8×8 и толкает его до конца роста.",
            "Клетки растущего тела не входят в чужую ткань, чужие — в его. $born После роста привод отпускает, оба тела тормозятся средой и засыпают.",
            grow + settle(DRIVE_SPEED), GROW, one) {
            val s = GenomeBodies.shape(e.slug)
            val block = Shapes.square(8)
            val face = maxOf(0.9, 0.5 * path + 0.4 * minOf(s.halfWidth, s.halfHeight))
            val k = body(block, face + block.halfWidth, 0.0)
            val g = grower(this, s)
            kick(g, DRIVE_SPEED, 0.0)
            during(0, grow) { drive(g, DRIVE_SPEED, 0.0, 0.0) }
            mustTouch(g, k, "растущее тело дошло до лежащего")
        }

        family.add("$id/balls-p4", "$name: рост на ходу сквозь россыпь частиц",
            "$about Привод держит ход вправо, $DRIVE_SPEED связи за тик; на пути — россыпь свободных частиц с шагом 1.2 единицы, тело идёт сквозь неё, пока растёт.",
            "Частицы не входят в ткань и не проходят сквозь неё. $born После роста привод отпускает, всё тормозится средой и засыпает.",
            grow + settle(DRIVE_SPEED, slow = true), GROW, one) {
            val s = GenomeBodies.shape(e.slug)
            val g = grower(this, s)
            val from = maxOf(1.5, 0.3 * path)
            val length = minOf(12.0, maxOf(3.0, 0.4 * path))
            val half = minOf(6.0, maxOf(1.5, 0.6 * maxOf(s.halfWidth, s.halfHeight)))
            val step = 1.2
            var x = from
            while (x <= from + length) {
                var y = -half
                while (y <= half) { ball(x, y); y += step }
                x += step
            }
            kick(g, DRIVE_SPEED, 0.0)
            during(0, grow) { drive(g, DRIVE_SPEED, 0.0, 0.0) }
            mustTouchBalls(g, "растущее тело встретило частицы")
        }

        if (e.cells in 20 until 400) {
            family.add("$id/twins-p4", "$name: два растущих тела идут навстречу",
                "$about Две зиготы одного генома, второе тело развёрнуто на пол-оборота; привод ведёт их навстречу, по $DRIVE_SPEED связи за тик, и они встречаются к середине роста, а дальше давят друг на друга до конца роста.",
                "Тела упираются друг в друга: ни одна клетка не оказывается в чужой ткани. $born После роста привод отпускает, всё замирает и засыпает.",
                grow + settle(DRIVE_SPEED), GROW_TIGHT, one) {
                val s = GenomeBodies.shape(e.slug)
                val apart = maxOf(2.4, path)
                val a = grower(this, s, -apart / 2)
                val b = grower(this, s, apart / 2, Math.PI)
                kick(a, DRIVE_SPEED, 0.0); kick(b, -DRIVE_SPEED, 0.0)
                during(0, grow) { drive(a, DRIVE_SPEED, 0.0, 0.0); drive(b, -DRIVE_SPEED, 0.0, 0.0) }
                mustTouch(a, b, "растущие тела встретились на ходу")
            }
        }
    }
    return family.list
}
