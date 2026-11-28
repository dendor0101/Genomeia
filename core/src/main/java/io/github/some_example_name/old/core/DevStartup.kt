package io.github.some_example_name.old.core

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.old.bug.BugOverlay
import io.github.some_example_name.old.commands.navigationCommandByName
import io.github.some_example_name.old.core.log.ActionLog

/**
 * Управление запуском игры из командной строки — для проверки вёрстки и для отчётов о багах.
 *
 * Всё выключено по умолчанию: без системных свойств это два чтения System.getProperty
 * за весь запуск, поэтому код спокойно живёт в релизной сборке.
 *
 * Открыть редактор мира и снять скриншот через 60 кадров:
 *
 *   gradlew :lwjgl3:run -Dgenomeia.start=GoWorldEditor \
 *       -Dgenomeia.screenshot=shot.png -Dgenomeia.width=1280 -Dgenomeia.height=720
 *
 * Свойства:
 *   genomeia.start           имя команды навигации (см. navigationCommandByName)
 *   genomeia.screenshot      путь к PNG; после съёмки игра закрывается
 *   genomeia.screenshotFrame номер кадра для съёмки (по умолчанию 60)
 *   genomeia.keepRunning     true — не закрывать игру после скриншота
 *   genomeia.logActions      true — печатать журнал действий в консоль по мере событий
 *   genomeia.bugReportAt     номер кадра: открыть окно отчёта об ошибке, как кнопкой-жуком
 *   genomeia.crashAt         кадр:поток — тестовый краш; поток render (игра падает, окно
 *                            покажется при следующем запуске) или thread (фоновый поток,
 *                            окно сразу), например 120:thread
 *   genomeia.resizeAt        кадр:ШxВ — поменять размер окна на этом кадре, через «;»
 *                            можно несколько: 30:800x900;60:1600x700. Проверка того, что
 *                            экран перестраивает вёрстку под новый размер окна.
 *   genomeia.clickAt         кадр:x,y — клик левой кнопкой в точке окна (x вправо, y вниз,
 *                            в пикселях), через «;» можно несколько; кадр:x,y:move — только
 *                            навести мышь. Проверка окон и переходов без рук.
 *
 * genomeia.width/height читает лаунчер (Lwjgl3Launcher), а не этот объект.
 */
object DevStartup {

    private const val START_PROPERTY = "genomeia.start"
    private const val SCREENSHOT_PROPERTY = "genomeia.screenshot"
    private const val SCREENSHOT_FRAME_PROPERTY = "genomeia.screenshotFrame"
    private const val KEEP_RUNNING_PROPERTY = "genomeia.keepRunning"
    private const val LOG_ACTIONS_PROPERTY = "genomeia.logActions"
    private const val BUG_REPORT_PROPERTY = "genomeia.bugReportAt"
    private const val CRASH_PROPERTY = "genomeia.crashAt"
    private const val RESIZE_PROPERTY = "genomeia.resizeAt"
    private const val CLICK_PROPERTY = "genomeia.clickAt"

    private const val DEFAULT_SCREENSHOT_FRAME = 60

    private var frame = 0
    private var renderedFrames = 0
    private var screenshotTaken = false

