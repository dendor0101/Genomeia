package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * УПОР В НЕПОДВИЖНОЕ — ЖЁСТКИЙ (BoundaryContacts.cFixed, 17.11.2026).
 *
 * Касание клетки с неподвижной частицей (рельеф, стенка, закреплённая клетка) решается своим
 * законом: перекрытие снимается почти целиком за проход, масса клетки делится между её упорами,
 * проходов несколько, а снятое перекрытие в скорость не превращается («рельеф не выстреливает»).
 * Клетка с дорастающей связью упирается по-старому. Числа «было» в сообщениях — тот же тест на
 * прежнем мягком касании (замер 17.11.2026).
 */
class FixedContactAnalyticTest {
    /** Расстояние касания двух свободных частиц радиуса T_RADIUS. */
    private val touch = 2 * T_RADIUS * CONTACT_SCALE

    @Test fun `частица внахлёст с неподвижной выходит на расстояние касания и стоит`() {
        // Было: частицу выстреливало — 0.48, 1.2 и 1.92 связи за тик при перекрытии 10, 25 и 40%.
        for (part in floatArrayOf(0.9f, 0.75f, 0.6f)) {
            val w = gameWorld().vacuum()
            val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, T_RADIUS))
            val a = w.freeParticle(w.addFree(1.0f + part * touch, 1.0f, T_RADIUS))
            repeat(2) { w.step() }
            assertEquals("перекрытие ${1 - part}: расстояние к расстоянию касания", 1.0f, w.distance(a, wall) / touch, 1e-6f)
            assertEquals("перекрытие ${1 - part}: скорость, связей за тик", 0.0f, w.vx[a] / linksPerTick(1.0f), 1e-6f)
            assertEquals("частица цела", 0, w.state.deadCount)
        }
    }

    @Test fun `частицу, зажатую между двумя неподвижными, упоры выталкивают вместе, а не каждый за двоих`() {
        // Две неподвижные частицы одна над другой, частица перекрыта с обеими поровну. Каждый упор один
        // вытолкнул бы её целиком; вместе, без деления массы, — дальше нужного.
        //
        // Замер 17.11.2026, расстояния до частиц к расстоянию касания и скорость после тика: без гашения
        // выстрела 2.73 и 2.73, 2.15 связи за тик; с гашением по упорам по очереди (первая версия) 1.014 и
        // 1.169, 0.24 связи за тик вбок — сцена зеркальна, а итог нет; то же без деления массы — 1.079 и
        // 1.455. С гашением всеми упорами разом (fixedRestitution) частица встаёт на расстояние касания
        // от обеих и остаётся на оси.
        val w = gameWorld().vacuum()
        val gap = 0.8f * touch
        val up = w.freeParticle(w.addFixed(1.0f, 1.0f + gap / 2, T_RADIUS))
        val down = w.freeParticle(w.addFixed(1.0f, 1.0f - gap / 2, T_RADIUS))
        // Точка касания обеих — на расстоянии sqrt(touch² − (gap/2)²) от их линии; частица стоит на 60% этого пути.
        val out = kotlin.math.sqrt(touch * touch - gap * gap / 4)
        val a = w.freeParticle(w.addFree(1.0f + 0.6f * out, 1.0f, T_RADIUS))
        w.step()
        val du = w.distance(a, up) / touch; val dd = w.distance(a, down) / touch
        val speed = kotlin.math.hypot(w.vx[a], w.vy[a]) / linksPerTick(1.0f)
        println("зажатая частица: до верхней $du, до нижней $dd расстояния касания, скорость $speed связи за тик, увод с оси ${(w.y[a] - 1.0f) / T_LINK}")
        assertTrue("частица вышла из обеих: до верхней $du, до нижней $dd расстояния касания", du >= 0.999f && dd >= 0.999f)
        assertTrue("и не улетела: до верхней $du, до нижней $dd расстояния касания", du <= 1.01f && dd <= 1.01f)
        assertTrue("скорость после выталкивания, связей за тик: $speed", speed <= 0.01f)
        assertEquals("сцена зеркальна — частица остаётся на оси, связей", 0.0f, (w.y[a] - 1.0f) / T_LINK, 1e-9f)
    }

    /** Стенка: столбик неподвижных частиц с шагом в полсвязи — круги касания перекрыты, щелей нет. */
    private fun wall(w: PhysicsWorld, x: Float, y0: Float, y1: Float) {
        var y = y0
        while (y < y1) { w.addFixed(x, y, T_RADIUS); y += 0.5f * T_LINK }
    }

    @Test fun `тело, налетевшее на стенку на 2 связи за тик, остаётся целым и отскакивает`() {
        // Клин (18.11–24.11) убран вместе с гашением: остался сам закон упора, прежний замер и прежний порог.
        wallHit(slowestBack = 0.5f)
    }

    private fun wallHit(slowestBack: Float) {
        // Мягкий лоскут 8×7 в пустоте, до стенки три связи. Было: погибло 29 клеток из 56 — мягкое
        // касание не успевало остановить передний ряд, центры клеток заходили в радиус частиц стенки.
        val w = gameWorld().vacuum().also { it.sleepOn = false }
        val cols = 8; val rows = 7
        val def = patch(cols, rows)
        val first = w.bodyFirst(w.addBody(def))
        val wallX = 1.0f + (cols + 3) * T_LINK
        wall(w, wallX, 1.0f - 4 * T_LINK, 1.0f + (rows + 4) * T_LINK)
        for (i in first until first + def.count) w.setVelocity(i, linksPerTick(2.0f), 0.0f)
        val st = w.state
        var deepest = -Float.MAX_VALUE; var who = -1; var whenTick = -1; var surface = false
        repeat(40) { t ->
            w.step()
            for (i in first until first + def.count) if (!st.cellDead[i] && st.px[i] > deepest) { deepest = st.px[i]; who = i - first; whenTick = t; surface = w.isSurfaceCell(i) }
        }
        val label = "удар о стенку"
        assertEquals("$label: погибло клеток", 0, st.deadCount)
        var vx = 0.0f
        for (i in first until first + def.count) vx += st.vx[i]
        val back = vx / def.count / linksPerTick(1.0f)
        // Замер 17.11.2026 (без клина): −1.09 связи за тик; 18.11.2026 с клином: −0.46.
        assertTrue("$label: тело отскочило от стенки, связей за тик: $back", back < -slowestBack && back > -2.0f)
        // За линию центров частиц стенки не заходит ни одна клетка. Замер: ближе всего, на 0.36 связи, к
        // ней подходит на тике удара ВНУТРЕННЯЯ клетка (столбец 5, ряд 3) — её выдавливает вперёд смятый
        // передний ряд, а внутренние клетки в касаниях не участвуют; клетки контура стенка держит.
        assertTrue("за линию стенки не прошла ни одна клетка: клетка $who на тике $whenTick (на контуре: $surface), " +
            "до линии ${(wallX - deepest) / T_LINK} связи", wallX - deepest > 0.0f)
    }

    /** Упор ли контакт клетки [a] с неподвижной [f] в последнем подшаге; null — контакта нет. */
    private fun fixedKind(w: PhysicsWorld, a: Int, f: Int): Boolean? {
        val ct = w.state.contacts!!
        for (c in 0 until ct.contactCount) {
            val i = ct.contactI(c); val j = ct.contactJ(c)
            if ((i == a && j == f) || (i == f && j == a)) return ct.contactIsFixed(c)
        }
        return null
    }

    @Test fun `клетка с дорастающей связью упирается в неподвижную по-старому, выросшая - жёстко`() {
        // Цепочка O–A–B вдоль x, B ещё не рождена; над A неподвижная частица. Рождение B делением A делает
        // A «растущей» на BIRTH_GROW_TICKS тиков (у неё дорастает связь с B; связь с O взрослая, поэтому
        // мягкого старта касаний новорождённой у A нет). Цепочку каждый тик подталкивают вверх, к частице.
        val w = gameWorld().vacuum().also { it.sleepOn = false }
        w.addFixed(0.5f, 0.5f, T_RADIUS)                       // мир не пуст: растущее тело дописывается
        val o = w.bodyFirst(w.addBody(chain(3), born = booleanArrayOf(true, true, false)))
        val a = o + 1
        w.step()
        val st = w.state
        val f = w.freeParticle(w.addFixed(w.x[a], w.y[a] + 1.3f * T_LINK, T_RADIUS))
        w.activateFromParent(a + 1, a)
        var softWhileGrowing = 0; var fixedWhileGrowing = 0; var fixedWhenGrown = 0; var softWhenGrown = 0
        repeat(3 * BIRTH_GROW_TICKS) {
            for (i in o..a + 1) w.setVelocity(i, 0.0f, linksPerTick(0.2f))
            w.step()
            val growing = st.growStamp[a] == st.growStampNow
            when (fixedKind(w, a, f)) {
                true -> if (growing) fixedWhileGrowing++ else fixedWhenGrown++
                false -> if (growing) softWhileGrowing++ else softWhenGrown++
                null -> {}
            }
        }
        assertEquals("клетка цела", 0, st.deadCount)
        assertTrue("пока связь дорастала, касание с неподвижной было: $softWhileGrowing", softWhileGrowing > 0)
        assertEquals("пока связь дорастает, упор обычный, не жёсткий", 0, fixedWhileGrowing)
        assertTrue("когда связь доросла, касание с неподвижной было: $fixedWhenGrown", fixedWhenGrown > 0)
        assertEquals("у выросшей клетки упор жёсткий", 0, softWhenGrown)
    }
}
