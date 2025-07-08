package io.github.some_example_name.old.logic

import io.github.some_example_name.attempts.game.physics.whiteColors
import io.github.some_example_name.old.good_one.utils.getIntersectedCells
import io.github.some_example_name.old.good_one.utils.xCoordsCircleCells
import io.github.some_example_name.old.good_one.utils.yCoordsCircleCells
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_WIDTH
import kotlin.math.abs
import kotlin.random.Random


//Экспериментальная
//TODO с веществами полное дерьмо, это точно нужно будет переделать
class SubstanceManager {
    @Volatile
    var cellMaxAmount = 5000

    @Volatile
    var cellLastId = -1
    val substanceIdMap = OrderedIntPairMap(1_000_000)

    var updateCellListId = -1
    var updateCellList = IntArray(cellMaxAmount) { -1 }

    var amountInCell = IntArray(cellMaxAmount) { 0 }
    var x = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var y = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var endX = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var endY = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var vx = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var vy = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 0f }
    var radius = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 5f }
    var colorR = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    var colorG = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    var colorB = FloatArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { 1f }
    var isNeedToMove = BooleanArray(cellMaxAmount * MAX_SUB_CELL_COUNT) { false }

    init {
//        repeat((1..100).count()) {
//            addCell(
//                x = Random.nextFloat() * 720f + 120f,
//                y = Random.nextFloat() * 720f + 120f,
//                0f,
//                0f
//            )
//        }
    }

    fun addCell(x: Float, y: Float, vx: Float, vy: Float) {
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
                vx + (Random.nextFloat() - 0.5f) * 5f,
                vy + (Random.nextFloat() - 0.5f) * 5f
            )
            println("Warning! Too much subs")
        }
    }

    //Вызывать каждый такт
    fun updateCells() {
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

    fun move(j: Int, cellId: Int): Boolean {
        vx[j] *= 0.93f
        vy[j] *= 0.93f
        x[j] += vx[j]
        y[j] += vy[j]
        if (x[j] < 0 || x[j] > WORLD_WIDTH || y[j] < 0 || y[j] > WORLD_HEIGHT) {
            deleteSub(j)
            return false
        }
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

    //Возможно решу к этому вернуться
//    fun merge(j: Int) {
//        println("merge $j")
//        val cellX = ((endX[j]) / CELL_SIZE).toInt()
//        val cellY = ((endY[j]) / CELL_SIZE).toInt()
//        for (x in -1..1) {
//            for (y in -1..1) {
//                val cellId = substanceIdMap.get(cellX + x, cellY + y)
//                if (cellId != -1) {
//                    for (i in cellId * MAX_SUB_CELL_COUNT..<cellId * MAX_SUB_CELL_COUNT + amountInCell[cellId]) {
//                        if (i != j) {
//                            val dx = this.x[i] - this.x[j]
//                            val dy = this.y[i] - this.y[j]
//                            val minDist = radius[i] + radius[j]
//                            if (sqrt(dx * dx + dy * dy) <= minDist) {
//                                //TODO рекурсивно после увеличения размера проверить на коллизии
//                                val areaI = radius[i] * radius[i]
//                                val areaJ = radius[j] * radius[j]
//                                val totalArea = areaI + areaJ
//
//                                radius[i] = sqrt(totalArea)
//
//                                colorR[i] = (colorR[i] * areaI + colorR[j] * areaJ) / totalArea
//                                colorG[i] = (colorG[i] * areaI + colorG[j] * areaJ) / totalArea
//                                colorB[i] = (colorB[i] * areaI + colorB[j] * areaJ) / totalArea
//
//                                this.x[i] = (this.x[i] * areaI + this.x[j] * areaJ) / totalArea
//                                this.y[i] = (this.y[i] * areaI + this.y[j] * areaJ) / totalArea
//                                deleteSub(j)
//                                merge(i)
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }

    fun deleteSub(i: Int) {
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

    fun sensor(findX: Float, findY: Float): Float {
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

    fun suckIt(findX: Float, findY: Float): Boolean {
        val cellX = (findX / SUB_CELL_SIZE).toInt()
        val cellY = (findY / SUB_CELL_SIZE).toInt()
        eat(substanceIdMap.get(cellX, cellY + 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX, cellY + 1)).let { if (it) return true }
        eat(substanceIdMap.get(cellX + 1, cellY)).let { if (it) return true }
        eat(substanceIdMap.get(cellX - 1, cellY)).let { if (it) return true }
        eat(substanceIdMap.get(cellX, cellY)).let { if (it) return true }
        return false
    }

    companion object {
        const val MAX_SUB_CELL_COUNT = 1
        const val SUB_CELL_SIZE = 10f
    }
}
