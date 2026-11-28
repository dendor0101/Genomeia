package io.github.some_example_name.old.core.ui

import io.github.some_example_name.old.core.DIGameGlobalContainer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode

/**
 * Выпадающий список режима рендера — один на все экраны, где режим можно выбрать.
 *
 * Пункты — в порядке [RenderMode], подписи — из бандла по имени режима
 * (renderMode.debug, renderMode.nostalgic, renderMode.tissue_map). Где хранить выбор,
 * решает экран: у симуляции и редактора генома режимы свои и друг на друга не влияют.
 */
fun renderModeSelectBox(
    selected: RenderMode,
    onSelect: (RenderMode) -> Unit
): StyledSelectBox<String> {
    val modes = RenderMode.entries
    return styledSelectBox(
        items = modes
            .map { DIGameGlobalContainer.bundle.get("renderMode." + it.name.lowercase()) }
            .toTypedArray(),
        selectedIndex = modes.indexOf(selected),
        onChange = { _, index -> onSelect(modes[index]) }
    )
}

fun Stage.showDebugFilterDialog(selected: DebugFilter, onSelect: (DebugFilter) -> Unit) {
    val bundle = DIGameGlobalContainer.bundle
    val dialog = VisDialog(bundle.get("debugFilter.title"))
    dialog.setupTitleSize(DIGameGlobalContainer.game)
    val choices = VisTable()
    for (filter in DebugFilter.entries) {
        choices.visToggleButton(bundle.get("debugFilter." + filter.name.lowercase()), filter == selected, {
            onSelect(filter)
            dialog.fadeOut()
        }) { growX() }
        choices.row()
    }
    dialog.contentTable.add(choices).grow()
    dialog.closeOnEscape()
    dialog.show(this)
}
