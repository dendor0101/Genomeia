package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Disposable
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.visLabel

/** Как и окно отчёта, эта подсказка не меняет игру и не входит в журнал команд. */
internal class FeedbackHint(onClose: () -> Unit) : Group(), Disposable {
    private val textures = mutableListOf<Texture>()
    private val density = Gdx.graphics.density
    private val card = VisTable()
    private val arrow = Image(makeArrow()).apply { touchable = Touchable.disabled }
    private val close = makeStyledButton(bundle.get("bug.hint.close"), game, textures)
    private var layoutWidth = -1f
    private var layoutHeight = -1f

    init {
        touchable = Touchable.childrenOnly
        card.touchable = Touchable.childrenOnly
        card.background = makeStyledNP(Color(0.09f, 0.11f, 0.13f, 0.97f), STYLE_BEIGE, textures)
        card.pad(18f * density)
        card.visLabel(bundle.get("bug.hint.title"), font = game.mediumFont,
            cellInit = { growX().padBottom(10f * density) }).apply {
            setWrap(true)
            touchable = Touchable.disabled
        }
        card.row()
        card.visLabel(bundle.get("bug.hint.body"), textColor = Color(0.92f, 0.91f, 0.87f, 1f),
            font = game.mediumFont, cellInit = { growX().padBottom(14f * density) }).apply {
            setWrap(true)
            touchable = Touchable.disabled
        }
        card.row()
        close.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = onClose()
        })
        card.add(close).right().minHeight(44f * density)
        addActor(arrow)
        addActor(card)
        color.a = 0f
        addAction(Actions.fadeIn(0.4f))
    }

    // Даже фон карточки пропускает ввод. Никаких Window.show, keyboardFocus или scrollFocus.
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
        val hit = super.hit(x, y, touchable) ?: return null
        return if (hit === close || hit.isDescendantOf(close)) hit else null
    }

    override fun act(delta: Float) {
        super.act(delta)
        val stage = stage ?: return
        if (layoutWidth == stage.width && layoutHeight == stage.height) return
        layoutWidth = stage.width
        layoutHeight = stage.height
        val margin = 12f * density
        card.width = minOf(420f * density, (stage.width - 2f * margin).coerceAtLeast(1f))
        card.invalidateHierarchy()
        card.validate()
        card.height = card.prefHeight
        card.validate()
        // Центр жука — в 30 dp от правого края, низ кнопки — в 52 dp от верха.
        arrow.setBounds(stage.width - 126f * density, stage.height - 116f * density,
            96f * density, 56f * density)
        card.setPosition(stage.width - card.width - margin,
            (arrow.y - card.height - 6f * density).coerceAtLeast(margin))
    }

    private fun makeArrow(): Texture {
        val p = Pixmap(192, 112, Pixmap.Format.RGBA8888)
        p.setColor(STYLE_BEIGE)
        // Плавная квадратичная кривая; текстура создаётся один раз, не в кадре.
        for (i in 0..200) {
            val t = i / 200f
            val u = 1f - t
            val x = u * u * 12f + 2f * u * t * 166f + t * t * 184f
            val y = u * u * 104f + 2f * u * t * 110f + t * t * 8f
            p.fillCircle(x.toInt(), y.toInt(), 3)
        }
        for (i in 0..30) {
            val t = i / 30f
            p.fillCircle((184 - 23 * t).toInt(), (8 + 19 * t).toInt(), 3)
            p.fillCircle((184 + 3 * t).toInt(), (8 + 29 * t).toInt(), 3)
        }
        return Texture(p).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            textures += it
            p.dispose()
        }
    }

    override fun dispose() {
        remove()
        textures.forEach { it.dispose() }
        textures.clear()
    }
}
