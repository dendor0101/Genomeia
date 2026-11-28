package io.github.some_example_name.old.features.levels

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisTable
import com.badlogic.gdx.utils.Align
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoLevelEditor
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

    private var composedOnce = false

    /**
     * Возврат на экран из уровня или из редактора уровней (GoBack): VisDslScreen сам
     * перестраивает экран только при первом показе и смене языка, и пройденный уровень не
     * подсвечивался, пока игрок не выйдет в меню и не зайдёт снова. Список заново читается
     * и потому, что в редакторе могли сохранить новый уровень.
     */
    override fun dslShow() {
        if (composedOnce) recompose()
    }

    override fun VisTable.compose() {
        composedOnce = true
        pad(24.dp())

        visTable(cellInit = { growX() }) {
            visTextButton(bundle.get("button.back"), onClick = {
                navigation.performCommand(GoBack)
            }) { left() }
            visLabel("") { growX() }
            // Редактор уровней — здесь, рядом с уровнями, а не в главном меню.
            visTextButton(bundle.get("menu.levelEditor"), onClick = {
                navigation.performCommand(GoLevelEditor)
            }) { right() }
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
                font = game.largeFont,
                textColor = EXPERIMENTAL_TEXT,
                align = Align.center
            ) { pad(6.dp(), 14.dp(), 0f, 14.dp()) }
            row()
            visLabel(
                text = bundle.get("menu.experimentalNote"),
                font = game.mediumFont,
                textColor = EXPERIMENTAL_TEXT,
                align = Align.center
            ) { pad(2.dp(), 14.dp(), 6.dp(), 14.dp()) }
        }
        row()

        val completed = LevelProgress.completedIds()

        // Встроенный — тот, что лежит в ассетах игры, а не тот, у кого так написано в JSON:
        // иначе любой файл игрока мог бы встать в раздел встроенных.
        visScrollPane(cellInit = { expand().fill() }, scrollingDisabledX = true) {
            levelSection(bundle.get("menu.builtinLevels"), loadLevels(LevelFiles.builtinNames), completed)
            levelSection(bundle.get("menu.userLevels"), loadLevels(LevelFiles.names()), completed)
        }
    }

    /** Уровень в списке: ссылка на файл (по ней — название), сам уровень и его название. */
    private class Entry(val level: LevelInfo, val title: String)

    private fun loadLevels(refs: List<String>): List<Entry> =
        refs.mapNotNull { ref ->
            runCatching { Entry(LevelFiles.read(ref), LevelFiles.title(ref)) }
                .onFailure { Gdx.app.error("Campaign", "не удалось открыть уровень $ref", it) }
                .getOrNull()
        }

    private fun VisTable.levelSection(title: String, entries: List<Entry>, completed: Set<Int>) {
        visLabel(title, font = game.mediumFont, textColor = SECTION) {
            left().growX().pad(16.dp(), 8.dp(), 8.dp(), 4.dp())
        }
        row()

        if (entries.isEmpty()) {
            visLabel(bundle.get("menu.noLevels"), textColor = MUTED) {
                left().pad(12.dp(), 8.dp(), 12.dp(), 8.dp())
            }
            row()
            return
        }

        // Встроенные идут курсом (LevelInfo.order), уровни без места (свои) — по названию.
        // Раньше всё шло по алфавиту, и курс начинался с Bone и Chameleon вместо Leaf.
        entries.sortedWith(compareBy<Entry>({ if (it.level.order > 0) it.level.order else Int.MAX_VALUE }, { it.title.lowercase() }))
            .forEach { levelCard(it, completed) }
    }

    private fun VisTable.levelCard(entry: Entry, completed: Set<Int>) {
        val level = entry.level
        val isCompleted = level.id in completed
        val card = visTable(
            backgroundColor = if (isCompleted) COMPLETED_BG else CARD_BG,
            cellInit = { growX().fillX().pad(3.dp()) }
        ) {
            visLabel(
                text = entry.title,
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
