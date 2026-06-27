package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer.genomeJsonReader
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.genomeManager
import io.github.some_example_name.old.core.DISimulationContainer.gridHeight
import io.github.some_example_name.old.core.DISimulationContainer.gridWidth
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.editor.ui.GenomeEditorScreen
import io.github.some_example_name.old.systems.render.CoreDesktopShaders
import io.github.some_example_name.old.systems.render.usePostProcess
import io.github.some_example_name.old.ui.dialogs.GenomeListDialog
import io.github.some_example_name.old.ui.dialogs.SpeedUpDialog

var isRenderUi = true

class SimulationScreen(
    val multiPlatformFileProvider: FileProvider,
    val game: MyGame,
    val map: Array<BooleanArray>?,
    val bundle: I18NBundle,
    val genomeName: String?
) : Screen, GestureDetector.GestureListener {

    private val simEntity = DISimulationContainer.simulationData
    private val simulationSystem = DISimulationContainer.simulationSystem
    private val renderSystem = DISimulationContainer.renderSystem
    private val userCommandManager = DISimulationContainer.userCommandManager

    private lateinit var camera: OrthographicCamera
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var stage: Stage
    private lateinit var root: Table
    private lateinit var fontMatrix: Matrix4
    private lateinit var shapeRenderer: ShapeRenderer

    private var currentScreenWidth = 0
    private var currentScreenHeight = 0

    private lateinit var genomeNames: List<String>

    private var putOrgs = true
    var onResize: (() -> Unit)? = null
    private val extraTextures = mutableListOf<Texture>()

    private var initialZoom = 0f
    private var currentPinchCenter: Vector2? = null


    override fun show() {
        spriteBatch = CoreDesktopShaders.newBatch()
        stage = CoreDesktopShaders.newStage(ScreenViewport())
        fontMatrix = Matrix4()
        shapeRenderer = CoreDesktopShaders.newShapeRenderer()

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
        DISimulationContainer.resizeWorld()

        simulationSystem.startThread()
        root = Table()
        root.setFillParent(true)
        stage.addActor(root)

        genomeNames = genomeManager.genomes.map { it.name }

        rebuildMenu()
        currentScreenWidth = Gdx.graphics.width
        currentScreenHeight = Gdx.graphics.height

        renderSystem.create(
            fontMatrix = fontMatrix,
            spriteBatch = spriteBatch,
            font = font,
            shapeRenderer = shapeRenderer,
            camera = camera
        )

        DISimulationContainer.simulationSystem.initWorld(map)

        camera.zoom = 0.08f
        camera.position.x = gridWidth / 2f
        camera.position.y = gridHeight / 2f
//        camera.rotate(90f)
        camera.update()
    }

    val keyCodes = intArrayOf(
        Input.Keys.NUM_0, Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3,
        Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7,
        Input.Keys.NUM_8, Input.Keys.NUM_9,
        Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.D,
        Input.Keys.SPACE,
        Input.Keys.UP, Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.RIGHT
    )

    override fun render(delta: Float) {
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            for (i in 0 until 19) {
                simulationSystem.simulationData.controllerKeyTouched[i] =
                    Gdx.input.isKeyPressed(keyCodes[i])
            }
        }


        if (usePostProcess) {
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1f)
        } else {
            Gdx.gl.glClearColor(1.0f * 0.7f, 0.969f * 0.7f, 0.855f * 0.7f, 1.0f)
        }
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

