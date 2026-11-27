package io.github.some_example_name.old.features.levels.editor

import kotlinx.serialization.Serializable

/**
 * Организм, который уровень ставит в мир сам.
 *
 * [genomeName] — имя из GenomeCatalog, с префиксом bundled/ у встроенных. Оно хранится
 * в уровне, а не индекс: порядок геномов в папке игрока может измениться.
 */
@Serializable
data class ScriptedOrganism(
    val genomeName: String,
    val x: Float,
    val y: Float,
    /** Угол в градусах: в редакторе так понятнее, в радианы переводит спавн. */
    val angleDegrees: Float = 0f
)
