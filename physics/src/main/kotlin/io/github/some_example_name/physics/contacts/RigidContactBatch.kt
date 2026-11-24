package io.github.some_example_name.physics

/**
 * Сумма импульсов и моментов на кластер; обход его клеток выполняется один раз.
 * [minSpinArm] — наименьший радиус инерции, у которого есть поворот (SPIN_MIN_ARM в единицах мира): кость из
 * одной клетки только переносится, её «инерция» — квадрат ошибки округления.
 */
class RigidContactBatch(minSpinArm: Float) {
    private val minArm2 = minSpinArm * minSpinArm
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var torque = FloatArray(0)

    fun begin(count: Int) {
        if (x.size != count) {
            x = FloatArray(count); y = FloatArray(count); torque = FloatArray(count)
        } else { x.fill(0.0f); y.fill(0.0f); torque.fill(0.0f) }
    }

    fun add(b: Int, rx: Float, ry: Float, jx: Float, jy: Float) {
        x[b] += jx; y[b] += jy; torque[b] += rx * jy - ry * jx
    }

    fun velocityX(b: Int, ry: Float, mass: Float, inertia: Float): Float =
        x[b] / mass - if (inertia > mass * minArm2) torque[b] * ry / inertia else 0.0f

    fun velocityY(b: Int, rx: Float, mass: Float, inertia: Float): Float =
        y[b] / mass + if (inertia > mass * minArm2) torque[b] * rx / inertia else 0.0f

    fun apply(ids: Array<IntArray>, mass: FloatArray, inertia: FloatArray,
              cx: FloatArray, cy: FloatArray, px: FloatArray, py: FloatArray,
              invMass: FloatArray, outX: FloatArray, outY: FloatArray, positions: Boolean) {
        for (b in ids.indices) {
            if (mass[b] <= 0.0f || (x[b] == 0.0f && y[b] == 0.0f && torque[b] == 0.0f)) continue
            val tx = x[b] / mass[b]; val ty = y[b] / mass[b]
            val angle = if (inertia[b] > mass[b] * minArm2) torque[b] / inertia[b] else 0.0f
            // Эйлеровское r + angle × r растягивало кость при каждом касании.
            // Общий конечный поворот сохраняет расстояния, независимо от числа контактов.
            val sin = if (positions) StrictMath.sin((angle).toDouble()).toFloat() else angle
            val cosMinusOne = if (positions) StrictMath.cos((angle).toDouble()).toFloat() - 1.0f else 0.0f
            for (i in ids[b]) {
                if (invMass[i] <= 0.0f) continue
                val rx = px[i] - cx[b]; val ry = py[i] - cy[b]
                outX[i] += tx + cosMinusOne * rx - sin * ry
                outY[i] += ty + sin * rx + cosMinusOne * ry
            }
            // ЦЕНТР КОСТИ ЕДЕТ ВМЕСТЕ С НЕЙ. Позиционная поправка сдвинула кость на (tx, ty), поворот
            // вокруг центра масс его не двигает. Отскок того же подшага (solveRestitution) считает плечи
            // клеток от этого центра; со старым центром сумма m·r по кости была не ноль, а M·(tx, ty), и
            // вращательная часть отскока давала линейный импульс ω × M·сдвиг из ничего. Тест
            // BoneContactAnalyticTest 16.11.2026: частица в кость, поперечный импульс пары 1.9e-4 от
            // исходного за один подшаг касания; после правки — ошибка округления.
            if (positions) { cx[b] += tx; cy[b] += ty }
        }
    }
}
