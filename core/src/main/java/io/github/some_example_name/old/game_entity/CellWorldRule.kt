package io.github.some_example_name.old.game_entity

fun sunIntensity(y: Float) = if (y < 7) 0f else {
    (y * 2 - 14) / 14
}

const val LINEAR_DAMPING = 0.95f
