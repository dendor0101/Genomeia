package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.inspection.DemoInspector
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.scenes.slamOrganisms

/**
 * ВОСПРОИЗВЕДЕНИЕ СПОСОБА ИГРОКА: клавиша O на любом стенде.
 *
 * Игрок сообщил, что получает проникновение сразу, стоило столкнуть организмы через O.
 * Мои сценарии охоты били вдвое слабее: там каждое тело получало ПОЛОВИНУ скорости
 * сближения, а slamOrganisms назначает КАЖДОМУ полную скорость пули навстречу. Поэтому
 * мерить надо ровно его способом, а не похожим.
 */
object SlamTest {

    /** Один прогон: таран через slamOrganisms и подсчёт проникновения по тикам. */
    fun run(bodyPath: String, copies: Int, free: Int, ticks: Int): String {
        val P = Probe
        P.boot(bodyPath, copies = copies, freeParticles = free)
        val dt = P.const("DT")
        val sub = P.constInt("SUBSTEPS")
        val ml = P.body.meanLinkLength.toDouble()
        P.setContacts(true)
        P.setTearing(true)
        P.resetState()
        // Тот же вызов, что и по клавише O.
        P.demo.slamOrganisms()
        // ЧЕМ ТЕЛА ОТЛИЧАЮТСЯ — ЧИСЛОМ, А НЕ ДОГАДКОЙ. Медуза пускает внутрь 651 клетку,
        // однородные тела ровно ноль, и надо знать, какое именно свойство за это отвечает.
        // Доля клеток НА КОНТУРЕ — главный подозреваемый: у тонкой мембраны внутренних
        // клеток почти нет, все треугольники внешние, и переход одного слоя сразу значит
        // «внутри». Печатается один раз на тело.
        run {
            val ct = P.contactsObj()
            var onB = 0
            var rmin = Double.MAX_VALUE; var rmax = 0.0
            for (i in 0 until P.n) {
                val r = ct?.contactRadiusOf(i) ?: 0.0
                if (r > 0.0) { onB++; if (r < rmin) rmin = r; if (r > rmax) rmax = r }
            }
            var lmin = Double.MAX_VALUE; var lmax = 0.0
            for (c in 0 until P.conCount) {
                val l = P.conRest[c] / ml
                if (l < lmin) lmin = l
                if (l > lmax) lmax = l
            }
            println(String.format(java.util.Locale.ROOT,
                "    СТРОЕНИЕ: клеток %d, с контактом %d (%.0f%%), треугольников на клетку %.2f, " +
                    "связь %.2f..%.2f, радиус %.2f..%.2f",
                P.n, onB, 100.0 * onB / P.n, P.triCount.toDouble() / P.n,
                lmin, lmax, rmin / ml, rmax / ml))
        }
        // ИСКЛЮЧЕНЫ ЛИ ПАРЫ МЕЖДУ ТЕЛАМИ КАК «КАСАНИЕ В ПОКОЕ».
        //
        // Пары, которые в позе покоя стоят вплотную, намеренно выводятся из контактов
        // (иначе тело отталкивалось бы само от себя). Но две копии тела загружаются РЯДОМ,
        // и если их граничные клетки попали в упор на старте, то контакта между телами не
        // будет НИКОГДА — и тогда дело не в решателе, а в постановке сцены.
        run {
            val ct = P.contactsObj()
            var near = 0; var excluded = 0
            if (ct != null) {
                for (i in 0 until P.n) {
                    if (ct.contactRadiusOf(i) <= 0.0) continue
                    for (j in i + 1 until P.n) {
                        if (ct.contactRadiusOf(j) <= 0.0) continue
                        if (P.organismOf[i] == P.organismOf[j]) continue
                        val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                        val d = Math.sqrt(dx * dx + dy * dy)
                        if (d > 1.5 * ct.contactDistanceOf(i, j)) continue
                        near++
                        if (ct.isBonded(i, j)) excluded++
                    }
                }
            }
            println(String.format(java.util.Locale.ROOT,
                "    ПАРЫ МЕЖДУ ТЕЛАМИ в упоре: %d, из них исключено как касание в покое: %d",
                near, excluded))
        }
        var worstInside = 0
        var worstTick = -1
        var everInside = 0
        var firstTick = -1
        // СКОЛЬКО РАЗ КЛЕТКА ВООБЩЕ ВХОДИЛА. Нужно, чтобы отличить «правило не ловит» от
        // «правило ловит, но разрыв не выталкивает уже вошедшего»: это разные вещи, и
        // путать их нельзя. Вход считается как переход снаружи внутрь между тиками.
        val wasIn = BooleanArray(P.n)
        var entries = 0
        for (t in 1..ticks) {
            P.frame(dt, sub, contract = false)
            var inside = 0
            for (i in 0 until P.n) {
                val nowIn = TrapHunt.insideForeign(i) >= 0
                if (nowIn && !wasIn[i]) entries++
                wasIn[i] = nowIn
                if (nowIn) inside++
            }
            if (inside > 0 && firstTick < 0) firstTick = t
            if (inside > worstInside) { worstInside = inside; worstTick = t }
            everInside += if (inside > 0) 1 else 0
        }
        // Сколько осталось внутри в самом конце — это и есть «проникло и осталось».
        var left = 0
        var deepest = 0.0
        for (i in 0 until P.n) {
            val tri = TrapHunt.insideForeign(i)
            if (tri < 0) continue
            left++
            val d = TrapHunt.depthIn(i, tri, ml)
            if (d > deepest) deepest = d
        }
        // СКОЛЬКО РАЗ СРАБОТАЛ ПРОБОЙ — без этого нельзя отличить «правило не помогает» от
        // «правило не срабатывает», а это разные болезни.
        val pierced = (DemoInspector.read(P.demo, "piercedCount") as Number).toInt()
        val torn = P.tornCount()
        // ЦЕНА ПРАВИЛА СМЕРТИ: сколько клеток оно убило. Без этого числа «проникновения
        // нет» нельзя отличить от «ткани не осталось».
        val dead = (DemoInspector.read(P.demo, "deadCount") as Number).toInt()
        return String.format(java.util.Locale.ROOT,
            "первое проникновение на тике %d, пик %d клеток (тик %d), тиков с проникновением %d из %d, " +
                "ОСТАЛОСЬ ВНУТРИ %d, глубина до %.2f связи, ВХОДОВ %d, пробоев %d, порвано связей %d, " +
                "УМЕРЛО %d из %d",
            firstTick, worstInside, worstTick, everInside, ticks, left, deepest, entries, pierced, torn,
            dead, P.n)
    }
}
