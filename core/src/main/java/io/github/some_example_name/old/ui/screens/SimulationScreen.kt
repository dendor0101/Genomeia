package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextButton.VisTextButtonStyle
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIContainer
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.ui.dialogs.GenomeListDialog

class SimulationScreen(
    val multiPlatformFileProvider: FileProvider,
    val game: MyGame,
    val map: Array<BooleanArray>?,
    val bundle: I18NBundle,
    val genomeName: String?
) : Screen, GestureDetector.GestureListener {

    private val simEntity = DIContainer.simulationData
    private val simulationSystem = DIContainer.simulationSystem
    private val renderSystem = DIContainer.renderSystem
    private val userCommandManager = DIContainer.userCommandManager

    private lateinit var camera: OrthographicCamera
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var stage: Stage
    private lateinit var root: Table

    private var currentScreenWidth = 0
    private var currentScreenHeight = 0

    private lateinit var genomeNames: List<String>

    private var putOrgs = true
    var onResize: (() -> Unit)? = null


    override fun show() {
        spriteBatch = SpriteBatch()
        stage = Stage(ScreenViewport())


        val screenPos = Vector3()
        val worldBefore = Vector3()
        val worldAfter = Vector3()
        val multiplexer = InputMultiplexer()
        val playGroundProcessor = object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {

                screenPos.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)

                camera.unproject(worldBefore.set(screenPos))

                val zoomFactor = if (amountY > 0) 1.05f else 0.95f
                val newZoom = MathUtils.clamp(camera.zoom * zoomFactor, 0.001f, 1000f)

                camera.zoom = newZoom
                camera.update()

                camera.unproject(worldAfter.set(screenPos))

                camera.position.sub(worldAfter.x - worldBefore.x, worldAfter.y - worldBefore.y, 0f)

                camera.update()
                return true
            }
        }
        multiplexer.addProcessor(playGroundProcessor)
        multiplexer.addProcessor(stage)
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

        font = BitmapFont()
        // Масштабируем шрифт симуляционной информации под DPI (density)
        // Это обеспечивает корректный размер текста при любом разрешении/DPI
        font.data.setScale(Gdx.graphics.density)

        simulationSystem.startThread()
        root = Table()
        root.setFillParent(true)
        stage.addActor(root)

        //TODO это должно находиться не тут
        val reader = simulationSystem.genomeManager.genomeJsonReader
        val assetsGenomes = reader.getGenomeFileNamesFromAssetsFolder("genomes")
        val userGenomes = reader.getGenomeFileNamesFromFolder("user_genomes")
        genomeNames = assetsGenomes + userGenomes

        rebuildMenu()
        currentScreenWidth = Gdx.graphics.width
        currentScreenHeight = Gdx.graphics.height

        renderSystem.create()