    /** Вызывается в конце MyGame.create(), когда стартовый экран уже выставлен. */
    fun applyStartCommand() {
        if (System.getProperty(LOG_ACTIONS_PROPERTY) == "true") {
            ActionLog.sink = { entry -> Gdx.app.log("ActionLog", entry.toString()) }
        }

        val script = System.getProperty(START_PROPERTY) ?: return

        // Список через запятую, а не одна команда: маршрут из нескольких переходов —
        // это уже минимальное воспроизведение сценария, а GoBack в одиночку бессмыслен.
        script.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            val command = navigationCommandByName(name)
            if (command == null) {
                Gdx.app.error("DevStartup", "Неизвестная команда навигации: $name")
                return@forEach
            }
            Gdx.app.log("DevStartup", "Стартовая навигация: $name")
            DIGameGlobalContainer.navigationCommandsManager.performCommand(command)
        }
    }

    /** Вызывается в конце MyGame.render(), когда кадр уже нарисован в бэкбуфер. */
    fun afterRender() {
        renderedFrames++
        if (renderedFrames == System.getProperty(BUG_REPORT_PROPERTY)?.toIntOrNull()) {
            BugOverlay.requestReport()
        }
        System.getProperty(CRASH_PROPERTY)?.let { crashTest(it) }
        System.getProperty(RESIZE_PROPERTY)?.let { resizeTest(it) }
        System.getProperty(CLICK_PROPERTY)?.let { clickTest(it) }

        val path = System.getProperty(SCREENSHOT_PROPERTY) ?: return
        if (screenshotTaken) return

        frame++
        val targetFrame = System.getProperty(SCREENSHOT_FRAME_PROPERTY)?.toIntOrNull()
            ?: DEFAULT_SCREENSHOT_FRAME
        if (frame < targetFrame) return

        screenshotTaken = true
        takeScreenshot(path)

        if (System.getProperty(KEEP_RUNNING_PROPERTY) != "true") {
            Gdx.app.exit()
        }
    }

    /** Проверка пути краша: отчёт, окно, передача исключения платформе. */
    private fun crashTest(spec: String) {
        val crashFrame = spec.substringBefore(':').toIntOrNull() ?: return
        if (renderedFrames != crashFrame) return
        val message = "тестовый краш (-D$CRASH_PROPERTY=$spec)"
        if (spec.substringAfter(':', "render") == "thread") {
            Thread({ throw IllegalStateException(message) }, "Crash-Test").start()
        } else {
            throw IllegalStateException(message)
        }
    }

    /** Смена размера окна по расписанию — как если бы игрок потянул за край окна. */
    private fun resizeTest(spec: String) {
        for (step in spec.split(';')) {
            val resizeFrame = step.substringBefore(':').trim().toIntOrNull() ?: continue
            if (renderedFrames != resizeFrame) continue
            val size = step.substringAfter(':').split('x')
            val width = size.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val height = size.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            Gdx.app.log("DevStartup", "Размер окна: ${width}x$height")
            Gdx.graphics.setWindowedMode(width, height)
        }
    }

    /** Клик по расписанию — тем же путём, что и мышь игрока: через текущий обработчик ввода. */
    private fun clickTest(spec: String) {
        for (step in spec.split(';')) {
            val parts = step.split(':')
            if (parts.getOrNull(0)?.trim()?.toIntOrNull() != renderedFrames) continue
            val xy = parts.getOrNull(1)?.split(',') ?: continue
            val x = xy.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val y = xy.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            val input = Gdx.input.inputProcessor ?: continue
            Gdx.app.log("DevStartup", "Клик: $x,$y ${parts.getOrNull(2) ?: ""}")
            input.mouseMoved(x, y)
            if (parts.getOrNull(2)?.trim() == "move") continue
            input.touchDown(x, y, 0, Input.Buttons.LEFT)
            input.touchUp(x, y, 0, Input.Buttons.LEFT)
        }
    }

    private fun takeScreenshot(path: String) {
        val pixmap = Pixmap.createFromFrameBuffer(
            0,
            0,
            Gdx.graphics.backBufferWidth,
            Gdx.graphics.backBufferHeight
        )
        try {
            // Кадровый буфер читается снизу вверх, PNG пишется сверху вниз — отсюда flipY.
            PixmapIO.writePNG(screenshotFile(path), pixmap, -1, true)
            Gdx.app.log("DevStartup", "Скриншот сохранён: $path")
            Gdx.app.log("DevStartup", "Журнал действий:\n${ActionLog.dump()}")
        } finally {
            pixmap.dispose()
        }
    }

    /** Относительный путь резолвится от рабочей директории запуска, абсолютный берётся как есть. */
    private fun screenshotFile(path: String) =
        if (path.startsWith("/") || (path.length > 1 && path[1] == ':')) {
            Gdx.files.absolute(path)
        } else {
            Gdx.files.local(path)
        }
}
