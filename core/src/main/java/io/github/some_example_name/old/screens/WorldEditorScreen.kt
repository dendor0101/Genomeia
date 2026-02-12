package io.github.some_example_name.old.screens

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.badlogic.gdx.utils.viewport.Viewport
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.good_one.WorldGenerator
import io.github.some_example_name.old.good_one.WorldGenerator.Companion.GENERATOR_DAY_NIGHT
import io.github.some_example_name.old.good_one.WorldGenerator.Companion.GENERATOR_INTERPOLATE
import io.github.some_example_name.old.good_one.CellSimulation
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.good_one.utils.brownColors
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_SIZE_TYPE

class WorldEditorScreen(
    private val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {
    companion object {
        const val SCALE_FACTOR = 6.3f * 4f
        const val OFFSET = 0f
        private fun createWhitePixelTexture(): Texture {
            val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            }
            return Texture(pixmap).also { pixmap.dispose() }
        }
    }

    // Графика
    private val batch = SpriteBatch()
    private lateinit var canvasTexture: Texture
    private lateinit var canvasPixmap: Pixmap
    private val whitePixel = createWhitePixelTexture()

    // Размеры и позиционирование
    private lateinit var viewport: Viewport
    private var editorX = 0f
    private var editorY = 0f
    private var editorWidth = 0f
    private var editorHeight = 0f

    // Инструменты
    private val wallColor = brownColors[2]
    private val leafColor = Color.BLACK
    private var brushSize = 9
    private var useCircleBrush = true
    private val mousePos = Vector2()
    private var needTextureUpdate = false
    private var isErasing = false // Режим стирания

    // UI элементы
    private lateinit var stage: Stage
    private var worldSeed = generateRandomSeed()
        set(value) {
            field = value
            updateSeedText()
        }
    private var worldSize = WORLD_SIZE_TYPE.generateWorldSize
    private var worldLifeGame = GENERATOR_DAY_NIGHT
    private var worldSmoothing = GENERATOR_INTERPOLATE
    private lateinit var seedLabel: VisLabel
    private lateinit var lifeGameLabel: VisLabel
    private lateinit var smoothingLabel: VisLabel
    private lateinit var brushSizeLabel: VisLabel
    private lateinit var textField: VisTextField
    private lateinit var eraseCheckBox: VisCheckBox
    private lateinit var circleBrushCheckBox: VisCheckBox
    private lateinit var setStartPointCheckBox: VisCheckBox

    private val worldGenerator = WorldGenerator()
    private lateinit var map: Array<BooleanArray> // Храним карту напрямую

    private var isSettingStartPoint = false

    private lateinit var backButton: VisTextButton
    private lateinit var settingsButton: VisTextButton
    private lateinit var createButton: VisTextButton
    private lateinit var dialog: VisDialog
    private lateinit var clearButton: VisTextButton

    override fun show() {
        stage = Stage(ScreenViewport())

        setupCanvas()
        createNewWorld() // Инициализируем карту при старте
        setupUI()
        Gdx.input.inputProcessor = stage
    }

    private fun setupCanvas() {
        canvasPixmap = Pixmap(worldSize, worldSize, Pixmap.Format.RGBA8888).apply {
            setColor(Color.WHITE)
            fill()
        }
        canvasTexture = Texture(canvasPixmap)
        canvasTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
    }

    private fun updateSeedText() {
        if (::textField.isInitialized) {
            textField.text = worldSeed
        }
    }

    private fun setupUI() {
        val scale = Gdx.graphics.density //* 2f

        // Кнопка "Назад" в левом верхнем углу
        backButton = VisTextButton(bundle.get("button.back")).apply {
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    game.screen = MenuScreen(game, multiPlatformFileProvider)
                }
            })
        }
        stage.addActor(backButton)

        // Кнопка для открытия диалога в правом верхнем углу
        settingsButton = VisTextButton(bundle.get("button.settings")).apply {
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    dialog.show(stage)
                }
            })
        }
        stage.addActor(settingsButton)

        // Кнопка "Создать" в нижнем правом углу
        createButton = VisTextButton(bundle.get("button.createNewWorld")).apply {
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    val oldScreen = game.screen
                    game.screen =
                        CellSimulation(multiPlatformFileProvider, game, map, bundle, null) // Передаем map
                    oldScreen.dispose()
                }
            })
        }
        stage.addActor(createButton)

        clearButton = VisTextButton(bundle.get("button.clearMap")).apply {
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    for (y in 0 until worldSize) {
                        for (x in 0 until worldSize) {
                            map[y][x] = false
                        }
                    }
                    updateCanvasFromMap()
                    needTextureUpdate = true
                }
            })
        }
        stage.addActor(clearButton)

        // Диалог
        dialog = VisDialog(bundle.get("dialog.worldEditorSettings")).apply {
            isModal = true
            isMovable = false
            setupTitleSize(game)
        }

        val table = Table()

        // Генерация seed
        seedLabel = VisLabel(bundle.get("label.seed") + worldSeed).apply {
            game.applyCustomFontMedium(this)
        }
        val generateSeedButton = VisTextButton(bundle.get("button.newSeed")).apply {
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    worldSeed = generateRandomSeed()
                    seedLabel.setText(bundle.get("label.seed") + worldSeed)
                    createNewWorld()
                }
            })
        }

        // Текстовое поле для seed
        textField = VisTextField(worldSeed).apply {
            game.applyCustomFont(this)
            messageText = bundle.get("textfield.enterSeed")
            setTextFieldListener { field, _ ->
                val text = field.text.removePrefix("Seed: ")
                if (text != worldSeed) {
                    worldSeed = text
                    createNewWorld()
                }
            }
        }

        // Слайдер для DAY_NIGHT
        lifeGameLabel = VisLabel(bundle.get("label.dayNight") + worldLifeGame).apply {
            game.applyCustomFontMedium(this)
        }
        val lifeGameSlider = VisSlider(0f, 25f, 1f, false).apply {
            value = worldLifeGame.toFloat()
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    worldLifeGame = value.toInt()
                    lifeGameLabel.setText(bundle.get("label.dayNight") + worldLifeGame)
                    GENERATOR_DAY_NIGHT = worldLifeGame
                    createNewWorld()
                }
            })
        }

        // Слайдер для сглаживания
        smoothingLabel = VisLabel(bundle.get("label.smoothing") + worldSmoothing).apply {
            game.applyCustomFontMedium(this)
        }
        val smoothingSlider = VisSlider(0f, 50f, 1f, false).apply {
            value = worldSmoothing.toFloat()
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    worldSmoothing = value.toInt()
                    smoothingLabel.setText(bundle.get("label.smoothing") + worldSmoothing)
                    GENERATOR_INTERPOLATE = worldSmoothing
                    createNewWorld()
                }
            })
        }

        // Чекбокс для режима стирания
        eraseCheckBox = VisCheckBox(bundle.get("checkbox.eraseMode")).apply {
            isChecked = isErasing
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    isErasing = isChecked
                }
            })
        }

        // Слайдер для размера кисти
        brushSizeLabel = VisLabel(bundle.get("label.brushSize") + brushSize).apply {
            game.applyCustomFontMedium(this)
        }
        val brushSizeSlider = VisSlider(0f, 20f, 1f, false).apply {
            value = brushSize.toFloat()
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    brushSize = value.toInt()
                    brushSizeLabel.setText(bundle.get("label.brushSize") + brushSize)
                }
            })
        }

        // Чекбокс для круглой кисти
        circleBrushCheckBox = VisCheckBox(bundle.get("checkbox.circleBrush")).apply {
            isChecked = useCircleBrush
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    useCircleBrush = isChecked
                }
            })
        }

        setStartPointCheckBox = VisCheckBox(bundle.get("checkbox.setStartPoint")).apply {
            isChecked = false
            game.applyCustomFont(this)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    isSettingStartPoint = isChecked
                    eraseCheckBox.isChecked = true
                    isErasing = true
                }
            })
        }

        // Компоновка UI в таблице
        table.add(generateSeedButton).width(80f * scale).height(25f * scale).left()
            .padRight(5f * scale)
        table.add(textField).width(100f * scale).height(25f * scale).left().row()
        table.add(lifeGameLabel).colspan(2).left().padTop(5f * scale).row()
        table.add(lifeGameSlider).colspan(2).width(200f * scale).height(25f * scale)
            .padBottom(5f * scale).row()
        table.add(smoothingLabel).colspan(2).left().padTop(5f * scale).row()
        table.add(smoothingSlider).colspan(2).width(200f * scale).height(25f * scale)
            .padBottom(5f * scale).row()
        table.add(eraseCheckBox).colspan(2).left().padTop(5f * scale).row()
        table.add(brushSizeLabel).colspan(2).left().padTop(5f * scale).row()
        table.add(brushSizeSlider).colspan(2).width(200f * scale).height(25f * scale)
            .padBottom(5f * scale).row()
