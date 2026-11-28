package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.lab.stands.GenomeBodies
import io.github.some_example_name.physics.lab.stands.GAME_LINK
import io.github.some_example_name.physics.lab.stands.PHYSICS_PER_GAME
import io.github.some_example_name.physics.lab.stands.STAND_WORLD_SCALE
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * ОПЫТ 21.11.2026: ДРЕЙФ ТЕЛА В ПОКОЕ ПРИ ТОЧНОСТИ FLOAT (FloatState.kt, настройка мира FP32_STATE=1).
 *
 * Тело генома ставится в покое — без мышц, мыши и внешних сил — в точку (d, d) клеток игры от начала координат: чем
 * дальше, тем крупнее шаг float в координатах (на 1000 клеток он в восемь раз крупнее, чем на 128), а размер связи тот
 * же. Мерится, насколько тело само уехало и повернулось за две минуты игры (30 тиков в секунду) после того, как
 * улеглось (тики 600 → 4200), и с какой скоростью ещё движется в конце. Сон включён (как в игре) и выключен (тело,
 * которое не засыпает: его толкают, у него работают мышцы) — второе строже. Каждый случай — в double и с состоянием во
 * float.
 *
 * Запуск — из снимка сборки: java -cp … FloatDriftCheckKt [тела через запятую] [точки через запятую] [тиков].
 */
fun main(args: Array<String>) {
    val slugs = (args.getOrNull(0) ?: "leech,crayfish,burdock-leaf").split(',')
    val offsets = (args.getOrNull(1) ?: "0,128,512,1000").split(',').map { it.toDouble() }
    val ticks = args.getOrNull(2)?.toInt() ?: 4200
    val settle = 600
    val link = GAME_LINK * PHYSICS_PER_GAME
    println("тело\tточка\tсон\tточность\tсдвиг ЦМ, связей\tповорот, °\tскорость в конце, связей/тик\tклеток")
    for (slug in slugs) for (off in offsets) for (sleep in listOf(true, false)) for (fp32 in listOf(false, true)) {
        val w = PhysicsWorld(PhysicsConfig(if (fp32) mapOf("FP32_STATE" to "1") else emptyMap()), workers = null,
            scale = STAND_WORLD_SCALE).apply { state.quiet = true; sleepOn = sleep }
        val def = GenomeBodies.shape(slug).definition(off, off)
        val first = w.bodyFirst(w.addBody(def, BooleanArray(def.count) { true }))
        val n = def.count
        repeat(settle) { w.step() }
        val st = w.state
        fun center(): DoubleArray {
            var sx = 0.0; var sy = 0.0; var m = 0.0
            for (k in 0 until n) { val p = first + k; if (st.cellDead[p]) continue; val mk = 1.0 / st.restInvMass[p]; sx += st.px[p] * mk; sy += st.py[p] * mk; m += mk }
            return doubleArrayOf(sx / m, sy / m)
        }
        val c0 = center()
        val rx = DoubleArray(n) { st.px[first + it] - c0[0] }; val ry = DoubleArray(n) { st.py[first + it] - c0[1] }
        repeat(ticks - settle) { w.step() }
        val c1 = center()
        var cross = 0.0; var dot = 0.0; var speed = 0.0; var alive = 0
        for (k in 0 until n) {
            val p = first + k
            if (st.cellDead[p]) continue
            val x = st.px[p] - c1[0]; val y = st.py[p] - c1[1]
            cross += rx[k] * y - ry[k] * x; dot += rx[k] * x + ry[k] * y
            speed += sqrt(st.vx[p] * st.vx[p] + st.vy[p] * st.vy[p]); alive++
        }
        val shift = sqrt((c1[0] - c0[0]) * (c1[0] - c0[0]) + (c1[1] - c0[1]) * (c1[1] - c0[1])) / link
        val turn = Math.toDegrees(atan2(cross, dot))
        val v = speed / alive * DT / link
        println("$slug\t${off.toInt()}\t${if (sleep) "да" else "нет"}\t${if (fp32) "float" else "double"}\t" +
            "${"%.6f".format(shift)}\t${"%.5f".format(turn)}\t${"%.2e".format(v)}\t$alive из $n")
    }
}
