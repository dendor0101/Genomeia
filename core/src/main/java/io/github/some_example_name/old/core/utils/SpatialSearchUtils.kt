package io.github.some_example_name.old.core.utils

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * АЛЛОЦИРУЕТ результат. Использовать только там, где результат нужен как массив
 * (редкие пользовательские команды, морфогенез). Для обхода есть forEachParticle.
 */
fun GridManager.collectParticles(gridX: Int, gridY: Int, radius: Int = 3): IntArray {
    val list = IntArrayList((2 * radius + 1) * (2 * radius + 1) * maxAmountOfParticles)
    forEachParticle(gridX, gridY, radius) { list.add(it) }
    return list.toIntArray()
}

/**
 * Обход квадрата (2*radius+1)^2 клеток вокруг (gridX, gridY) без аллокаций.
 * Каждый ряд читается одним отрезком: индексы клеток внутри ряда последовательные.
 */
inline fun GridManager.forEachParticle(
    gridX: Int,
    gridY: Int,
    radius: Int,
    action: (Int) -> Unit
) {
    for (dy in -radius..radius) {
        forEachParticleInRowSegment(gridY + dy, gridX - radius, gridX + radius, action)
    }
}

/**
 * Частица под курсором: из тех, в чей радиус попадает точка (x, y), — ближайшая по
 * центру. Если таких нет — -1.
 *
 * ПОЧЕМУ НЕ ТОЛЬКО СЕТКА
 * ----------------------
 * Внутренних клеток организма в сетке нет (ParticleEntity.isInGrid): для столкновений
 * они не нужны, и это порядковая экономия на каждом тике. Но курсор обязан попадать и
 * по ним, иначе тело «прозрачно» для мыши везде, кроме кромки.
 *
 * Поэтому поиск двухступенчатый: сначала сетка (кромка тел, субстанции, всё прочее),
 * затем линейный проход по живым клеткам, отобранный ровно по !isInGrid — то есть
 * ровно по тем, кого в сетке нет. Проход — O(число клеток), но он случается только на
 * клик игрока, а не на тик: несколько тысяч сравнений — это десятки микросекунд раз в
 * человеческую реакцию. Держать ради этого вторую сетку и перестраивать её каждый тик
 * было бы дороже на порядки.
 *
 * Вызывать там же, где читается сетка, — между перестройками (в потоке симуляции).
 */
fun GridManager.pickParticle(
    x: Float,
    y: Float,
    particleEntity: ParticleEntity,
    cellEntity: CellEntity
): Int {
    var best = -1
    var bestDistSq = Float.MAX_VALUE

    val radius = particleEntity.radius

    forEachParticle(x.toInt(), y.toInt(), radius = 1) { p ->
        val dx = particleEntity.x(p) - x
        val dy = particleEntity.y(p) - y
        val distSq = dx * dx + dy * dy
        val r = radius[p]
        if (distSq < r * r && distSq < bestDistSq) {
            bestDistSq = distSq
            best = p
        }
    }

    val inGrid = particleEntity.isInGrid
    val particleIndexes = cellEntity.particleIndexes
    val cells = cellEntity.aliveList
    for (i in 0 until cells.size) {
        val p = particleIndexes[cells.getInt(i)]
        if (p == -1 || inGrid[p]) continue
        val dx = particleEntity.x(p) - x
        val dy = particleEntity.y(p) - y
        val distSq = dx * dx + dy * dy
        val r = radius[p]
        if (distSq < r * r && distSq < bestDistSq) {
            bestDistSq = distSq
            best = p
        }
    }

    return best
}

/**
 * Обход только "кольца" на расстоянии radius (границы квадрата), без аллокаций.
 */
inline fun GridManager.forEachParticleOnRadius(
    gridX: Int,
    gridY: Int,
    radius: Int,
    action: (Int) -> Unit
) {
    // верхний и нижний ряды — целиком, вместе с углами
    forEachParticleInRowSegment(gridY - radius, gridX - radius, gridX + radius, action)
    if (radius != 0) {
        forEachParticleInRowSegment(gridY + radius, gridX - radius, gridX + radius, action)
    }

    // лево и право без углов
    for (dy in -radius + 1 until radius) {
        forEachParticleAt(gridX - radius, gridY + dy, action)
        if (radius != 0) {
            forEachParticleAt(gridX + radius, gridY + dy, action)
        }
    }
}
