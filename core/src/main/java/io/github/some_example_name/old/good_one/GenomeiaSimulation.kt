package io.github.some_example_name.old.good_one

import com.badlogic.gdx.*
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextButton.VisTextButtonStyle
import com.kotcrab.vis.ui.widget.color.ColorPickerAdapter
import io.github.some_example_name.old.genome_editor.GenomeEditorScreen
import io.github.some_example_name.old.genome_editor.dialog.color.ColorPicker
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.good_one.shader.*
import io.github.some_example_name.old.good_one.utils.getMouseCoord
import io.github.some_example_name.old.world_logic.CellManager
import kotlin.math.roundToInt
import io.github.some_example_name.old.world_logic.maxSpeed
import io.github.some_example_name.old.good_one.shader.updateDraw
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.screens.MenuScreen
import io.github.some_example_name.old.world_logic.cells.currentGenomeIndex
import io.github.some_example_name.old.world_logic.isPlay
import java.io.File

const val MIN_ZOOM = 4f
lateinit var pikSounds: List<Sound>
var editButtonClicked = false
var playButtonClicked = false
var isTouchedAfterPlay = false
const val PANEL_WIDTH = 440f
var externalFileDir: File? = null

const val SHADER_TEXTURE_SIZE = 512
const val SHADER_TEXTURE_SIZE_POW_2 = 9

var fileProvider: FileProvider? = null

