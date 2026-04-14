package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import kotlin.reflect.KClass

sealed class Cell(
    val defaultColor: Color,
    val cellTypeId: Int,
    val isDirected: Boolean = false,
    val isNeural: Boolean = false,
    val maxEnergy: Float = 5f,
    val isNeuronTransportable: Boolean = true,
    val effectOnContact: Boolean = false,
    val isCollidable: Boolean = true,
    val descriptionBundle: String? = null,
    val specialData: KClass<out SpecialModData> = Plug::class,
) {
    val name: String = this::class.simpleName ?: "UnknownCell"
    val description = descriptionBundle?.let { bundle.get(descriptionBundle) } ?: ""
    val doesItHasSpecialModData = specialData != Plug::class

    lateinit var context: DIContext

    //TODO передавать Di конеткст в методы, onStart, doOnTick, onContact и тд
    val particleEntity get() = context.particleEntity
    val cellEntity get() = context.cellEntity
    val linkEntity get() = context.linkEntity
    val substancesEntity get() = context.substancesEntity
    val specialEntity get() = context.specialEntity
    val substrateSettings get() = context.substrateSettings
    val worldCommandsManager get() = context.worldCommandsManager
    val organEntity get() = context.organEntity
    val genomeManager get() = context.genomeManager
    val pheromoneEntity get() = context.pheromoneEntity

    val gridManager get() = context.gridManager
    val organManager get() = context.organManager

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

interface SpecialModData

object Plug: SpecialModData
