package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Test

class TickMomentumTest {
    private fun momentum(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray, w: DoubleArray): DoubleArray {
        val v = DoubleArray(3)
        for (i in x.indices) if (w[i] > 0.0) {
            v[0] += vx[i] / w[i]; v[1] += vy[i] / w[i]
            v[2] += (x[i] * vy[i] - y[i] * vx[i]) / w[i]
        }
        return v
    }
    private fun same(expected: DoubleArray, actual: DoubleArray) {
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-10)
    }
    @Test fun restoresWholeTickMomentumAfterShapeChanges() {
        val x = doubleArrayOf(-1.0, 0.0, 2.0); val y = doubleArrayOf(0.0, 1.0, -1.0)
        val vx = doubleArrayOf(2.0, 3.0, -2.0); val vy = doubleArrayOf(1.0, -4.0, 3.0)
        val w = doubleArrayOf(1.0, 0.5, 2.0); val a = TickMomentum(3)
        val expected = momentum(x,y,vx,vy,w)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0,0))
        x[0] += 0.3; y[2] -= 0.1; vx[1] += 7.0; vy[0] -= 3.0
        a.cancel(x,y,vx,vy,w)
        same(expected, momentum(x,y,vx,vy,w))
    }
    @Test fun preservesExternalImpulseAndTorque() {
        val x = doubleArrayOf(-2.0, 2.0); val y = doubleArrayOf(1.0, 1.0)
        val vx = doubleArrayOf(0.0,0.0); val vy = doubleArrayOf(0.0,0.0)
        val w = doubleArrayOf(1.0,0.5); val a = TickMomentum(2)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0))
        // Ошибка решателя, затем настоящая внешняя сила в той же геометрии.
        vx[0] = 10.0; vy[1] = -5.0
        a.beforeExternal(vx,vy)
        vx[1] += 3.0; vy[0] += 4.0
        a.afterExternal(x,y,vx,vy,w)
        a.cancel(x,y,vx,vy,w)
        same(doubleArrayOf(6.0,4.0,-14.0),momentum(x,y,vx,vy,w))
    }
    @Test fun disconnectedPiecesDoNotShareCorrection() {
        val x = doubleArrayOf(-2.0,-1.0,1.0,2.0); val y = DoubleArray(4)
        val vx = doubleArrayOf(1.0,1.0,-1.0,-1.0); val vy = DoubleArray(4)
        val w = DoubleArray(4) { 1.0 }; val a = TickMomentum(4)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0,1,1))
        vx[0] += 10.0; vy[0] += 3.0
        a.cancel(x,y,vx,vy,w)
        assertEquals(-1.0,vx[2],0.0);assertEquals(-1.0,vx[3],0.0)
        assertEquals(0.0,vy[2],0.0);assertEquals(0.0,vy[3],0.0)
        assertEquals(2.0,vx[0]+vx[1],1e-10)
    }
    @Test fun nextTickDiscardsPreviousGroupAndExternalLedger() {
        val x = doubleArrayOf(-1.0,1.0); val y = DoubleArray(2)
        val vx = DoubleArray(2); val vy = DoubleArray(2); val w = DoubleArray(2) { 1.0 }
        val a = TickMomentum(2)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0));a.beforeExternal(vx,vy)
        vx[0]=10.0;a.afterExternal(x,y,vx,vy,w)
        vx.fill(0.0);a.begin(x,y,vx,vy,w,intArrayOf(0,1))
        vx[0]=7.0;a.cancel(x,y,vx,vy,w)
        assertEquals(0.0,vx[0],0.0);assertEquals(0.0,vx[1],0.0)
    }
}
