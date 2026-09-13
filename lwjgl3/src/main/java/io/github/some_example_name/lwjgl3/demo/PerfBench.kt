package io.github.some_example_name.lwjgl3.demo

/**
 * ЗАМЕР ВРЕМЕНИ ТИКА НА НАСТОЯЩЕМ ТЕЛЕ, без окна и без рендера.
 *
 * Нужен потому, что «вчетверо больше работы решателя» и «вчетверо медленнее» — разные
 * вещи. В тике кроме ограничений есть широкая фаза контактов с сеткой, DDA и CCD,
 * стадии среды, интегрирование и восстановление скоростей. Доля решателя в этом
 * наборе заранее неизвестна, поэтому её надо мерить, а не прикидывать.
 *
 * Прогрев обязателен: без него меряется работа JIT, а не физики.
 *
 *   gradlew :lwjgl3:perfBench
 *   gradlew :lwjgl3:perfBench -Pbody=body-export-кирпич.txt
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "body-export.txt"
    val P = Probe
    P.boot(path)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    P.setContacts(true)
    P.resetState()

    val warm = 300
    val measured = 600
    for (i in 0 until warm) P.frame(dt, sub, contract = false)
    var best = Double.MAX_VALUE
    repeat(3) {
        P.resetState()
        for (i in 0 until warm / 3) P.frame(dt, sub, contract = false)
        val t0 = System.nanoTime()
        for (i in 0 until measured) P.frame(dt, sub, contract = false)
        val ms = (System.nanoTime() - t0) / 1e6 / measured
        if (ms < best) best = ms
    }
    // Бюджет кадра при 60 в секунду — 16.7 мс, при 30 — 33.3 мс.
    println("PERF %s | cells=%d | %.3f ms/tick | %.1f tick/s | budget60=%.0f%%"
        .format(path, P.n, best, 1000.0 / best, best / 16.7 * 100))
}
