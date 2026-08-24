package io.github.some_example_name.old.core

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Json
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.NavigationCommandsManager
import io.github.some_example_name.old.core.log.ActionLog
import io.github.some_example_name.old.core.DISimulationContainer.gridHeight
import io.github.some_example_name.old.core.DISimulationContainer.heightMultiplier
import io.github.some_example_name.old.systems.genomics.Morphogenesis
import io.github.some_example_name.old.systems.genomics.genome_deprecated.GenomeJsonReader
import io.github.some_example_name.old.systems.render.ShaderManager
import io.github.some_example_name.old.game.MyGame
import java.util.Locale

object DIGameGlobalContainer {

    lateinit var game: MyGame


    lateinit var fileProvider: FileProvider
    val json by lazy { Json() }
//    val bundle: I18NBundle by lazy {
//        I18NBundle.createBundle(
//            Gdx.files.internal("ui/i18n/MyBundle"),
//            Locale.getDefault()
//        )
//    }

    private var _currentLocale: Locale = Locale.getDefault()
    private var _bundle: I18NBundle = createBundle(_currentLocale)

    val bundle: I18NBundle get() = _bundle
    val currentLocale: Locale get() = _currentLocale

    fun setLanguage(locale: Locale) {
        if (_currentLocale == locale) return

        _currentLocale = locale
        Locale.setDefault(locale)
        _bundle = createBundle(locale)
    }

    private fun createBundle(locale: Locale): I18NBundle {
        return I18NBundle.createBundle(
            Gdx.files.internal("ui/i18n/MyBundle"),
            locale
        )
    }

    /** Подписываются экраны, которые хотят автоматически перестраиваться */
    var onLanguageChanged: (() -> Unit)? = null

    val genomeJsonReader = GenomeJsonReader()

    private val cellListBuilder = CellListBuilder()

    val particleTexturePaths: List<String> = cellListBuilder.instances.map {
        "cell_textures/" + it.textureName
    } + "cell_textures/not_cell.png"

    val defaultCellSettingsMap = cellListBuilder.instances.associate {
        it.name to it.defaultCellSettings
    }

    val shaderManager: ShaderManager = ShaderManager(particleTexturePaths)

    val morphogenesis = Morphogenesis()

    val substrateSettings = SubstrateSettings()

    var roundStyle: VisTextButton.VisTextButtonStyle
    var roundStyleToggle: VisTextButton.VisTextButtonStyle


    init {
        if (gridHeight % heightMultiplier != 0) throw Exception("gridHeight should be a multiple of (halfChunkHeight * 2 * 2)")

        val patch = NinePatch(Texture(Gdx.files.internal("button.png")), 20, 20, 20, 20)

        val roundUp = NinePatchDrawable(patch).tint(Color(0.44f, 0.40f, 0.40f, 1f))
        val roundDown = NinePatchDrawable(patch).tint(Color(0.2f,0.2f,0.2f,1f))
        val roundOver = NinePatchDrawable(patch).tint(Color(0f, 0.9f, 1f, 1f))

        val baseStyle = VisUI.getSkin().get("blue", VisTextButton.VisTextButtonStyle::class.java)

        //Стиль для обычных кнопок
        roundStyle = VisTextButton.VisTextButtonStyle(baseStyle).apply {
            up = roundUp
            down = roundDown
            over = roundOver
        }

        val toggleBaseStyle = VisUI.getSkin().get("toggle", VisTextButton.VisTextButtonStyle::class.java)
        roundStyleToggle = VisTextButton.VisTextButtonStyle(toggleBaseStyle).apply {
            up = roundUp
            over = roundOver
            down = roundDown

            // Перезаписываем прямоугольные текстуры toggle на наши скругленные
            checked = roundOver
            checkedOver = roundOver
        }
    }

    val navigationCommandsManager = NavigationCommandsManager().apply {
        // Побочный канал навигации. Переходы не схлопываются: два GoBack подряд — это два
        // разных перехода, и назначение у них разное, а именно оно и делает запись
        // проверяемой при воспроизведении.
        onCommand = { command, destination ->
            // Стрелку дописываем только когда команда сама не называет, куда ведёт:
            // у GoWorldEditor назначение очевидно, а вот GoBack без него бесполезен.
            val namesItsDestination = destination == command.name || destination == command.detail
            ActionLog.record(
                source = "Navigation",
                kind = command.name,
                detail = if (namesItsDestination) command.detail else "${command.detail} -> $destination".trim(),
                coalesce = false
            )
        }
    }
}
