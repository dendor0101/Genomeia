package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visScrollPane
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.editor.tutorial.EditorTutorial
import kotlin.math.min

/**
 * Панель обучения поверх редактора генома: текст шага, задания с прогрессом и «Далее».
 *
 * Панель только рисует [EditorTutorial] и отдаёт нажатия: «Далее» — командой
 * TutorialNextStep ([onNext]), «Завершить» на последнем шаге — выходом в меню ([onFinish]).
 *
 * Текст шага собирается только при смене шага, а на каждую команду редактора
 * (EditorTutorial.onChanged) пересобираются одни задания: иначе любой клик по полю
 * пересоздавал прокрутку текста и отбрасывал её в начало.
 */
class TutorialPanel(
    private val tutorial: EditorTutorial,
    private val onNext: () -> Unit,
    private val onFinish: () -> Unit
) {

    /** Сама панель. Редактор ставит её в среднюю строку вёрстки, между кнопками и временем. */
    val root: VisTable = VisTable(true)

    /**
     * Проход ввода к панели поверх модальных окон — см. [InputBridge]. Экран держит его
     * последним на сцене (GenomeEditorScreen.render).
     */
    val inputBridge: Actor = InputBridge()

    /** Не `width`: внутри лямбд DSL это имя берётся у таблицы-получателя (а там 0). */
    private val panelWidth = min(w * 0.38f, 480.dp())

    private lateinit var tasks: VisTable

    /** Шаг, для которого собран текст; -1 — ещё не собран. */
    private var composedStep = -1

    init {
        refresh()
    }

    fun refresh() {
        if (composedStep != tutorial.stepIndex) composeStep()
        composeTasks()
    }

    private fun composeStep() {
        composedStep = tutorial.stepIndex
        val step = tutorial.step

        root.clearChildren()
        root.visTable(backgroundColor = BACKGROUND) {
            pad(16.dp())
            defaults().left().width(panelWidth)

            visLabel(
                bundle.format("tutorial.progress", tutorial.stepIndex + 1, tutorial.steps.size),
                textColor = MUTED,
                font = game.mediumFont
            )
            row()

            visLabel(bundle.get("tutorial.${step.id}.title"), font = game.extraLargeFont)
                .setWrap(true)
            row()

            // Текст шага бывает длинным — он прокручивается, а задания и кнопка остаются
            // на месте.
            visScrollPane({ maxHeight(h * 0.33f).padTop(8.dp()) }, scrollingDisabledX = true) {
                // Ширину тексту даёт сама прокрутка (по X она выключена): заданная вручную
                // не учитывала отступы содержимого, и край текста уходил под панель.
                visLabel(bundle.get("tutorial.${step.id}.text")) { growX() }.setWrap(true)
            }
            row()

            tasks = visTable { }
        }
        root.invalidateHierarchy()
    }

    private fun composeTasks() {
        val step = tutorial.step
        tasks.clearChildren()
        tasks.defaults().left()

        // Задания — в рамке: без неё они сливались с текстом шага.
        if (step.tasks.isNotEmpty()) {
            tasks.visTable(cellInit = { width(panelWidth).padTop(12.dp()) }) {
                background = tasksFrame
                pad(10.dp(), 12.dp(), 10.dp(), 12.dp())
                defaults().left().growX()

                visLabel(bundle.get("tutorial.tasks"), textColor = MUTED, font = game.mediumFont)
                row()

                step.tasks.forEachIndexed { i, task ->
                    val done = tutorial.isDone(i)
                    val text = bundle.format("tutorial.task.${task.id}", tutorial.progress(i), task.target)
                    visLabel(
                        (if (done) "+ " else "- ") + text,
                        textColor = if (done) DONE else STYLE_BEIGE
                    ) { padTop(4.dp()) }.setWrap(true)
                    row()
                }
            }
            tasks.row()
        }

        lateinit var next: VisTextButton
        next = tasks.visTextButton(
            bundle.get(if (tutorial.isLastStep) "tutorial.finish" else "tutorial.next"),
            onClick = {
                if (next.isDisabled) return@visTextButton
                if (tutorial.isLastStep) onFinish() else onNext()
            }
        ) { padTop(12.dp()).width(panelWidth * 0.5f).expandX().right() }
        next.isDisabled = !tutorial.isStepDone
        if (next.isDisabled) next.hint(bundle.get("tutorial.next.locked"))

        tasks.invalidateHierarchy()
    }

    /**
     * Невидимый актёр поверх всех окон сцены: нажатие, наведение и колесо над панелью он
     * отдаёт панели, если над этим местом нет окна.
     *
     * Диалоги редактора модальные: модальное окно забирает себе любое нажатие мимо себя,
     * и панель, лежащая под ним, не прокручивалась, пока окно открыто. Делать окна
     * немодальными нельзя — клик мимо окна попал бы в поле и изменил геном. Мост же
     * пропускает только то, что над самой панелью, а то, что над окном, оставляет окну.
     *
     * Цена — один обход детей корня сцены на событие ввода (их единицы), рисовать нечего.
     */
    private inner class InputBridge : Actor() {
        private val point = Vector2()

        override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
            val stage = stage ?: return null
            // Мост лежит в корне сцены без сдвига: x, y — координаты сцены.
            for (child in stage.root.children) {
                if (child is Window && child.isVisible &&
                    x >= child.x && x < child.x + child.width && y >= child.y && y < child.y + child.height
                ) return null
            }
            if (root.stage == null) return null
            root.stageToLocalCoordinates(point.set(x, y))
            return root.hit(point.x, point.y, touchable)
        }
    }

    private companion object {
        val BACKGROUND = Color(0.08f, 0.09f, 0.10f, 0.88f)
        val MUTED = Color(0.65f, 0.62f, 0.56f, 1f)
        val DONE = Color(0.55f, 0.85f, 0.45f, 1f)

        /** Рамка заданий. Одна на процесс, как фон всплывающих подсказок (hoverPopup). */
        val tasksFrame: NinePatchDrawable by lazy {
            makeStyledNP(
                fill = Color(0.12f, 0.13f, 0.14f, 1f),
                border = Color(STYLE_BEIGE).also { it.a = 0.6f },
                textures = mutableListOf()
            )
        }
    }
}
