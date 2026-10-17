package io.github.some_example_name.physics

/** Сумма импульсов и моментов на кластер; обход его клеток выполняется один раз. */
class RigidContactBatch {
    private var x = DoubleArray(0)
    private var y = DoubleArray(0)
    private var torque = DoubleArray(0)

    fun begin(count: Int) {
        if (x.size != count) {
            x = DoubleArray(count); y = DoubleArray(count); torque = DoubleArray(count)
        } else { x.fill(0.0); y.fill(0.0); torque.fill(0.0) }
    }

    fun add(b: Int, rx: Double, ry: Double, jx: Double, jy: Double) {
        x[b] += jx; y[b] += jy; torque[b] += rx * jy - ry * jx
    }

    fun velocityX(b: Int, ry: Double, mass: Double, inertia: Double): Double =
        x[b] / mass - if (inertia > 1e-18) torque[b] * ry / inertia else 0.0

    fun velocityY(b: Int, rx: Double, mass: Double, inertia: Double): Double =
        y[b] / mass + if (inertia > 1e-18) torque[b] * rx / inertia else 0.0

    fun apply(ids: Array<IntArray>, mass: DoubleArray, inertia: DoubleArray,
              cx: DoubleArray, cy: DoubleArray, px: DoubleArray, py: DoubleArray,
              invMass: DoubleArray, outX: DoubleArray, outY: DoubleArray, positions: Boolean) {
        for (b in ids.indices) {
            if (mass[b] <= 0.0 || (x[b] == 0.0 && y[b] == 0.0 && torque[b] == 0.0)) continue
            val tx = x[b] / mass[b]; val ty = y[b] / mass[b]
            val angle = if (inertia[b] > 1e-18) torque[b] / inertia[b] else 0.0
            // Эйлеровское r + angle × r растягивало кость при каждом касании.
            // Общий конечный поворот сохраняет расстояния, независимо от числа контактов.
            val sin = if (positions) StrictMath.sin(angle) else angle
            val cosMinusOne = if (positions) StrictMath.cos(angle) - 1.0 else 0.0
            for (i in ids[b]) {
                if (invMass[i] <= 0.0) continue
                val rx = px[i] - cx[b]; val ry = py[i] - cy[b]
                outX[i] += tx + cosMinusOne * rx - sin * ry
                outY[i] += ty + sin * rx + cosMinusOne * ry
            }
        }
    }
}
