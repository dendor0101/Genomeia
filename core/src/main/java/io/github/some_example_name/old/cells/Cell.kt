package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.DIContainer

sealed class Cell(
    val defaultColor: Color,
    val cellTypeId: Int,
    val isDirected: Boolean = false,
    val isNeural: Boolean = false,
    val maxEnergy: Float = 5f,
    val isNeuronTransportable: Boolean = true,
    val effectOnContact: Boolean = false,
    val isCollidable: Boolean = true,
    val descriptionBundle: String? = null
) {
    val name: String = this::class.simpleName ?: "UnknownCell"
    val description = descriptionBundle?.let { DIContainer.bundle.get(descriptionBundle) } ?: ""


    val cellEntity get() = DIContainer.cellEntity
    val linkEntity get() = DIContainer.linkEntity
    val simEntity get() = DIContainer.simEntity
    val substrateSettings get() = DIContainer.substrateSettings
    val commandsManager get() = DIContainer.worldCommandsManager
    val organEntity get() = DIContainer.organEntity
    val genomeManager get() = DIContainer.genomeManager
    val pheromoneEntity get() = DIContainer.pheromoneEntity

    open fun onStart(index: Int, threadId: Int) {

    }

    open fun doOnTick(index: Int, threadId: Int) {

    }

    open fun onContact(index: Int, indexCollided: Int, threadId: Int) {

    }

    open fun onDie(index: Int, threadId: Int) {

    }

}
