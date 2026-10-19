package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer

abstract class VisDslScreen(
    val background: Color = Color(0.04f, 0.04f, 0.06f, 1f),
    val isScrollable: Boolean = false
) : Screen {

    protected val extraTextures = mutableListOf<Texture>()//TODO понять нужно ли dispode обрабатывать

    protected val stage = Stage(ScreenViewport())
    protected val rootTable: VisTable = globalVisTable {
        setFillParent(true)
    }

    protected val navigation = DIGameGlobalContainer.navigationCommandsManager

    private var currentScreenWidth = 0
    private var currentScreenHeight = 0
    private var currentLocale = DIGameGlobalContainer.currentLocale
    private var isComposed = false
    protected val inputProcessor = InputMultiplexer()

    init {
        stage.addActor(rootTable)

        // Подписываемся на смену языка (чтобы весь экран перерисовался)
        DIGameGlobalContainer.onLanguageChanged = {
            recompose()
        }
    }

    override fun show() {
        // Экран, вернувшийся из стека (GoBack), показывается заново: без очистки stage и
        // «Назад» добавлялись бы ещё раз при каждом возвращении.
        inputProcessor.clear()
        inputProcessor.addProcessor(stage)

        val backProcessor = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.BACK) {
                    navigation.performCommand(GoBack)
                    return true
                }
                return false
            }
        }
        inputProcessor.addProcessor(backProcessor)
        dslShow()
        Gdx.input.inputProcessor = inputProcessor

        if (!isComposed || currentLocale != DIGameGlobalContainer.currentLocale) {
            recompose()
            isComposed = true
            currentScreenWidth = Gdx.graphics.width
            currentScreenHeight = Gdx.graphics.height
        }

        if (currentScreenWidth != Gdx.graphics.width || currentScreenHeight != Gdx.graphics.height) {
            resize(Gdx.graphics.width, Gdx.graphics.height)
        }
        currentScreenWidth = Gdx.graphics.width
        currentScreenHeight = Gdx.graphics.height
    }

    /**
     * Главный метод экрана. Полностью в стиле Jetpack Compose.
     *
     * Пример использования:
     * override fun VisTable.compose() {
     *     visLabel("Заголовок") { center(); padBottom(30f) }
     *     row()
     *     visTextButton("Играть") {
     *         onClick = { ... }
     *         expandX().fillX()
     *     }
     * }
     */
    protected abstract fun VisTable.compose()

    /**
     * Полная перерисовка экрана (аналог recomposition).
     * Используй, когда сильно поменялось состояние (новый список, смена режима и т.д.).
     */
    protected fun recompose() {
        rootTable.clearChildren()

        rootTable.apply {
            if (isScrollable) {
                visScrollPane({
                    expand().fill()
                }) {
                    compose()
                }
            } else {
                compose()
            }
        }
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(background.r, background.g, background.b, background.a)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        dslRender(delta)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return
        stage.viewport.update(width, height, true)
        recompose()
        stage.keepWindowsOnScreen()
        dslResize(width, height)

        currentScreenWidth = width
        currentScreenHeight = height
    }

    override fun pause() {}

    override fun resume() {}

    override fun hide() {
//        println("hide VisDslScreen")
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        extraTextures.forEach { it.dispose() }
        dslDispose()
    }
    protected open fun dslShow() {}

    protected open fun dslResize(width: Int, height: Int) {}
    protected open fun dslDispose() {}
    protected open fun dslRender(delta: Float) {}
}
