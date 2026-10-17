package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.CONTACT_MAX_STEP
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.act
import io.github.some_example_name.physics.lab.session.closePlayerLog
import io.github.some_example_name.physics.lab.session.openPlayerLog
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.session.replayRun
import io.github.some_example_name.physics.lab.session.replayStateHash
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoSettingsTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun snapshotOwnsParametersAndHeaderPreservesEmptyAndWhitespaceValues() {
        val parameters = mutableMapOf("CT_LEGACY" to "", "RB_WATCH" to "1 2\n3", "RB_MAXSTEP" to "0.4")
        val config = PhysicsConfig(parameters)
        parameters["RB_MAXSTEP"] = "9"
        assertEquals(0.4, config.CONTACT_MAX_STEP, 0.0)
        val out = StringWriter()
        DemoSettings.writeHeader(out, config)
        val log = files.newFile().apply { writeText(out.toString()) }
        val restored = DemoSettings.forReplay(PlayerLog.parse(log).header)
        assertEquals(config.snapshot(), restored.snapshot())
        assertTrue(restored.contacts.abLegacy)
        assertTrue(DemoSettings.forReplay(mapOf("physicsConfig" to "1")).snapshot().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownConfigVersionIsRejected() {
        DemoSettings.forReplay(mapOf("physicsConfig" to "2"))
    }

    private fun body() = files.newFile().apply {
        writeText("""
            P 0 0 0 0.5 0 0 0
            P 1 1 0 0.5 0 0 0
            P 2 1 1 0.5 0 0 0
            P 3 0 1 0.5 0 0 0
            L 0 1
            L 1 2
            L 2 0
            L 2 3
            L 3 0
        """.trimIndent())
    }

    @Test fun contactSettingsBelongToSessionAndCustomSettingsReplay() {
        val path = body().path
        val config = PhysicsConfig(mapOf("CT_OMEGA_PAIR" to "1.5", "CT_SEAL" to "1.1",
            "RB_DRAG_ACCEL" to "80", "RB_KILLDEEP" to "0"))
        val live = DemoSession(path, copies = 2, freeParticles = 0, physicsConfig = config)
        live.replayBoot()
        val other = DemoSession(path, copies = 2, freeParticles = 0, physicsConfig = PhysicsConfig())
        other.replayBoot()
        val contacts = live.state.contacts!!
        contacts.abLegacy = true
        assertFalse(other.state.contacts!!.abLegacy)
        contacts.abLegacy = false
        assertEquals(1.5, contacts.omegaPair, 0.0)
        assertEquals(2.0, other.state.contacts!!.omegaPair, 0.0)
        assertFalse(contacts.killOnDeep)

        val logFile = files.newFile()
        live.openPlayerLog(logFile.path)
        live.act('O')
        repeat(30) { live.stepOnce() }
        live.act('R')
        live.act('D', 0)
        live.state.mouseX = live.state.px[0] + 0.02
        live.state.mouseY = live.state.py[0] + 0.01
        repeat(30) { live.stepOnce() }
        live.act('U')
        live.closePlayerLog()

        val log = PlayerLog.parse(logFile)
        val replay = DemoSession(path, copies = 2, freeParticles = 0,
            physicsConfig = DemoSettings.forReplay(log.header))
        replay.replayBoot()
        replay.replayRun(log.lines, onEvent = {}, onTick = {})
        assertEquals(2, replay.replayHashOk)
        assertEquals(0, replay.replayHashBad)
        assertEquals(live.replayStateHash(), replay.replayStateHash())
    }
}