//        table.add(circleBrushCheckBox).colspan(2).left().padTop(10f * scale).row()
//        table.add(setStartPointCheckBox).colspan(2).left().padTop(10f * scale).row()

        // Создаем ScrollPane и оборачиваем таблицу
        val scrollPane = VisScrollPane(table).apply {
            setScrollingDisabled(true, false) // Разрешаем только вертикальный скролл
            setCancelTouchFocus(false)
        }
        scrollPane.addListener(object : InputListener() {
            override fun enter(
                event: InputEvent,
                x: Float,
                y: Float,
                pointer: Int,
                fromActor: Actor?
            ) {
                if (pointer == -1) {  // Проверяем, что это hover мыши (без нажатия кнопки)
                    stage.scrollFocus = scrollPane  // Устанавливаем scroll focus на эту панель
                }
            }

            override fun exit(
                event: InputEvent,
                x: Float,
                y: Float,
                pointer: Int,
                toActor: Actor?
            ) {
                if (pointer == -1) {  // Опционально: снимаем фокус при выходе курсора
                    stage.scrollFocus = null
                }
            }

            override fun scrolled(
                event: InputEvent,
                x: Float,
                y: Float,
                amountX: Float,
                amountY: Float
            ): Boolean {
                if (amountY != 0f) {
                    // Обновляем позицию скролла (amountY > 0 - вниз, < 0 - вверх)
                    scrollPane.scrollY = MathUtils.clamp(
                        scrollPane.scrollY + amountY * 30f,
                        0f,
                        scrollPane.maxY
                    )
                    return true  // Событие потреблено
                }
                return false
            }
        })

        // Добавляем ScrollPane в диалог
        dialog.contentTable.add(scrollPane).grow().pad(10f)