//        camera.rotate(-90f)
        camera.zoom = 0.08f
        camera.translate(-430f, -430f)
        camera.update()
    }


    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.50f, 0.62f, 0.64f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        if (Gdx.input.isKeyPressed(Input.Keys.SPACE)) {
            val zoomFactor = if (true) 1.005f else 0.95f
            val newZoom = MathUtils.clamp(camera.zoom * zoomFactor, 0.001f, 1000f)

            camera.zoom = newZoom
            camera.update()
        }

        renderSystem.drawShader(camera)
        renderSystem.drawTextSimInfo(spriteBatch, font)

        stage.act(Gdx.graphics.deltaTime)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return

        stage.viewport.update(width, height, true)

        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()

        font.data.setScale(Gdx.graphics.density)

        currentScreenWidth = width
        currentScreenHeight = height
        rebuildMenu()
        onResize?.invoke()
    }

    override fun pause() {
        simEntity.isPlay = false
    }

    override fun resume() {
        simEntity.isPlay = true
    }

    override fun hide() { }

    override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        return true
    }

    override fun pinchStop() {
    }

    override fun pinch(
        initialPointer1: Vector2, initialPointer2: Vector2,
        pointer1: Vector2, pointer2: Vector2
    ): Boolean {
        return true
    }

    override fun zoom(initialDistance: Float, distance: Float) = false

    override fun tap(x: Float, y: Float, count: Int, button: Int) = true

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val screenPos = Vector3(screenX, screenY, 0f)
        val worldPos = camera.unproject(screenPos)
        return Pair(worldPos.x, worldPos.y)
    }

    override fun longPress(x: Float, y: Float) = false
    override fun fling(dx: Float, dy: Float, button: Int): Boolean {
        userCommandManager.push(PlayerCommand.Drag(dx, dy))
        return true
    }
    override fun panStop(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        return true
    }

    override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
        val world = screenToWorld(x, y)

        when (button) {
            Input.Buttons.LEFT -> {
                userCommandManager.push(PlayerCommand.TouchDown(world.first, world.second, isLeftButton = true))
            }
            Input.Buttons.RIGHT -> {
                userCommandManager.push(PlayerCommand.TouchDown(world.first, world.second, isLeftButton = false))
            }
        }

        return true
    }

    override fun dispose() {
        renderSystem.dispose()

        simulationSystem.simulationData.isFinish = true
        simulationSystem.stopUpdateThread()
        stage.dispose()
        spriteBatch.dispose()
        font.dispose()

    }


    private fun applyCustomFont(button: VisTextButton) {
        val newStyle = VisTextButtonStyle(button.style as VisTextButtonStyle)  // Копируем текущий стиль
        newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.mediumFont else game.largeFont  // Применяем большой шрифт
        button.style = newStyle  // Устанавливаем стиль обратно
    }

    //TODO сделать работу с UI в другом месте
    private fun rebuildMenu() {
        root.clear()

        root.top().left()

        val menuButton =
            VisTextButton(if (genomeName == null) bundle.get("button.menu") else bundle.get("button.backToEditor"))
        val putOrganismToggle = VisTextButton(bundle.get("button.putOrganism"), "toggle")
        putOrganismToggle.isChecked = putOrgs
        val selectGenomeButton = VisTextButton(bundle.get("button.selectGenome"))
        val speedUpSimToggle = VisTextButton(bundle.get("button.speedUp"), "toggle")
        speedUpSimToggle.isChecked = simEntity.maxSpeed
        val pauseSimToggle = VisTextButton(bundle.get("button.pause"), "toggle")
        pauseSimToggle.isChecked = !simEntity.isPlay
        val restartSimulationButton = VisTextButton(bundle.get("button.restart"))
//        val chooseColorButton = VisTextButton(bundle.get("button.chooseColor"))
        val drawRaysToggle = VisTextButton(bundle.get("button.drawRays"), "toggle")
//        drawRaysToggle.isChecked = playGround.drawRays
//        chooseColorButton.addListener(object : ClickListener() {
//            override fun clicked(event: InputEvent, x: Float, y: Float) {
//                // Открываем палитру цветов
//                if (picker == null) {
//                    picker = ColorPicker(
//                        title = bundle.get("button.chooseColor"),
//                        listener = object : ColorPickerAdapter() {
//                            override fun finished(newColor: Color) {
//                                simulationSystem.backgroundColor.set(newColor)  // Меняем цвет фона меню при выборе
//                            }
//                        },
//                        game = game,
//                        colorInit = simulationSystem.backgroundColor
//                    )
//                }
//                picker?.setColor(simulationSystem.backgroundColor)  // Начальный цвет - текущий фон
//                stage.addActor(picker?.fadeIn())  // Показываем диалог с анимацией
//            }
//        })

        val buttons = if (genomeName == null) {
            listOf(
                menuButton, putOrganismToggle, selectGenomeButton, speedUpSimToggle,
                pauseSimToggle, restartSimulationButton/*, chooseColorButton*/, drawRaysToggle
            )
        } else {
            listOf(
                menuButton, putOrganismToggle, speedUpSimToggle, pauseSimToggle,
                restartSimulationButton/*, chooseColorButton*/, drawRaysToggle
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
                simEntity.maxSpeed = speedUpSimToggle.isChecked
            }
        })

        drawRaysToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
//                playGround.drawRays = drawRaysToggle.isChecked
//                simulationSystem.simEntity.drawRays = drawRaysToggle.isChecked
            }
        })


        pauseSimToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                simEntity.isPlay = !pauseSimToggle.isChecked
            }
        })

        putOrganismToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                putOrgs = putOrganismToggle.isChecked
            }
        })

        restartSimulationButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                simulationSystem.simulationData.isRestart = true
            }
        })

        menuButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                game.screen.dispose()
                if (genomeName == null)
                    game.screen = MenuScreen(game, multiPlatformFileProvider)
                else {
//                    game.screen =
//                        GenomeEditorScreen(multiPlatformFileProvider, game, genomeName, bundle)
                }
            }
        })


        selectGenomeButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GenomeListDialog(
                    genomesList = genomeNames,
                    selectedGenomeIndex = simulationSystem.simulationData.currentGenomeIndex,
                    title = bundle.get("button.selectGenome"),
                    new = bundle.get("button.new"),
                    select = bundle.get("button.select"),
                    import = bundle.get("button.import"),
                    onNew = {
//                        game.screen.dispose()
//                        game.screen = GenomeEditorScreen(
//                            multiPlatformFileProvider,
//                            game,
//                            genomeName = null,
//                            bundle = bundle
//                        )
                    },
                    onNext = { genomeName ->
                        simulationSystem.simulationData.currentGenomeIndex = genomeNames.indexOf(genomeName)
                    },
                    onRestart = {
                        val reader = simulationSystem.genomeManager.genomeJsonReader
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
}
