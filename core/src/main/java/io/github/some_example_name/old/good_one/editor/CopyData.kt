package io.github.some_example_name.old.good_one.editor

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.ui.UiResult
import kotlin.math.sqrt

data class CellCopy(
    var x: Float,
    var y: Float,
    val index: Int,
    var cellTypeId: Int,
    var colorCore: Color,
    var isSelected: Boolean,
    val isAdded: Boolean,
    var isAlreadyDividedInGenomeStage: Boolean,
    var childCellId: String? = null,
    var parentCellId: String?,
    val physicalLink: HashMap<String, LinkDataCopy>,
    val linkToOriginalCell: Cell? = null,
    val debugCurrentNeuronImpulseImport: Float? = null,
    var angleDirected: Float? = null,
    var startDirectionId: String? = null,
    var lengthDirected: Float? = 35f,
    var activationFuncType: Int? = 0,
    var a: Float? = 1f,
    var b: Float? = 0f,
    var c: Float? = 0f,
    var addedCellSettings: UiResult?,
) {
    fun distanceTo(px: Float, py: Float): Float {
        val dx = px - x
        val dy = py - y
        val sqrt = dx * dx + dy * dy
        if (sqrt <= 0) return 0f
        val result = sqrt(sqrt)
        if (result.isNaN()) throw Exception("TODO потом убрать")
        return result
    }
}

data class LinkDataCopy(
    var connectId: Int,
    var isNeuronal: Boolean,
    var directedNeuronLink: Int?
)

data class LinkCopy(
    val c1: Cell,
    val c2: Cell
)
