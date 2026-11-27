package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.SplittableRandom

/**
 * Цепочка Kotlin → JNI → Rust и математика, на которой держится побитовая сверка.
 * Без собранной библиотеки (машина без Rust) тесты пропускаются.
 */
class RustSmokeTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    @Test
    fun abiMatches() {
        assertEquals(RustPhysics.ABI_VERSION, RustPhysics.abiVersion())
    }

    /**
     * fdlibm в Rust против StrictMath: случайные аргументы всех порядков, окрестности
     * точек разбиения rem_pio2 (кратные π/4) и края — сравнение по битам.
     */
    @Test
    fun strictMathPortIsBitExact() {
        val rnd = SplittableRandom(11)
        val xs = DoubleArray(1_000_000) { k ->
            when (k % 6) {
                0 -> (rnd.nextDouble() - 0.5) * 8.0
                1 -> (rnd.nextDouble() - 0.5) * 2.0
                2 -> Math.scalb(rnd.nextDouble() - 0.5, rnd.nextInt(-60, 1000))
                3 -> (k / 6 % 4000 - 2000) * Math.PI / 4 + (rnd.nextDouble() - 0.5) * 1e-9
                4 -> java.lang.Double.longBitsToDouble(rnd.nextLong())
                else -> (rnd.nextDouble() - 0.5) * 1e7
            }
        }
        val edge = doubleArrayOf(0.0, -0.0, 1.0, -1.0, 0.5, -0.5, Math.PI, -Math.PI, Math.PI / 2,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE,
            Double.MAX_VALUE, 1e-300, Math.pow(2.0, -27.0), 0.3, 0.78125, 1e22)
        for (input in listOf(xs, edge)) {
            val out = DoubleArray(input.size)
            for ((op, f) in listOf<(Double) -> Double>(StrictMath::sin, StrictMath::cos, StrictMath::acos).withIndex()) {
                RustPhysics.strictBatch(op, input, out)
                for (k in input.indices) {
                    val want = f(input[k])
                    if (want.toRawBits() != out[k].toRawBits() && !(want.isNaN() && out[k].isNaN()))
                        throw AssertionError("op=$op x=${input[k]} (${java.lang.Double.toHexString(input[k])}): " +
                            "StrictMath=${java.lang.Double.toHexString(want)} Rust=${java.lang.Double.toHexString(out[k])}")
                }
            }
        }
    }
}
