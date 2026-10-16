package io.github.some_example_name.render.components

import io.github.some_example_name.render.RenderMode

/**
 * Шейдеры дополнительного режима нужны только при его первом кадре. Их создание до
 * меню стоило 183–276 мс на Intel UHD Graphics (15.10). После первого включения остаются
 * только проверка режима и чтение ссылки; фабрика и GPU-ресурсы на кадр не создаются.
 */
internal class DeferredModeRenderer(
    private val mode: RenderMode,
    private val factory: () -> RenderComponent
) : RenderComponent {
    private var renderer: RenderComponent? = null
    private var width = 0
    private var height = 0

    override fun create() = Unit

    override fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        renderer?.resize(width, height)
    }

    override fun render(context: RenderContext) {
        if (context.mode != mode) return
        val active = renderer ?: createRenderer()
        active.render(context)
    }

    private fun createRenderer(): RenderComponent {
        val created = factory()
        created.create()
        // Окно могло изменить размер до первого включения этого режима.
        if (width > 0 && height > 0) created.resize(width, height)
        renderer = created
        return created
    }

    override fun dispose() {
        // Закрытие из меню не должно создавать ещё не использованные шейдеры.
        renderer?.dispose()
        renderer = null
    }
}
