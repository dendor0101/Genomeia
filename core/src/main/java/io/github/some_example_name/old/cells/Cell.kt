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


    val particleEntity get() = DIContainer.particleEntity
    val cellEntity get() = DIContainer.cellEntity
    val linkEntity get() = DIContainer.linkEntity
    val simEntity get() = DIContainer.simulationData
    val substrateSettings get() = DIContainer.substrateSettings
    val worldCommandsManager get() = DIContainer.worldCommandsManager
    val organEntity get() = DIContainer.organEntity
    val genomeManager get() = DIContainer.genomeManager
    val pheromoneEntity get() = DIContainer.pheromoneEntity

    val gridManager get() = DIContainer.gridManager
    val organManager get() = DIContainer.organManager

    open fun onStart(cellIndex: Int, threadId: Int) {

    }

    open fun doOnTick(cellIndex: Int, threadId: Int) {

    }

    open fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) {

    }

    open fun onDie(cellIndex: Int) {

    }

    open fun onLinkDeleted(cellIndex: Int, linkIndex: Int, threadId: Int) {

    }

}
