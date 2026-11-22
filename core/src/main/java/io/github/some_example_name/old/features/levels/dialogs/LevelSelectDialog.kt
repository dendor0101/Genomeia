package io.github.some_example_name.old.features.levels.dialogs

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.features.levels.editor.LevelEditorScreen
import kotlin.math.min

/**
 * Список уровней из папки levels: файлы лежат в личном хранилище приложения, поэтому
 * системное окно выбора файлов их не покажет — список читает папку сам.
 *
 * Только вёрстка: что делать с выбранным именем, решает [onPick].
 */
class LevelSelectDialog(names: List<String>, private val onPick: (String) -> Unit) : VisDialog("Открыть уровень") {

    /** Девятипатчи кнопок: освобождаются вместе с диалогом, см. [remove]. */
    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        setupTitleSize(game)

        val table = VisTable()

        table.add(VisLabel(if (names.isEmpty()) "Нет сохранённых уровней" else "Выберите уровень"))
            .center().pad(10.dp()).row()

        val list = VisTable()
        list.top()
        list.background = makeStyledNP(LevelEditorScreen.PANEL_BG, LevelEditorScreen.PANEL_BORDER, textures)

        for (name in names) {
            val button = makeStyledButton(name, game, textures)
            // Длинное имя уровня иначе рисуется поверх соседних кнопок: ячейка
            // сжимается по ширине диалога, а Label без переноса этого не замечает.
            button.label.setWrap(true)
            button.label.setAlignment(Align.center)
            button.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    super.clicked(event, x, y)
                    // Сначала снимаем диалог, потом грузим: загрузка меняет холст под ним.
                    this@LevelSelectDialog.remove()
                    onPick(name)
                }
            })
            list.add(button).width(360.dp()).pad(8.dp()).row()
        }

        val scroll = VisScrollPane(list)
        scroll.setScrollingDisabled(true, false)
        // Высота по содержимому, но не выше половины экрана: при четырёх уровнях список
        // виден целиком (обрезанная последняя строка выглядела как ошибка вёрстки), а
        // длинный список прокручивается. Ползунок не гаснет, иначе непонятно, что ниже
        // есть ещё строки.
        list.pack()
        val listHeight = min(list.prefHeight, Gdx.graphics.height * 0.55f)
        scroll.setFadeScrollBars(false)
        table.add(scroll).center().width(400.dp()).height(listHeight).padBottom(10.dp()).row()

        // Без этой кнопки диалог закрывался только Escape, а на телефоне Escape нет:
        // при пустом списке или неверном выборе игрок оставался в модальном окне.
        val closeButton = makeStyledButton(bundle.get("button.close"), game, textures)
        closeButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                remove()
            }
        })
        table.add(closeButton).center().padBottom(10.dp()).row()

        add(table)

        closeOnEscape()
        pack()
        centerWindow()
    }

    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }
}
