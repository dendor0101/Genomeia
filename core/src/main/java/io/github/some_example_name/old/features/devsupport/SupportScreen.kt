package io.github.some_example_name.old.features.devsupport

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Timer
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.forEach
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.visImage
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visLeftArrowButton
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.w

class SupportScreen : VisDslScreen(isScrollable = true) {

    private val viewModel = SupportViewModel()
    /**
     * Кэш по имени файла: compose() вызывается на каждый resize, и без кэша каждый
     * поворот окна заводил бы ещё пару текстур QR, которые доживали бы до dispose().
     */
    private val qrTextures = mutableMapOf<String, Texture>()

    override fun VisTable.compose() {

        visLeftArrowButton(
            onClick = { navigation.performCommand(GoBack) }
        ) {
            left()
            padTop(32.dp())
        }

        row()

        visLabel(bundle.get("label.support")) {
            center()
            padBottom(25f.dp())
        }
        row()

        visLabel(bundle.get("label.supportText")) {
            center()
            padBottom(35f.dp())
            width(w * 0.9f)
        }.apply {
            setWrap(true)
        }
        row()

        forEach(viewModel.wallets) { wallet ->
            visTable(backgroundColor = Color(0.12f, 0.15f, 0.18f, 1f)) {
                visLabel(wallet.title) {
                    colspan(2)
                    padBottom(15f.dp())
                }
                row()

                visLabel(wallet.address) {
                    width(w * 0.4f)
                    padBottom(12f.dp())
                }.apply {
                    setWrap(true)
                }
                row()

                var copyBtn: VisTextButton? = null
                copyBtn = visTextButton(bundle.get("label.copy"), onClick = {
                    viewModel.handle(SupportIntent.CopyAddress(wallet.title))
                    copyBtn?.setText(bundle.get("label.copied"))
                    Timer.schedule(object : Timer.Task() {
                        override fun run() {
                            copyBtn?.setText(bundle.get("label.copy"))
                        }
                    }, 1.5f)
                })
                row()

                val qrTexture = qrTextures.getOrPut(wallet.qrFile) {
                    Texture(Gdx.files.internal(wallet.qrFile))
                }
                visImage(qrTexture) {
                    size(h * 0.3f, h * 0.3f)
                    colspan(2)
                    padTop(18f.dp())
                    padBottom(64.dp())
                }
            }
            row()
        }
        row()
    }

    override fun dslDispose() {
        qrTextures.values.forEach { it.dispose() }
        qrTextures.clear()
    }
}
