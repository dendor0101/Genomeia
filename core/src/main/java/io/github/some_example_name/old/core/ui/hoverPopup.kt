package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.features.settings.GlobalSettings
import kotlin.math.min

// ==================== MINI DIALOG / HOVER POPUP (улучшенная версия) ====================

private var _popupBackground: NinePatchDrawable? = null

private fun getPopupBackground(): NinePatchDrawable {
    if (_popupBackground == null) {
        _popupBackground = makeStyledNP(
            fill = Color(0.14f, 0.13f, 0.11f, 0.96f),
            border = Color(STYLE_BEIGE).also { it.a = 0.65f },
            textures = mutableListOf()
        )
    }
    return _popupBackground!!
}

/** Внутренняя функция позиционирования (над указателем или под ним + центрирование) */
private fun positionPopupSmart(
    popup: VisTable,
    pointerX: Float,
    pointerY: Float,
    stage: Stage
) {
    val pw = popup.width
    val ph = popup.height
    val gap = 10f.dp()
    val margin = 8f.dp()
    val stageW = stage.width
    val stageH = stage.height

    // Пробуем разместить НАД указателем (по центру)
    var x = pointerX - pw / 2f
    var y = pointerY + gap

    // Если не влезает сверху — размещаем ПОД указателем
    if (y + ph > stageH - margin) {
        y = pointerY - ph - gap
    }

    // Не вылезаем за края по X
    x = x.coerceIn(margin, stageW - pw - margin)

    popup.setPosition(x, y)
}

/**
 * Показать мини-диалог в любой точке экрана (для НЕ-UI элементов).
 * Ты полностью контролируешь появление и исчезновение.
 *
 * Пример использования с игровым объектом:
 * val screenPos = camera.project(worldPos)
 * val popup = showMiniDialog(stage, screenPos.x, screenPos.y) {
 *     visLabel("HP: 87")
 *     row()
 *     visLabel("Уровень: 12")
 * }
 * // позже
 * hideMiniDialog(popup)
 */
fun showMiniDialog(
    stage: Stage,
    stageX: Float,
    stageY: Float,
    init: VisTable.() -> Unit
): VisTable {
    val content = VisTable(true).apply {
        background = getPopupBackground()
        defaults().pad(12f.dp(), 16f.dp(), 12f.dp(), 16f.dp())
        init()
    }

    content.pack()                    // ← важно! убирает моргание и даёт правильный размер
    stage.addActor(content)
    positionPopupSmart(content, stageX, stageY, stage)

    return content
}

fun hideMiniDialog(dialog: VisTable) {
    dialog.remove()
}

/** Сколько держать палец на элементе, чтобы всплыла подсказка: обычный тап её не мигает. */
private const val LONG_PRESS_SECONDS = 0.4f

private val isTouchScreen: Boolean
    get() = Gdx.app.type == Application.ApplicationType.Android ||
        Gdx.app.type == Application.ApplicationType.iOS

/**
 * Всплывающая подсказка для UI-элемента: мышью — при наведении, на сенсорном экране —
 * пока палец зажат на элементе.
 *
 * Подсказка не принимает ввод: она не перехватывает наведение у элемента и не мешает
 * модальному диалогу, поверх которого показана.
 */
fun Actor.hoverPopup(
    /** Спрашивается при каждом показе: флаг можно переключить, не пересобирая экран. */
    isEnabled: () -> Boolean = { true },
    init: VisTable.() -> Unit
): Actor {
    val target = this
    var tooltip: VisTable? = null
    var pendingShow: Action? = null

    fun cancelPendingShow() {
        pendingShow?.let { target.removeAction(it) }
        pendingShow = null
    }

    fun hidePopup() {
        cancelPendingShow()
        tooltip?.remove()
        tooltip = null
    }

    fun showPopup(stageX: Float, stageY: Float) {
        if (tooltip != null || !isEnabled()) return
        val stg = target.stage ?: return

        val content = object : VisTable(true) {
            // Подсказка лежит на сцене отдельно от элемента. Если диалог закрыли, пока она
            // видна, exit элементу уже не придёт — без этой проверки она бы так и висела.
            override fun act(delta: Float) {
                super.act(delta)
                if (target.stage == null) {
                    hidePopup()
                    return
                }
                // Окно по клику поднимает себя наверх (Window.toFront) и закрыло бы подсказку
                // до следующего появления. Если она уже сверху, toFront ничего не делает.
                toFront()
            }
        }.apply {
            background = getPopupBackground()
            defaults().pad(12f.dp(), 16f.dp(), 12f.dp(), 16f.dp())
            touchable = Touchable.disabled
            init()
        }

        tooltip = content
        stg.addActor(content)
        content.pack()
        positionPopupSmart(content, stageX, stageY, stg)
    }

    val listener = object : InputListener() {
        // Мышь без нажатия приходит с pointer == -1. Касания тоже шлют enter/exit, но
        // с pointer >= 0 — на сенсорном экране подсказку открывает только зажатие.
        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            if (pointer == -1 && !isTouchScreen) showPopup(event.stageX, event.stageY)
        }

        override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            if (pointer == -1 && !isTouchScreen) hidePopup()
        }

        override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
            val t = tooltip ?: return false
            val stg = target.stage ?: return false
            positionPopupSmart(t, event.stageX, event.stageY, stg)
            return false
        }

        // true нужен, чтобы получить touchUp и touchDragged. Всплытие события к родителям
        // это не останавливает, а другие слушатели элемента (у поля ввода — свой) его
        // всё равно получают.
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (!isTouchScreen) return false

            cancelPendingShow()
            val stageX = event.stageX
            val stageY = event.stageY
            val show = Actions.delay(LONG_PRESS_SECONDS, Actions.run {
                pendingShow = null
                showPopup(stageX, stageY)
            })
            pendingShow = show
            target.addAction(show)
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            // Палец ушёл с элемента — это уже не зажатие, а прокрутка или промах.
            if (target.hit(x, y, false) == null) hidePopup()
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            hidePopup()
        }
    }

    target.addListener(listener)
    return target
}

/** Ширина подсказки, после которой текст переносится на следующую строку. */
private const val HINT_MAX_WIDTH_DP = 420f

/**
 * Пояснение к элементу интерфейса — всплывает при наведении мыши или зажатии пальцем
 * (см. [hoverPopup]). Все такие подсказки включаются и выключаются одним флагом
 * GlobalSettings.SHOW_HINTS (настройки, вкладка «Графика»).
 *
 * Возвращает сам элемент, поэтому вешается прямо на результат DSL:
 * `visTextButton(...).hint(bundle.get("editor.hint.save"))`.
 */
fun <T : Actor> T.hint(text: String): T {
    hoverPopup(isEnabled = { GlobalSettings.SHOW_HINTS }) {
        val label = visLabel(text)
        val maxWidth = min(HINT_MAX_WIDTH_DP.dp(), (stage?.width ?: w) * 0.8f)
        if (label.prefWidth > maxWidth) {
            label.setWrap(true)
            getCell(label).width(maxWidth)
        }
    }
    return this
}