//        dialog.button(bundle.get("button.close")) // Кнопка закрытия диалога
        dialog.pack()
    }

    private fun createNewWorld() {
        map = worldGenerator.generateWorld(worldSeed.hashCode().toLong()) // Получаем карту
        updateCanvasFromMap()
    }

    private fun updateCanvasFromMap() {
        canvasPixmap.setColor(Color.WHITE)
        canvasPixmap.fill()
        for (y in 0 until worldSize) {
            for (x in 0 until worldSize) {
                canvasPixmap.setColor(if (map[y][x]) wallColor else leafColor)
                canvasPixmap.drawPixel(x, worldSize - 1 - y)
            }
        }
        needTextureUpdate = true
    }

    private fun generateRandomSeed(): String {
        return (1..8).map { ('0'..'9').random() }.joinToString("")
    }

    override fun render(delta: Float) {
        clearScreen()
        handleInput()
        drawEditor()
        stage.act(delta)
        stage.draw()
    }

    private fun clearScreen() {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    }

    private fun handleInput() {
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            val screenX = Gdx.input.x.toFloat()
            val screenY = Gdx.input.y.toFloat()
            val temp = Vector2(screenX, Gdx.graphics.height - screenY)
            val hit = stage.hit(temp.x, temp.y, true)
            if (hit != null) return // Если клик на UI, игнорируем

            if (isMouseInsideEditor()) {
                updateMousePosition()
                if (isSettingStartPoint) {
                    val cx = (mousePos.x * worldSize / editorWidth).toInt().coerceIn(0, worldSize - 1)
                    val cy = (mousePos.y * worldSize / editorHeight).toInt().coerceIn(0, worldSize - 1)
                    map[cy][cx] = false // Set to erased (leaf)
                    isSettingStartPoint = false
                    setStartPointCheckBox.isChecked = false
                    updateCanvasFromMap()
                    needTextureUpdate = true
                } else {
                    drawOnCanvas()
                    needTextureUpdate = true
                }
            }
        }

        if (needTextureUpdate) {
            updateCanvasTexture()
        }
    }

    private fun updateMousePosition() {
        val screenX = Gdx.input.x.toFloat()
        val screenY = Gdx.input.y.toFloat()
        val worldPos = stage.viewport.unproject(Vector2(screenX, screenY))
        mousePos.set(worldPos.x - editorX, worldPos.y - editorY)
    }

    private fun isMouseInsideEditor(): Boolean {
        val screenX = Gdx.input.x.toFloat()
        val screenY = Gdx.input.y.toFloat()
        val worldPos = stage.viewport.unproject(Vector2(screenX, screenY))
        return worldPos.x >= editorX && worldPos.x <= editorX + editorWidth &&
            worldPos.y >= editorY && worldPos.y <= editorY + editorHeight
    }

    private fun drawOnCanvas() {
        val cx = (mousePos.x * worldSize / editorWidth).toInt().coerceIn(0, worldSize - 1)
        val cy = (mousePos.y * worldSize / editorHeight).toInt().coerceIn(0, worldSize - 1)
        val value = !isErasing

        val r2 = brushSize * brushSize
        for (dy in -brushSize..brushSize) {
            for (dx in -brushSize..brushSize) {
                if (useCircleBrush && dx * dx + dy * dy > r2) continue // круглая кисть
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until worldSize && y in 0 until worldSize) {
                    map[y][x] = value
                }
            }
        }

        updateCanvasFromMap()
    }

    private fun updateCanvasTexture() {
        canvasTexture.dispose()
        canvasTexture = Texture(canvasPixmap)
        canvasTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        needTextureUpdate = false
    }

    private fun drawEditor() {
        batch.begin()
        batch.draw(canvasTexture, editorX, editorY, editorWidth, editorHeight)
        drawBorder()
        batch.end()
    }

    private fun drawBorder() {
        batch.projectionMatrix = stage.camera.combined
        batch.color = Color.BLACK
        val borderSize = 2f
        batch.draw(
            whitePixel,
            editorX - borderSize,
            editorY - borderSize,
            editorWidth + 2 * borderSize,
            borderSize
        )
        batch.draw(
            whitePixel,
            editorX - borderSize,
            editorY + editorHeight,
            editorWidth + 2 * borderSize,
            borderSize
        )
        batch.draw(
            whitePixel,
            editorX - borderSize,
            editorY - borderSize,
            borderSize,
            editorHeight + 2 * borderSize
        )
        batch.draw(
            whitePixel,
            editorX + editorWidth,
            editorY - borderSize,
            borderSize,
            editorHeight + 2 * borderSize
        )
        batch.color = Color.WHITE
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)

        val editorSize = Math.min(width, height).toFloat()
        editorWidth = editorSize
        editorHeight = editorSize
        editorX = (width - editorSize) / 2f
        editorY = (height - editorSize) / 2f

        val scale = Gdx.graphics.density * 2f
        val buttonWidth = 100f * scale
        val buttonHeight = 25f * scale
        val padding = 10f * scale

        backButton.setSize(buttonWidth, buttonHeight)
        backButton.setPosition(padding, height - buttonHeight - padding)

        settingsButton.setSize(buttonWidth, buttonHeight)
        settingsButton.setPosition(width - buttonWidth - padding, height - buttonHeight - padding)

        createButton.setSize(buttonWidth, buttonHeight)
        createButton.setPosition(width - buttonWidth - padding, padding)

        clearButton.setSize(buttonWidth, buttonHeight)
        clearButton.setPosition(padding, padding)

        dialog.setSize(300f * scale, 400f * scale) // Фиксированный размер для диалога, чтобы скролл появлялся если нужно
        dialog.pack()
    }

    override fun dispose() {
        batch.dispose()
        canvasTexture.dispose()
        canvasPixmap.dispose()
        whitePixel.dispose()
        stage.dispose()
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
}
