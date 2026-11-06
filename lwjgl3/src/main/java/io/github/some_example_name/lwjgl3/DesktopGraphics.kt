package io.github.some_example_name.lwjgl3

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.PlatformGraphics
import java.awt.Desktop
import java.net.URI
import java.util.Locale

/** Windows выбирает GPU до создания OpenGL-контекста. Открываем официальный
 * системный экран вместо записи недокументированного UserGpuPreferences.
 * Вызывается только при нажатии кнопки, не создаёт работы в кадре. */
object DesktopGraphics {
    @JvmStatic fun install() {
        if (!System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows")) return
        PlatformGraphics.gpuSettings = object : PlatformGraphics.GpuSettings {
            override val executable: String = ProcessHandle.current().info().command().orElse("")
            override fun open(): Boolean = try {
                Desktop.getDesktop().browse(URI("ms-settings:display-advancedgraphics"))
                true
            } catch (e: Exception) {
                Gdx.app.error("Graphics", "Cannot open Windows graphics settings", e)
                false
            }
        }
    }
}
