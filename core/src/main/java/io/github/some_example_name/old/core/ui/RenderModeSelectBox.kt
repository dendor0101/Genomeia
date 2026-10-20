package io.github.some_example_name.old.core.ui

import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode

/**
 * Выпадающий список режима рендера — один на все экраны, где режим можно выбрать.
 *
 * Пункты — в порядке [RenderMode], подписи — из бандла по имени режима
 * (renderMode.realistic, renderMode.debug, renderMode.nostalgic). Где хранить выбор,
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

/**
 * Выпадающий список фильтра отладочного режима (оригинал, энергия, нейросигнал) — сверху
 * по центру экрана симуляции, пока выбран отладочный режим. Подписи — debugFilter.* из
 * бандла, пункты — в порядке [DebugFilter].
 */
fun debugFilterSelectBox(
    selected: DebugFilter,
    onSelect: (DebugFilter) -> Unit
): StyledSelectBox<String> {
    val filters = DebugFilter.entries
    return styledSelectBox(
        items = filters
            .map { DIGameGlobalContainer.bundle.get("debugFilter." + it.name.lowercase()) }
            .toTypedArray(),
        selectedIndex = filters.indexOf(selected),
        onChange = { _, index -> onSelect(filters[index]) }
    )
}
