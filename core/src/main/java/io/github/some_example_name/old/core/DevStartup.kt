package io.github.some_example_name.old.core

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.old.commands.navigationCommandByName

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
 *
 * genomeia.width/height читает лаунчер (Lwjgl3Launcher), а не этот объект.
 */
object DevStartup {

    private const val START_PROPERTY = "genomeia.start"
    private const val SCREENSHOT_PROPERTY = "genomeia.screenshot"
    private const val SCREENSHOT_FRAME_PROPERTY = "genomeia.screenshotFrame"
    private const val KEEP_RUNNING_PROPERTY = "genomeia.keepRunning"

    private const val DEFAULT_SCREENSHOT_FRAME = 60

    private var frame = 0
    private var screenshotTaken = false

    /** Вызывается в конце MyGame.create(), когда стартовый экран уже выставлен. */
    fun applyStartCommand() {
        val name = System.getProperty(START_PROPERTY) ?: return
        val command = navigationCommandByName(name)

        if (command == null) {
            Gdx.app.error("DevStartup", "Неизвестная команда навигации: $name")
            return
        }

        Gdx.app.log("DevStartup", "Стартовая навигация: $name")
        DIGameGlobalContainer.navigationCommandsManager.performCommand(command)
    }

    /** Вызывается в конце MyGame.render(), когда кадр уже нарисован в бэкбуфер. */
    fun afterRender() {
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
