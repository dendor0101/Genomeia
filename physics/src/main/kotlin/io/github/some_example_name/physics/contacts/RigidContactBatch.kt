package io.github.some_example_name.physics

class RigidContactBatch(minSpinArm: Float) {
    private val minArm2 = minSpinArm * minSpinArm
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var torque = FloatArray(0)

    // ЗАДЕТЫЕ КОСТИ (25.11.2026, острова). Раньше begin обнулял суммы всех костей мира, а apply обходил их все — на
    // каждом решении касаний. У копии касаний острова (IslandRun.kt) это проход по костям мира на каждый остров и подшаг.
    // Теперь обнуляются и обходятся только кости, которым что-то добавили. apply идёт по ним в порядке номеров, как шёл
    // по всем: кость без суммы он и раньше пропускал, так что биты те же.
    private var touched = BooleanArray(0)
    private var list = IntArray(16)
    private var listN = 0

    fun begin(count: Int) {
        if (x.size != count) {
            x = FloatArray(count); y = FloatArray(count); torque = FloatArray(count); touched = BooleanArray(count)
            listN = 0
            return
        }
        for (k in 0 until listN) { val b = list[k]; x[b] = 0.0f; y[b] = 0.0f; torque[b] = 0.0f; touched[b] = false }
        listN = 0
    }

    fun add(b: Int, rx: Float, ry: Float, jx: Float, jy: Float) {
        if (!touched[b]) {
            touched[b] = true
            if (listN == list.size) list = list.copyOf(listN * 2)
            list[listN++] = b
        }
        x[b] += jx; y[b] += jy; torque[b] += rx * jy - ry * jx
    }

    fun velocityX(b: Int, ry: Float, mass: Float, inertia: Float): Float =
        x[b] / mass - if (inertia > mass * minArm2) torque[b] * ry / inertia else 0.0f

    fun velocityY(b: Int, rx: Float, mass: Float, inertia: Float): Float =
        y[b] / mass + if (inertia > mass * minArm2) torque[b] * rx / inertia else 0.0f

    fun apply(ids: Array<IntArray>, mass: FloatArray, inertia: FloatArray,
              cx: FloatArray, cy: FloatArray, px: FloatArray, py: FloatArray,
              invMass: FloatArray, outX: FloatArray, outY: FloatArray, positions: Boolean) {
        java.util.Arrays.sort(list, 0, listN)
        for (k in 0 until listN) {
            val b = list[k]
            if (mass[b] <= 0.0f || (x[b] == 0.0f && y[b] == 0.0f && torque[b] == 0.0f)) continue
            val tx = x[b] / mass[b]; val ty = y[b] / mass[b]
            val angle = if (inertia[b] > mass[b] * minArm2) torque[b] / inertia[b] else 0.0f
            val sin = if (positions) StrictMath.sin((angle).toDouble()).toFloat() else angle
            val cosMinusOne = if (positions) StrictMath.cos((angle).toDouble()).toFloat() - 1.0f else 0.0f
            for (i in ids[b]) {
                if (invMass[i] <= 0.0f) continue
                val rx = px[i] - cx[b]; val ry = py[i] - cy[b]
                outX[i] += tx + cosMinusOne * rx - sin * ry
                outY[i] += ty + sin * rx + cosMinusOne * ry
            }
            if (positions) { cx[b] += tx; cy[b] += ty }
        }
    }
}
