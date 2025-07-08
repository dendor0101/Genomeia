package io.github.some_example_name.old.good_one

import com.badlogic.gdx.*
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.attempts.game.main.*
import io.github.some_example_name.old.good_one.editor.CellCopy
import io.github.some_example_name.old.good_one.editor.GenomeEditorRefactored
import io.github.some_example_name.old.good_one.shader.*
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.CELLS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.LINKS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.ui.Pause
import io.github.some_example_name.old.good_one.ui.Play
import io.github.some_example_name.old.good_one.ui.UiProcessor
import io.github.some_example_name.old.good_one.utils.drawArrowWithRotation
import io.github.some_example_name.old.good_one.utils.drawTriangleMiddle
import io.github.some_example_name.old.good_one.utils.getMouseCoord
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_WIDTH
import io.github.some_example_name.old.logic.LinksDrawGridStructure
import io.github.some_example_name.old.logic.LinksDrawGridStructure.Companion.SCREEN_CELL_WIDTH_LINK
import io.github.some_example_name.old.logic.ThreadManager.Companion.THREAD_COUNT
import io.github.some_example_name.old.logic.isPlay
import kotlin.math.roundToInt

const val MIN_ZOOM = 2f
const val MAX_ZOOM = 0.5f
val ZOOM_SPEED
    get() = zoomScale * zoomScale / 15

val VIRTUAL_WIDTH = 960f
val VIRTUAL_HEIGHT = 480f
lateinit var pikSounds: List<Sound>

class CellSimulation : ApplicationAdapter(), GestureDetector.GestureListener {
    private lateinit var camera: OrthographicCamera
    private lateinit var renderer: ShapeRenderer
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private val uiProcessor = UiProcessor()
    private lateinit var shaderManager: ShaderManagerSampler2D

    private lateinit var stage: Stage
    private lateinit var skin: Skin

    private lateinit var genomeEditor: GenomeEditorRefactored
    private lateinit var playGround: PlayGround
    private lateinit var cellManager: CellManager
    private val cellsDrawGrid = LinksDrawGridStructure()

