package io.github.some_example_name.physics.lab.window

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.FREE_PARTICLES
import io.github.some_example_name.physics.lab.session.ORGANISMS

class RealBodyDemo(
    bodyPath: String, copies: Int = ORGANISMS, freeParticles: Int = FREE_PARTICLES,
    labKillFraction: Double = 0.0, labKillSeed: Long = 0L, physicsConfig: PhysicsConfig = DemoSettings.fromEnvironment(),
) : ApplicationAdapter() {
    internal val session = DemoSession(bodyPath, copies, freeParticles, labKillFraction, labKillSeed, physicsConfig)
    private val window = DemoWindow(session)
    override fun create() = window.create()
    override fun resize(width: Int, height: Int) = window.resize(width, height)
    override fun render() = window.render()
    override fun dispose() = window.dispose()
}


/** Запуск: зелёная стрелка. Путь к выгрузке можно передать аргументом. */
fun launchRealBodyDemo(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export-медуза.txt"
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Организм из редактора — XPBD + shape matching")
        setWindowedMode(1100, 720)
        useVsync(true)
        setForegroundFPS(144)
    }
    // ВОСПРОИЗВЕДЕНИЕ ЖУРНАЛА В ОКНЕ: -Pplay=player-log.txt у задачи realBodyDemo.
    //
    // Тело и все параметры берутся ИЗ ЗАГОЛОВКА ЖУРНАЛА, как в разборе без окна: иначе
    // воспроизводилось бы другое тело и суммы разошлись бы по причине, не имеющей
    // отношения к физике. Контрольные суммы проверяются те же, и их итог виден в HUD —
    // по нему и судят, то ли это, что видел игрок.
    val playPath = args.getOrNull(1)?.takeIf { it.isNotEmpty() }
    if (playPath != null) {
        val f = java.io.File(playPath)
        require(f.isFile) { "нет журнала: ${f.absolutePath}" }
        val log = PlayerLog.parse(f)
        val hdr = log.header
        val bodyPath = hdr["body"] ?: error("в журнале нет строки HDR body")
        val d = RealBodyDemo(bodyPath,
            copies = hdr["copies"]?.toInt() ?: 2,
            freeParticles = hdr["free"]?.toInt() ?: 6,
            labKillFraction = hdr["killFraction"]?.toDouble() ?: 0.0,
            labKillSeed = hdr["killSeed"]?.toLong() ?: 0L,
            physicsConfig = DemoSettings.forReplay(hdr))
        d.session.pendingReplay = log.lines
        // СКОРОСТЬ ПОКАЗА: -Pspeed=4. Умножается РЕАЛЬНОЕ время, а не шаг физики, поэтому
        // состояния остаются побитово теми же — их просто показывают на меньшем числе
        // кадров. Запись на 4959 тиков это 166 секунд, а метки игрока бывают в середине,
        // и ждать их вживую незачем.
        d.session.pendingReplaySpeed = args.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        println("[RealBodyDemo] воспроизведение журнала: ${f.absolutePath}, тело $bodyPath, " +
            "строк ${log.lines.size}")
        Lwjgl3Application(d, config)
        return
    }
    val demo = RealBodyDemo(path)
    // ВЫЛЕТ НА ПРОНИКНОВЕНИИ ПО УМОЛЧАНИЮ ВЫКЛЮЧЕН.
    //
    // Он был включён по просьбе игрока, и тут же выяснилось, чем мешает: окно падает
    // раньше, чем успеваешь поставить метку на проникшую клетку, а метка нужнее — по ней
    // потом разбирается конкретный случай. Включается явно:
    //     gradlew :lwjgl3:realBodyDemo -Pcrash=1     (или RB_TRAP_CRASH=1)
    if (System.getenv("RB_TRAP_CRASH") == "1") demo.session.state.trapCrash = 1
    Lwjgl3Application(demo, config)
}
