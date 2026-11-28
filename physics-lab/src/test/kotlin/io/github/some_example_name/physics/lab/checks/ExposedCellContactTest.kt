package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.tuning.Topology
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Открытые клетки без граничных рёбер участвуют в касаниях. Раньше тест сверял ещё и зеркало решателя
 * SwimSolver с демо побитово; зеркало удалено 26.11.2026, проверка настоящего решателя осталась.
 */
class ExposedCellContactTest {
    @Test fun exposedCellsWithoutBoundaryEdgesParticipateInContacts() {
        val file = Files.createTempFile("exposed-cells-", ".txt").toFile()
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
            val contacts = demo.state.contacts!!
            for (i in 0 until topo.n) assertTrue("радиус касания клетки $i", contacts.contactRadiusOf(i) > 0.0)
        } finally { file.delete() }
    }
}
