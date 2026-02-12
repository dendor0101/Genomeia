package io.github.some_example_name.old.substances

import io.github.some_example_name.old.good_one.utils.whiteColors
import io.github.some_example_name.old.good_one.utils.xCoordsCircleCells
import io.github.some_example_name.old.good_one.utils.yCoordsCircleCells
import io.github.some_example_name.old.good_one.utils.primitive_hash_map.OrderedIntPairMap
import kotlin.math.abs
import kotlin.random.Random

interface SubstancePlug {

    var cellLastId: Int
    var y: FloatArray
    var x: FloatArray
    var radius: FloatArray
    val substanceIdMap: OrderedIntPairMap
    var isNeedToMove: BooleanArray
    var amountInCell: IntArray

    fun addCell(x: Float, y: Float, vx: Float, vy: Float)
    fun updateCells()
    fun move(j: Int, cellId: Int): Boolean
    fun deleteSub(i: Int)
    fun sensor(findX: Float, findY: Float): Float
    fun suckIt(findX: Float, findY: Float): Boolean

    fun clear()
}

class SubstancePlugImpl: SubstancePlug {

    override var cellLastId = -1
    override var y: FloatArray = FloatArray(0)
    override var x: FloatArray = FloatArray(0)
    override var radius: FloatArray = FloatArray(0)
    override val substanceIdMap: OrderedIntPairMap = OrderedIntPairMap(0)
    override var isNeedToMove: BooleanArray = BooleanArray(0)
    override var amountInCell: IntArray = IntArray(0)


    override fun addCell(x: Float, y: Float, vx: Float, vy: Float) {

    }

    override fun updateCells() {

    }

    override fun move(j: Int, cellId: Int): Boolean {
        return false
    }

    override fun deleteSub(i: Int) {

    }

    override fun sensor(findX: Float, findY: Float): Float {
        return 0f
    }

    override fun suckIt(findX: Float, findY: Float): Boolean {
        return false
    }

    override fun clear() {

    }

}
//Экспериментальная // Experimental
//TODO с веществами полное дерьмо, это точно нужно будет переделать
//TODO with substances is complete crap, this will definitely need to be redone
class SubstanceManager: SubstancePlug {
    @Volatile
    var cellMaxAmount = 5000

    @Volatile
    override var cellLastId = -1
    override val substanceIdMap = OrderedIntPairMap(300_000)

    private var updateCellListId = -1
    private var updateCellList = IntArray(cellMaxAmount) { -1 }

