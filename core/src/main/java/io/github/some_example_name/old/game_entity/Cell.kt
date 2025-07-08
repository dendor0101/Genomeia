package io.github.some_example_name.old.game_entity

import com.badlogic.gdx.math.Vector2
import java.awt.Color
import kotlin.random.Random

class Cell {
    var id: Int = -1
    var genome: Genome = Genome()
    var mass: Float = 0.5f
    var x: Float = 0f
    var y: Float = 0f
    var parentCell: Cell? = null
    var childCell1: Cell? = null
    var childCell2: Cell? = null
    var isAlive: Boolean = true
    var startImpulse: Vector2? = null

    var survivalTime: Float = 0f

    inline fun worldTick(deltaTime: Float, divide: () -> Unit, died: () -> Unit) {
        survivalTime += deltaTime
        eat()
        loseEnergy()
        division(divide)
        death(died)
//        mutate()
    }

    fun eat() {
        if (isAlive) {
            mass += sunIntensity(y) * TICK_ENERGY_EAT_THRESHOLD
        }
    }

    fun loseEnergy() {
        if (isAlive) {
            mass -= LOSE_ENERGY_THRESHOLD
        }
    }

    inline fun division(divide: () -> Unit) {
//        println("divide $mass ${genome.massThreshold}")
        if (mass > genome.massThreshold && isAlive) {
            divide()
            isAlive = false
//            println("divide")
        }
    }

    inline fun death(died: () -> Unit) {
        if (mass < DEATH_THRESHOLD) {
            isAlive = false
            died()
        }
    }

    fun mutate() {
        val randomValue = Random.nextFloat()
        if (randomValue < 0.001f) {
            val shift = Random.nextFloat()
            genome = genome.copy(
                massThreshold = genome.massThreshold + (Random.nextFloat() - 0.5f) / 5,
                angleCellDivision = Random.nextFloat() * 180,
                isLeaveJoin = if (genome.isLeaveJoin) !(shift < 0.01f) else shift < 0.01f,
                joinLength = genome.joinLength + (Random.nextFloat() - 0.5f) / 5
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cell

        return id == other.id
    }

    override fun hashCode(): Int {
        return id
    }


    companion object {
        const val DEATH_THRESHOLD = 0.2f
        const val LOSE_ENERGY_THRESHOLD = 0.004f
        const val TICK_ENERGY_EAT_THRESHOLD = 0.005f
    }
}
