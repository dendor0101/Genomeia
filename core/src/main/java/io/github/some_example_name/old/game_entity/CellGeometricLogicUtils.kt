package io.github.some_example_name.old.game_entity

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import javax.swing.text.Position
import kotlin.math.cos
import kotlin.math.sin

fun calculateNewCellPosition(
    position: Vector2,
    angleDegrees: Float,
    offset: Float
): Pair<Float, Float> {
    val angleRadians = Math.toRadians(angleDegrees.toDouble())
    val xOffset = position.x + offset * cos(angleRadians).toFloat()
    val yOffset = position.y + offset * sin(angleRadians).toFloat()
    return Pair(xOffset, yOffset)
}

fun calculateImpulse(angleDegrees: Float, momentumForce: Float): Vector2 {
    val angleRadians = MathUtils.degreesToRadians * angleDegrees
    return Vector2(
        momentumForce * MathUtils.cos(angleRadians),
        momentumForce * MathUtils.sin(angleRadians)
    )
}

fun createNewCell(cell: Cell, x: Float, y: Float, impulse: Vector2, cellCounter: Int): Cell {
    return Cell().apply {
        id = cellCounter
        this.x = x
        this.y = y
        this.genome = cell.genome
        this.startImpulse = impulse
    }
}
