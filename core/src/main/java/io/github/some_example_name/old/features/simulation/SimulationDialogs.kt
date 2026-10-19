package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.closeOnClickOutside
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledSlider
import io.github.some_example_name.old.core.ui.renderModeSelectBox
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.render.RenderMode

/**
 * Окно «Меню» экрана симуляции: всё, что раньше висело над миром рядом кнопок.
 *
 * Как и все окна экрана, само ничего не меняет: каждое действие уходит колбэком, а экран
 * превращает его в команду. Показывает то, что ему передали при открытии.
 *
 * [onSave] и [onSaveAndExit] — только у обычного мира: тест генома временный, из него
 * возвращаются в редактор, и [exitLabel] у него — «Назад в редактор».
 */
class SimulationMenuDialog(
    game: MyGame,
    bundle: I18NBundle,
    renderMode: RenderMode,
    targetUps: Int,
    maxSpeed: Boolean,
    exitLabel: String,
    onTargetUps: (Int) -> Unit,
    onMaxSpeed: (Boolean) -> Unit,
    onRenderMode: (RenderMode) -> Unit,
    onRestart: () -> Unit,
    onSettings: () -> Unit,
    onSave: (() -> Unit)?,
    onSaveAndExit: (() -> Unit)?,
    onExit: () -> Unit
) : VisDialog(bundle.get("button.menu")) {

    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        isMovable = true
        setupTitleSize(game)

        val density = Gdx.graphics.density
        val pad = 8f * density

        // --- Скорость: та же, что была в окне «Ускорить» ---
        val upsLabel = VisLabel(bundle.format("label.targetUps", targetUps))
        game.applyCustomFontMedium(upsLabel)
        val upsSlider = makeStyledSlider(1f, 1000f, 1f, false, textures).apply {
            value = targetUps.toFloat()
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor?) {
                    onTargetUps(value.toInt())
                    upsLabel.setText(bundle.format("label.targetUps", value.toInt()))
                }
            })
        }
        val maxSpeedToggle = makeStyledButton(bundle.get("button.maxSpeed"), game, textures, toggle = true).apply {
            isChecked = maxSpeed
            onChange { onMaxSpeed(isChecked) }
        }

        // --- Шейдер мира ---
        val shaderLabel = VisLabel(bundle.get("label.renderMode"))
        game.applyCustomFontMedium(shaderLabel)
        val shaderSelect = renderModeSelectBox(selected = renderMode, onSelect = onRenderMode)

        contentTable.pad(pad)
        contentTable.defaults().growX().padBottom(pad)
        contentTable.add(upsLabel).left().row()
        contentTable.add(upsSlider).minWidth(280f * density).row()
        contentTable.add(maxSpeedToggle).row()

        val shaderRow = VisTable()
        shaderRow.add(shaderLabel).left().padRight(pad)
        shaderRow.add(shaderSelect).growX()
        contentTable.add(shaderRow).padBottom(pad * 2).row()

        // --- Действия: окно закрывается до действия, чтобы не висело над новым экраном ---
        fun action(key: String, label: String = bundle.get(key), block: () -> Unit) {
            val button = makeStyledButton(label, game, textures).onChange { fadeOut(); block() }
            contentTable.add(button).row()
        }
        action("button.restart", block = onRestart)
        action("button.settings", block = onSettings)
        if (onSave != null) action("button.save", block = onSave)
        if (onSaveAndExit != null) action("button.saveAndExit", block = onSaveAndExit)
        action("button.exit", label = exitLabel, block = onExit)

        closeOnEscape()
        pack()
        centerWindow()
    }

    /** Окно ушло со сцены (закрыли, выбрали) — рамки кнопок больше не рисуются. */
    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }

}

/**
 * Выбор инструмента — что делает касание мира. Всплывает под кнопкой инструмента, касание
 * мимо закрывает. Выбранный отмечен.
 */
class SimulationToolDialog(
    game: MyGame,
    bundle: I18NBundle,
    icons: HudIcons,
    current: SimulationTool,
    onSelect: (SimulationTool) -> Unit
) : VisDialog(bundle.get("tool.title")) {

    private val textures = mutableListOf<Texture>()

    init {
        isModal = true
        isMovable = false
        setupTitleSize(game)
        closeOnClickOutside()

        val density = Gdx.graphics.density
        contentTable.pad(8f * density)
        contentTable.defaults().growX().padBottom(6f * density)

        for (tool in SimulationTool.entries) {
            val button = makeStyledButton(bundle.get(tool.labelKey), game, textures, toggle = true)
            button.isChecked = tool == current
            // Значок слева от подписи: в кнопке уже есть ячейка с надписью.
            val icon = Image(TextureRegionDrawable(icons.of(tool)).tint(STYLE_BEIGE)).apply { setScaling(Scaling.fit) }
            button.clearChildren()
            // Значок с высоту строки: шрифт кнопок зависит от экрана, а dp — нет.
            button.add(icon).size(maxOf(ICON_DP * density, button.label.prefHeight * 1.1f)).padRight(10f * density)
            button.add(button.label).left().growX()
            button.label.setAlignment(Align.left)
            button.onChange {
                fadeOut()
                if (tool != current) onSelect(tool)
            }
            contentTable.add(button).row()
        }
        pack()
    }

    /** Под кнопкой [anchor], правым краем по ней; не за край экрана. */
    fun showUnder(stage: Stage, anchor: Actor) {
        show(stage, null)
        val corner = anchor.localToStageCoordinates(Vector2(anchor.width, 0f))
        val gap = 6f * Gdx.graphics.density
        val x = (corner.x - width).coerceIn(0f, (stage.width - width).coerceAtLeast(0f))
        val y = (corner.y - gap - height).coerceIn(0f, (stage.height - height).coerceAtLeast(0f))
        setPosition(x, y)
    }

    /** Окно ушло со сцены (закрыли, выбрали) — рамки кнопок больше не рисуются. */
    override fun remove(): Boolean {
        textures.forEach { it.dispose() }
        textures.clear()
        return super.remove()
    }

    private companion object {
        const val ICON_DP = 28f
    }
}

/** Ключ подписи инструмента в бандле. */
val SimulationTool.labelKey: String
    get() = when (this) {
        SimulationTool.SPAWN_CELL -> "tool.spawnCell"
        SimulationTool.SPAWN_FOOD -> "tool.spawnFood"
        SimulationTool.KILL_CELL -> "tool.killCell"
    }

/** Изменение кнопки, а не click: так срабатывает и клавиатура, и переключатель. */
private fun VisTextButton.onChange(action: () -> Unit): VisTextButton = apply {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor?) = action()
    })
}
