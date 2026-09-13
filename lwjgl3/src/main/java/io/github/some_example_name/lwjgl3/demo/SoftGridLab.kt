package io.github.some_example_name.lwjgl3.demo

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.StartupHelper
import java.io.File
import java.util.Random

/**
 * СТЕНД ЧИСТОЙ МЯГКОЙ ТКАНИ: квадрат 10x10, ни костей, ни мышц, треть связей убита.
 *
 * ЗАЧЕМ ОН НУЖЕН ОТДЕЛЬНО ОТ ВСЕГО ОСТАЛЬНОГО. Стенд столкновений три дня подряд
 * отвечал «всё в порядке» на сценах, собранных им же, а руками дребезг виден каждый
 * запуск. Разница в том, что настоящее тело — это сразу всё: кости, мышцы, гребок,
 * разрушение, среда, два организма. Когда симптом не воспроизводится, отделять причины
 * в такой каше бессмысленно.
 *
 * Здесь не остаётся почти ничего. Решётка одинаковых клеток, все мягкие, все одной
 * массы. Мышц нет, значит нечему менять длины покоя. Костей нет, значит нет ни жёсткой
 * проекции, ни shape matching. Гребка нет. Тело одно. Если такое тело, брошенное в
 * покое, начнёт дребезжать или само поедет — виноват решатель, и больше некому.
 *
 * ТРЕТЬ СВЯЗЕЙ УБИТА СРАЗУ, при сборке, а не разрывом по ходу дела. Так получается та
 * же рваная топология, что после разрушения — дыры, тонкие перемычки, висящие клетки, —
 * но без самого разрушения, без пересборки контура на ходу и без переходных процессов.
 * Остаётся только конечное состояние, в котором и живёт жалоба.
 *
 * СИД ПЕЧАТАЕТСЯ. Расклад дыр решает всё: одна укладка спокойна, соседняя дребезжит.
 * Поэтому номер видно в консоли и в заголовке окна, неудачный вариант перебирается
 * клавишей Y, а удачный воспроизводится точно: -Pseed=<номер>.
 *
 *   gradlew :lwjgl3:softGridLab
 *   gradlew :lwjgl3:softGridLab -Pseed=12345
 *   gradlew :lwjgl3:softGridLab -Pseed=12345 -Pkill=0.3
 */
object LabControl {
    /** Клавиша Y в демо просит новый сид: окно закрывается, стенд открывает следующий. */
    @Volatile
    var restartRequested = false
}

internal const val GRID = 10

/** Шаг решётки и радиус берутся те же, что у настоящего тела: 0.6 и 0.5. */
internal const val STEP = 0.6
internal const val RADIUS = 0.5
internal const val ORIGIN = 64.0

/**
 * Пишет квадрат GRID x GRID в формате body-export.
 *
 * Связи: вправо, вверх и ОДНА диагональ на клетку. Диагональ обязательна — треугольники
 * движок собирает из графа связей, и без неё в решётке не будет ни одного треугольника,
 * то есть не будет и стадии площадей. Тело выйдет не мягкой тканью, а верёвочной сеткой.
 *
 * Убитые связи просто не пишутся. Это ровно то же, что разрыв: треугольник, потерявший
 * ребро, не соберётся, а клетка, потерявшая все связи, станет свободной частицей.
 */
internal fun writeGrid(path: File): IntArray {
    val links = ArrayList<IntArray>()
    fun id(x: Int, y: Int) = y * GRID + x
    for (y in 0 until GRID) for (x in 0 until GRID) {
        if (x + 1 < GRID) links.add(intArrayOf(id(x, y), id(x + 1, y)))
        if (y + 1 < GRID) links.add(intArrayOf(id(x, y), id(x, y + 1)))
        if (x + 1 < GRID && y + 1 < GRID) links.add(intArrayOf(id(x, y), id(x + 1, y + 1)))
    }
    val sb = StringBuilder()
    sb.append("# genomeia body export\n")
    sb.append("# СТЕНД: мягкая решётка ").append(GRID).append('x').append(GRID)
        .append(", связей ").append(links.size).append(10.toChar())
    sb.append("# P <id> <x> <y> <radius> <cellType> <bone> <muscle>\n")
    for (y in 0 until GRID) for (x in 0 until GRID) {
        sb.append("P ").append(id(x, y)).append(' ')
            .append(String.format(java.util.Locale.ROOT, "%.4f", ORIGIN + x * STEP)).append(' ')
            .append(String.format(java.util.Locale.ROOT, "%.4f", ORIGIN + y * STEP)).append(' ')
            .append(String.format(java.util.Locale.ROOT, "%.4f", RADIUS))
            .append(" 0 0 0\n")
    }
    for (k in links.indices) {
        sb.append("L ").append(links[k][0]).append(' ').append(links[k][1]).append('\n')
    }
    path.writeText(sb.toString())
    return intArrayOf(links.size, 0)
}

fun main(args: Array<String>) {
    if (StartupHelper.startNewJvmIfRequired()) return
    var seed = args.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: Random().nextInt(100000).toLong()
    val killFraction = args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.30
    val file = File(System.getProperty("java.io.tmpdir"), "soft-grid-lab.txt")
    while (true) {
        val stat = writeGrid(file)
        println()
        println("=====================================================")
        println("  СИД РАЗРУШЕНИЯ: $seed")
        println("  решётка ${GRID}x$GRID, связей ${stat[0]}, убьётся ${(killFraction * 100).toInt()}%")
        println("  J — следующий сид, ESC — выход")
        println("  повторить этот же:  gradlew :lwjgl3:softGridLab -Pseed=$seed")
        println("=====================================================")
        LabControl.restartRequested = false
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Мягкая решётка ${GRID}x$GRID — сид $seed — J новый сид")
            setWindowedMode(1100, 720)
            useVsync(true)
            setForegroundFPS(144)
        }
        Lwjgl3Application(
            RealBodyDemo(file.path, copies = 1, freeParticles = 0,
                labKillFraction = killFraction, labKillSeed = seed,
                killOnDeep = true), config)
        if (!LabControl.restartRequested) break
        seed = Random().nextInt(100000).toLong()
    }
}
