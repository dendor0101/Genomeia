package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.tuning.SwimParams
import io.github.some_example_name.physics.lab.tuning.SwimSolver
import io.github.some_example_name.physics.lab.tuning.Topology
import io.github.some_example_name.physics.solveLinkMaxLength
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SwimLimitRegressionTest {
    @Test fun linkCorrectionUsesItsOwnCap() {
        val file = Files.createTempFile("swim-limit-", ".txt").toFile()
        try {
            file.writeText("P 0 0 0 0.5 0 0 0\nP 1 1 0 0.5 0 0 0\nL 0 1\n")
            val demo = DemoSession(file.path)
            demo.replayBoot()
            val state = demo.state
            state.tearingOn = false
            val topology = Topology.load(file.path)
            // Контактный потолок специально отличается от потолка длины связи.
            val swim = SwimSolver(topology, SwimParams(contactMaxStep = 2.0))
            swim.reset()
            val i = state.conA[0]; val j = state.conB[0]
            state.px[j] = state.px[i] + 10.0 * state.conRest[0]
            state.px.copyInto(swim.px); state.py.copyInto(swim.py)
            state.solveLinkMaxLength()
            SwimSolver::class.java.getDeclaredMethod("solveLinkMaxLength")
                .apply { isAccessible = true }.invoke(swim)
            for (k in 0 until state.n) {
                assertEquals("x[$k]", state.px[k].toRawBits(), swim.px[k].toRawBits())
                assertEquals("y[$k]", state.py[k].toRawBits(), swim.py[k].toRawBits())
            }
        } finally { file.delete() }
    }
}
