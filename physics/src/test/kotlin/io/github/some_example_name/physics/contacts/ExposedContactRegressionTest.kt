package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class ExposedContactRegressionTest {
    private fun pair(linked: Boolean = false): BoundaryContacts {
        val empty = IntArray(0)
        val ct = BoundaryContacts.build(
            3, if (linked) intArrayOf(0) else empty, if (linked) intArrayOf(1) else empty,
            if (linked) 1 else 0, empty, empty, 0, empty,
            floatArrayOf(0.5f, 0.5f, 0.5f), 1.0f, CCD_CORE, 0.0f, 0.0f,
            floatArrayOf(-2f, 2f, -4f), FloatArray(3), 1.0f, 0.2f,
            booleanArrayOf(true, true, true),
        )
        ct.killOnDeep = true
        ct.killDepth = 1.0f
        return ct
    }

    private fun separating(ct: BoundaryContacts, start: Float = 0.225f, bone: Boolean = false) {
        // После открытия контура в начале подшага центры ближе радиуса.
        // К prepare клетка уже вышла из контакта: конечная
        // позиция не доказывает, что пересечения за подшаг не было.
        val qx = floatArrayOf(-start, start, -2.0f)
        val x = floatArrayOf(-0.6f, 0.6f, -2.0f)
        val y = FloatArray(3)
        val v = FloatArray(3)
        val mass = floatArrayOf(1.0f, 1.0f, 1.0f)
        ct.prepare(x, y, qx, y, v, v, mass)
        ct.updateBones(x, y, mass, if (bone) intArrayOf(0, -1, 0) else null,
            if (bone) arrayOf(intArrayOf(0, 2)) else emptyArray())
        ct.solveContacts(x, y, v, v, mass, 0.01f)
    }

    @Test fun newlyExposedOverlapCannotEscapeDamageByLeavingBeforeContactPass() {
        val ct = pair()
        separating(ct)
        assertEquals("Пересечение исчезло между началом подшага и проверкой", 2, ct.killN)
        assertEquals(setOf(0, 1), ct.killList.take(ct.killN).toSet())
    }

    @Test fun boneProtectionIsAppliedAfterFirstBoneUpdateAndCandidatesDoNotLeak() {
        val ct = pair()
        separating(ct, bone = true)
        assertEquals(1, ct.killN)
        assertEquals(1, ct.killList[0])
        separating(ct, start = 0.5f, bone = true)
        assertEquals("Заявка пережила подшаг без глубокого перекрытия", 0, ct.killN)
        separating(ct)
        assertEquals(2, ct.killN)
        ct.killOnDeep = false
        separating(ct)
        assertEquals(0, ct.killN)
    }

    @Test fun bondedPairsAndTouchingThresholdAreStillAllowed() {
        val ct = pair(linked = true)
        separating(ct)
        assertEquals("Связанные клетки не должны наносить друг другу контактный урон", 0, ct.killN)
        val unlinked = pair()
        separating(unlinked, start = 0.25f)
        assertEquals("Ровно на радиусе глубокого перекрытия ещё нет", 0, unlinked.killN)
    }
}
