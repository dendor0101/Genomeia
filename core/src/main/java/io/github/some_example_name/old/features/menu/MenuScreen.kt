package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.core.FileDropTarget
import io.github.some_example_name.old.core.GameVersion
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.STYLE_BEIGE_BLACK
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.simulation.GenomeListDialog
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.render.RenderMode

class MenuScreen : VisDslScreen(
    background = Color(0f, 0f, 0f, 1f),
    isScrollable = false
), ReplayTarget, FileDropTarget {

    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()
    var onResize: (() -> Unit)? = null
    private val spriteBatch = SpriteBatch()
    private val font = BitmapFont()
    private val fontMatrix = Matrix4()
    private val shapeRenderer = ShapeRenderer()

    private val menuViewModel = MenuViewModel()

    /** Открытый список геномов: после импорта он открывается заново, уже с новыми. */
    private var genomeList: GenomeListDialog? = null

    init {
        menuViewModel.onGenomeImportFinished = ::showImportResult
        menuViewModel.startMenuSimulation()
        menuViewModel.renderSystem.isRenderUi = false
        // Мир за меню — всегда реалистичный. У него свой RenderSystem, и выбор режима в
        // симуляции или редакторе сюда не доходит; здесь это зафиксировано явно.
        menuViewModel.renderSystem.mode = RenderMode.REALISTIC

        menuViewModel.renderSystem.create(
            fontMatrix = fontMatrix,
            spriteBatch = spriteBatch,
            font = font,
            shapeRenderer = shapeRenderer,
            camera = camera
        )
    }

    override fun dslShow() {
        camera.setToOrtho(false, w, h)
        camera.position.set(24f, 24f, 0f)
        camera.zoom = 0.02f
        camera.update()
    }

    override fun VisTable.compose() {
        visTable {
            visLabel(text = "GENOMEIA", font = game.buttonFont, textColor = STYLE_BEIGE_BLACK)
            row()

            visTextButton(bundle.get("button.empty"), onClick = { browseWorlds() }) { growX() }
            row()

            visTextButton(bundle.get("button.editor"), onClick = { browseGenomes() }) { growX() }
            row()

            visTextButton(bundle.get("button.guide"), onClick = {
                menuViewModel.handle(MenuIntent.OpenGuide)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.options"), onClick = {
                menuViewModel.handle(MenuIntent.OpenSettings)
            }) { growX() }
            row()

            visTextButton(bundle.get("label.support"), onClick = {
                menuViewModel.handle(MenuIntent.OpenSupport)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.exit"), onClick = {
                menuViewModel.handle(MenuIntent.Exit)
            }) { growX() }
            row()

            visLabel(text = GameVersion.NAME, font = game.mediumFont, textColor = STYLE_BEIGE_BLACK)
            row()
        }
    }

    /**
     * «Запуск симуляции»: список сохранённых миров, а если их нет — сразу новый мир.
     * Сам список — только окно, команда — то, что в нём выбрали.
     */
    private fun browseWorlds() {
        val worlds = menuViewModel.savedWorlds()
        if (worlds.isEmpty()) {
            menuViewModel.handle(MenuIntent.openWorldEditor())
            return
        }
        MapSelect(
            game = game,
            title = bundle.get("dialog.worlds"),
            worlds = worlds,
            newWorld = bundle.get("button.newWorld"),
            onLoad = { slot -> menuViewModel.handle(MenuIntent.OpenSavedWorld(slot)) },
            onNew = { menuViewModel.handle(MenuIntent.openWorldEditor()) },
            onResize = { h -> onResize = if (h == {}) null else h }
        ).show(stage)
    }

    /**
     * «Редактор»: свои геномы. Открывается и пустым — в нём же «Новый», встроенные и импорт.
     * [selected] — какой геном отметить (только что импортированный).
     */
    private fun browseGenomes(selected: String? = null) {
        val genomes = menuViewModel.genomeNames()
        val isDesktop = Gdx.app.type == Application.ApplicationType.Desktop
        genomeList = GenomeListDialog(
            genomesList = genomes,
            selectedGenomeIndex = genomes.indexOf(selected).takeIf { it >= 0 },
            title = bundle.get("button.selectGenome"),
            new = bundle.get("button.new"),
            select = bundle.get("button.select"),
            import = bundle.get("button.import"),
            onNew = { menuViewModel.handle(MenuIntent.OpenGenomeEditor(null)) },
            onNext = { name -> menuViewModel.handle(MenuIntent.OpenGenomeEditor(name)) },
            game = game,
            onResize = { h -> onResize = if (h == {}) null else h },
            onImport = if (game.multiPlatformFileProvider.canPickGenomeFiles) {
                { menuViewModel.handle(MenuIntent.ImportGenome) }
            } else null,
            hint = if (isDesktop) bundle.get("label.dropGenomes") else null,
            bundled = bundle.get("button.bundledGenomes"),
            // Свой список закрывается: два полупрозрачных окна друг на друге не читаются.
            onBundled = {
                genomeList?.let { if (it.stage != null) it.close() }
                browseBundledGenomes()
            },
            emptyText = bundle.get("label.noGenomes")
        ).also { it.show(stage) }
    }

    /**
     * Встроенные геномы — поверх списка своих. Выбранный открывается в редакторе; в список
     * своих он попадёт, только когда игрок его там сохранит.
     */
    private fun browseBundledGenomes() {
        GenomeListDialog(
            genomesList = menuViewModel.bundledGenomeNames(),
            selectedGenomeIndex = null,
            title = bundle.get("button.bundledGenomes"),
            new = bundle.get("button.new"),
            select = bundle.get("button.select"),
            import = bundle.get("button.import"),
            onNew = null,
            onNext = { name -> menuViewModel.handle(MenuIntent.OpenGenomeEditor(name)) },
            game = game,
            onResize = { h -> onResize = if (h == {}) null else h },
            displayName = GenomeCatalog::displayName
        ).show(stage)
    }

    /** Файлы из лаунчера или перетаскивания: карта открывает мир, остальные — импорт геномов. */
    override fun onFilesDropped(files: List<FileHandle>) {
        val maps = files.filter { it.extension().equals("map", true) }
        if (maps.isNotEmpty()) {
            // Один жест открывает один мир: несколько переходов подряд скрыли бы ошибки первого.
            menuViewModel.importMapFile(maps.first())
        } else {
            menuViewModel.importGenomeFiles(files)
        }
    }

    /**
     * Импорт закончился. Список открывается заново — уже с новыми геномами, последний
     * отмечен, — а то, что не удалось, называется отдельным окном: иначе файл пропал бы молча.
     * Отменённый выбор файлов ничего не меняет.
     */
    private fun showImportResult(result: GenomeImportResult) {
        if (result.imported.isEmpty() && result.failed.isEmpty()) return
        // close(), а не remove(): он же освобождает текстуры кнопок списка.
        genomeList?.let { if (it.stage != null) it.close() }
        genomeList = null
        if (result.imported.isNotEmpty()) browseGenomes(selected = result.imported.last())
        if (result.failed.isNotEmpty()) showImportFailed(result.failed)
    }

    private fun showImportFailed(files: List<String>) {
        VisDialog(bundle.get("button.import")).apply {
            isModal = true
            setupTitleSize(game)
            val message = VisLabel(bundle.format("label.importFailed", files.joinToString("\n")))
            game.applyCustomFontMedium(message)
            contentTable.add(message).pad(16f * Gdx.graphics.density)
            closeOnEscape()
            pack()
            centerWindow()
        }.show(stage)
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is MenuIntent) return false
        menuViewModel.handle(command)
        return true
    }

    override fun dslRender(delta: Float) {
        menuViewModel.updateFrame()
        menuViewModel.moveCamera(camera, delta)

        batch.projectionMatrix = camera.combined
        batch.begin()
        batch.end()

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dslResize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()

        font.data.setScale(Gdx.graphics.density)

        menuViewModel.renderSystem.resize(width, height)
        spriteBatch.projectionMatrix = fontMatrix.setToOrtho2D(0f, 0f, w, h)

        onResize?.invoke()
    }

    override fun dslDispose() {
        batch.dispose()
        spriteBatch.dispose()
        font.dispose()
        shapeRenderer.dispose()
    }
}
