package io.github.some_example_name.old.features.devsupport

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Timer
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.*

/** Имя экрана и навигационной команды сохраняем для старых записей .bug. */
class SupportScreen : VisDslScreen(), ReplayTarget {
    private val viewModel = SupportViewModel()
    private val qrTextures = mutableMapOf<String, Texture>()
    private val copyTimers = mutableListOf<Timer.Task>()
    private val contentWidth get() = minOf(w * 0.90f, 960.dp())

    override fun replay(command: GameCommand): Boolean {
        if (command !is SupportIntent) return false
        viewModel.handle(command)
        return true
    }

    override fun VisTable.compose() {
        copyTimers.forEach { it.cancel() }
        copyTimers.clear()
        top()
        visTable(cellInit = { pad(8.dp(), 12.dp(), 8.dp(), 56.dp()).growX() }) {
            visLeftArrowButton(onClick = { navigation.performCommand(GoBack) })
            visLabel(bundle.get("about.title"), font = game.extraLargeFont) {
                minWidth(0f).growX().left().padLeft(12.dp())
            }.setWrap(true)
        }
        row()
        val scroll = visScrollPane(
            cellInit = { grow() },
            scrollingDisabledX = true,
            fadeScrollBars = false
        ) {
            top()
            section("about.intro.title", "about.intro")
            heading("about.community")
            for (id in listOf("youtube", "discord", "tiktok", "itch", "github")) {
                link(id)
            }
            heading("about.roadmap")
            paragraph("about.roadmap.note")
            for (i in 1..9) paragraph("about.plan.$i")
            heading("label.support")
            paragraph("label.supportText")
            link("donate")
            paragraph("about.donate.note")
            viewModel.wallets.forEach { wallet ->
                visTable(cellInit = { width(contentWidth).padBottom(20.dp()) }, backgroundColor = Color(0.12f, 0.15f, 0.18f, 1f)) {
                    pad(20.dp())
                    visLabel(wallet.title) { padBottom(12.dp()) }
                    row()
                    visLabel(wallet.address, font = game.mediumFont) {
                        width(contentWidth - 48.dp()).padBottom(12.dp())
                    }.setWrap(true)
                    row()
                    var copyButton: VisTextButton? = null
                    copyButton = visTextButton(bundle.get("label.copy"), onClick = {
                        viewModel.handle(SupportIntent.CopyAddress(wallet.title))
                        copyButton?.setText(bundle.get("label.copied"))
                        val task = object : Timer.Task() {
                            override fun run() {
                                copyButton?.setText(bundle.get("label.copy"))
                                copyTimers.remove(this)
                            }
                        }
                        copyTimers.add(task)
                        Timer.schedule(task, 1.5f)
                    })
                    row()
                    val texture = qrTextures.getOrPut(wallet.qrFile) {
                        Texture(Gdx.files.internal(wallet.qrFile))
                    }
                    visImage(texture) {
                        size(minOf(240.dp(), contentWidth - 48.dp())).padTop(18.dp())
                    }
                }
                row()
            }
        }
        stage.scrollFocus = scroll
    }

    private fun VisTable.heading(key: String) {
        visLabel(bundle.get(key), font = game.largeFont) {
            width(contentWidth).left().padTop(24.dp()).padBottom(12.dp())
        }.setWrap(true)
        row()
    }

    private fun VisTable.paragraph(key: String) {
        visLabel(bundle.get(key), font = game.largeFont) {
            width(contentWidth).left().padBottom(14.dp())
        }.setWrap(true)
        row()
    }

    private fun VisTable.section(title: String, body: String) {
        heading(title)
        paragraph(body)
    }

    private fun VisTable.link(id: String) {
        visTextButton(bundle.get("about.link.$id"), onClick = {
            viewModel.handle(SupportIntent.OpenLink(id))
        }) { width(contentWidth).padBottom(8.dp()) }
        row()
    }

    override fun dslDispose() {
        copyTimers.forEach { it.cancel() }
        copyTimers.clear()
        qrTextures.values.forEach { it.dispose() }
        qrTextures.clear()
    }
}
