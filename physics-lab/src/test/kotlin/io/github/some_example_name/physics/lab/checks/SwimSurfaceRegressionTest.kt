package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.tuning.SwimParams
import io.github.some_example_name.physics.lab.tuning.SwimSolver
import io.github.some_example_name.physics.lab.tuning.Topology
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwimSurfaceRegressionTest {
    @Test fun exposedCellsWithoutBoundaryEdgesParticipateInContacts() {
        val file = Files.createTempFile("swim-surface-", ".txt").toFile()
        try {
            // У квадрата с обеими диагоналями каждое ребро входит в два
            // треугольника. Список граничных рёбер пуст, но наружные клетки
            // открыты по сумме углов и обязаны участвовать в контактах.
            file.writeText("""
                P 0 0 0 0.5 0 0 0
                P 1 1 0 0.5 0 0 0
                P 2 1 1 0.5 0 0 0
                P 3 0 1 0.5 0 0 0
                L 0 1
                L 0 2
                L 0 3
                L 1 2
                L 1 3
                L 2 3
            """.trimIndent())
            val demo = DemoSession(file.path)
            demo.replayBoot()
            val topo = Topology.load(file.path)
            assertEquals(0, topo.boundCount)
            assertTrue(!topo.isFree[0])
            val swim = SwimSolver(topo, SwimParams())
            val contacts = demo.state.contacts!!
            assertTrue(contacts.contactRadiusOf(0) > 0.0)
            for (i in 0 until topo.n) {
                assertEquals("contactRadius[$i]", contacts.contactRadiusOf(i).toRawBits(),
                    swim.contacts.contactRadiusOf(i).toRawBits())
            }
        } finally { file.delete() }
    }
}
