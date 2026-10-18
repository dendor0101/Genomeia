package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.ui.ConfirmDialog
import io.github.some_example_name.old.core.ui.KeyboardFocus
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.ui.CameraControl
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.renderModeSelectBox
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.simulation.ControllerKeys
import io.github.some_example_name.render.debug.RenderSceneDump
import java.io.File

class SimulationScreen(
    /** Рецепт мира из редактора. null — мир не из редактора (тест генома). */
    val world: WorldSpec?,
    val genomeName: String?,
    /** Слот сохранённого мира: мир загружается из него, см. MapSave. */
    val savedWorld: String? = null
) : Screen, ReplayTarget {

    private val simulationSystem = DISimulationContainer.simulationSystem
    private val renderSystem = DISimulationContainer.renderSystem
    private val userCommandManager = DISimulationContainer.userCommandManager

    private val camera = OrthographicCamera().apply { setToOrtho(false, w, h) }

    /**
     * Все кнопки экрана — через неё. Сам экран состояние не меняет: он собирает команду
     * из нажатия и показывает результат, см. GameCommand.
     */
    private val viewModel = SimulationViewModel(
        genomeName = genomeName,
        world = world,
        savedWorld = savedWorld,
        camera = camera
    )

    val cameraControl = CameraControl(
        camera = camera,
        zoom = 0.08f,
        // Центр мира ставится в show(), когда размер сетки уже взят из мира.
        onTouchDown = { x, y, isLeft ->
            userCommandManager.push(PlayerCommand.TouchDown(x, y, isLeftButton = isLeft))
        },
        onTap = { x, y, isLeft ->
            // «Поставить организм» меняет кнопки местами. Решается здесь, при создании
            // команды: в запись сессии уходит уже итоговая кнопка.
            val putOrganism = viewModel.putOrganism
            userCommandManager.push(PlayerCommand.Tap(x, y, isLeftButton = if (isLeft) putOrganism else !putOrganism))
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
                viewModel.handle(SimulationIntent.MoveCamera(dx, dy))
            }
        },
        onZoom = { zoom, x, y ->
            viewModel.handle(SimulationIntent.ZoomCamera(zoom, x, y))
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

    var onResize: (() -> Unit)? = null
    private val extraTextures = mutableListOf<Texture>()

    /** Кнопки, которым экран меняет вид по эффектам ViewModel. Пересоздаются в rebuildMenu. */
    private var pauseSimToggle: VisTextButton? = null
    private var saveWorldButton: VisTextButton? = null

    /**
     * Последнее состояние клавиш контроллера НА КЛАВИАТУРЕ. Команда уходит только при
     * смене состояния: опрос идёт каждый кадр, а журналу нужно нажатие, а не кадр.
     */
    private val keyboardKeys = BooleanArray(CONTROLLER_KEY_CODES.size)

    override fun show() {
        viewModel.loadGenomes()
        viewModel.onEffect = ::applyEffect

        spriteBatch = SpriteBatch()
        stage = Stage(ScreenViewport())
        fontMatrix = Matrix4()
        shapeRenderer = ShapeRenderer()

        Gdx.input.inputProcessor = cameraControl.getInputMultiplexer(stage)

        font = BitmapFont()
        // Масштабируем шрифт симуляционной информации под DPI (density)
        // Это обеспечивает корректный размер текста при любом разрешении/DPI
        font.data.setScale(Gdx.graphics.density)

        root = Table()
        root.setFillParent(true)
        stage.addActor(root)

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

        // Построение мира и запуск потока симуляции — в ViewModel, см. startWorld.
        val started = viewModel.startWorld()
        // Размер сетки известен только теперь: его задаёт рецепт или сохранение мира.
        camera.position.set(DISimulationContainer.gridWidth / 2f, DISimulationContainer.gridHeight / 2f, 0f)
        camera.update()
        if (!started) showLoadError()
    }

    private fun showLoadError() {
        val game = DIGameGlobalContainer.game
        val bundle = DIGameGlobalContainer.bundle
        VisDialog(bundle.get("dialog.worlds")).apply {
            isModal = true
            setupTitleSize(game)
            val message = VisLabel(bundle.get("label.loadFailed"))
            game.applyCustomFontMedium(message)
            contentTable.add(message).pad(16f * Gdx.graphics.density)
            closeOnEscape()
            pack()
            centerWindow()
        }.show(stage)
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is SimulationIntent) return false
        viewModel.handle(command)
        return true
    }

    override fun onReplayFinished() = applyEffect(SimulationEffect.PauseChanged)

    /** Вёрстка того, что попросила ViewModel. */
    private fun applyEffect(effect: SimulationEffect) {
        when (effect) {
            SimulationEffect.RebuildMenu -> rebuildMenu()

            SimulationEffect.PauseChanged -> pauseSimToggle?.isChecked = viewModel.isPaused

            is SimulationEffect.WorldSaved -> saveWorldButton?.let {
                flashText(it, if (effect.success) "label.saved" else "label.saveFailed")
            }
        }
    }

    /**
     * «Сохранить»: имя мира, а если под ним лежит другой мир — вопрос, перезаписать ли.
     * Оба окна только спрашивают; сохраняет команда SaveWorld с уже решённым именем.
     */
    private fun showSaveDialog() {
        val bundle = DIGameGlobalContainer.bundle
        lateinit var dialog: SaveWorldDialog
        dialog = SaveWorldDialog(viewModel.suggestedSaveName(bundle.get("map.defaultName"))) { name ->
            if (!viewModel.wouldOverwrite(name)) {
                dialog.fadeOut()
                viewModel.handle(SimulationIntent.SaveWorld(name))
                return@SaveWorldDialog
            }
            ConfirmDialog(
                title = bundle.get("dialog.saveWorld"),
                message = bundle.format("label.overwriteMap", name),
                confirm = bundle.get("button.overwrite"),
                cancel = bundle.get("button.otherName"),
                onConfirm = {
                    dialog.fadeOut()
                    viewModel.handle(SimulationIntent.SaveWorld(name))
                },
                onCancel = { dialog.editName() }
            ).show(stage)
        }
        dialog.show(stage)
    }

    /** Ненадолго подменить надпись на кнопке — единственный отклик на сохранение. */
    private fun flashText(button: VisTextButton, key: String) {
        val original = button.text.toString()
        button.setText(DIGameGlobalContainer.bundle.get(key))
        button.clearActions()
        button.addAction(Actions.delay(1.5f, Actions.run { button.setText(original) }))
    }

    override fun render(delta: Float) {
        // Пока игрок печатает (окно отчёта, поле ввода), клавиатура — его тексту, а не игре:
        // иначе «в» в описании бага — это клавиша D, а «н» — Y. Нажатые клавиши при этом
        // отпускаются, чтобы не залипли.
        val typing = KeyboardFocus.isTyping(stage)
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            for (i in CONTROLLER_KEY_CODES.indices) {
                val pressed = !typing && Gdx.input.isKeyPressed(CONTROLLER_KEY_CODES[i])
                if (pressed != keyboardKeys[i]) {
                    keyboardKeys[i] = pressed
                    viewModel.handle(SimulationIntent.SetControllerKey(i, pressed))
                }
            }
        }

        // Отладочный стресс-тест (A убивает клетки, D разгоняет их) берёт клавиши из снимка
        // контроллера в потоке симуляции — см. SimulationSystem.latchControllerKeys.

        // Фон здесь не важен ни в одном режиме: реалистичный закрывает экран постпроцессом,
        // а отладочный и ностальгический рисуют свой фон сами — он часть их рисунка.
        // Раньше под отладкой здесь заливался бежевый: тогда отладка рисовала клетки прямо
        // поверх очищенного экрана.
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined

        renderSystem.render()

        stage.act(Gdx.graphics.deltaTime)
        stage.draw()

        if (!typing && Gdx.input.isKeyJustPressed(Input.Keys.Z)) {
            viewModel.handle(SimulationIntent.SetUiVisible(false))
        }
        if (!typing && Gdx.input.isKeyJustPressed(Input.Keys.Y)) {
            viewModel.handle(SimulationIntent.SetUiVisible(true))
        }

        // ВРЕМЕННОЕ: F9 выгружает вход рендера в файл для стенда RenderLab.
        //
        // Именно после renderSystem.render(), а не до: пишется тот кадр, который только
        // что ушёл на GPU. Открывается потом так:
        //   gradlew :render:renderLab
        // Удалять — вместе с dumpSceneToFile ниже.
        if (Gdx.app.type == Application.ApplicationType.Desktop && !typing &&
            Gdx.input.isKeyJustPressed(Input.Keys.F9)
        ) {
            dumpSceneToFile()
        }
    }

    /**
     * ВРЕМЕННОЕ: см. обработчик F9 выше.
     *
     * Пишется два файла: с меткой времени — чтобы прошлые снимки не затирались, и
     * scene-last.scene — чтобы стенд можно было запускать вообще без аргументов.
     */
    private fun dumpSceneToFile() {
        val stamp = System.currentTimeMillis()
        try {
            val directory = sceneDumpDirectory()
            val file = directory.child("scene-$stamp.${RenderSceneDump.EXTENSION}")
            renderSystem.dumpScene(file)
            renderSystem.dumpScene(directory.child("scene-last.${RenderSceneDump.EXTENSION}"))
            // Путь именно АБСОЛЮТНЫЙ: относительный ничего не говорит, пока не знаешь
            // рабочий каталог, а он у игры разный — см. sceneDumpDirectory.
            Gdx.app.log("SceneDump", "сцена выгружена: ${file.file().absolutePath}")
        } catch (e: Exception) {
            Gdx.app.error("SceneDump", "не удалось выгрузить сцену", e)
        }
    }

    /**
     * Куда складывать дампы.
     *
     * Рабочий каталог у игры РАЗНЫЙ: задача gradlew :lwjgl3:run запускает её из assets/
     * (так надо шейдерам и текстурам), а из IDE она стартует в корне проекта. Поэтому
     * жёсткий относительный путь верен ровно в одном из двух случаев — и промах тихий:
     * файл пишется, лог рапортует об успехе, а в проекте ничего не появляется.
     *
     * Отсюда: пляшем от рабочего каталога, но если это assets — поднимаемся на уровень
     * выше. Складывать дампы в assets нельзя ни при каком раскладе, их подхватит
     * generateAssetList и они уедут в сборку игры.
     *
     * Переопределяется: -Dgenomeia.sceneDumpDir=C:\куда\надо
     */
    private fun sceneDumpDirectory(): FileHandle {
        System.getProperty("genomeia.sceneDumpDir")?.let { return Gdx.files.absolute(it) }

        val working = File("").absoluteFile
        val root = if (working.name.equals("assets", ignoreCase = true)) working.parentFile else working
        return Gdx.files.absolute(File(root, "scene-dumps").absolutePath)
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
        viewModel.handle(SimulationIntent.AppPaused)
    }

    override fun resume() {
        viewModel.handle(SimulationIntent.AppResumed)
    }

    override fun hide() { }

    override fun dispose() {
        viewModel.stopWorld()
        renderSystem.dispose()
        simulationSystem.simulationData.isFinish = true
        simulationSystem.stopUpdateThread()
        // isFinish обрабатывает тик, но цикл мог остановиться раньше, чем до него дошёл
        // (на паузе — наверняка). Тогда флаг пережил бы экран, и первый тик следующего
        // захода снёс бы только что построенный мир, а запись сессии осталась бы
        // незакрытой. Поток уже остановлен, так что дочищаем отсюда.
        if (simulationSystem.simulationData.isFinish) simulationSystem.dispose()
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
        pauseSimToggle = null
        saveWorldButton = null

        // Интерфейс спрятан (клавиша Z) — только мир, без единой кнопки.
        if (!viewModel.isUiVisible) return

        root.top().left()

        val bundle = DIGameGlobalContainer.bundle
        val game = DIGameGlobalContainer.game

        val menuButton = makeStyledButton(
            if (genomeName == null) bundle.get("button.menu") else bundle.get("button.backToEditor"),
            game, extraTextures
        ).onClick {
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

        val putOrganismToggle = makeStyledButton(bundle.get("button.putOrganism"), game, extraTextures, toggle = true)
        putOrganismToggle.isChecked = viewModel.putOrganism
        putOrganismToggle.onClick {
            viewModel.handle(SimulationIntent.SetPutOrganism(putOrganismToggle.isChecked))
        }

        val selectGenomeButton = makeStyledButton(bundle.get("button.selectGenome"), game, extraTextures).onClick {
            showGenomeList()
        }

        val speedUpButton = makeStyledButton(bundle.get("button.speedUp"), game, extraTextures).onClick {
            SpeedUpDialog(
                game = game,
                bundle = bundle,
                onTargetUps = { viewModel.handle(SimulationIntent.SetTargetUps(it)) },
                onMaxSpeed = { viewModel.handle(SimulationIntent.SetMaxSpeed(it)) }
            ).show(stage)
        }

        val pauseToggle = makeStyledButton(bundle.get("button.pause"), game, extraTextures, toggle = true)
        pauseToggle.isChecked = viewModel.isPaused
        pauseToggle.onClick {
            viewModel.handle(SimulationIntent.SetPaused(pauseToggle.isChecked))
        }
        pauseSimToggle = pauseToggle

        val restartSimulationButton = makeStyledButton(bundle.get("button.restart"), game, extraTextures).onClick {
            viewModel.handle(SimulationIntent.Restart)
        }

        // Режим — свой у симуляции: ни меню, ни редактор генома его не видят.
        val renderModeSelect = renderModeSelectBox(
            selected = renderSystem.mode,
            onSelect = { viewModel.handle(SimulationIntent.SetRenderMode(it)) }
        )

        val controllerKeysToggle = makeStyledButton("Controller Keys", game, extraTextures, toggle = true)
        controllerKeysToggle.isChecked = simulationSystem.simulationData.showControllerKeys
        controllerKeysToggle.onClick {
            // Панель пересоберёт эффект RebuildMenu: клавиатуру надо показать или спрятать.
            viewModel.handle(SimulationIntent.SetControllerKeysVisible(controllerKeysToggle.isChecked))
        }

        val saveButton = makeStyledButton(bundle.get("button.save"), game, extraTextures).onClick {
            showSaveDialog()
        }
        saveWorldButton = saveButton

        // Сохранение только у обычного мира: тест генома — временный мир, из которого
        // возвращаются в редактор.
        val buttons: List<Actor> = if (genomeName == null) {
            listOf(
                menuButton, putOrganismToggle, selectGenomeButton, speedUpButton,
                pauseToggle, restartSimulationButton, renderModeSelect, controllerKeysToggle,
                saveButton
            )
        } else {
            listOf(
                menuButton, putOrganismToggle, speedUpButton, pauseToggle,
                restartSimulationButton, renderModeSelect, controllerKeysToggle
            )
        }

        val controls = Table()
        controls.defaults().pad(8f * Gdx.graphics.density).left() // Pad 8f around each cell, align left

        var currentWidth = 0f
        var rowTable = Table()
        rowTable.defaults().pad(8f * Gdx.graphics.density).left()

        for (button in buttons) {
            // Кнопки и список режима — разные виджеты, общее у них только Layout.
            val prefWidth = (button as Layout).prefWidth + 16f * Gdx.graphics.density
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

        if (simulationSystem.simulationData.showControllerKeys) {
            controllerKeyboard()
        }
    }

    /**
     * Экранная клавиатура контроллера: цифры сверху, WASD, пробел и стрелки снизу.
     * Индексы клавиш — те же, что в [CONTROLLER_KEY_CODES].
     */
    private fun controllerKeyboard() {
        val density = Gdx.graphics.density
        val screenW = minOf(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

        // Размеры кнопок
        val keySize = screenW * 0.075f          // ~8.5% ширины экрана
        val spaceSize = screenW * 0.38f         // ~38% ширины экрана

        // === 1. ЦИФРЫ (0-9) — полноширинная строка под верхними кнопками ===
        val numbersTable = Table()
        numbersTable.defaults().pad(3f * density).width(keySize).height(keySize)
        for (num in 0..9) {
            numbersTable.add(controllerKeyButton(num.toString(), index = num))
        }
        root.add(numbersTable).growX().top().left().padTop(8f * density).row()

        // === Spacer, который выталкивает нижнюю клавиатуру вниз ===
        root.add().growY().row()

        // === 2. НИЖНЯЯ КЛАВИАТУРА (WASD + Space + стрелки) ===
        val bottomKeyboard = Table()
        bottomKeyboard.defaults().pad(4f * density).left()

        // Строка 2: W                    ↑   (W над S, ↑ над ↓)
        val row2 = Table()
        row2.defaults().height(keySize)
        row2.add(controllerKeyButton("W=10", index = 10))
            .padLeft(keySize + 8f * density)
            .width(keySize)
            .left()
        // Пустое растягивающееся пространство
        row2.add().growX()
        row2.add(controllerKeyButton("^=15", index = 15))
            .padRight(keySize + 8f * density)
            .width(keySize)
            .right()
        bottomKeyboard.add(row2).growX().row()

        // Строка 3: A S D   (SPACE)   ← ↓ →
        val row3 = Table()
        row3.defaults().pad(3f * density).height(keySize)

        val leftGroup = Table()
        leftGroup.defaults().pad(3f * density).width(keySize).height(keySize)
        leftGroup.add(controllerKeyButton("A=11", index = 11))
        leftGroup.add(controllerKeyButton("S=12", index = 12))
        leftGroup.add(controllerKeyButton("D=13", index = 13))

        val rightGroup = Table()
        rightGroup.defaults().pad(3f * density).width(keySize).height(keySize)
        rightGroup.add(controllerKeyButton("<=16", index = 16))
        rightGroup.add(controllerKeyButton("v=17", index = 17))
        rightGroup.add(controllerKeyButton(">=18", index = 18))

        row3.add(leftGroup).left().expandX()
        row3.add(controllerKeyButton("(SPACE)=14", index = 14))
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

    /** Клавиша экранной клавиатуры: нажата, пока на ней палец. */
    private fun controllerKeyButton(text: String, index: Int): VisTextButton =
        makeStyledButton(text, DIGameGlobalContainer.game, extraTextures).apply {
            addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    viewModel.handle(SimulationIntent.SetControllerKey(index, pressed = true))
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    viewModel.handle(SimulationIntent.SetControllerKey(index, pressed = false))
                }
            })
        }

    /**
     * Выбор генома для посадки: свои, а под чертой — встроенные, их можно сажать сразу.
     * Список — тот же, что у ViewModel: номер в нём и есть номер генома в мире.
     */
    private fun showGenomeList() {
        val bundle = DIGameGlobalContainer.bundle
        val genomes = viewModel.genomeNames
        GenomeListDialog(
            genomesList = genomes,
            selectedGenomeIndex = simulationSystem.simulationData.currentGenomeIndex,
            title = bundle.get("button.selectGenome"),
            new = bundle.get("button.new"),
            select = bundle.get("button.select"),
            import = bundle.get("button.import"),
            onNew = {
                DIGameGlobalContainer.navigationCommandsManager.performCommand(
                    GoReplace(GoGenomeEditor(null))
                )
            },
            onNext = { selectedFileName ->
                viewModel.handle(SimulationIntent.SelectGenome(selectedFileName))
            },
            game = DIGameGlobalContainer.game,
            onResize = { handler ->
                onResize = if (handler == {}) null else handler
            },
            displayName = GenomeCatalog::displayName,
            sectionStart = genomes.indexOfFirst(GenomeCatalog::isBundled),
            sectionTitle = bundle.get("label.bundledGenomes")
        ).show(stage)
    }

    /** Обработчик нажатия в одну строку: здесь их много, и все устроены одинаково. */
    private fun VisTextButton.onClick(action: () -> Unit): VisTextButton = apply {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = action()
        })
    }

    private companion object {
        /**
         * Клавиши контроллера на клавиатуре — по индексу в controllerKeyTouched:
         * 0–9 цифры, 10–13 WASD, 14 пробел, 15–18 стрелки. Порядок — тот же, что у имён
         * в ControllerKeys.NAMES: по имени клетка Controller находит номер клавиши.
         */
        val CONTROLLER_KEY_CODES = intArrayOf(
            Input.Keys.NUM_0, Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3,
            Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7,
            Input.Keys.NUM_8, Input.Keys.NUM_9,
            Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.D,
            Input.Keys.SPACE,
            Input.Keys.UP, Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.RIGHT
        ).also { check(it.size == ControllerKeys.COUNT) { "клавиш контроллера ${it.size}, а имён ${ControllerKeys.COUNT}" } }
    }
}