class CellSimulation(
    val multiPlatformFileProvider: FileProvider,
    val game: MyGame,
    val map: Array<BooleanArray>?,
    val bundle: I18NBundle,
    val genomeName: String?
) :
    Screen, GestureDetector.GestureListener {
    private lateinit var camera: OrthographicCamera
    private lateinit var renderer: ShapeRenderer
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var shaderManager: ShaderManagerSampler2D
    private lateinit var stage: Stage
    private lateinit var playGround: PlayGround
    private lateinit var cellManager: CellManager
    private lateinit var root: Table

    private var currentScreenWidth = 0
    private var currentScreenHeight = 0

    private var initialPointer1 = Vector2()
    private var initialPointer2 = Vector2()
    private var currentPointer1 = Vector2()
    private var currentPointer2 = Vector2()
    private var zoomStart = 1f
    private lateinit var genomeNames: List<String>

    var picker: ColorPicker? = null

    private var putOrgs = true
    var onResize: (() -> Unit)? = null

    override fun show() {
        spriteBatch = SpriteBatch()
        stage = Stage(ScreenViewport())
        fileProvider = multiPlatformFileProvider
        externalFileDir = multiPlatformFileProvider.getExternalFilesDir(null)

        val multiplexer = InputMultiplexer()
        val playGroundProcessor = object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val (mouseX, mouseY) = getMouseCoord()
                val (oldWorldX, oldWorldY) = playGround.screenToWorldPC(mouseX, mouseY)
                val zoomDirection = if (amountY > 0) -1f else 1f
                val dynamicMinZoom = computeDynamicMinZoom()
                val newZoomScale =
                    (cellManager.zoomManager.zoomScale + zoomDirection * 0.03f).coerceIn(dynamicMinZoom, MIN_ZOOM) // Updated to use dynamic min
                // Обновляем масштаб
                cellManager.zoomManager.zoomScale = (newZoomScale * 100000).roundToInt() / 100000.0f
                val (newWorldX, newWorldY) = playGround.screenToWorldPC(mouseX, mouseY)
                cellManager.zoomManager.screenOffsetX += oldWorldX - newWorldX
                cellManager.zoomManager.screenOffsetY += oldWorldY - newWorldY
                clampCamera()
                shaderManager.updateGrid()
                return true
            }
        }
        multiplexer.addProcessor(playGroundProcessor)
        multiplexer.addProcessor(stage) // если есть другие
        val gestureDetector = GestureDetector(this)
        multiplexer.addProcessor(gestureDetector)
        Gdx.input.inputProcessor = multiplexer

        camera = OrthographicCamera().apply {
            setToOrtho(
                false,
                Gdx.graphics.width.toFloat(),
                Gdx.graphics.height.toFloat()
            )
        }
        renderer = ShapeRenderer()

        font = BitmapFont()
        cellManager = CellManager(map, 20_000, 50_000, genomeName = genomeName)
        playGround = PlayGround(spriteBatch, font, cellManager)

        shaderManager = ShaderManagerSampler2D(cellManager)

        cellManager.startThread()
        clampCamera()
        root = Table()
        root.setFillParent(true)
        stage.addActor(root)


        val reader = cellManager.genomeManager.genomeJsonReader
        val assetsGenomes = reader.getGenomeFileNamesFromAssetsFolder("genomes")
        val userGenomes = reader.getGenomeFileNamesFromFolder("user_genomes")
        genomeNames = assetsGenomes + userGenomes

        rebuildMenu()
        currentScreenWidth = Gdx.graphics.width
        currentScreenHeight = Gdx.graphics.height
    }

    private fun computeDynamicMinZoom(): Float {
        val cs = cellManager.zoomManager.shaderCellSize
        val effectiveWorldW = cellManager.gridManager.WORLD_WIDTH - 2f
        val effectiveWorldH = cellManager.gridManager.WORLD_HEIGHT - 2f
        val minFromWidth = Gdx.graphics.width.toFloat() / (effectiveWorldW * cs)
        val minFromHeight = Gdx.graphics.height.toFloat() / (effectiveWorldH * cs)
        return maxOf(minFromWidth, minFromHeight)
    }

    private fun applyCustomFont(button: VisTextButton) {
        val newStyle = VisTextButtonStyle(button.style as VisTextButtonStyle)  // Копируем текущий стиль
        newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.mediumFont else game.largeFont  // Применяем большой шрифт
        button.style = newStyle  // Устанавливаем стиль обратно
    }

    private fun rebuildMenu() {
        root.clear()

        root.top().left()

        val menuButton =
            VisTextButton(if (genomeName == null) bundle.get("button.menu") else bundle.get("button.backToEditor"))
        val putOrganismToggle = VisTextButton(bundle.get("button.putOrganism"), "toggle")
        putOrganismToggle.isChecked = putOrgs
        val selectGenomeButton = VisTextButton(bundle.get("button.selectGenome"))
        val speedUpSimToggle = VisTextButton(bundle.get("button.speedUp"), "toggle")
        speedUpSimToggle.isChecked = maxSpeed
        val pauseSimToggle = VisTextButton(bundle.get("button.pause"), "toggle")
        pauseSimToggle.isChecked = !isPlay
        val restartSimulationButton = VisTextButton(bundle.get("button.restart"))
        val chooseColorButton = VisTextButton(bundle.get("button.chooseColor"))
        val drawRaysToggle = VisTextButton(bundle.get("button.drawRays"), "toggle")
        drawRaysToggle.isChecked = playGround.drawRays
        chooseColorButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                // Открываем палитру цветов
                if (picker == null) {
                    picker = ColorPicker(
                        title = bundle.get("button.chooseColor"),
                        listener = object : ColorPickerAdapter() {
                            override fun finished(newColor: Color) {
                                cellManager.backgroundColor.set(newColor)  // Меняем цвет фона меню при выборе
                            }
                        },
                        game = game,
                        colorInit = cellManager.backgroundColor
                    )
                }
                picker?.setColor(cellManager.backgroundColor)  // Начальный цвет - текущий фон
                stage.addActor(picker?.fadeIn())  // Показываем диалог с анимацией
            }
        })

        val buttons = if (genomeName == null) {
            listOf(
                menuButton, putOrganismToggle, selectGenomeButton, speedUpSimToggle,
                pauseSimToggle, restartSimulationButton, chooseColorButton, drawRaysToggle
            )
        } else {
            listOf(
                menuButton, putOrganismToggle, speedUpSimToggle, pauseSimToggle,
                restartSimulationButton, chooseColorButton, drawRaysToggle
            )
        }

        val controls = Table()
        controls.defaults().pad(8f * Gdx.graphics.density).left() // Pad 8f around each cell, align left

        var currentWidth = 0f
        var rowTable = Table()
        rowTable.defaults().pad(8f * Gdx.graphics.density).left()

        for (button in buttons) {
            applyCustomFont(button)
            val prefWidth = button.prefWidth + 16f * Gdx.graphics.density // Approximate with padding
            if (currentWidth + prefWidth > Gdx.graphics.width && currentWidth > 0f) {
                controls.add(rowTable).growX().row()
                rowTable = Table()
                rowTable.defaults().padLeft(8f * Gdx.graphics.density).padRight(8f * Gdx.graphics.density).left()
                currentWidth = 0f
            }
            rowTable.add(button).height(25f * Gdx.graphics.density)
            currentWidth += prefWidth
        }
        if (rowTable.hasChildren()) {
            controls.add(rowTable).growX()
        }

        root.add(controls).growX().top().left()

        speedUpSimToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                maxSpeed = speedUpSimToggle.isChecked
            }
        })

        drawRaysToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                playGround.drawRays = drawRaysToggle.isChecked
                cellManager.drawRays = drawRaysToggle.isChecked
            }
        })


        pauseSimToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                isPlay = !pauseSimToggle.isChecked
            }
        })

        putOrganismToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                putOrgs = putOrganismToggle.isChecked
            }
        })

        restartSimulationButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                cellManager.isRestart = true
            }
        })

        menuButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                game.screen.dispose()
                if (genomeName == null)
                    game.screen = MenuScreen(game, multiPlatformFileProvider)
                else
                    game.screen = GenomeEditorScreen(multiPlatformFileProvider, game, genomeName, bundle)
            }
        })


        selectGenomeButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GenomeListDialog(
                    genomesList = genomeNames,
                    selectedGenomeIndex = currentGenomeIndex,
                    title = bundle.get("button.selectGenome"),
                    new = bundle.get("button.new"),
                    select = bundle.get("button.select"),
                    import = bundle.get("button.import"),
                    onNew = {
                        game.screen.dispose()
                        game.screen = GenomeEditorScreen(multiPlatformFileProvider, game, genomeName = null, bundle = bundle)
                    },
                    onNext = { genomeName ->
                        currentGenomeIndex = genomeNames.indexOf(genomeName)
                    },
                    onRestart = {
                        val reader = cellManager.genomeManager.genomeJsonReader
                        val assetsGenomes = reader.getGenomeFileNamesFromAssetsFolder("genomes")
                        val userGenomes = reader.getGenomeFileNamesFromFolder("user_genomes")
                        genomeNames = assetsGenomes + userGenomes
                    },
                    game = game,
                    onResize = { handler ->
                        onResize = if (handler == {}) null else handler
                    },
                    isMenu = false
                ).show(stage)
            }
        })
    }

    override fun render(delta: Float) {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        //TODO избавиться от synchronized, заменив на двойной буфер
        synchronized(cellManager) {
            cellManager.updateDraw()
        }

        shaderManager.render()

        playGround.handlePlay()
        renderer.projectionMatrix = camera.combined
        playGround.update(renderer)

        stage.act(Gdx.graphics.deltaTime)
        stage.draw()

    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return
        val zoomScreenOld = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val oldViewWidth = currentScreenWidth.toFloat() / zoomScreenOld
        val oldViewHeight = currentScreenHeight.toFloat() / zoomScreenOld
        val oldCenterX = cellManager.zoomManager.screenOffsetX + oldViewWidth / 2f
        val oldCenterY = cellManager.zoomManager.screenOffsetY + oldViewHeight / 2f
        // Added: Enforce dynamic min zoom on resize (e.g., orientation change)
        val dynamicMinZoom = computeDynamicMinZoom()
        var zoomScale = cellManager.zoomManager.zoomScale
        if (zoomScale < dynamicMinZoom) {
            zoomScale = dynamicMinZoom
        }
        cellManager.zoomManager.zoomScale = zoomScale
        val zoomScreenNew = zoomScale * cellManager.zoomManager.shaderCellSize
        val newViewWidth = width.toFloat() / zoomScreenNew
        val newViewHeight = height.toFloat() / zoomScreenNew
        cellManager.zoomManager.screenOffsetX = oldCenterX - newViewWidth / 2f
        cellManager.zoomManager.screenOffsetY = oldCenterY - newViewHeight / 2f
        clampCamera()
        shaderManager.updateGrid()
        stage.viewport.update(width, height, true) // Update stage viewport if needed
        rebuildMenu() // Rebuild menu to handle wrapping on resize
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.position.set(width / 2f, height / 2f, 0f);
        camera.update()
        spriteBatch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        currentScreenWidth = width
        currentScreenHeight = height
        onResize?.invoke()
    }

    override fun pause() {
        isPlay = false
    }

    override fun resume() {
        isPlay = true
    }

    override fun hide() { }

    override fun dispose() {
        renderer.dispose()
        cellManager.isFinish = true
        cellManager.stopUpdateThread()
        stage.dispose()
        spriteBatch.dispose()
        font.dispose()
        time = 0f
        isEditPossible = false
        isShowCell = true
        isShowPhysicalLink = true
        isShowNeuronLink = true
        isEqualLinks = false
        editButtonClicked = false
        playButtonClicked = false
        isTouchedAfterPlay = false
        picker?.dispose()
    }

    fun clampCamera() {
        val zoomScreen = cellManager.zoomManager.zoomScale * cellManager.zoomManager.shaderCellSize
        val viewWidth = Gdx.graphics.width / zoomScreen
        val viewHeight = Gdx.graphics.height / zoomScreen

        if (cellManager.zoomManager.screenOffsetX < 0) {
            cellManager.zoomManager.screenOffsetX = 0f
        }
        if (cellManager.zoomManager.screenOffsetX + viewWidth > cellManager.gridManager.WORLD_WIDTH - 2f) {
            cellManager.zoomManager.screenOffsetX = cellManager.gridManager.WORLD_WIDTH - viewWidth - 2f
        }

        if (cellManager.zoomManager.screenOffsetY < 0) {
            cellManager.zoomManager.screenOffsetY = 0f
        }
        if (cellManager.zoomManager.screenOffsetY + viewHeight > cellManager.gridManager.WORLD_HEIGHT - 2f) {
            cellManager.zoomManager.screenOffsetY = cellManager.gridManager.WORLD_HEIGHT - viewHeight - 2f
        }
    }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        if (!putOrgs) {
            if (cellManager.grabbedCell != -1) {
                val (worldX, worldY) = playGround.screenToWorld(x, y)
                cellManager.moveTo(worldX, worldY)
            } else if (playGround.isDragged) {
                if (!isTouchedAfterPlay) {
                    val zoom = cellManager.zoomManager.zoomScale
                    val cellSize = cellManager.zoomManager.shaderCellSize
                    cellManager.zoomManager.screenOffsetX -= deltaX / (zoom * cellSize)
                    cellManager.zoomManager.screenOffsetY += deltaY / (zoom * cellSize)
                    clampCamera()
                }
            }
        }

        return true
    }

    override fun pinchStop() {
        zoomStart = cellManager.zoomManager.zoomScale
    }

    override fun pinch(
        initialPointer1: Vector2, initialPointer2: Vector2,
        pointer1: Vector2, pointer2: Vector2
    ): Boolean {
        this.initialPointer1.set(initialPointer1)
        this.initialPointer2.set(initialPointer2)
        this.currentPointer1.set(pointer1)
        this.currentPointer2.set(pointer2)

        // Центр между двумя пальцами
        val midX = (pointer1.x + pointer2.x) / 2f
        val midY = (pointer1.y + pointer2.y) / 2f

        val oldWorld = playGround.screenToWorld(midX, midY)

        val initialDistance = this.initialPointer1.dst(this.initialPointer2)
        val currentDistance = this.currentPointer1.dst(this.currentPointer2)
        val ratio = currentDistance / initialDistance

        val dynamicMinZoom = computeDynamicMinZoom()
        val newZoomScale = (zoomStart * ratio).coerceIn(dynamicMinZoom, MIN_ZOOM) // Updated to use dynamic min
        cellManager.zoomManager.zoomScale = newZoomScale

        val newWorld = playGround.screenToWorld(midX, midY)
        cellManager.zoomManager.screenOffsetX += oldWorld.first - newWorld.first
        cellManager.zoomManager.screenOffsetY += oldWorld.second - newWorld.second
        clampCamera()
        shaderManager.updateGrid()

        return true
    }

    // Остальные методы не используются, но должны быть реализованы
    override fun zoom(initialDistance: Float, distance: Float) = false
    override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
        cellManager.grabbedCell = -1

        val (worldX, worldY) = playGround.screenToWorld(x, y)
        if (putOrgs) {
            isTouchedAfterPlay = false
            onlyFirstTime --
            cellManager.onMouseClick(worldX, worldY)
        }
        return true
    }

    override fun longPress(x: Float, y: Float) = false
    override fun fling(velocityX: Float, velocityY: Float, button: Int): Boolean {
        cellManager.moveTo(0f, 0f)
        return true
    }
    override fun panStop(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        cellManager.grabbedCell = -1
        return true
    }

    var onlyFirstTime = 100

    override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        val (worldX, worldY) = playGround.screenToWorld(x, y)
        playGround.isDragged = !cellManager.grabbed(worldX, worldY)
        isTouchedAfterPlay = false
        if (playGround.isDragged) {
            playGround.zoomOffsetX = x
            playGround.zoomOffsetY = y
        }
        return true
    }
}