//        if (Gdx.input.isKeyPressed(Input.Keys.SPACE)) {
//            val zoomFactor = if (true) 1.005f else 0.95f
//            val newZoom = MathUtils.clamp(camera.zoom * zoomFactor, 0.001f, 1000f)
//
//            camera.zoom = newZoom
//            camera.update()
//        }

        shapeRenderer.projectionMatrix = camera.combined

        renderSystem.render()

        stage.act(Gdx.graphics.deltaTime)
        stage.draw()

        if (Gdx.input.isKeyPressed(Input.Keys.Z)) {
            root.clear()
            isRenderUi = false
        }
        if (Gdx.input.isKeyPressed(Input.Keys.Y)) {
            rebuildMenu()
            isRenderUi = true
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return

        stage.viewport.update(width, height, true)

        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()

        font.data.setScale(Gdx.graphics.density)

        renderSystem.resize(width, height)
        val uiProjection = fontMatrix.setToOrtho2D(
            0f,
            0f,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat()
        )
        spriteBatch.projectionMatrix = uiProjection

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
        val dx = -deltaX * camera.zoom
        val dy = deltaY * camera.zoom
        val angle = -0/*90*/ * MathUtils.degreesToRadians
        val cos = MathUtils.cos(angle)
        val sin = MathUtils.sin(angle)

        val worldDx = dx * cos - dy * sin
        val worldDy = dx * sin + dy * cos
        //TODO есть подозрения что это вызывает краш cuncurent изменений
        if (userCommandManager.grabbedParticleIndex != -1) {
            val world = screenToWorld(x, y)
            userCommandManager.push(
                PlayerCommand.Drag(
                    world.first,
                    world.second,
                    worldDx,
                    worldDy
                )
            )
        } else {
            renderSystem.moveCamera(worldDx, worldDy)
        }
        return true
    }

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        if (currentPinchCenter == null) return false
        val centerX = currentPinchCenter!!.x
        val centerY = currentPinchCenter!!.y
        val screenPos = Vector3(centerX, centerY, 0f)
        val worldBefore = camera.unproject(screenPos.cpy())
        val ratio = initialDistance / distance
        camera.zoom = initialZoom * ratio
        camera.zoom = MathUtils.clamp(camera.zoom, 0.001f, 1000f)
        camera.update()
        val worldAfter = camera.unproject(screenPos.cpy())
        camera.position.add(worldBefore.x - worldAfter.x, worldBefore.y - worldAfter.y, 0f)
        return true
    }

    override fun pinch(
        initialPointer1: Vector2?,
        initialPointer2: Vector2?,
        pointer1: Vector2?,
        pointer2: Vector2?
    ): Boolean {
        if (initialPointer1 != null && initialPointer2 != null && currentPinchCenter == null) {
            initialZoom = camera.zoom
        }
        if (pointer1 == null || pointer2 == null) {
            currentPinchCenter = null
            return false
        }
        currentPinchCenter = pointer1.cpy().add(pointer2).scl(0.5f)
        return false
    }

    override fun pinchStop() {
        currentPinchCenter = null
        initialZoom = 0f
    }

    override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
        val world = screenToWorld(x, y)

        when (button) {
            Input.Buttons.LEFT -> {
                userCommandManager.push(PlayerCommand.Tap(world.first, world.second, isLeftButton = putOrgs))
            }
            Input.Buttons.RIGHT -> {
                userCommandManager.push(PlayerCommand.Tap(world.first, world.second, isLeftButton = !putOrgs))
            }
        }

        return true
    }

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val screenPos = Vector3(screenX, screenY, 0f)
        val worldPos = camera.unproject(screenPos)
        return Pair(worldPos.x, worldPos.y)
    }

    override fun longPress(x: Float, y: Float) = false
    override fun fling(dx: Float, dy: Float, button: Int): Boolean {
        userCommandManager.push(PlayerCommand.StopDrag)
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
        extraTextures.forEach { it.dispose() }
    }


    private fun rebuildMenu() {
        extraTextures.forEach { it.dispose() }
        extraTextures.clear()
        root.clear()

        root.top().left()

        val menuButton = makeStyledButton(
            if (genomeName == null) bundle.get("button.menu") else bundle.get("button.backToEditor"),
            game, extraTextures
        )
        val putOrganismToggle = makeStyledButton(bundle.get("button.putOrganism"), game, extraTextures, toggle = true)
        putOrganismToggle.isChecked = putOrgs
        val selectGenomeButton = makeStyledButton(bundle.get("button.selectGenome"), game, extraTextures)
        val speedUpSimToggle   = makeStyledButton(bundle.get("button.speedUp"),      game, extraTextures)
        val pauseSimToggle     = makeStyledButton(bundle.get("button.pause"),         game, extraTextures, toggle = true)
        pauseSimToggle.isChecked = !simEntity.isPlay
        val restartSimulationButton = makeStyledButton(bundle.get("button.restart"), game, extraTextures)
        val drawRaysToggle = makeStyledButton(bundle.get("button.drawRays"), game, extraTextures, toggle = true)
        drawRaysToggle.isChecked = usePostProcess

        val controllerKeysToggle = makeStyledButton("Controller Keys", game, extraTextures, toggle = true)
        controllerKeysToggle.isChecked = simulationSystem.simulationData.showControllerKeys

        val buttons = if (genomeName == null) {
            listOf(
                menuButton, putOrganismToggle, selectGenomeButton, speedUpSimToggle,
                pauseSimToggle, restartSimulationButton, drawRaysToggle, controllerKeysToggle
            )
        } else {
            listOf(
                menuButton, putOrganismToggle, speedUpSimToggle, pauseSimToggle,
                restartSimulationButton, drawRaysToggle, controllerKeysToggle
            )
        }

        val controls = Table()
        controls.defaults().pad(8f * Gdx.graphics.density).left() // Pad 8f around each cell, align left

        var currentWidth = 0f
        var rowTable = Table()
        rowTable.defaults().pad(8f * Gdx.graphics.density).left()

        for (button in buttons) {
            val prefWidth = button.prefWidth + 16f * Gdx.graphics.density
            if (currentWidth + prefWidth > Gdx.graphics.width && currentWidth > 0f) {
                controls.add(rowTable).growX().row()
                rowTable = Table()
                rowTable.defaults().padLeft(8f * Gdx.graphics.density).padRight(8f * Gdx.graphics.density).left()
                currentWidth = 0f
            }
            rowTable.add(button).height(Gdx.graphics.height * 0.05f)
            currentWidth += prefWidth
        }
        if (rowTable.hasChildren()) {
            controls.add(rowTable).growX()
        }

        root.add(controls).growX().top().left().row()

        // === НОВАЯ КЛАВИАТУРА (3 строки) ===
        if (simulationSystem.simulationData.showControllerKeys) {

            val density = Gdx.graphics.density
            val screenW = minOf(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

            // Размеры кнопок
            val keySize = screenW * 0.075f          // ~8.5% ширины экрана
            val spaceSize = screenW * 0.38f         // ~38% ширины экрана

            // === 1. ЦИФРЫ (0-9) — полноширинная строка под верхними кнопками ===
            val numbersTable = Table()
            numbersTable.defaults().pad(3f * density).width(keySize).height(keySize)

            for (num in 0..9) {
                val btn = makeStyledButton(num.toString(), game, extraTextures)

                // === Слушатель для цифр ===
                val index = num          // 0..9
                btn.addListener(object : ClickListener() {
                    override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                        simulationSystem.simulationData.controllerKeyTouched[index] = true
                        return true
                    }
                    override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                        simulationSystem.simulationData.controllerKeyTouched[index] = false
                    }
                })
                numbersTable.add(btn)
            }
            root.add(numbersTable).growX().top().left().padTop(8f * density).row()

            // === Spacer, который выталкивает нижнюю клавиатуру вниз ===
            root.add().growY().row()

            // === 2. НИЖНЯЯ КЛАВИАТУРА (WASD + Space + стрелки) ===
            val bottomKeyboard = Table()
            bottomKeyboard.defaults().pad(4f * density).left()

            // Строка 2: W                    ↑   (W над S, ↑ над ↓)
            val row2 = Table()

            // таблица занимает всю ширину
            row2.defaults().height(keySize)

            // Левая кнопка
            val wBtn = makeStyledButton("W=10", game, extraTextures)
//            applyCustomFont(wBtn)

            wBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[10] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[10] = false
                }
            })

            // Правая кнопка
            val upBtn = makeStyledButton("^=15", game, extraTextures)