    override var amountInCell = IntArray(cellMaxAmount) { 0 }
    override var x = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    override var y = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    private var endX = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    private var endY = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    private var vx = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    private var vy = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    override var radius = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 5f }
    private var colorR = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    private var colorG = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    private var colorB = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    override var isNeedToMove = BooleanArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { false }

    override fun addCell(x: Float, y: Float, vx: Float, vy: Float) {
        if (cellLastId + 1 >= cellMaxAmount * 0.9) {
            resize((cellMaxAmount * 1.5).toInt())
        }

        val friction = 0.93f
        val totalDx = (vx * friction) / (1f - friction)
        val totalDy = (vy * friction) / (1f - friction)


        val cellX = ((x + totalDx) / SUB_CELL_SIZE).toInt()
        val cellY = ((y + totalDy) / SUB_CELL_SIZE).toInt()
        var cellId = substanceIdMap.get(cellX, cellY)

        if (cellId == -1) {
            cellLastId++
            substanceIdMap.put(cellX, cellY, cellLastId)
            cellId = cellLastId
        }

        if (amountInCell[cellId] < MAX_SUB_CELL_COUNT) {
            val subId = cellId * MAX_SUB_CELL_COUNT + amountInCell[cellId]
            this.x[subId] = x
            this.y[subId] = y
            this.endX[subId] = x + totalDx
            this.endY[subId] = y + totalDy
            this.vx[subId] = vx
            this.vy[subId] = vy
            val color = whiteColors[0]
            colorR[subId] = color.r
            colorG[subId] = color.g
            colorB[subId] = color.b

            amountInCell[cellId] += 1
            if (vx != 0f || vy != 0f) {
                updateCellListId++
                updateCellList[updateCellListId] = cellId
                isNeedToMove[subId] = true
            } else {
                isNeedToMove[subId] = false
            }
        } else {
            addCell(
                x,
                y,
                vx + (Random.Default.nextFloat() - 0.5f) * 50f,
                vy + (Random.Default.nextFloat() - 0.5f) * 50f
            )
//            println("Warning! Too much subs")
        }
    }

    //Вызывать каждый такт // Call every beat
    override fun updateCells() {
        var isAllDeleted = true
        for (i in 0..<updateCellListId + 1) {
            val cellId = updateCellList[i]
            if (cellId == -1) {
                continue
            }
            isAllDeleted = false
            var isAllMoved = true
            for (j in cellId * MAX_SUB_CELL_COUNT..<cellId * MAX_SUB_CELL_COUNT + amountInCell[cellId]) {
                if (isNeedToMove[j]) {
                    isAllMoved = isAllMoved && move(j, cellId)
                }
            }
            if (isAllMoved && updateCellListId >= 0) {
                updateCellList[i] = -1
            }
        }
        if (isAllDeleted && updateCellListId != -1) {
            updateCellListId = -1
        }
    }

    override fun move(j: Int, cellId: Int): Boolean {
        vx[j] *= 0.93f
        vy[j] *= 0.93f
        x[j] += vx[j]
        y[j] += vy[j]
//        if (x[j] < 0 || x[j] > GridManager.Companion.WORLD_WIDTH || y[j] < 0 || y[j] > GridManager.Companion.WORLD_HEIGHT) {
//            deleteSub(j)
//            return false
//        }
        if (abs(vx[j]) < 0.01f && abs(vy[j]) < 0.01f) {
            vx[j] = 0f
            vy[j] = 0f
            x[j] = endX[j]
            y[j] = endY[j]
            isNeedToMove[j] = false
            return true
        }
        return false
    }

    override fun deleteSub(i: Int) {
        val cellId = i / MAX_SUB_CELL_COUNT
        val subCount = amountInCell[cellId]
        if (subCount <= 0) return // Нечего удалять

        val lastIndexInCell = cellId * MAX_SUB_CELL_COUNT + subCount - 1

        if (i != lastIndexInCell) {
            x[i] = x[lastIndexInCell]
            y[i] = y[lastIndexInCell]
            endX[i] = endX[lastIndexInCell]
            endY[i] = endY[lastIndexInCell]
            vx[i] = vx[lastIndexInCell]
            vy[i] = vy[lastIndexInCell]
            radius[i] = radius[lastIndexInCell]
            colorR[i] = colorR[lastIndexInCell]
            colorG[i] = colorG[lastIndexInCell]
            colorB[i] = colorB[lastIndexInCell]
            isNeedToMove[i] = isNeedToMove[lastIndexInCell]
        }

        x[lastIndexInCell] = 0f
        y[lastIndexInCell] = 0f
        endX[lastIndexInCell] = 0f
        endY[lastIndexInCell] = 0f
        vx[lastIndexInCell] = 0f
        vy[lastIndexInCell] = 0f
        radius[lastIndexInCell] = 5f
        colorR[lastIndexInCell] = 1f
        colorG[lastIndexInCell] = 1f
        colorB[lastIndexInCell] = 1f
        isNeedToMove[lastIndexInCell] = false

        amountInCell[cellId]--
    }

    override fun sensor(findX: Float, findY: Float): Float {
        var senseValue = 0f
        val cellX = (findX / SUB_CELL_SIZE).toInt()
        val cellY = (findY / SUB_CELL_SIZE).toInt()

        for (i in xCoordsCircleCells.indices) {
            val x = cellX + xCoordsCircleCells[i]
            val y = cellY + yCoordsCircleCells[i]
            val cellId = substanceIdMap.get(x, y)
            if (cellId != -1) {
                for (i in cellId * MAX_SUB_CELL_COUNT..<cellId * MAX_SUB_CELL_COUNT + amountInCell[cellId]) {
                    if (!isNeedToMove[i]) {
                        val dx = this.x[i] - findX
                        val dy = this.y[i] - findY
                        senseValue += 625 / (dx * dx + dy * dy)
                        if (senseValue > 1f) return 1f
                    }
                }
            }
        }
        return senseValue
    }

    private fun eat(cellId: Int): Boolean {
        if (cellId == -1) return false
        for (i in cellId * MAX_SUB_CELL_COUNT..<cellId * MAX_SUB_CELL_COUNT + amountInCell[cellId]) {
            if (!isNeedToMove[i]) {
                deleteSub(i)
                return true
            }
        }
        return false
    }

    override fun suckIt(findX: Float, findY: Float): Boolean {
        val cellX = (findX / SUB_CELL_SIZE).toInt()
        val cellY = (findY / SUB_CELL_SIZE).toInt()
        eat(substanceIdMap.get(cellX, cellY + 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX, cellY - 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX + 1, cellY)).let { if (it) return true }
        eat(substanceIdMap.get(cellX - 1, cellY)).let { if (it) return true }
        eat(substanceIdMap.get(cellX + 1, cellY + 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX - 1, cellY + 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX + 1, cellY - 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX - 1, cellY - 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX, cellY)).let { if (it) return true }
        return false
    }

    override fun clear() {
        substanceIdMap.clear()
        cellLastId = -1
        updateCellListId = -1
        updateCellList.fill(-1)
        amountInCell.fill(0)
        x.fill(0f)
        y.fill(0f)
        endX.fill(0f)
        endY.fill(0f)
        vx.fill(0f)
        vy.fill(0f)
        radius.fill(5f)
        colorR.fill(1f)
        colorG.fill(1f)
        colorB.fill(1f)
        isNeedToMove.fill(false)
    }

    private fun resize(newMaxAmount: Int) {
        if (newMaxAmount <= cellMaxAmount) return

        val oldMaxAmount = cellMaxAmount
        cellMaxAmount = newMaxAmount

        // Resize per-cell arrays
        val newAmountInCell = IntArray(newMaxAmount) { if (it < oldMaxAmount) amountInCell[it] else 0 }
        amountInCell = newAmountInCell

        val newUpdateCellList = IntArray(newMaxAmount) { if (it < oldMaxAmount) updateCellList[it] else -1 }
        updateCellList = newUpdateCellList

        // Resize per-sub-cell arrays
        val oldSubSize = oldMaxAmount * MAX_SUB_CELL_COUNT
        val newSubSize = newMaxAmount * MAX_SUB_CELL_COUNT

        val newX = FloatArray(newSubSize) { if (it < oldSubSize) x[it] else 0f }
        x = newX

        val newY = FloatArray(newSubSize) { if (it < oldSubSize) y[it] else 0f }
        y = newY

        val newEndX = FloatArray(newSubSize) { if (it < oldSubSize) endX[it] else 0f }
        endX = newEndX

        val newEndY = FloatArray(newSubSize) { if (it < oldSubSize) endY[it] else 0f }
        endY = newEndY

        val newVx = FloatArray(newSubSize) { if (it < oldSubSize) vx[it] else 0f }
        vx = newVx

        val newVy = FloatArray(newSubSize) { if (it < oldSubSize) vy[it] else 0f }
        vy = newVy

        val newRadius = FloatArray(newSubSize) { if (it < oldSubSize) radius[it] else 5f }
        radius = newRadius

        val newColorR = FloatArray(newSubSize) { if (it < oldSubSize) colorR[it] else 1f }
        colorR = newColorR

        val newColorG = FloatArray(newSubSize) { if (it < oldSubSize) colorG[it] else 1f }
        colorG = newColorG

        val newColorB = FloatArray(newSubSize) { if (it < oldSubSize) colorB[it] else 1f }
        colorB = newColorB

        val newIsNeedToMove = BooleanArray(newSubSize) { if (it < oldSubSize) isNeedToMove[it] else false }
        isNeedToMove = newIsNeedToMove
    }

    companion object {
        const val MAX_SUB_CELL_COUNT = 1
        const val SUB_CELL_SIZE = 10f
    }
}
