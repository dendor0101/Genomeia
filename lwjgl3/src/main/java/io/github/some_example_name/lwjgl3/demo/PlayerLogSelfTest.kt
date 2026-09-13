package io.github.some_example_name.lwjgl3.demo

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * САМОПРОВЕРКА ЖУРНАЛА ИГРОКА: пишет сессию и воспроизводит её, без окна и без рук.
 *
 *   gradlew :lwjgl3:playerLogSelfTest
 *
 * Сессия идёт через те же приватные функции, которые зовёт окно, — act, stepOnce,
 * toggleMark, — а клавиатура подменена заглушкой, где ничего не нажато. Наведение на
 * мышцу и курсор задаются полями демо, как их задал бы handleInput.
 *
 * Проверок две, и вторая не менее важна первой:
 *   1. Воспроизведение сходится с записью: все контрольные суммы журнала и итоговое
 *      состояние совпадают побитово.
 *   2. Проверка не пустая: в копии журнала сдвигается ОДНА координата курсора на
 *      миллионную долю, и воспроизведение обязано это заметить. Если не заметило —
 *      контрольная сумма ничего не сторожит, и «сошлось» в первом пункте ничего не стоит.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodyPath = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "body-export.txt"

    // Клавиатура и мышь окна: ничего не нажато. Нужна одному updateMuscleTargets.
    Gdx.input = java.lang.reflect.Proxy.newProxyInstance(
        Input::class.java.classLoader, arrayOf(Input::class.java)
    ) { _, m, _ ->
        when (m.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as Input

    val logFile = File.createTempFile("player-log-selftest", ".txt")
    logFile.deleteOnExit()

    // ---------------- запись ----------------
    val live = RealBodyDemo(bodyPath)
    live.replayBoot()
    fun method(name: String, vararg types: Class<*>) =
        RealBodyDemo::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
    fun set(name: String, v: Any) =
        RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }.set(live, v)
    fun <T> get(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }.get(live) as T
    }
    val act = method("act", Char::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    val step = method("stepOnce")
    val mark = method("toggleMark", Int::class.javaPrimitiveType!!)
    method("openPlayerLog", String::class.java).invoke(live, logFile.path)

    fun a(op: Char, v: Int = 0) { act.invoke(live, op, v) }
    fun steps(k: Int) { repeat(k) { step.invoke(live) } }

    val px: DoubleArray = get("px"); val py: DoubleArray = get("py")
    val isFree: BooleanArray = get("isFree")
    val muscles = (get<DoubleArray>("muscleTarget")).size
    val grab = (0 until px.size).first { !isFree[it] }
    val scenes: List<BenchScene> = get("scenes")
    val fragment = scenes.indexOfFirst { it.name == "оторванный кусок" }.coerceAtLeast(0)

    steps(30)
    a('G', 1); steps(60); a('G', 0); steps(10)
    if (muscles > 0) { set("hoveredMuscle", 0); steps(20); set("hoveredMuscle", -1); steps(5) }
    a('D', grab)
    for (k in 0 until 45) {
        set("mouseX", px[grab] + 0.01 * k); set("mouseY", py[grab] + 0.004 * k)
        steps(1)
    }
    a('U'); steps(20)
    a('P'); steps(30)
    a('O'); steps(30)
    mark.invoke(live, grab); steps(10); mark.invoke(live, grab); mark.invoke(live, grab + 1)
    a('B', 0); steps(15); a('B', 1)
    a('K', 0); steps(5); a('K', 1)
    a('X', 0); steps(5); a('X', 1)
    a('E', 1); steps(10); a('E', 0)
    a('N', fragment); a('S'); steps(30)
    a('R'); steps(31)
    method("closePlayerLog").invoke(live)
    // Второй аргумент — куда сохранить записанный журнал, чтобы прогнать по нему разбор.
    args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { logFile.copyTo(File(it), overwrite = true) }
    val liveTick: Int = live.currentTick
    val liveHash = PlayerLog.stateHash(px.size, px, py, get("vx"), get("vy"))
    val lines = PlayerLog.parse(logFile).lines
    println("записано: тиков $liveTick, строк ${lines.size} " +
        "(M ${lines.count { it.op == "M" }}, C ${lines.count { it.op == "C" }}, H ${lines.count { it.op == "H" }}, " +
        "MARK ${lines.count { it.op == "MARK" }})")

    // ---------------- воспроизведение ----------------
    fun replay(ls: List<PlayerLog.Line>): Triple<RealBodyDemo, Long, Int> {
        val d = RealBodyDemo(bodyPath)
        d.replayBoot()
        d.replayRun(ls, onEvent = { }, onTick = { })
        Probe.attach(d)
        return Triple(d, PlayerLog.stateHash(Probe.n, Probe.px, Probe.py, Probe.vx, Probe.vy), d.currentTick)
    }

    var fails = 0
    val (d1, h1, t1) = replay(lines)
    val okSame = d1.replayHashBad == 0 && d1.replayHashOk == lines.count { it.op == "H" } &&
        h1 == liveHash && t1 == liveTick
    println("воспроизведение: тиков $t1, сумм сошлось ${d1.replayHashOk}, не сошлось ${d1.replayHashBad}, " +
        "итог ${if (h1 == liveHash) "совпал" else "НЕ совпал"}")
    println(if (okSame) "  OK   запись воспроизводится побитово" else "  FAIL запись не воспроизводится")
    if (!okSame) fails++

    // Порча одной координаты курсора на миллионную долю.
    val ci = lines.indexOfFirst { it.op == "C" }
    if (ci < 0) {
        println("  FAIL в журнале нет строк C — перетаскивание не записалось")
        fails++
    } else {
        val c = lines[ci]
        val x = c.args[0].toDouble() + 1e-6
        val bad = lines.toMutableList()
        bad[ci] = PlayerLog.Line(c.tick, "C", listOf(x.toString(), c.args[1]), "${c.tick} C $x ${c.args[1]}")
        val (d2, _, _) = replay(bad)
        // Итоговое состояние тут не показатель: сессия кончается сбросом, и после
        // него любые две истории сходятся в одно и то же. Сторож — суммы по ходу.
        val caught = d2.replayHashBad > 0
        println("испорченный курсор на тике ${c.tick}: не сошлось сумм ${d2.replayHashBad}, первая на тике ${d2.replayFirstBadTick}")
        println(if (caught) "  OK   контрольная сумма ловит расхождение" else "  FAIL порча не замечена — сумма ничего не сторожит")
        if (!caught) fails++
    }

    println(if (fails == 0) "ВСЁ ЧИСТО" else "ПРОВАЛОВ: $fails")
    exitProcess(if (fails == 0) 0 else 1)
}
