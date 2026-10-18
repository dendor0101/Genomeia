package io.github.some_example_name.old.features.genomes

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.FileDropTarget
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.game.applyCustomFontMedium
import kotlin.math.max
import kotlin.math.min

/**
 * Экран «Геномы»: свои и встроенные геномы карточками с превью.
 *
 * Нажатие на карточку открывает окно генома (GenomeCardDialog): свой геном можно
 * редактировать, отдать («поделиться»), переименовать и удалить, встроенный — только
 * открыть в редакторе, а сохраняется он тогда копией в свои. Внизу — новый геном, импорт
 * и возврат в меню; на ПК файлы геномов можно ещё и перетащить в окно.
 *
 * Экран только собирает команды (GenomesIntent) и рисует список — менять его состояние
 * может лишь [GenomesViewModel].
 *
 * Экран переживает переход в редактор (он остаётся в стеке навигации), поэтому список
 * перестраивается при каждом возвращении: в редакторе могли сохранить новый геном или
 * изменить старый — у изменённого и превью рисуется заново (GenomePreviewCache).
 */
class GenomesScreen : VisDslScreen(
    background = Color(0.04f, 0.04f, 0.06f, 1f),
    isScrollable = false
), ReplayTarget, FileDropTarget {

    private val viewModel = GenomesViewModel()

    /** Картинки карточек списка и крупная — для окна генома. */
    private val listPreviews = GenomePreviewCache(previewPixels(CARD_PREVIEW_DP))
    private val cardPreviews = GenomePreviewCache(previewPixels(GenomeCardDialog.PREVIEW_DP))

    /** Фон карточки — один на все: текстура рамки общая. */
    private val cardBackground: Drawable by lazy {
        makeStyledNP(CARD_FILL, Color(STYLE_BEIGE).also { it.a = 0.45f }, extraTextures)
    }
    private val previewBackground: Drawable by lazy { VisUI.getSkin().newDrawable("white", PREVIEW_FILL) }

    private var shownOnce = false

    init {
        viewModel.onGenomesChanged = { recompose() }
        viewModel.onImportFinished = ::showImportResult
    }

    override fun dslShow() {
        // Первый показ собирает VisDslScreen сам; повторный — возвращение из редактора.
        if (shownOnce) recompose()
        shownOnce = true
    }

    override fun VisTable.compose() {
        val contentWidth = min(w * 0.94f, MAX_CONTENT_DP.dp())
        visTable(cellInit = { width(contentWidth).growY().pad(12f.dp()) }) {
            visLabel(bundle.get("button.genomes"), font = game.buttonFont, align = Align.center) {
                center().padBottom(8f.dp())
            }
            row()

            tabs()
            row()

            visScrollPane(
                cellInit = { grow().padTop(8f.dp()) },
                fadeScrollBars = false,
                scrollingDisabledX = true
            ) {
                // Карточки — от верха, а не посередине пустой области.
                top()
                cards(contentWidth)
            }
            row()

            bottomButtons()
        }
    }

    private fun VisTable.tabs() {
        visTable(cellInit = { growX() }) {
            lateinit var mine: VisTextButton
            lateinit var bundled: VisTextButton
            var marking = false

            fun select(tab: GenomesTab) {
                // Отметки ставим сами, а setChecked снова зовёт обработчик кнопки: без этой
                // защиты «Встроенные» снимает отметку с «Мои», та выбирает себя, и по кругу.
                if (marking) return
                marking = true
                mine.isChecked = tab == GenomesTab.MINE
                bundled.isChecked = tab == GenomesTab.BUNDLED
                marking = false
                if (viewModel.currentTab == tab) return
                viewModel.handle(GenomesIntent.SelectTab(tab))
                recompose()
            }

            mine = visToggleButton(
                text = bundle.get("tab.myGenomes"),
                checked = viewModel.currentTab == GenomesTab.MINE,
                // Повторное нажатие на выбранную вкладку сняло бы с неё отметку — select вернёт.
                onCheckedChange = { select(GenomesTab.MINE) }
            ) { expandX().fillX() }

            bundled = visToggleButton(
                text = bundle.get("button.bundledGenomes"),
                checked = viewModel.currentTab == GenomesTab.BUNDLED,
                onCheckedChange = { select(GenomesTab.BUNDLED) }
            ) { expandX().fillX() }
        }
    }

    /** Сетка карточек: столько столбцов, сколько влезает в ширину экрана. */
    private fun VisTable.cards(contentWidth: Float) {
        val names = viewModel.genomeNames()
        if (names.isEmpty()) {
            visLabel(bundle.get("label.noGenomes"), font = game.mediumFont) { center().pad(24f.dp()) }
            return
        }
        val cardWidth = (CARD_PREVIEW_DP + 2 * CARD_PAD_DP).dp()
        val gap = CARD_GAP_DP.dp()
        val columns = max(1, ((contentWidth - gap) / (cardWidth + gap)).toInt())
        names.forEachIndexed { index, name ->
            add(card(name)).width(cardWidth).pad(gap * 0.5f).top()
            if ((index + 1) % columns == 0) row()
        }
    }

    private fun card(name: String): VisTable {
        val card = VisTable()
        card.background = cardBackground
        card.pad(CARD_PAD_DP.dp())
        card.touchable = Touchable.enabled

        val image = previewImage()
        card.add(image).size(CARD_PREVIEW_DP.dp()).row()
        listPreviews.request(name) { region -> if (region != null) image.drawable = TextureRegionDrawable(region) }

        val label = VisLabel(viewModel.displayName(name))
        game.applyCustomFontMedium(label)
        label.setAlignment(Align.center)
        label.setEllipsis(true)
        card.add(label).width(CARD_PREVIEW_DP.dp()).padTop(6f.dp())

        card.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = openCard(name)
        })
        return card
    }

    /** Квадрат под превью: пока картинки нет (или снимка нет вовсе) — тёмная заглушка. */
    private fun previewImage(): Image = Image(previewBackground).apply { setScaling(Scaling.fit) }

    private fun openCard(name: String) {
        val bundledGenome = viewModel.currentTab == GenomesTab.BUNDLED
        GenomeCardDialog(
            title = viewModel.displayName(name),
            preview = previewImage().also { image ->
                cardPreviews.request(name) { region -> if (region != null) image.drawable = TextureRegionDrawable(region) }
            },
            onEdit = { viewModel.handle(GenomesIntent.OpenEditor(name)) },
            onShare = if (bundledGenome) null else ({ viewModel.handle(GenomesIntent.Share(name)) }),
            onRename = if (bundledGenome) null else ({ askRename(name) }),
            onDelete = if (bundledGenome) null else ({ askDelete(name) })
        ).show(stage)
    }

    private fun askRename(name: String) {
        RenameGenomeDialog(
            current = viewModel.displayName(name),
            problemOf = { newName -> viewModel.renameProblem(name, newName) },
            onRename = { newName ->
                forgetPreviews(name)
                viewModel.handle(GenomesIntent.Rename(name, newName))
            }
        ).show(stage)
    }

    private fun askDelete(name: String) {
        ConfirmDeleteDialog(viewModel.displayName(name)) {
            forgetPreviews(name)
            viewModel.handle(GenomesIntent.Delete(name))
        }.show(stage)
    }

    private fun forgetPreviews(name: String) {
        listPreviews.forget(name)
        cardPreviews.forget(name)
    }

    private fun VisTable.bottomButtons() {
        visTable(cellInit = { padTop(10f.dp()) }) {
            visTextButton(bundle.get("button.new"), onClick = {
                viewModel.handle(GenomesIntent.OpenEditor(null))
            }) { padRight(8f.dp()) }

            if (game.multiPlatformFileProvider.canPickGenomeFiles) {
                visTextButton(bundle.get("button.import"), onClick = {
                    viewModel.handle(GenomesIntent.ImportGenome)
                }) { padRight(8f.dp()) }
            }

            visTextButton(bundle.get("button.back"), onClick = { navigation.performCommand(GoBack) })
        }
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            row()
            visLabel(bundle.get("label.dropGenomes"), font = game.mediumFont, align = Align.center) {
                center().padTop(6f.dp())
            }
        }
    }

    /** Файлы, перетащенные в окно (ПК) или отданные меню: импорт в свои геномы. */
    override fun onFilesDropped(files: List<FileHandle>) = viewModel.importGenomeFiles(files)

    /**
     * Импорт закончился. Импортированное — в своих, поэтому показываем их; то, что не
     * удалось, называется отдельным окном: иначе файл пропал бы молча.
     */
    private fun showImportResult(result: GenomeImportResult) {
        if (result.imported.isNotEmpty() && viewModel.currentTab != GenomesTab.MINE) {
            viewModel.handle(GenomesIntent.SelectTab(GenomesTab.MINE))
            recompose()
        }
        if (result.failed.isNotEmpty()) showImportFailed(result.failed)
    }

    private fun showImportFailed(files: List<String>) {
        MessageDialog(
            title = bundle.get("button.import"),
            message = bundle.format("label.importFailed", files.joinToString("\n"))
        ).show(stage)
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is GenomesIntent) return false
        viewModel.handle(command)
        // Переход в редактор уже сменил экран — перестраивать незачем.
        if (game.screen === this) recompose()
        return true
    }

    override fun dslRender(delta: Float) {
        // До отрисовки интерфейса: превью рисуются в свои буферы, экран им не нужен.
        listPreviews.update()
        cardPreviews.update()
    }

    override fun dslDispose() {
        listPreviews.dispose()
        cardPreviews.dispose()
    }

    private companion object {
        /** Сторона превью в карточке списка. */
        const val CARD_PREVIEW_DP = 120f
        const val CARD_PAD_DP = 8f
        const val CARD_GAP_DP = 10f

        /** Шире экран не растягивается: на мониторе карточки иначе разъезжаются. */
        const val MAX_CONTENT_DP = 980f

        val CARD_FILL = Color(0.10f, 0.10f, 0.12f, 0.95f)
        val PREVIEW_FILL = Color(0.03f, 0.03f, 0.04f, 1f)

        /**
         * Пиксели превью под сторону [dp]: картинка не крупнее, чем её покажут (лишние
         * пиксели — лишняя видеопамять), но и не мельче — иначе мыло на плотных экранах.
         */
        fun previewPixels(dp: Float): Int = (dp * Gdx.graphics.density).toInt().coerceIn(64, 512)
    }
}
