package io.github.some_example_name.old.features.simulation

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSettings
import io.github.some_example_name.old.commands.Menu
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.ui.ConfirmDialog
import io.github.some_example_name.old.core.ui.KeyboardFocus
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.ui.CameraControl
import io.github.some_example_name.old.core.ui.STYLE_BEIGE
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.keepWindowsOnScreen
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledIconButton
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.editor.GenomeEditorCaller
import io.github.some_example_name.old.features.settings.GlobalSettings
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
) : Screen, ReplayTarget, GenomeEditorCaller {

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
        onTap = ::onWorldTap,
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

    private val extraTextures = mutableListOf<Texture>()
    private lateinit var icons: HudIcons

    /** Кнопка паузы: эффект ViewModel поправляет её отметку. Пересоздаётся в rebuildMenu. */
    private var pauseButton: Button? = null

    /**
     * Самая верхняя из кнопок внизу экрана: над ней рисуется текст угла (FPS, UPS…), чтобы
     * не ложиться на кнопки ни при каком размере окна.
     */
    private var bottomAnchor: Actor? = null
    private val anchorTop = Vector2()

    /**
     * Мир уже построен. Экран показывается второй раз, когда игрок возвращается из
     * редактора генома или настроек, открытых поверх мира: тогда мир не строится заново.
     */
    private var isStarted = false

    /** Снять паузу, когда игрок вернётся (из настроек): ставил её не он, а уход с экрана. */
    private var resumeOnReturn = false

    /** «Сохранить и выйти»: выйти, как только мир сохранится. */
    private var exitAfterSave = false

    /**
     * Последнее состояние клавиш контроллера НА КЛАВИАТУРЕ. Команда уходит только при
     * смене состояния: опрос идёт каждый кадр, а журналу нужно нажатие, а не кадр.
     */
    private val keyboardKeys = BooleanArray(CONTROLLER_KEY_CODES.size)

    override fun show() {
        if (isStarted) {
            returnFromScreenOnTop()
            return
        }
        isStarted = true

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

        icons = HudIcons()
        renderSystem.showDebugInfo = GlobalSettings.SHOW_DEBUG_INFO

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

    /**
     * Вернулись с экрана, открытого поверх мира (редактор генома, настройки). Мир стоял под
     * ним на паузе и строиться заново не должен. Возвращаются ввод и рендер; вёрстку
     * пересоберёт resize, который Game.setScreen зовёт сразу после show: пока игрока не
     * было, могли смениться и размер окна, и язык.
     */
    private fun returnFromScreenOnTop() {
        Gdx.input.inputProcessor = cameraControl.getInputMultiplexer(stage)
        // Рендер мира один на всю игру, и редактор генома мог перенастроить его под себя.
        renderSystem.create(
            fontMatrix = fontMatrix,
            spriteBatch = spriteBatch,
            font = font,
            shapeRenderer = shapeRenderer,
            camera = camera
        )
        renderSystem.showDebugInfo = GlobalSettings.SHOW_DEBUG_INFO
        currentScreenWidth = 0
        currentScreenHeight = 0
        if (resumeOnReturn) {
            resumeOnReturn = false
            viewModel.handle(SimulationIntent.SetPaused(false))
        }
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

            SimulationEffect.PauseChanged -> pauseButton?.isChecked = viewModel.isPaused

            // Значок кнопки инструмента — нынешний инструмент.
            SimulationEffect.ToolChanged -> rebuildMenu()

            is SimulationEffect.WorldSaved -> onWorldSaved(effect.success)
        }
    }

    private fun onWorldSaved(success: Boolean) {
        showToast(if (success) "label.saved" else "label.saveFailed")
        if (!exitAfterSave) return
        exitAfterSave = false
        // Выходим только с сохранённым миром: упало — игрок остаётся и видит ошибку.
        // Не сразу, а следующим кадром: сохранение заканчивается внутри отрисовки мира,
        // а выход освобождает этот экран.
        if (success) Gdx.app.postRunnable { exit() }
    }

    /** Касание мира: что делать, решает инструмент. */
    private fun onWorldTap(x: Float, y: Float, isLeft: Boolean) {
        // Решается здесь, при создании команды: в запись сессии уходит уже итоговое действие.
        when (val tool = viewModel.tool) {
            SimulationTool.KILL_CELL -> userCommandManager.push(PlayerCommand.KillCell(x, y))
            SimulationTool.SPAWN_CELL, SimulationTool.SPAWN_FOOD -> {
                // «Еда» меняет кнопки местами: левая сыплет еду, правая ставит организм.
                val putOrganism = tool == SimulationTool.SPAWN_CELL
                userCommandManager.push(PlayerCommand.Tap(x, y, isLeftButton = if (isLeft) putOrganism else !putOrganism))
            }
        }
    }

    /** Из редактора, открытого кнопкой «Создать новый геном», вернулись с сохранённым геномом. */
    override fun onGenomeSaved(fileName: String) {
        viewModel.handle(SimulationIntent.UseSavedGenome(fileName))
        // Геном сделан, чтобы сразу его посадить: мир идёт дальше. Вышел из редактора без
        // сохранения («Назад в мир») — мир остаётся на паузе, см. openOnTop.
        viewModel.handle(SimulationIntent.SetPaused(false))
        // Кнопки уже пересобраны при возвращении на экран — с паузой, какой она была до этого.
        applyEffect(SimulationEffect.PauseChanged)
    }

    /**
     * Через менеджер, а не game.screen = ...: иначе переход не виден журналу, а стек
     * остаётся от прошлого маршрута — «Назад» в меню уводило бы туда, откуда игрок уже вышел.
     */
    private fun exit() {
        DIGameGlobalContainer.navigationCommandsManager.performCommand(
            if (genomeName == null) {
                GoReplace(GoMenu)
            } else {
                GoReplace(GoGenomeEditor(genomeName.replace(".json", "")))
            }
        )
    }

    /**
     * Экран поверх мира (редактор генома, настройки). Мир на это время встаёт: его не
     * видно, а на телефоне он ещё и жёг бы батарею в фоне. [resume] — снять паузу по
     * возвращении (настройки). Из редактора паузу снимает только сохранённый геном
     * ([onGenomeSaved]); вернулся без него — мир ждёт игрока на паузе.
     */
    private fun openOnTop(command: Menu, resume: Boolean) {
        resumeOnReturn = resume && !viewModel.isPaused
        viewModel.handle(SimulationIntent.SetPaused(true))
        DIGameGlobalContainer.navigationCommandsManager.performCommand(command)
    }

    /**
     * «Сохранить»: имя мира, а если под ним лежит другой мир — вопрос, перезаписать ли.
     * Оба окна только спрашивают; сохраняет команда SaveWorld с уже решённым именем.
     */
    private fun showSaveDialog(exitAfter: Boolean) {
        val bundle = DIGameGlobalContainer.bundle
        fun save(name: String) {
            exitAfterSave = exitAfter
            viewModel.handle(SimulationIntent.SaveWorld(name))
        }
        lateinit var dialog: SaveWorldDialog
        dialog = SaveWorldDialog(viewModel.suggestedSaveName(bundle.get("map.defaultName"))) { name ->
            if (!viewModel.wouldOverwrite(name)) {
                dialog.fadeOut()
                save(name)
                return@SaveWorldDialog
            }
            ConfirmDialog(
                title = bundle.get("dialog.saveWorld"),
                message = bundle.format("label.overwriteMap", name),
                confirm = bundle.get("button.overwrite"),
                cancel = bundle.get("button.otherName"),
                onConfirm = {
                    dialog.fadeOut()
                    save(name)
                },
                onCancel = { dialog.editName() }
            ).show(stage)
        }
        dialog.show(stage)
    }

    /** Короткая надпись сверху по центру, сама гаснет — отклик на сохранение мира. */
    private fun showToast(key: String) {
        val game = DIGameGlobalContainer.game
        val label = VisLabel(DIGameGlobalContainer.bundle.get(key))
        game.applyCustomFontMedium(label)
        label.setAlignment(Align.center)
        val density = Gdx.graphics.density
        // Сцена экрана, а не таблицы: у ещё не добавленной таблицы своей сцены нет.
        val screenStage = stage
        val toast = Table().apply {
            background = makeStyledNP(TOAST_FILL, Color(STYLE_BEIGE).also { it.a = 0.65f }, extraTextures)
            touchable = Touchable.disabled
            add(label).pad(8f * density, 16f * density, 8f * density, 16f * density)
            pack()
            setPosition((screenStage.width - width) / 2f, screenStage.height - height - (HUD_BUTTON_DP + 24f) * density)
            addAction(Actions.sequence(Actions.delay(1.5f), Actions.fadeOut(0.4f), Actions.removeActor()))
        }
        screenStage.addActor(toast)
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

        // Вёрстка с прошлого кадра: сцена в пикселях экрана (ScreenViewport), поэтому верх
        // кнопки в координатах сцены — это и есть высота над нижним краем в пикселях.
        renderSystem.textBottom = bottomAnchor?.let { it.localToStageCoordinates(anchorTop.set(0f, it.height)).y } ?: 0f

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
        stage.keepWindowsOnScreen()
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
        icons.dispose()
    }


    private fun rebuildMenu() {
        extraTextures.forEach { it.dispose() }
        extraTextures.clear()
        root.clear()
        pauseButton = null
        bottomAnchor = null

        // Интерфейс спрятан (клавиша Z) — только мир, без единой кнопки.
        if (!viewModel.isUiVisible) return

        val bundle = DIGameGlobalContainer.bundle
        val density = Gdx.graphics.density
        val size = hudButtonSize()
        val pad = 8f * density

        // --- Сверху: слева меню и геном, справа инструмент и пауза ---
        val topBar = Table()
        topBar.add(iconButton(icons.menu).hint(bundle.get("button.menu")).onClick { showMenu() })
            .size(size).padRight(pad)
        // Геномы выбирают только в обычном мире: у теста генома он один.
        if (viewModel.isSandbox) {
            topBar.add(iconButton(icons.organism).hint(bundle.get("button.selectGenome")).onClick { showGenomeList() })
                .size(size)
        }
        topBar.add().expandX()

        val toolButton = iconButton(icons.of(viewModel.tool))
        toolButton.hint(bundle.get("tool.title") + ": " + bundle.get(viewModel.tool.labelKey))
        toolButton.onClick { showToolMenu(toolButton) }
        topBar.add(toolButton).size(size).padRight(pad)

        val pause = iconButton(icons.pause, checkedIcon = icons.play)
        pause.isChecked = viewModel.isPaused
        pause.hint(bundle.get("button.pause"))
        pause.onClick { viewModel.handle(SimulationIntent.SetPaused(pause.isChecked)) }
        pauseButton = pause
        // Справа место под кнопку-жук отчёта об ошибке (BugOverlay): она поверх всех экранов.
        topBar.add(pause).size(size).padRight(BUG_BUTTON_RESERVE_DP * density)

        root.top().left()
        root.add(topBar).growX().pad(pad).row()

        // --- Посередине — мир; экранная клавиатура контроллера, если включена ---
        val keyboardShown = simulationSystem.simulationData.showControllerKeys
        if (keyboardShown) root.add(controllerNumbers()).growX().left().padTop(pad).row()
        root.add().grow().row()
        val bottomKeyboard = if (keyboardShown) controllerKeyboard() else null
        if (bottomKeyboard != null) root.add(bottomKeyboard).growX().bottom().left().padBottom(pad).row()

        // --- Снизу слева — клавиши контроллера ---
        val keysToggle = controllerKeysToggle(size)
        root.add(keysToggle).size(size * KEYS_BUTTON_WIDTH, size).left().pad(pad)

        bottomAnchor = bottomKeyboard ?: keysToggle
    }

    /**
     * Сторона кнопок-значков: не меньше текстовой кнопки (шрифт кнопок свой у каждого
     * экрана и плотности), иначе значки рядом с текстом выглядели бы мелкими.
     */
    private fun hudButtonSize(): Float {
        val textButton = makeStyledButton("Ag", DIGameGlobalContainer.game, extraTextures).prefHeight
        return maxOf(HUD_BUTTON_DP * Gdx.graphics.density, textButton)
    }

    private fun iconButton(icon: TextureRegion, checkedIcon: TextureRegion? = null): Button =
        makeStyledIconButton(icon, extraTextures, checkedIcon).also {
            // Свой отступ вместо отступов рамки: у скруглённой рамки они почти с саму
            // кнопку, и значку не оставалось места.
            it.pad(HUD_ICON_INSET_DP * Gdx.graphics.density)
            it.imageCell.grow()
        }

    /**
     * Кнопка «Клавиши контроллера»: на ней нарисованы клавиши W, A, S, D. Переключатель —
     * показывает и прячет экранную клавиатуру.
     */
    private fun controllerKeysToggle(size: Float): Button {
        val game = DIGameGlobalContainer.game
        val button = makeStyledButton("", game, extraTextures, toggle = true)
        button.clearChildren()
        button.pad(size * 0.06f)

        // Клавиша — тёмный квадрат в светлой обводке. Не скруглённая рамка makeStyledNP:
        // её углы больше такой маленькой клавиши, и она рисовалась кругами.
        val skin = VisUI.getSkin()
        val keyBorder = skin.newDrawable("white", STYLE_BEIGE)
        val keyFill = skin.newDrawable("white", KEYCAP_FILL)
        val keyStyle = Label.LabelStyle(game.smallFont, STYLE_BEIGE)
        val keySize = size * 0.3f
        // Буква — в полклавиши, какой бы ни была плотность экрана и размер шрифта.
        val letterScale = keySize * 0.42f / game.smallFont.capHeight
        fun key(letter: String) = Table().apply {
            background = keyBorder
            add(Table().apply {
                background = keyFill
                add(Label(letter, keyStyle).apply { setFontScale(letterScale) }).expand().center()
            }).grow().pad(maxOf(1.5f, keySize * 0.08f))
        }

        val keys = Table()
        keys.defaults().size(keySize).pad(size * 0.02f)
        keys.add()
        keys.add(key("W"))
        keys.add().row()
        keys.add(key("A"))
        keys.add(key("S"))
        keys.add(key("D"))
        button.add(keys).expand().center()

        button.isChecked = simulationSystem.simulationData.showControllerKeys
        button.hint(DIGameGlobalContainer.bundle.get("button.controllerKeys"))
        button.onClick {
            // Панель пересоберёт эффект RebuildMenu: клавиатуру надо показать или спрятать.
            viewModel.handle(SimulationIntent.SetControllerKeysVisible(button.isChecked))
        }
        return button
    }

    /** Окно «Меню»: всё, что раньше было рядом кнопок над миром. */
    private fun showMenu() {
        val bundle = DIGameGlobalContainer.bundle
        val data = simulationSystem.simulationData
        SimulationMenuDialog(
            game = DIGameGlobalContainer.game,
            bundle = bundle,
            renderMode = renderSystem.mode,
            targetUps = data.targetUPS,
            maxSpeed = data.maxSpeed,
            exitLabel = bundle.get(if (genomeName == null) "button.exit" else "button.backToEditor"),
            onTargetUps = { viewModel.handle(SimulationIntent.SetTargetUps(it)) },
            onMaxSpeed = { viewModel.handle(SimulationIntent.SetMaxSpeed(it)) },
            // Режим — свой у симуляции: ни меню, ни редактор генома его не видят.
            onRenderMode = { viewModel.handle(SimulationIntent.SetRenderMode(it)) },
            onRestart = { viewModel.handle(SimulationIntent.Restart) },
            onSettings = { openOnTop(GoSettings, resume = true) },
            // Сохранение только у обычного мира: тест генома — временный мир, из которого
            // возвращаются в редактор.
            onSave = if (viewModel.isSandbox) ({ showSaveDialog(exitAfter = false) }) else null,
            onSaveAndExit = if (viewModel.isSandbox) ({ showSaveDialog(exitAfter = true) }) else null,
            onExit = ::exit
        ).show(stage)
    }

    private fun showToolMenu(anchor: Actor) {
        SimulationToolDialog(
            game = DIGameGlobalContainer.game,
            bundle = DIGameGlobalContainer.bundle,
            icons = icons,
            current = viewModel.tool,
            onSelect = { viewModel.handle(SimulationIntent.SetTool(it)) }
        ).showUnder(stage, anchor)
    }

    /** Цифры экранной клавиатуры контроллера — строкой под верхними кнопками. */
    private fun controllerNumbers(): Table {
        val density = Gdx.graphics.density
        val keySize = minOf(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()) * 0.075f
        val numbersTable = Table()
        numbersTable.defaults().pad(3f * density).width(keySize).height(keySize)
        for (num in 0..9) {
            numbersTable.add(controllerKeyButton(num.toString(), index = num))
        }
        return numbersTable
    }

    /**
     * Нижняя часть экранной клавиатуры контроллера: WASD, пробел и стрелки.
     * Индексы клавиш — те же, что в [CONTROLLER_KEY_CODES].
     */
    private fun controllerKeyboard(): Table {
        val density = Gdx.graphics.density
        val screenW = minOf(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

        // Размеры кнопок
        val keySize = screenW * 0.075f          // ~8.5% ширины экрана
        val spaceSize = screenW * 0.38f         // ~38% ширины экрана

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
        return bottomKeyboard
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
     * Список — из папки геномов, какой она есть сейчас; номер генома в мире по имени
     * находит ViewModel (SelectGenome).
     */
    private fun showGenomeList() {
        val bundle = DIGameGlobalContainer.bundle
        val genomes = GenomeCatalog.all()
        GenomeListDialog(
            genomesList = genomes,
            selectedGenome = viewModel.genomeNames.getOrNull(simulationSystem.simulationData.currentGenomeIndex),
            title = bundle.get("button.selectGenome"),
            createLabel = bundle.get("button.createGenome"),
            onCreate = { openOnTop(GoGenomeEditor(null), resume = false) },
            onSelect = { viewModel.handle(SimulationIntent.SelectGenome(it)) },
            game = DIGameGlobalContainer.game,
            displayName = GenomeCatalog::displayName,
            sectionStart = genomes.indexOfFirst(GenomeCatalog::isBundled),
            sectionTitle = bundle.get("label.bundledGenomes")
        ).show(stage)
    }

    /** Обработчик нажатия в одну строку: здесь их много, и все устроены одинаково. */
    private fun <T : Button> T.onClick(action: () -> Unit): T = apply {
        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) = action()
        })
    }

    private companion object {
        /** Сторона кнопок-значков по углам экрана. */
        const val HUD_BUTTON_DP = 52f

        /** Отступ значка от рамки кнопки. */
        const val HUD_ICON_INSET_DP = 8f

        /** Кнопка клавиш контроллера шире квадрата: на ней три клавиши в ряд. */
        const val KEYS_BUTTON_WIDTH = 1.35f

        /** Место справа сверху под кнопку-жук BugOverlay (36 dp и отступы). */
        const val BUG_BUTTON_RESERVE_DP = 56f

        val KEYCAP_FILL = Color(0.10f, 0.10f, 0.12f, 0.9f)
        val TOAST_FILL = Color(0.14f, 0.13f, 0.11f, 0.92f)

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
