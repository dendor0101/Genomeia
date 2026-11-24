package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Test

class TickMomentumTest {
    private fun momentum(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray, w: FloatArray): FloatArray {
        val v = FloatArray(3)
        for (i in x.indices) if (w[i] > 0.0f) {
            v[0] += vx[i] / w[i]; v[1] += vy[i] / w[i]
            v[2] += (x[i] * vy[i] - y[i] * vx[i]) / w[i]
        }
        return v
    }
    private fun same(expected: FloatArray, actual: FloatArray) {
        for (i in expected.indices) assertEquals(expected[i], actual[i], F_REL * maxOf(1f, kotlin.math.abs(expected[i])))
    }
    @Test fun restoresWholeTickMomentumAfterShapeChanges() {
        val x = floatArrayOf(-1.0f, 0.0f, 2.0f); val y = floatArrayOf(0.0f, 1.0f, -1.0f)
        val vx = floatArrayOf(2.0f, 3.0f, -2.0f); val vy = floatArrayOf(1.0f, -4.0f, 3.0f)
        val w = floatArrayOf(1.0f, 0.5f, 2.0f); val a = TickMomentum(3, SPIN_MIN_ARM)
        val expected = momentum(x,y,vx,vy,w)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0,0))
        x[0] += 0.3f; y[2] -= 0.1f; vx[1] += 7.0f; vy[0] -= 3.0f
        a.cancel(x,y,vx,vy,w)
        same(expected, momentum(x,y,vx,vy,w))
    }
    @Test fun preservesExternalImpulseAndTorque() {
        val x = floatArrayOf(-2.0f, 2.0f); val y = floatArrayOf(1.0f, 1.0f)
        val vx = floatArrayOf(0.0f,0.0f); val vy = floatArrayOf(0.0f,0.0f)
        val w = floatArrayOf(1.0f,0.5f); val a = TickMomentum(2, SPIN_MIN_ARM)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0))
        // Ошибка решателя, затем настоящая внешняя сила в той же геометрии.
        vx[0] = 10.0f; vy[1] = -5.0f
        a.beforeExternal(vx,vy)
        vx[1] += 3.0f; vy[0] += 4.0f
        a.afterExternal(x,y,vx,vy,w)
        a.cancel(x,y,vx,vy,w)
        same(floatArrayOf(6.0f,4.0f,-14.0f),momentum(x,y,vx,vy,w))
    }
    @Test fun disconnectedPiecesDoNotShareCorrection() {
        val x = floatArrayOf(-2.0f,-1.0f,1.0f,2.0f); val y = FloatArray(4)
        val vx = floatArrayOf(1.0f,1.0f,-1.0f,-1.0f); val vy = FloatArray(4)
        val w = FloatArray(4) { 1.0f }; val a = TickMomentum(4, SPIN_MIN_ARM)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0,1,1))
        vx[0] += 10.0f; vy[0] += 3.0f
        a.cancel(x,y,vx,vy,w)
        assertEquals(-1.0f,vx[2],0.0f);assertEquals(-1.0f,vx[3],0.0f)
        assertEquals(0.0f,vy[2],0.0f);assertEquals(0.0f,vy[3],0.0f)
        assertEquals(2.0f,vx[0]+vx[1],F_POS)
    }
    @Test fun nextTickDiscardsPreviousGroupAndExternalLedger() {
        val x = floatArrayOf(-1.0f,1.0f); val y = FloatArray(2)
        val vx = FloatArray(2); val vy = FloatArray(2); val w = FloatArray(2) { 1.0f }
        val a = TickMomentum(2, SPIN_MIN_ARM)
        a.begin(x,y,vx,vy,w,intArrayOf(0,0));a.beforeExternal(vx,vy)
        vx[0]=10.0f;a.afterExternal(x,y,vx,vy,w)
        vx.fill(0.0f);a.begin(x,y,vx,vy,w,intArrayOf(0,1))
        vx[0]=7.0f;a.cancel(x,y,vx,vy,w)
        assertEquals(0.0f,vx[0],0.0f);assertEquals(0.0f,vx[1],0.0f)
    }
    @Test fun deadCellDoesNotSwitchOffItsOrganism() {
        // Погибшая клетка (вне учёта, обратная масса ноль) не блокирует группу своего организма.
        val x = floatArrayOf(-1.0f, 0.0f, 1.0f, 2.0f); val y = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)
        val vx = FloatArray(4); val vy = FloatArray(4)
        val w = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f); val dead = booleanArrayOf(false, false, true, false)
        val a = TickMomentum(4, SPIN_MIN_ARM)
        a.begin(x, y, vx, vy, w, intArrayOf(0, 0, 0, 0), null, dead)
        vx[0] += 0.9f; vy[3] -= 0.3f
        a.cancel(x, y, vx, vy, w)
        same(floatArrayOf(0.0f, 0.0f, 0.0f), momentum(x, y, vx, vy, w))
        assertEquals("погибшая клетка стоит", 0.0f, vx[2], 0.0f); assertEquals(0.0f, vy[2], 0.0f)
    }

    @Test fun frozenParticleInsideGroupBlocksCorrection() {
        // Неподвижная частица В ГРУППЕ (спящая, закреплённая — не погибшая) держит импульс группы: поправка не делается.
        val x = floatArrayOf(-1.0f, 0.0f, 1.0f); val y = FloatArray(3)
        val vx = FloatArray(3); val vy = FloatArray(3)
        val w = floatArrayOf(1.0f, 0.0f, 1.0f)
        val a = TickMomentum(3, SPIN_MIN_ARM)
        a.begin(x, y, vx, vy, w, intArrayOf(0, 0, 0))
        vx[0] += 0.9f
        a.cancel(x, y, vx, vy, w)
        assertEquals(0.9f, vx[0], 0.0f); assertEquals(0.0f, vx[2], 0.0f)
    }

    @Test fun contactMergesGroupsAndTouchingFrozenBlocks() {
        // Два организма в контакте — одна группа: импульс, перешедший от одного к другому, не «исправляется».
        val x = floatArrayOf(-2.0f, -1.0f, 1.0f, 2.0f); val y = FloatArray(4)
        val vx = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f); val vy = FloatArray(4)
        val w = FloatArray(4) { 1.0f }
        val a = TickMomentum(4, SPIN_MIN_ARM)
        a.begin(x, y, vx, vy, w, intArrayOf(0, 0, 1, 1))
        a.noteJoint(1, 2, w)                         // то же слияние, что у контакта
        vx[1] -= 0.6f; vx[2] += 0.6f                   // обмен импульсом внутри пары
        a.cancel(x, y, vx, vy, w)
        assertEquals(0.4f, vx[1], F_POS); assertEquals(0.6f, vx[2], F_POS)
        // Без слияния тот же обмен считался бы ошибкой каждого организма и был бы снят.
        val vx2 = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f)
        val b = TickMomentum(4, SPIN_MIN_ARM)
        b.begin(x, y, vx2, vy, w, intArrayOf(0, 0, 1, 1))
        vx2[1] -= 0.6f; vx2[2] += 0.6f
        b.cancel(x, y, vx2, vy, w)
        assertEquals("импульс первого организма возвращён", 2.0f, vx2[0] + vx2[1], F_POS)
        assertEquals("импульс второго организма возвращён", 0.0f, vx2[2] + vx2[3], F_POS)
        // Касание неподвижной частицы блокирует группу касающегося.
        val w3 = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f); val vx3 = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f)
        val c = TickMomentum(4, SPIN_MIN_ARM)
        c.begin(x, y, vx3, vy, w3, intArrayOf(0, 0, 1, 1))
        c.noteJoint(1, 2, w3)
        vx3[1] -= 0.6f
        c.cancel(x, y, vx3, vy, w3)
        assertEquals(0.4f, vx3[1], F_POS); assertEquals(1.0f, vx3[0], F_POS)
    }
}
