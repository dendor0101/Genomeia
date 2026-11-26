package io.github.some_example_name.physics

import kotlin.math.sqrt

/**
 * Одностороннее ограничение знаковой площади у текущего контура: A >= 0.
 *
 * Запись 21.10, тик 1198: площадь расправляла треугольник 121–141–123,
 * затем контакт снова вдавливал 121 через противоположную сторону. После
 * отрыва клетка оставалась внутри ткани до тика 1395. Ограничение длины двух
 * сторон не ограничивает знак площади, а внутренние клетки не сталкиваются.
 * Поэтому после контакта закрываем именно геометрию контура, без второго
 * обхода всех площадей, расширения контактной сетки или удаления частицы.
 *
 * Используем существующий плотный список рёбер. Если треугольник имеет два
 * граничных ребра, второй заход обычно сразу выходит. Голые рёбра пропускаем.
 * Читаем 13 байт/ребро потоком (три индекса и знак); координаты трёх вершин —
 * 48 байт по индексам, массы нужны лишь при нарушении. Для 400 рёбер индексы
 * занимают 5.2 КБ; новых буферов и аллокаций на проход нет.
 */
fun solveBoundaryOrientation(
    count: Int, a: IntArray, b: IntArray, opposite: IntArray, orientation: ByteArray,
    px: FloatArray, py: FloatArray, invMass: FloatArray, bone: IntArray? = null,
) {
    for (k in 0 until count) orientEdge(k, a, b, opposite, orientation, px, py, invMass, bone)
}

/** Ориентация контура: по организмам, если есть воркеры (ParallelPhysics), иначе подряд. */
internal fun BodyState.solveBoundaryOrientationStage() {
    val w = activeWorkers
    val layout = constraintBlocks
    // ПОРЯДОК ПО БЛОКАМ — ПРИ ЛЮБОМ ЧИСЛЕ ОРГАНИЗМОВ (25.11, находка 42). Раньше при одном организме контур шёл своим
    // списком, а при нескольких — по блокам: стенд (одно тело) и игра (тело и пул свободных частиц) обходили контур
    // тела из двух блоков в разном порядке, и гребок рыбы A-11 расходился (standParityCheck). Порядок не должен
    // зависеть от того, что ещё лежит в мире.
    if (w != null && (organismCount > 1 || layout != null)) { parallelPhysics(w).orientation(); return }
    // Без воркеров — тот же порядок, что у них: по блокам, затем швы.
    if (layout != null) { edgesByBlocksSerial(layout, 0, 0.0f); return }
    if (sleep.asleepCount == 0) solveBoundaryOrientation(boundCount, boundA, boundB, boundOpp,
        localBoundary.orientation, px, py, invMass, orientationBones())
    else solveBoundaryOrientationAwake()
}

/** Кости для стадии ориентации: клетки включённых костей она не двигает. См. projectBoundaryOrientation. */
internal fun BodyState.orientationBones(): IntArray? = if (bonesRigid) boneOf else null

internal fun orientEdge(
    k: Int, a: IntArray, b: IntArray, opposite: IntArray, orientation: ByteArray,
    px: FloatArray, py: FloatArray, invMass: FloatArray, bone: IntArray? = null,
) {
    val c = opposite[k]
    if (c < 0) return
    val i = a[k]; val j = b[k]
    val area = (px[j] - px[i]) * (py[c] - py[i]) - (py[j] - py[i]) * (px[c] - px[i])
    if (area * orientation[k] >= 0.0f) return
    projectBoundaryOrientation(i, j, c, area, px, py, invMass, bone)
}

/**
 * То же со спящими: только рёбра блоков с бодрствующими частицами и шва. Спящий контур
 * заморожен, а проход по всему контуру мира 32 раза за тик был последней крупной
 * статьёй спящей сцены (1.3 из 1.9 мс на 100k). Порядок внутри блока — порядок
 * добавления, поэтому без сна идёт прежний сплошной список. См. BodySleep.
 */
internal fun BodyState.solveBoundaryOrientationAwake() {
    val bone = orientationBones()
    val layout = constraintBlocks ?: return solveBoundaryOrientation(boundCount, boundA, boundB,
        boundOpp, localBoundary.orientation, px, py, invMass, bone)
    val index = layout.edges
    val orientation = localBoundary.orientation
    for (block in 0 until layout.buckets) {
        if (!layout.active(block)) continue
        var k = index.head[block]
        while (k >= 0) {
            orientEdge(k, boundA, boundB, boundOpp, orientation, px, py, invMass, bone)
            k = index.next[k]
        }
    }
}

/** Редкая поправка вынесена из горячего обхода. Градиент площади сохраняет
 * центр масс и не вносит суммарного момента импульса: сумма градиентов и
 * сумма r×grad равны нулю. При разных массах поправка распределяется по invMass.
 */
internal fun projectBoundaryOrientation(
    a: Int, b: Int, c: Int, area: Float,
    x: FloatArray, y: FloatArray, w: FloatArray, bone: IntArray? = null,
) {
    val gax = y[b] - y[c]; val gay = x[c] - x[b]
    val gbx = y[c] - y[a]; val gby = x[a] - x[c]
    val gcx = y[a] - y[b]; val gcy = x[b] - x[a]
    var wa = w[a]; var wb = w[b]; var wc = w[c]
    // КЛЕТКУ КОСТИ ОРИЕНТАЦИЯ НЕ ДВИГАЕТ (16.11.2026), пока в треугольнике есть подвижная мягкая клетка.
    // Стадия стоит после контактов и проекции кости: сдвинутая ею клетка кости остаётся вне позы до
    // следующего подшага, и кость выходит смятой. Трассировка удара двух тел с костными пластинами и
    // мягким суставом (2 связи за тик): после починки предела длины кость всё ещё искажалась на 0.07
    // связи — вывернутый треугольник на стыке кости и ткани расправлялся за счёт костных вершин.
    // Вывернулась мягкая клетка — ей и возвращаться. Отдачу кости за тик возвращает учёт импульса.
    if (bone != null) {
        val ba = bone[a] >= 0; val bb = bone[b] >= 0; val bc = bone[c] >= 0
        if ((ba || bb || bc) && ((!ba && wa > 0.0f) || (!bb && wb > 0.0f) || (!bc && wc > 0.0f))) {
            if (ba) wa = 0.0f; if (bb) wb = 0.0f; if (bc) wc = 0.0f
        }
    }
    val den = wa * (gax * gax + gay * gay) + wb * (gbx * gbx + gby * gby) +
        wc * (gcx * gcx + gcy * gcy)
    if (den < 1e-18f) return
    // Площадь вдоль взвешенного градиента — квадратичная функция λ.
    // Линейный шаг -A/den оставляет отрицательный остаток. Берём ближайший
    // корень A + den*λ + q*λ² = 0 в форме без вычитания близких чисел.
    val q = (wa * wb + wb * wc + wc * wa) * area
    val discriminant = (den * den - 4.0f * q * area).coerceAtLeast(0.0f)
    val lambda = -2.0f * area / (den + sqrt(discriminant))
    x[a] += wa * lambda * gax; y[a] += wa * lambda * gay
    x[b] += wb * lambda * gbx; y[b] += wb * lambda * gby
    x[c] += wc * lambda * gcx; y[c] += wc * lambda * gcy
}