    override fun create() {
        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        val multiplexer = InputMultiplexer()
//        val playGroundProcessor = object : InputAdapter() {
//            override fun scrolled(amountX: Float, amountY: Float): Boolean {
//                val (mouseX, mouseY) = getMouseCoord()
//                val (oldWorldX, oldWorldY) = playGround.screenToWorld(mouseX, mouseY)
//                val zoomDirection = if (amountY > 0) -1f else 1f
//                val newZoomScale =
//                    (cellManager.zoomManager.zoomScale + zoomDirection * 0.06f).coerceIn(MAX_ZOOM, MIN_ZOOM)
//                // Обновляем масштаб
//
//                cellManager.zoomManager.zoomScale = (newZoomScale * 10).roundToInt() / 10.0f
//                val (newWorldX, newWorldY) = playGround.screenToWorld(mouseX, mouseY)
//                cellManager.zoomManager.screenOffsetX += oldWorldX - newWorldX
//                cellManager.zoomManager.screenOffsetY += oldWorldY - newWorldY
//                shaderManager.updateGrid()
////                cellManager.updateDraw()
//                return true
//            }
//        }

//        val androidZoomProcessor = GestureDetector(object : GestureDetector.GestureAdapter() {
//            private var initialZoomDistance = 0f
//            private var lastZoomScale = 1f
//            private var activePointers = 0
//
//            override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
//                activePointers++
//                return false
//            }
//
//            override fun pinch(
//                initialPointer1: Vector2,
//                initialPointer2: Vector2,
//                pointer1: Vector2,
//                pointer2: Vector2
//            ): Boolean {
//                if (activePointers != 2) return false
//
//                val currentDistance = pointer1.dst(pointer2)
//
//                if (initialZoomDistance == 0f) {
//                    initialZoomDistance = currentDistance
//                    lastZoomScale = cellManager.zoomManager.zoomScale
//                    return true
//                }
//
//                val zoomFactor = currentDistance / initialZoomDistance
//                val newZoomScale = (lastZoomScale * zoomFactor).coerceIn(0.2f, 3f)
//
//                val centerX = (pointer1.x + pointer2.x) / 2
//                val centerY = (pointer1.y + pointer2.y) / 2
//
//                updateZoom(newZoomScale, centerX, centerY)
//                return true
//            }
//
//            override fun pinchStop() {
//                initialZoomDistance = 0f
//                activePointers = 0
//            }
//        })
//
//        val androidDragProcessor = object : InputAdapter() {
//            private var isZoomActive = false
//            private var firstPointer = -1
//            private var lastTouchX = 0f
//            private var lastTouchY = 0f
//            private var isDragging = false
//
//            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
//                if (pointer == 0) firstPointer = pointer
//                if (pointer > 1) return false
//
//                if (pointer != firstPointer) {
//                    isZoomActive = true
//                    return false
//                }
//
//                if (!isZoomActive) {
//                    val (worldX, worldY) = playGround.screenToWorld(screenX.toFloat(), screenY.toFloat())
//                    isDragging = !cellManager.grabbed(worldX, worldY)
//                    lastTouchX = screenX.toFloat()
//                    lastTouchY = screenY.toFloat()
//                    return isDragging
//                }
//                return false
//            }
//
//            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
//                if (isZoomActive) return false
//                if (pointer != firstPointer) return false
//
//                if (cellManager.grabbedCell != -1) {
//                    val (worldX, worldY) = playGround.screenToWorld(screenX.toFloat(), screenY.toFloat())
//                    cellManager.moveTo(worldX, worldY)
//                    return true
//                } else if (isDragging) {
//                    val deltaX = screenX - lastTouchX
//                    val deltaY = screenY - lastTouchY
//                    cellManager.zoomManager.screenOffsetX -= deltaX / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
//                    cellManager.zoomManager.screenOffsetY += deltaY / (cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize)
//                    lastTouchX = screenX.toFloat()
//                    lastTouchY = screenY.toFloat()
//                    return true
//                }
//                return false
//            }
//
//            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
//                if (pointer == firstPointer) {
//                    firstPointer = -1
//                    isZoomActive = false
//                    cellManager.grabbedCell = -1
//                    isDragging = false
//                }
//                return false
//            }
//        }
//
//        multiplexer.addProcessor(0, androidZoomProcessor)
//        multiplexer.addProcessor(1, androidDragProcessor)
        multiplexer.addProcessor(stage) // если есть другие
        Gdx.input.inputProcessor = multiplexer


        skin = Skin(Gdx.files.internal("ui/uiskin.json")) // Загрузите скин
        camera = OrthographicCamera().apply {
            setToOrtho(
                false,
                Gdx.graphics.width.toFloat(),
                Gdx.graphics.height.toFloat()
            )
        }
        renderer = ShapeRenderer()

        pikSounds = listOf<Sound>(
            Gdx.audio.newSound(Gdx.files.internal("pik1.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik2.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik3.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik4.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik5.mp3"))
        )

        spriteBatch = SpriteBatch()
        font = BitmapFont()
        cellManager = CellManager()
        genomeEditor = GenomeEditorRefactored(stage, skin, uiProcessor, cellManager)
        playGround = PlayGround(stage, camera, skin, genomeEditor, spriteBatch, font, cellManager)

        shaderManager = ShaderManagerSampler2D(uiProcessor, genomeEditor, cellManager)

    }

//    override fun resize(width: Int, height: Int) {
//        // Рассчитываем viewport с сохранением соотношения сторон
//        val aspectRatio = VIRTUAL_WIDTH / VIRTUAL_HEIGHT
//        var viewportHeight = height.toFloat()
//        var viewportWidth = viewportHeight * aspectRatio
//
//        if (viewportWidth < width) {
//            val scale = width / viewportWidth
//            viewportWidth *= scale
//            viewportHeight *= scale
//        }
//
//        // Устанавливаем viewport по центру
//        val x = (width - viewportWidth) / 2
//        val y = (height - viewportHeight) / 2
//        Gdx.gl.glViewport(x.toInt(), y.toInt(), viewportWidth.toInt(), viewportHeight.toInt())
//
//        camera.setToOrtho(false, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
//        camera.update()
//    }