//            applyCustomFont(upBtn)

            upBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[15] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[15] = false
                }
            })

            // Левая кнопка
            row2.add(wBtn)
                .padLeft(keySize + 8f * density)
                .width(keySize)
                .left()

            // Пустое растягивающееся пространство
            row2.add()
                .growX()

            // Правая кнопка
            row2.add(upBtn)
                .padRight(keySize + 8f * density)
                .width(keySize)
                .right()

            bottomKeyboard.add(row2)
                .growX()
                .row()

            // Строка 3: A S D   (SPACE)   ← ↓ →
            val row3 = Table()
            row3.defaults().pad(3f * density).height(keySize)

// Левая группа -------------------------------------------------------------

            val leftGroup = Table()
            leftGroup.defaults().pad(3f * density).width(keySize).height(keySize)

// A (индекс 11)
            val aBtn = makeStyledButton("A=11", game, extraTextures)
//            applyCustomFont(aBtn)

            aBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[11] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[11] = false
                }
            })

            leftGroup.add(aBtn)

// S (индекс 12)
            val sBtn = makeStyledButton("S=12", game, extraTextures)
//            applyCustomFont(sBtn)

            sBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[12] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[12] = false
                }
            })

            leftGroup.add(sBtn)

// D (индекс 13)
            val dBtn = makeStyledButton("D=13", game, extraTextures)
