package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Timer
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.systems.render.CoreDesktopShaders
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.FileProvider

class SupportScreen(
    private val game: MyGame,
    private val multiPlatformFileProvider: FileProvider
) : Screen {

    private lateinit var stage: Stage
    private lateinit var skin: Skin
    private val qrTextures = mutableListOf<Texture>()

    // === ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ ДЛЯ DENSITY ===
    private fun dp(value: Float): Float {
        return value * Gdx.graphics.density.coerceAtLeast(1f)
    }

    override fun show() {
        stage = CoreDesktopShaders.newStage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        if (!VisUI.isLoaded()) VisUI.load()
        skin = VisUI.getSkin()

        val contentTable = Table()
        contentTable.pad(dp(30f))                    // ← теперь с density

        // Заголовок
        val title = VisLabel(bundle.get("label.support"))
        game.applyCustomFont(title)
        contentTable.add(title).padBottom(dp(25f)).row()

        // Описание
        val desc = VisLabel(bundle.get("label.supportText"))
        game.applyCustomFontMedium(desc)
        desc.setWrap(true)
        contentTable.add(desc)
            .width(getContentWidth() * 0.92f)
            .padBottom(dp(35f))
            .row()

        // USDT
        val usdtCard = createDonationCard(
            "USDT (TRC20 / TRON)",
            "TXVmZKM8K5NFcfJpYMgpWm9MpaLPADoC7f",
            "ui/trc-20-qr.png"
        )
        contentTable.add(usdtCard).padBottom(dp(25f)).row()

        // TON
        val tonCard = createDonationCard(
            "TON",
            "UQANA9T_wuxvg73xQz-N7e-WfzDAf5uwMT0f6HIBQGCwEjBO",
            "ui/ton-qr.png"
        )
        contentTable.add(tonCard).padBottom(dp(45f)).row()

        // Кнопка назад
        val backBtn = VisTextButton(bundle.get("label.backToMenu"))
        game.applyCustomFont(backBtn)
        backBtn.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.setScreen(MenuScreen(game, multiPlatformFileProvider))
            }
        })
        contentTable.add(backBtn).width(dp(320f)).height(dp(55f))

        // ScrollPane
        val scrollPane = VisScrollPane(contentTable)
        scrollPane.setFadeScrollBars(false)
        scrollPane.setScrollingDisabled(true, false)

        val mainTable = Table()
        mainTable.setFillParent(true)
        mainTable.add(scrollPane).grow().pad(dp(15f))

        stage.addActor(mainTable)
    }

    /** Адаптивная ширина контента */
    private fun getContentWidth(): Float {
        val viewportWidth = stage.viewport.worldWidth
        val density = Gdx.graphics.density.coerceAtLeast(1f)

        // На очень маленьких экранах берём почти всю ширину
        val maxWidth = if (viewportWidth / density < 400f) {
            viewportWidth * 0.95f
        } else {
            (viewportWidth / density).coerceAtMost(720f)
        }
        return maxWidth * 0.6f
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.08f, 0.1f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}

    override fun dispose() {
        qrTextures.forEach { it.dispose() }
        qrTextures.clear()
        stage.dispose()
    }

    private fun createDonationCard(title: String, address: String, qrFile: String): Table {
        val card = Table()
        card.background = skin.newDrawable("white", 0.12f, 0.15f, 0.18f, 1f)
        card.pad(dp(20f))

        val header = VisLabel(title)
        game.applyCustomFont(header)
        card.add(header).colspan(2).padBottom(dp(15f)).row()

        val addrLabel = VisLabel(address)
        game.applyCustomFontMedium(addrLabel)
        addrLabel.setWrap(true)
        card.add(addrLabel)
            .width(getContentWidth() * 0.88f)
            .padBottom(dp(12f))
            .row()

        val copyBtn = VisTextButton(bundle.get("label.copy"))
        game.applyCustomFont(copyBtn)
        copyBtn.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                Gdx.app.clipboard.contents = address
                copyBtn.setText(bundle.get("label.copied"))

                Timer.schedule(object : Timer.Task() {
                    override fun run() {
                        copyBtn.setText(bundle.get("label.copy"))
                    }
                }, 1.5f)
            }
        })
        card.add(copyBtn).padLeft(dp(10f)).row()

        try {
            val texture = Texture(Gdx.files.internal(qrFile))
            qrTextures.add(texture)
            val qrImage = Image(texture)
            card.add(qrImage).size(dp(200f)).padTop(dp(18f)).colspan(2)
        } catch (e: Exception) {
            card.add(Label("QR code not available", skin)).colspan(2).padTop(dp(15f))
        }

        return card
    }
}
