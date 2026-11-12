package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import io.github.some_example_name.old.core.ui.closeOnClickOutside
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.game.applyCustomFont
import io.github.some_example_name.old.game.MyGame

/** Состояние галочек отладочного режима на момент открытия окна. */
class DebugOptions(val showBoundary: Boolean, val showLinks: Boolean, val fillAreas: Boolean, val noTail: Boolean, val noDeepKill: Boolean)

/**
 * Галочки отладочного режима — окном под своей кнопкой рядом с фильтром, чтобы не занимали
 * верхнюю полосу. Как и все окна экрана, само ничего не меняет: каждое действие отдаёт
 * наружу, экран превращает его в команду. Клик мимо закрывает.
 */
class DebugOptionsDialog(
    game: MyGame,
    bundle: I18NBundle,
    state: DebugOptions,
    onBoundary: (Boolean) -> Unit,
    onLinks: (Boolean) -> Unit,
    onFillAreas: (Boolean) -> Unit,
    onPhysics: (noTail: Boolean, noDeepKill: Boolean) -> Unit
) : VisDialog(bundle.get("debugOptions")) {

    init {
        isModal = true
        isMovable = false
        setupTitleSize(game)
        closeOnClickOutside()

        val density = Gdx.graphics.density
        contentTable.pad(8f * density)
        contentTable.defaults().left().padBottom(6f * density)

        fun box(key: String, checked: Boolean, onChange: (VisCheckBox) -> Unit): VisCheckBox {
            val box = VisCheckBox(bundle.get(key))
            game.applyCustomFont(box)
            box.isChecked = checked
            box.hint(bundle.get("$key.hint"))
            box.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = onChange(box)
            })
            contentTable.add(box).row()
            return box
        }

        box("debugBoundary", state.showBoundary) { onBoundary(it.isChecked) }
        box("debugLinks", state.showLinks) { onLinks(it.isChecked) }
        box("debugFillAreas", state.fillAreas) { onFillAreas(it.isChecked) }
        // Обе галочки физики идут одной командой мира, сразу с обоими значениями.
        lateinit var noDeepKill: VisCheckBox
        val noTail = box("debugNoTail", state.noTail) { onPhysics(it.isChecked, noDeepKill.isChecked) }
        noDeepKill = box("debugNoDeepKill", state.noDeepKill) { onPhysics(noTail.isChecked, it.isChecked) }
        pack()
    }

    /** Под кнопкой [anchor], левым краем по ней; не за край экрана. */
    fun showUnder(stage: Stage, anchor: Actor) {
        show(stage, null)
        val corner = anchor.localToStageCoordinates(Vector2(0f, 0f))
        val gap = 6f * Gdx.graphics.density
        setPosition(
            corner.x.coerceIn(0f, (stage.width - width).coerceAtLeast(0f)),
            (corner.y - gap - height).coerceIn(0f, (stage.height - height).coerceAtLeast(0f))
        )
    }
}
