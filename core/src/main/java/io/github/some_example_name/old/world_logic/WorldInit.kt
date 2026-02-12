package io.github.some_example_name.old.world_logic

import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_SIZE_TYPE
import io.github.some_example_name.old.screens.WorldEditorScreen
import kotlin.random.Random


private fun CellManager.launchWorld(map: Array<BooleanArray>) {
    val random = Random(3)
    for (y in 0 until map.size) {
        for (x in 0 until map[y].size) {
            if (x == 0 || x == map.size - 1 || y == 0 || y == map[y].size - 1) continue
            if (map[y][x]) {
                addCell(
                    x * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
                    y * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
                    type = -1
                )
                if (cellMaxAmount * 0.9 < cellLastId) {
                    resizeCells()
                }
            } else {
                if (random.nextInt(30) == 1) {
                    subManager.addCell(
                        x * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
                        y * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
                        0f, 0f
                    )
                }
            }
        }
    }
}
fun CellManager.worldInit() {
    map?.let {
        launchWorld(map)
    }

    for (i in 1..<WORLD_SIZE_TYPE.size * 2) {
        addCell(0f, (i * 20).toFloat(), -1)
        if (cellMaxAmount * 0.9 < cellLastId) {
            resizeCells()
        }
    }
    for (i in 0..<WORLD_SIZE_TYPE.size * 2) {
        addCell((i * 20).toFloat(), 0f, -1)
        if (cellMaxAmount * 0.9 < cellLastId) {
            resizeCells()
        }
    }
    for (i in 0..<WORLD_SIZE_TYPE.size * 2) {
        addCell(WORLD_SIZE_TYPE.size * 39.99f, (i * 20).toFloat(), -1)
        if (cellMaxAmount * 0.9 < cellLastId) {
            resizeCells()
        }
    }
    for (i in 0..<WORLD_SIZE_TYPE.size * 2) {
        addCell((i * 20).toFloat(), WORLD_SIZE_TYPE.size * 39.99f, -1)
        if (cellMaxAmount * 0.9 < cellLastId) {
            resizeCells()
        }
    }

//        WALL_AMOUNT = cellLastId + 1
//        if (map == null) {
//            addCell(480f, 300f, 0, "0")
//        } else {
//            zoomManager.screenOffsetX = startCellX * WorldEditorScreen.SCALE_FACTOR - 480f
//            zoomManager.screenOffsetY = startCellY * WorldEditorScreen.SCALE_FACTOR - 300f
//            addCell(startCellX * WorldEditorScreen.SCALE_FACTOR, startCellY * WorldEditorScreen.SCALE_FACTOR, 0, "0")
//        }
}
