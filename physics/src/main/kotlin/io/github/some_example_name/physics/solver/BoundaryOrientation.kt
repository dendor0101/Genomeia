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
    px: DoubleArray, py: DoubleArray, invMass: DoubleArray,
) {
    for (k in 0 until count) {
        val c = opposite[k]
        if (c < 0) continue
        val i = a[k]; val j = b[k]
        val area = (px[j] - px[i]) * (py[c] - py[i]) - (py[j] - py[i]) * (px[c] - px[i])
        if (area * orientation[k] >= 0.0) continue
        projectBoundaryOrientation(i, j, c, area, px, py, invMass)
    }
}

/** Редкая поправка вынесена из горячего обхода. Градиент площади сохраняет
 * центр масс и не вносит суммарного момента импульса: сумма градиентов и
 * сумма r×grad равны нулю. При разных массах поправка распределяется по invMass.
 */
internal fun projectBoundaryOrientation(
    a: Int, b: Int, c: Int, area: Double,
    x: DoubleArray, y: DoubleArray, w: DoubleArray,
) {
    val gax = y[b] - y[c]; val gay = x[c] - x[b]
    val gbx = y[c] - y[a]; val gby = x[a] - x[c]
    val gcx = y[a] - y[b]; val gcy = x[b] - x[a]
    val wa = w[a]; val wb = w[b]; val wc = w[c]
    val den = wa * (gax * gax + gay * gay) + wb * (gbx * gbx + gby * gby) +
        wc * (gcx * gcx + gcy * gcy)
    if (den < 1e-18) return
    // Площадь вдоль взвешенного градиента — квадратичная функция λ.
    // Линейный шаг -A/den оставляет отрицательный остаток. Берём ближайший
    // корень A + den*λ + q*λ² = 0 в форме без вычитания близких чисел.
    val q = (wa * wb + wb * wc + wc * wa) * area
    val discriminant = (den * den - 4.0 * q * area).coerceAtLeast(0.0)
    val lambda = -2.0 * area / (den + sqrt(discriminant))
    x[a] += wa * lambda * gax; y[a] += wa * lambda * gay
    x[b] += wb * lambda * gbx; y[b] += wb * lambda * gby
    x[c] += wc * lambda * gcx; y[c] += wc * lambda * gcy
}