    private fun updateZoom(newZoomScale: Float, screenX: Float, screenY: Float) {
        val (oldWorldX, oldWorldY) = playGround.screenToWorld(screenX, screenY)
        cellManager.zoomManager.zoomScale = newZoomScale
        val (newWorldX, newWorldY) = playGround.screenToWorld(screenX, screenY)

        cellManager.zoomManager.screenOffsetX += oldWorldX - newWorldX
        cellManager.zoomManager.screenOffsetY += oldWorldY - newWorldY
        shaderManager.updateGrid()
    }

    private fun putLinksToGrid(endCameraX: Float, endCameraY: Float): List<CellCopy> {
        val cells = genomeEditor.cellsCopy.values.toList()
//            .filter {
//            //TODO доделать фильтр
//            it.x > 0 && it.y > 0
//        }
        val linksAmount = cells.size
        val threadAmount = THREAD_COUNT * 4
        val batchSize = (linksAmount + threadAmount - 1) / threadAmount

        for (i in 0 until threadAmount) {
            val start = i * batchSize
            val end = minOf(start + batchSize, linksAmount)

            if (start >= end) break

            cellManager.threadManager.futures.add(cellManager.threadManager.executor.submit {
                for (cellId in start until end) {

                    val cx = (cells[cellId].x / CELL_SIZE).toInt()
                    val cy = (cells[cellId].y / CELL_SIZE).toInt()
                    cellsDrawGrid.addLink(cx, cy, cellId)
                }
            })
        }

        cellManager.threadManager.futures.forEach { it.get() }
        cellManager.threadManager.futures.clear()
        return cells
    }


