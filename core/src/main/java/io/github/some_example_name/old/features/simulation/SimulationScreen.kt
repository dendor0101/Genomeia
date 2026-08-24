package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.ui.CameraControl
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.menu.MenuScreen
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.render.RenderSettings

class SimulationScreen(
    /** Рецепт мира из редактора. null — мир не из редактора (тест генома). */
    val world: WorldSpec?,
    val genomeName: String?
) : Screen {

    private val simEntity = DISimulationContainer.simulationData
    private val simulationSystem = DISimulationContainer.simulationSystem
    private val renderSystem = DISimulationContainer.renderSystem
    private val userCommandManager = DISimulationContainer.userCommandManager

    private val camera = OrthographicCamera().apply { setToOrtho(false, w, h) }

    val cameraControl = CameraControl(
        camera = camera,
        zoom = 0.08f,
        positionX = DISimulationContainer.gridWidth / 2f,
        positionY = DISimulationContainer.gridHeight / 2f,
        onTouchDown = { x, y, isLeft ->
            if (isLeft) {
                userCommandManager.push(PlayerCommand.TouchDown(x, y, isLeftButton = true))
            } else {
                userCommandManager.push(PlayerCommand.TouchDown(x, y, isLeftButton = false))
            }
        },
        onTap = { x, y, isLeft ->
            if (isLeft) {
                userCommandManager.push(PlayerCommand.Tap(x, y, isLeftButton = putOrgs))
            } else {
                userCommandManager.push(PlayerCommand.Tap(x, y, isLeftButton = !putOrgs))
            }
        },
        onFling = {
            userCommandManager.push(PlayerCommand.StopDrag)
        },
        onPan = { x, y, dx, dy ->
            if (userCommandManager.grabbedParticleIndex != -1) {
                userCommandManager.push(
                    PlayerCommand.Drag(x, y, dx, dy)
                )
            } else {
                renderSystem.moveCamera(dx, dy)
            }
        }
    )

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

    override fun show() {
        DISimulationContainer.genomeManager.loadGenomes(genomeName)

        spriteBatch = SpriteBatch()
        stage = Stage(ScreenViewport())
        fontMatrix = Matrix4()
        shapeRenderer = ShapeRenderer()

        Gdx.input.inputProcessor = cameraControl.getInputMultiplexer(stage)

        font = BitmapFont()
        // Масштабируем шрифт симуляционной информации под DPI (density)
        // Это обеспечивает корректный размер текста при любом разрешении/DPI
        font.data.setScale(Gdx.graphics.density)
        DISimulationContainer.resizeWorld()

        root = Table()
        root.setFillParent(true)
        stage.addActor(root)

        // Здесь именно имена ФАЙЛОВ, а не Genome.name.
        //
        // Индекс в этом списке используется как индекс в genomeManager.genomes, а тот
        // наполняется в loadGenomes ровно обходом getGenomeFileNamesFromFolder. Внутреннее
        // имя генома с именем файла совпадать не обязано: файл может быть переименован,
        // а при импорте на Android имя файла вообще приходило с префиксом (см.
        // AndroidFileProvider.sanitizeGenomeFileName). Диалог выбора отдаёт обратно именно
        // имя файла, поэтому и искать индекс надо в списке имён файлов.
        genomeNames = if (genomeName == null) {
            DIGameGlobalContainer.genomeJsonReader.getGenomeFileNamesFromFolder()
        } else {
            listOf(genomeName)
        }

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

        DISimulationContainer.worldTerrainManager.map = world?.buildMap()
        simulationSystem.initMap()

        // Поток симуляции стартует ТОЛЬКО после того, как мир создан.
        //
        // Раньше startThread() стоял выше по show(), а initMap() — здесь, и оба вызова
        // идут с GL-потока. То есть initWorld создавал десятки тысяч частиц, пока поток
        // симуляции уже крутил тики: addParticle мутирует aliveList/lastId/deadStack
        // сущности и pending/pendingCount в GridManager, а тик в это же время читает их
        // и в конце rebuild делает pendingCount = 0.
        //
        // Частица, зарегистрированная между захватом pendingSize в rebuild и обнулением
        // счётчика, теряется безвозвратно: в particleIdx она не попала, а из pending её
        // стёрло. Дальше она жива, имеет корректный gridId, рисуется из aliveList — но
        // в сетке её нет, поэтому по ней не считаются столкновения и её не берёт курсор.
        //
        // На экране это горизонтальные полосы, потому что initWorld заполняет карту
        // построчно, а rebuild случается раз в тик: каждый тик терялась очередная пачка
        // подряд идущих строк. Шаг полос — сколько строк GL-поток успевал создать за один
        // тик, и он лишь по случайности похож на высоту чанка.
        //
        // На десктопе заполнение карты укладывалось в пару тиков, поэтому эффект был почти
        // невидим; на Android те же десятки тысяч addParticle идут в разы дольше, и окно
        // гонки растягивалось на десятки тиков.
        simulationSystem.startThread()
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

        // Отладочный стресс-тест жизненного цикла клеток: A убивает случайные клетки,
        // D разгоняет их до предела, чтобы они улетали и рвали свои связи. Пока клавиша
        // зажата — каждый тик. Нужен, чтобы гонки и битые ссылки в связях воспроизводились
        // сами, а не руками.
        //
        // A и D заняты управлением организмом (controllerKeyTouched), но в обычной сборке
        // этот блок вырезается компилятором целиком, так что конфликт живёт только в
        // отладочной.
        if (DEBUG_CHECKS) {
            simulationSystem.simulationData.debugKillCells =
                Gdx.input.isKeyPressed(Input.Keys.A)
            simulationSystem.simulationData.debugFlingCells =
                Gdx.input.isKeyPressed(Input.Keys.D)
        }

        if (RenderSettings.usePostProcess) {
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1f)
        } else {
            Gdx.gl.glClearColor(1.0f * 0.7f, 0.969f * 0.7f, 0.855f * 0.7f, 1.0f)
        }
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined

        renderSystem.render()

        stage.act(Gdx.graphics.deltaTime)
        stage.draw()

        if (Gdx.input.isKeyPressed(Input.Keys.Z)) {
            root.clear()
            renderSystem.isRenderUi = false
        }
        if (Gdx.input.isKeyPressed(Input.Keys.Y)) {
            rebuildMenu()
            renderSystem.isRenderUi = true
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
        // Приложение свёрнуто: воркерам разрешаем спать долго. Без этого каждый из них
        // просыпается по таймауту парковки сотни раз в секунду всё время, что игра висит
        // в фоне, — на телефоне это заметный расход батареи впустую.
        DISimulationContainer.threadManager.executor.setBackground(true)
    }

    override fun resume() {
        simEntity.isPlay = true
        DISimulationContainer.threadManager.executor.setBackground(false)
    }

    override fun hide() { }

    override fun dispose() {
        renderSystem.dispose()
        simulationSystem.simulationData.isFinish = true
        simulationSystem.stopUpdateThread()
        // Пул воркеров живёт в синглтоне контейнера и раньше не останавливался никогда.
        // На десктопе это сходило с рук: потоки daemon, и они умирали вместе с JVM. На
        // Android процесс переживает Activity, поэтому после выхода в меню пул продолжал
        // бы просыпаться по таймауту до самой смерти процесса. Порядок важен: сначала
        // останавливается поток симуляции, только потом пул, которым он пользуется.
        DISimulationContainer.threadManager.dispose()
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
            if (genomeName == null) DIGameGlobalContainer.bundle.get("button.menu") else DIGameGlobalContainer.bundle.get(
                "button.backToEditor"
            ),
            DIGameGlobalContainer.game, extraTextures
        )
        val putOrganismToggle =
            makeStyledButton(
                DIGameGlobalContainer.bundle.get("button.putOrganism"),
                DIGameGlobalContainer.game,
                extraTextures,
                toggle = true
            )
        putOrganismToggle.isChecked = putOrgs
        val selectGenomeButton =
            makeStyledButton(
                DIGameGlobalContainer.bundle.get("button.selectGenome"),
                DIGameGlobalContainer.game,
                extraTextures
            )
        val speedUpSimToggle   = makeStyledButton(
            DIGameGlobalContainer.bundle.get("button.speedUp"),
            DIGameGlobalContainer.game,
            extraTextures
        )
        val pauseSimToggle     =
            makeStyledButton(
                DIGameGlobalContainer.bundle.get("button.pause"),
                DIGameGlobalContainer.game,
                extraTextures,
                toggle = true
            )
        pauseSimToggle.isChecked = !simEntity.isPlay
        val restartSimulationButton =
            makeStyledButton(
                DIGameGlobalContainer.bundle.get("button.restart"),
                DIGameGlobalContainer.game,
                extraTextures
            )
        val drawRaysToggle =
            makeStyledButton(
                DIGameGlobalContainer.bundle.get("button.drawRays"),
                DIGameGlobalContainer.game,
                extraTextures,
                toggle = true
            )
        drawRaysToggle.isChecked = RenderSettings.usePostProcess

        val controllerKeysToggle =
            makeStyledButton(
                "Controller Keys",
                DIGameGlobalContainer.game,
                extraTextures,
                toggle = true
            )
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
                val btn =
                    makeStyledButton(num.toString(), DIGameGlobalContainer.game, extraTextures)

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
            val wBtn = makeStyledButton("W=10", DIGameGlobalContainer.game, extraTextures)
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
            val upBtn = makeStyledButton("^=15", DIGameGlobalContainer.game, extraTextures)
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
            val aBtn = makeStyledButton("A=11", DIGameGlobalContainer.game, extraTextures)
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
            val sBtn = makeStyledButton("S=12", DIGameGlobalContainer.game, extraTextures)
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
            val dBtn = makeStyledButton("D=13", DIGameGlobalContainer.game, extraTextures)
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

            val spaceBtn = makeStyledButton("(SPACE)=14", DIGameGlobalContainer.game, extraTextures)
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
            val leftBtn = makeStyledButton("<=16", DIGameGlobalContainer.game, extraTextures)
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
            val downBtn = makeStyledButton("v=17", DIGameGlobalContainer.game, extraTextures)
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
            val rightBtn = makeStyledButton(">=18", DIGameGlobalContainer.game, extraTextures)
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
                SpeedUpDialog(DIGameGlobalContainer.game, DIGameGlobalContainer.bundle).show(stage)
            }
        })

        drawRaysToggle.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                RenderSettings.usePostProcess = drawRaysToggle.isChecked
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
                // Через менеджер, а не game.screen = ...: иначе переход не виден журналу,
                // а стек остаётся от прошлого маршрута — «Назад» в меню уводило бы туда,
                // откуда игрок уже вышел.
                DIGameGlobalContainer.navigationCommandsManager.performCommand(
                    if (genomeName == null) {
                        GoReplace(GoMenu)
                    } else {
                        GoReplace(GoGenomeEditor(genomeName.replace(".json", "")))
                    }
                )
            }
        })


        selectGenomeButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GenomeListDialog(
                    genomesList = DIGameGlobalContainer.genomeJsonReader.getGenomeFileNamesFromFolder()/*genomeManager.genomes.map { it.name }*/,
                    selectedGenomeIndex = simulationSystem.simulationData.currentGenomeIndex,
                    title = DIGameGlobalContainer.bundle.get("button.selectGenome"),
                    new = DIGameGlobalContainer.bundle.get("button.new"),
                    select = DIGameGlobalContainer.bundle.get("button.select"),
                    import = DIGameGlobalContainer.bundle.get("button.import"),
                    onNew = {
                        DIGameGlobalContainer.navigationCommandsManager.performCommand(
                            GoReplace(GoGenomeEditor(null))
                        )
                    },
                    onNext = { selectedFileName ->
                        val index = genomeNames.indexOf(selectedFileName)
                        if (index >= 0) {
                            simulationSystem.simulationData.currentGenomeIndex = index
                        } else {
                            // Раньше -1 молча уезжал в currentGenomeIndex, а падало уже
                            // потом и в другом месте — на genomes[-1] в момент постановки
                            // клетки, то есть в потоке симуляции.
                            Gdx.app.error(
                                "Genome",
                                "геном '$selectedFileName' не найден среди $genomeNames"
                            )
                        }
                    },
                    onRestart = {
                        // Импортированный геном должен появиться и в genomeManager.genomes,
                        // и в списке имён — иначе индексы разъедутся. Раньше сюда
                        // подмешивались ещё и json-геномы из ассетов, которых в genomes нет
                        // вообще, что сдвигало индексы всех остальных.
                        DISimulationContainer.genomeManager.loadGenomes()
                        genomeNames = DIGameGlobalContainer.genomeJsonReader
                            .getGenomeFileNamesFromFolder()
                    },
                    game = DIGameGlobalContainer.game,
                    onResize = { handler ->
                        onResize = if (handler == {}) null else handler
                    },
                    isMenu = false
                ).show(stage)
            }
        })
    }
}
