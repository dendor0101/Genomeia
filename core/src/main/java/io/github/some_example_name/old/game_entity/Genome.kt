package io.github.some_example_name.old.game_entity

import com.badlogic.gdx.graphics.Color


data class Genome(
    val massThreshold: Float = 1f,
    val angleCellDivision: Float = 40f,
    val isLeaveJoin: Boolean = false,
    val joinLength: Float = 1f,
    val typeHeir1: Cell? = null,
    val typeHeir2: Cell? = null,
    val color: Color = Color.PINK,
    val geneName: String = "dendor_studio"
)