    private fun updateDrawChunk(
        start: Int,
        end: Int,
        startX: Int,
        endX: Int,
        startY: Int,
        cameraX: Int,
        cameraY: Int,
        zoom: Float,
        aspectRatio: Float,
        cellsDraw: List<CellCopy>
    ) {
        var counterThread = cellManager.drawStrokeCellCounts[start]
        for (cy in start + startY..end + startY) {
            for (cx in startX..endX) {
                val cellsCount = cellsDrawGrid.getLinksCount(cx, cy)

                val batchSize = cellsCount * CELLS_FLOAT_COUNT

                val base = if (cellsCount == 0) {
                    val hasNeighbor =
                        cellsDrawGrid.getLinksCount(cx + 1, cy - 1) == 0 &&
                            cellsDrawGrid.getLinksCount(cx + 1, cy) == 0 &&
                            cellsDrawGrid.getLinksCount(cx + 1, cy + 1) == 0 &&
                            cellsDrawGrid.getLinksCount(cx - 1, cy - 1) == 0 &&
                            cellsDrawGrid.getLinksCount(cx - 1, cy) == 0 &&
                            cellsDrawGrid.getLinksCount(cx - 1, cy + 1) == 0 &&
                            cellsDrawGrid.getLinksCount(cx, cy + 1) == 0 &&
                            cellsDrawGrid.getLinksCount(cx, cy - 1) == 0
                    if (hasNeighbor) 2 else 1
                } else counterThread

                cellManager.setGridValue(base, batchSize, cx - cameraX, cy - cameraY)
                if (base == 2) continue

                val cells = cellsDrawGrid.getLinks(cx, cy)
                for (id in cells) {
                    if (counterThread < cellManager.cellsMaxAmount) {
                        val cellMode = when {
                            cellsDraw[id].isSelected -> 3f
                            cellsDraw[id].isAdded -> 1f
                            else -> 0f
                        }

                        cellManager.bufferFloat.put(counterThread++, (1f))
                        cellManager.bufferFloat.put(counterThread++, ((cellsDraw[id].x - cellManager.zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width)
                        cellManager.bufferFloat.put(counterThread++, (((cellsDraw[id].y - cellManager.zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height) * aspectRatio)
                        cellManager.bufferFloat.put(counterThread++, (0f))
                        cellManager.bufferFloat.put(counterThread++, (0f))
                        cellManager.bufferFloat.put(counterThread++, (1f))
                        cellManager.bufferFloat.put(counterThread++, (cellsDraw[id].colorCore.r))
                        cellManager.bufferFloat.put(counterThread++, (cellsDraw[id].colorCore.g))
                        cellManager.bufferFloat.put(counterThread++, (cellsDraw[id].colorCore.b))
                        cellManager.bufferFloat.put(counterThread++, (cellMode))
                    }
                }
            }
        }
    }

    private fun updateDraw() {

//        val startTime = System.nanoTime()
        val zoomManager = cellManager.zoomManager
        val aspectRatio = Gdx.graphics.height.toFloat() / Gdx.graphics.width.toFloat()
        val zoom = zoomManager.zoomScale * zoomManager.shaderCellSize
        val endCameraX = zoomManager.screenOffsetX + Gdx.graphics.width / zoom
        val endCameraY = zoomManager.screenOffsetY + Gdx.graphics.width / zoom
        val startX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_WIDTH)
        val startY = ((zoomManager.screenOffsetY) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_HEIGHT)
        val endX = ((zoomManager.screenOffsetX + Gdx.graphics.width / zoom) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_WIDTH - 1)
        val endY = ((zoomManager.screenOffsetY + Gdx.graphics.height / zoom) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_HEIGHT - 1)
        val cameraX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt()
        val cameraY = ((zoomManager.screenOffsetY) / CELL_SIZE).toInt()

        val cells = putLinksToGrid(endCameraX, endCameraY)

        val strokeCountsSize = endY - startY
        for (cy in startY..endY) {
            for (cx in startX..endX) {
                val cellIndex = cy * WORLD_CELL_WIDTH + cx
                cellManager.drawStrokeCellCounts[cy - startY] += cellsDrawGrid.linkCounts[cellIndex] * CELLS_FLOAT_COUNT
            }
        }

        //0.22 мс на сам сбор данных
        var sum = 0
        for (i in 0..strokeCountsSize) {
            val cellsCountInStroke = cellManager.drawStrokeCellCounts[i]
            cellManager.drawStrokeCellCounts[i] = sum
            sum += cellsCountInStroke
        }

        val threadAmount = THREAD_COUNT * 4
        val total = strokeCountsSize + 1
        val chunkSize = (total + threadAmount - 1) / threadAmount // округление вверх
        for (threadIndex in 0 until threadAmount) {
            val start = threadIndex * chunkSize
            val end = minOf((threadIndex + 1) * chunkSize, total)
            if (start >= end) break
            cellManager.threadManager.futures.add(cellManager.threadManager.executor.submit {
                updateDrawChunk(start, end, startX, endX, startY, cameraX, cameraY, zoom, aspectRatio, cells)
            })
        }

        cellManager.threadManager.futures.forEach { it.get() }
        cellManager.threadManager.futures.clear()

        cellManager.bufferFloat.position(0)

        cellManager.bufferInt.clear()
        cellManager.bufferInt.put(cellManager.drawIntGrid)
        cellManager.bufferInt.position(0)

//        cellManager.threadManager.futures.add(cellManager.threadManager.executor.submit {
//        })
        cellManager.drawIntGrid.fill(2)
        cellManager.drawStrokeCellCounts.fill(0)
        cellsDrawGrid.clear()

//        val elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0 // в миллисекундах
        //0.43 - на весь updateDraw метод
        //С линками теперь 0.62 - не так плохо, но и не хорошо
//        println("$elapsedTime")
    }

    override fun render() {
        if (isPlay) {
            cellManager.threadManager.updateDone.acquireUninterruptibly()
        }

        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

//        val start = System.nanoTime()
//        val end = System.nanoTime()
//        println("Method took ${(end - start) / 1_000_000.0} ms ${Gdx.graphics.framesPerSecond}")

        val cameraX = cellManager.zoomManager.screenOffsetX
        val cameraY = cellManager.zoomManager.screenOffsetY

        val xOffset = cellManager.zoomManager.screenOffsetX
        val yOffset = cellManager.zoomManager.screenOffsetY
        val zoom = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val cameraEndX = cellManager.zoomManager.screenOffsetX + Gdx.graphics.width / zoom
        val cameraEndY = cellManager.zoomManager.screenOffsetY + Gdx.graphics.height / yOffset

        when (uiProcessor.uiState) {
            is Pause -> {
                //TODO отрисовка в режиме генома не очень производительная, но здесь будут отрисовываться только те элементы
                // которые находятся в пределах экрана
                updateDraw()
                shaderManager.render()

                renderer.projectionMatrix = camera.combined
                Gdx.gl.glLineWidth(2f)
                if (isShowPhysicalLink) {
                    renderer.begin(ShapeRenderer.ShapeType.Line)

                    genomeEditor.cellsCopy.forEach { (_, u) ->
                        u.physicalLink.forEach {
                            if (it.value.isNeuronal) {
                                renderer.color = Color.CYAN
                            } else {
                                renderer.color = Color.RED
                            }
                            genomeEditor.cellsCopy[it.key]?.let { c2 ->
                                //zoomManager.screenOffsetX
                                //zoomManager.screenOffsetY
                                renderer.line((u.x - xOffset) * zoom, (u.y - yOffset) * zoom, (c2.x - xOffset) * zoom, (c2.y - yOffset) * zoom)
                                if (it.value.isNeuronal && it.value.directedNeuronLink != null) {
                                    if (it.value.directedNeuronLink!! == c2.index) {
                                        renderer.drawTriangleMiddle((u.x - xOffset) * zoom, (u.y - yOffset) * zoom, (c2.x - xOffset) * zoom, (c2.y - yOffset) * zoom)
                                    } else if (it.value.directedNeuronLink!! == u.index) {
                                        renderer.drawTriangleMiddle((c2.x - xOffset) * zoom, (c2.y - yOffset) * zoom, (u.x - xOffset) * zoom, (u.y - yOffset) * zoom)
                                    }
                                }
                            }
                        }
                    }
                    renderer.end()


                    Gdx.gl.glLineWidth(2f)
                    renderer.begin(ShapeRenderer.ShapeType.Line)
                    renderer.color = Color.GREEN

                    genomeEditor.cellsCopy.forEach { (_, u) ->
                        u.angleDirected?.let { angle ->
                            genomeEditor.cellsCopy[u.startDirectionId]?.let { endPoint ->
                                renderer.drawArrowWithRotation(
                                    (u.x - xOffset) * zoom, (u.y - yOffset) * zoom, (endPoint.x - xOffset) * zoom, (endPoint.y - yOffset) * zoom, angle,
                                    length = u.lengthDirected ?: 30f
                                )
                            }
                        }
                    }

                    renderer.end()
                }

                stage.act(Gdx.graphics.deltaTime)
                stage.draw()
                genomeEditor.handlePause()
                if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                    genomeEditor.finishEditing()
                    isPlay = true
                }
            }

            Play -> {
                shaderManager.render()
                spriteBatch.begin()
                font.draw(
                    spriteBatch, "FPS: ${Gdx.graphics.framesPerSecond}\n", 30f, Gdx.graphics.height - 30f
                )
                spriteBatch.end()
                playGround.handlePlay()
                playGround.update(renderer)

            }
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            writeGenome(genomeStageInstruction, "gen2.bin")
        }
    }

    override fun dispose() {
        renderer.dispose()
        cellManager.stopUpdateThread()
        stage.dispose()
        skin.dispose()
        spriteBatch.dispose()
        font.dispose()
    }
}

