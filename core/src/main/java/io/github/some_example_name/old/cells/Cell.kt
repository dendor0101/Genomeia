package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.reflect.KClass

sealed class Cell(
    val defaultColor: Color,
    val cellTypeId: Int,
    val textureName: String = "not_cell.png",
    val isDirected: Boolean = false,
    val isNeural: Boolean = false,
    val maxEnergy: Float = 5f,
    val isNeuronTransportable: Boolean = true,
    val doesNeedNeuralConnections: Boolean = false,
    val effectOnContact: Boolean = false,
    val isCollidable: Boolean = true,
    val descriptionBundle: String? = null,
    val specialData: KClass<out SpecialModData> = Plug::class,
    val defaultCellSettings: CellSettings = CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0.0005f,
    )
) {
    val name: String = this::class.simpleName ?: "UnknownCell"
    val description = descriptionBundle?.let { bundle.get(descriptionBundle) } ?: ""
    val doesItHasSpecialModData = specialData != Plug::class

    lateinit var context: DIContext

    //TODO передавать Di конеткст в методы, onStart, doOnTick, onContact и тд
    val particleEntity get() = context.particleEntity
    val cellEntity get() = context.cellEntity
    val linkEntity get() = context.linkEntity
    val neuralLinkEntity get() = context.neuralLinkEntity
    val substancesEntity get() = context.substancesEntity
    val specialEntity get() = context.specialEntity
    val worldCommandsManager get() = context.worldCommandsManager
    val organEntity get() = context.organEntity
    val genomeManager get() = context.genomeManager
    val pheromoneEntity get() = context.pheromoneEntity

    val gridManager get() = context.gridManager
    val organManager get() = context.organManager
    val pheromonesManager get() = context.pheromonesManager

    open fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int = -1) {

    }

    open fun doOnTick(cellIndex: Int, threadId: Int) {

    }

    open fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) {

    }

    open fun onDie(cellIndex: Int) {

    }

    open fun onLinkDeleted(cellIndex: Int, linkIndex: Int, threadId: Int) {

    }

    protected fun getNeuralLinks(cellIndex: Int): IntArrayList {
        return if (doesNeedNeuralConnections) {
            cellEntity.neuralConnections.get(cellIndex)
        } else throw Exception("doesNeedConnections = false")
    }

}

interface SpecialModData

object Plug: SpecialModData
