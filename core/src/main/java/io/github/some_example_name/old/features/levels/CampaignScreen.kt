package io.github.some_example_name.old.features.levels

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.features.levels.editor.LevelFiles
import io.github.some_example_name.old.features.levels.editor.LevelInfo

/**
 * Кампания: встроенные уровни и уровни игрока отдельными секциями.
 *
 * Пройденные уровни подсвечиваются зелёным. Состояние прохождения лежит в
 * campaign/levels/levels.json и связано с LevelInfo.id, а не с именем файла: имя можно
 * поменять, id останется прежним.
 */
class CampaignScreen : VisDslScreen(background = Color(0.045f, 0.055f, 0.075f, 1f)) {

    override fun VisTable.compose() {
        pad(24.dp())

        visTable(cellInit = { growX().left() }) {
            visTextButton(bundle.get("button.back"), onClick = {
                navigation.performCommand(GoBack)
            }) { left() }
        }
        row()

        visLabel(
            text = bundle.get("menu.levels"),
            font = game.buttonFont,
            textColor = ACCENT
        ) { center().growX().padTop(8.dp()).padBottom(8.dp()) }
        row()

        // Кампания и редактор уровней пока экспериментальные: плашка своего цвета, чтобы её
        // не спутать ни с оранжевым заголовком, ни с бежевыми подписями разделов.
        visTable(backgroundColor = EXPERIMENTAL_BG, cellInit = { center().padBottom(18.dp()) }) {
            visLabel(
                text = bundle.get("menu.experimental"),
                font = game.mediumFont,
                textColor = EXPERIMENTAL_TEXT
            ) { pad(6.dp(), 14.dp(), 6.dp(), 14.dp()) }
        }
        row()

        val levels = loadLevels()
        val completed = LevelProgress.completedIds()

        visScrollPane(cellInit = { expand().fill() }, scrollingDisabledX = true) {
            levelSection(bundle.get("menu.builtinLevels"), levels.filter { it.isBuiltin }, completed)
            levelSection(bundle.get("menu.userLevels"), levels.filterNot { it.isBuiltin }, completed)
        }
    }

    private fun loadLevels(): List<LevelInfo> =
        LevelFiles.names().mapNotNull { name ->
            runCatching { LevelFiles.read(name) }
                .onFailure { Gdx.app.error("Campaign", "не удалось открыть уровень $name", it) }
                .getOrNull()
        }

    private fun VisTable.levelSection(title: String, levels: List<LevelInfo>, completed: Set<Int>) {
        visLabel(title, font = game.mediumFont, textColor = SECTION) {
            left().growX().pad(16.dp(), 8.dp(), 8.dp(), 4.dp())
        }
        row()

        if (levels.isEmpty()) {
            visLabel(bundle.get("menu.noLevels"), textColor = MUTED) {
                left().pad(12.dp(), 8.dp(), 12.dp(), 8.dp())
            }
            row()
            return
        }

        levels.sortedBy { it.nameKey.lowercase() }.forEach { level ->
            levelCard(level, completed)
        }
    }

    private fun VisTable.levelCard(level: LevelInfo, completed: Set<Int>) {
        val isCompleted = level.id in completed
        val card = visTable(
            backgroundColor = if (isCompleted) COMPLETED_BG else CARD_BG,
            cellInit = { growX().fillX().pad(3.dp()) }
        ) {
            visLabel(
                text = level.nameKey,
                font = game.largeFont,
                textColor = if (isCompleted) COMPLETED_TEXT else Color.WHITE
            ) { left().growX().pad(12.dp()) }.apply { setWrap(true) }

            if (isCompleted) {
                visLabel(bundle.get("menu.completed"), textColor = COMPLETED_TEXT) {
                    right().pad(12.dp())
                }
            }
        }

        card.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                playLevel(level)
            }
        })
        row()
    }

    private fun playLevel(level: LevelInfo) {
        // Карта, lua, палитра и цель едут в команде: повтор .bug увидит тот же уровень.
        navigation.performCommand(GoSimulation(world = null, genomeName = null, level = level))
    }

    private companion object {
        val ACCENT = Color(0.92f, 0.62f, 0.28f, 1f)
        val SECTION = Color(0.84f, 0.74f, 0.58f, 1f)
        val MUTED = Color(0.55f, 0.58f, 0.64f, 1f)
        val CARD_BG = Color(0.11f, 0.13f, 0.17f, 0.96f)
        val COMPLETED_BG = Color(0.08f, 0.27f, 0.14f, 0.96f)
        val COMPLETED_TEXT = Color(0.72f, 1f, 0.78f, 1f)
        val EXPERIMENTAL_BG = Color(0.32f, 0.25f, 0.04f, 0.96f)
        val EXPERIMENTAL_TEXT = Color(1f, 0.88f, 0.3f, 1f)
    }
}
