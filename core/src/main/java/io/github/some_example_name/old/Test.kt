package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Array
import com.badlogic.gdx.utils.viewport.ScreenViewport

class UIExample1 : ApplicationAdapter(), InputProcessor {
    private lateinit var stage: Stage
    private lateinit var skin: Skin
    private lateinit var slider: Slider
    private lateinit var dropDownList: SelectBox<String>
    private lateinit var colorPicker: Table
    private var selectedColorButton: TextButton? = null // Хранит выбранную кнопку цвета

    override fun create() {
        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = this // Устанавливаем текущий класс как обработчик ввода

        skin = Skin(Gdx.files.internal("ui/uiskin.json")) // Загрузите скин

        val table = Table()
        table.setFillParent(true)
        stage.addActor(table)

        // Ползунок
        slider = Slider(0f, 100f, 1f, false, skin)
        slider.value = 50f // Установите начальное значение
        table.add(slider).width(200f).pad(10f)
        table.row()

        // Выпадающий список
        dropDownList = SelectBox(skin)
        Array<String>().apply {
            add("Cell")
            add("Leaf")
            add("Fat")
            add("Bone")
            add("Tail")
            add("Neuron")
            add("Muscle")
            dropDownList.items = this
        }
        table.add(dropDownList).width(200f).pad(10f)
        table.row()

        // Панель с палитрой для выбора цвета
        colorPicker = Table()
        val colors = arrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (color in colors) {
            val colorButton = TextButton("", skin)
            colorButton.color = color
            colorButton.addListener { event ->
                if (event is com.badlogic.gdx.scenes.scene2d.InputEvent && event.type == com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown) {
                    selectColorButton(colorButton) // Выбираем кнопку при нажатии
                }
                false
            }
            colorPicker.add(colorButton).width(50f).height(50f).pad(5f)
        }
        table.add(colorPicker).pad(10f)
        table.row()

        // Кнопка (перенесена вниз)
        val button = TextButton("Click Me", skin)
        table.add(button).pad(10f)

        // Обработчик нажатия на кнопку
        button.addListener { event ->
            if (event is com.badlogic.gdx.scenes.scene2d.InputEvent && event.type == com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown) {
                printValues()
            }
            false
        }
    }

    override fun render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act(Gdx.graphics.deltaTime)
        stage.draw()
    }

    override fun dispose() {
        stage.dispose()
        skin.dispose()
    }

    // Метод для вывода значений в консоль
    private fun printValues() {
        println("Slider value: ${slider.value}")
        println("Selected option: ${dropDownList.selected}")
        println("Selected color: ${getSelectedColor()}")
    }

    // Метод для получения выбранного цвета
    private fun getSelectedColor(): Color? {
        return selectedColorButton?.color
    }

    // Метод для выбора кнопки цвета
    private fun selectColorButton(button: TextButton) {
        selectedColorButton?.isChecked = false // Снимаем выделение с предыдущей кнопки
        selectedColorButton = button
        button.isChecked = true // Выделяем новую кнопку
    }

    // Обработчик нажатия на экран
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        printValues() // Выводим значения при нажатии на экран
        return true
    }

    // Остальные методы InputProcessor (не используются, но должны быть реализованы)
    override fun keyDown(keycode: Int) = false
    override fun keyUp(keycode: Int) = false
    override fun keyTyped(character: Char) = false
    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int) = false
    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int) = false
    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int) = false
    override fun mouseMoved(screenX: Int, screenY: Int) = false
    override fun scrolled(amountX: Float, amountY: Float) = false
}
