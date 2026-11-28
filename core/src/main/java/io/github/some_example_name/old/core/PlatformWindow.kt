package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx

/**
 * Окно на ПК: полный экран и возврат из него.
 *
 * Сама проверка «окно целиком на экране» живёт в лаунчере (`lwjgl3/WindowFit`): ей нужны
 * GLFW и размеры рамки окна, которых в `:core` нет. Лаунчер кладёт её в [fitWindow], а
 * здесь она вызывается после каждого выхода из полного экрана.
 *
 * Только вид окна: на симуляцию и повтор .bug не влияет, поэтому при повторе окно не трогаем.
 */
object PlatformWindow {

    /** Вместить окно в рабочую область монитора. Выставляет лаунчер; по умолчанию ничего не делает. */
    var fitWindow: () -> Unit = {}

    /** Размер окна до полного экрана — в него возвращаемся. */
    private var windowedWidth = 0
    private var windowedHeight = 0

    /** Кнопка полного экрана нужна только там, где окно вообще умеет менять режим (ПК). */
    val isSupported: Boolean
        get() {
            val app = Gdx.app ?: return false
            return app.type == Application.ApplicationType.Desktop && Gdx.graphics.supportsDisplayModeChange()
        }

    val isFullscreen: Boolean get() = Gdx.graphics?.isFullscreen == true

    fun setFullscreen(enabled: Boolean) {
        if (!isSupported || enabled == isFullscreen) return
        val graphics = Gdx.graphics
        if (enabled) {
            windowedWidth = graphics.width
            windowedHeight = graphics.height
            graphics.setFullscreenMode(graphics.displayMode)
        } else {
            if (windowedWidth > 0 && windowedHeight > 0) graphics.setWindowedMode(windowedWidth, windowedHeight)
            else graphics.setWindowedMode(graphics.width, graphics.height)
            // Рамка окна и позиция устанавливаются уже после возврата: проверять — в следующем кадре.
            Gdx.app.postRunnable { fitWindow() }
        }
    }
}
