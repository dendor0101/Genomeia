package io.github.some_example_name.physics.lab.checks

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.act
import io.github.some_example_name.physics.lab.session.closePlayerLog
import io.github.some_example_name.physics.lab.session.openPlayerLog
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.session.replayStateHash
import io.github.some_example_name.physics.lab.window.DemoWindow
import java.io.File

/** Проверяет настоящее окно/рисование, не затрагивая player-log.txt пользователя.
 * Время кадра задано явно: разная скорость показа должна дать тот же конец записи.
 */
fun main(args: Array<String>) {
    val body = File(args.getOrElse(0) { "body-export.txt" }).absolutePath
    val output = File(args.getOrElse(1) { "build/reports/demo-window-check" }).absoluteFile.apply { mkdirs() }
    val logFile = File(output, "window-record.txt")
    val live = DemoSession(body, physicsConfig = PhysicsConfig())
    live.replayBoot()
    live.openPlayerLog(logFile.path)
    live.act('O')
    repeat(30) { live.stepOnce() }
    live.act('R')
    repeat(30) { live.stepOnce() }
    live.act('D', 0)
    repeat(30) {
        live.state.mouseX = live.state.px[0] + 0.003
        live.state.mouseY = live.state.py[0] + 0.001
        live.stepOnce()
    }
    live.act('U')
    live.act('G', 1)
    repeat(60) { live.stepOnce() }
    live.act('G', 0)
    repeat(30) { live.stepOnce() }
    live.closePlayerLog()
    val expected = live.replayStateHash()
    val log = PlayerLog.parse(logFile)
    val hashes = log.lines.count { it.op == "H" }
    val speeds = doubleArrayOf(0.5, 1.0, 4.0)

    Lwjgl3Application(object : ApplicationAdapter() {
        lateinit var window: DemoWindow
        var mode = 0
        var frames = 0
        var allFrames = 0

        fun start() {
            val session = DemoSession(body, physicsConfig = DemoSettings.forReplay(log.header))
            session.pendingReplay = log.lines
            window = DemoWindow(session)
            window.create(File(output, "unused-live-log.txt").path, warmUp = false)
            window.resize(1100, 720)
            window.interpMode = mode
            window.controls.timeScale = speeds[mode]
            window.controls.toggle()
            frames = 0
        }

        override fun create() = start()
        override fun render() {
            check(++allFrames < 3000) { "Окно не дошло до конца журнала" }
            val paused = frames in 20..22
            val tick = window.session.currentTick
            val hash = window.session.replayStateHash()
            window.paused = paused
            window.render(1.0 / 60.0)
            if (paused) {
                check(window.session.currentTick == tick)
                check(window.session.replayStateHash() == hash)
            }
            if (++frames == 60) window.renderer.saveScreenshot(File(output, "mode-$mode.png").path)
            if (!window.session.replayActive) {
                check(window.session.replayHashBad == 0)
                check(window.session.replayHashOk == hashes)
                check(window.session.replayStateHash() == expected)
                println("WINDOW interp=$mode speed=${speeds[mode]} checksums=$hashes/$hashes final=exact pause=exact")
                window.dispose()
                mode++
                if (mode == speeds.size) Gdx.app.exit() else start()
            }
        }
    }, Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — проверка окна физики")
        setWindowedMode(1100, 720)
        setInitialVisible(false)
        useVsync(false)
        setForegroundFPS(0)
        setIdleFPS(0)
    })
}