//            applyCustomFont(dBtn)

            dBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[13] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[13] = false
                }
            })

            leftGroup.add(dBtn)


// Центральная кнопка -------------------------------------------------------

            val spaceBtn = makeStyledButton("(SPACE)=14", game, extraTextures)
//            applyCustomFont(spaceBtn)

            spaceBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[14] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[14] = false
                }
            })


// Правая группа ------------------------------------------------------------

            val rightGroup = Table()
            rightGroup.defaults().pad(3f * density).width(keySize).height(keySize)

// ← (индекс 16)
            val leftBtn = makeStyledButton("<=16", game, extraTextures)
//            applyCustomFont(leftBtn)

            leftBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[16] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[16] = false
                }
            })

            rightGroup.add(leftBtn)

// ↓ (индекс 17)
            val downBtn = makeStyledButton("v=17", game, extraTextures)
//            applyCustomFont(downBtn)

            downBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[17] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[17] = false
                }
            })

            rightGroup.add(downBtn)

// → (индекс 18)
            val rightBtn = makeStyledButton(">=18", game, extraTextures)
//            applyCustomFont(rightBtn)

            rightBtn.addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    simulationSystem.simulationData.controllerKeyTouched[18] = true
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    simulationSystem.simulationData.controllerKeyTouched[18] = false
                }
            })

            rightGroup.add(rightBtn)


// Компоновка ---------------------------------------------------------------

            row3.add(leftGroup).left().expandX()

            row3.add(spaceBtn)
                .width(spaceSize)
                .center()

            row3.add(rightGroup).right().expandX()

            bottomKeyboard.add(row3).growX().row()

            root.add(bottomKeyboard)
                .growX()
                .bottom()
                .left()
                .padBottom(30f * density)
                .row()
        }

        // === Слушатели (Listeners) ===

        controllerKeysToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                simulationSystem.simulationData.showControllerKeys = controllerKeysToggle.isChecked
                rebuildMenu() // перестраиваем меню, чтобы показать/скрыть клавиатуру
            }
        })

        // ... остальные слушатели остаются без изменений ...
        speedUpSimToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                SpeedUpDialog(game, bundle).show(stage)
            }
        })

        drawRaysToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                usePostProcess = drawRaysToggle.isChecked
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
                    game.screen = GenomeEditorScreen(game, genomeName.replace(".json", ""))
                }
            }
        })


        selectGenomeButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GenomeListDialog(
                    genomesList = genomeManager.genomes.map { it.name },
                    selectedGenomeIndex = simulationSystem.simulationData.currentGenomeIndex,
                    title = bundle.get("button.selectGenome"),
                    new = bundle.get("button.new"),
                    select = bundle.get("button.select"),
                    import = bundle.get("button.import"),
                    onNew = {
                        game.screen.dispose()
                        game.screen = GenomeEditorScreen(
                            game,
                            genomeName = null
                        )
                    },
                    onNext = { genomeName ->
                        println("$genomeName ${genomeNames.indexOf(genomeName)}")
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
