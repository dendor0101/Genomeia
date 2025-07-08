package io.github.some_example_name.old.logic

class ZoomManager(val cellManager: CellManager) {

    val widthScreen = 960f
    val heightScreen = 960f

    var screenOffsetX = 0f
    var screenOffsetY = 0f

    var zoomScale = 1f
    var shaderCellSize = 1f
}
