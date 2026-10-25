package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.*
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.WorldResizable
import java.util.BitSet

/** Список клеток одного контейнера: экземпляры по реестру [CellTypes], привязанные к его DI. */
class CellListBuilder: WorldResizable {

    lateinit var context: DIContext

    val instances: CellTypeList = CellTypes.createAll()

    val zygote: Zygote = instances.filterIsInstance<Zygote>().single()

    private val eyes = instances.filterIsInstance<Eye>()

    override fun resize() {
        eyes.forEach { it.bindBuffers() }
    }

    fun bindToDIContext(context: DIContext) {
        this.context = context
        instances.forEach { it.context = context }
        eyes.forEach { it.bindBuffers() }
    }

    private fun Eye.bindBuffers() {
        visitedBits = Array(this@CellListBuilder.context.threadCount) { BitSet(this@CellListBuilder.context.gridManager.gridSize) }
        checkedObjectListId = Array(this@CellListBuilder.context.threadCount) { IntArray(16) { -1 } }
    }
}
