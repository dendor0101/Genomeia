package io.github.some_example_name.old.good_one.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array
import io.github.some_example_name.attempts.game.physics.redColors
import io.github.some_example_name.old.good_one.*
import io.github.some_example_name.old.good_one.cells.cellsTypeFormula
import io.github.some_example_name.old.good_one.utils.cellsType


fun drawResultButton(table: Table, skin: Skin, text: String, clickDivide: () -> Unit) {
    val button = TextButton(text, skin)
    table.add(button).width(150f).height(70f).padTop(10f).colspan(2)
    table.row()

    button.addListener { event ->
        if (event is InputEvent && event.type == InputEvent.Type.touchDown) {
            clickDivide.invoke()
        }
        false
    }
}


fun drawFormulaOdds(
    table: Table,
    skin: Skin,
    uiState: Pause.Selected,
    defaultA: Float = 1f,
    defaultB: Float = 0f,
    defaultC: Float = 0f,
    onChange: (Triple<Float, Float, Float>) -> Unit
): Triple<TextField, TextField, TextField> {
    val a = TextField(defaultA.toString(), skin)
    val aLabel = Label("a:", skin)
    a.alignment = Align.center
    table.add(aLabel).width(20f).padTop(10f)
    table.add(a).width(70f).padTop(10f)
    table.row()

    val b = TextField(defaultB.toString(), skin)
    val bLabel = Label("b:", skin)
    b.alignment = Align.center
    table.add(bLabel).width(20f).padTop(10f)
    table.add(b).width(70f).padTop(10f)
    table.row()

    val c = TextField(defaultC.toString(), skin)
    val cLabel = Label("c:", skin)
    c.alignment = Align.center
    table.add(cLabel).width(20f).padTop(10f)
    table.add(c).width(70f).padTop(10f)
    table.row()

    val listener = TextField.TextFieldListener { textField, _ -> // Парсим значения при каждом изменении
        val aValue = a.text.toFloatOrNull() ?: 0f
        val bValue = b.text.toFloatOrNull() ?: 0f
        val cValue = c.text.toFloatOrNull() ?: 0f

        onChange(Triple(aValue, bValue, cValue))
    }

    a.setTextFieldListener(listener)
    b.setTextFieldListener(listener)
    c.setTextFieldListener(listener)
    return Triple(a, b, c)
}

fun drawDropDownListFormula(table: Table, skin: Skin, typeId: Int = 0, onSelect: ((String) -> Unit)): SelectBox<String> {
    val dropDownListFormula = SelectBox<String>(skin)
    val itemsFormula = Array<String>()

    cellsTypeFormula.forEach {
        itemsFormula.add(it)
    }

    dropDownListFormula.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            onSelect.invoke(dropDownListFormula.selected)
        }
    })

    dropDownListFormula.items = itemsFormula
    dropDownListFormula.selectedIndex = typeId
    table.add(dropDownListFormula).width(150f).padTop(10f).colspan(2)
    table.row()
    return dropDownListFormula
}

fun drawColorPicker(table: Table, skin: Skin, uiState: Pause.Selected) {
    // Панель с палитрой для выбора цвета
    val colorPicker = Table()
    val colors = redColors
    for (color in colors) {
        val colorButton = TextButton("", skin)
        colorButton.isDisabled = true
        colorButton.color = color
        colorPicker.add(colorButton).width(19f).height(19f).pad(1f)
        colorButton.addListener { event ->
            if (event is InputEvent && event.type == InputEvent.Type.touchDown) {

            }
            false
        }
    }
    table.add(colorPicker).colspan(2).padTop(10f)
    table.row()
}

fun drawCellTypeDropDownList(
    table: Table,
    skin: Skin,
    typeId: Int,
    isDisabled: Boolean = false,
    onSelect: ((Int) -> Unit)? = null,
): SelectBox<String> {
    // Выпадающий список
    val dropDownList = SelectBox<String>(skin)
    val items = Array<String>()

    cellsType.forEach {
        items.add(it)
    }

    dropDownList.items = items
    dropDownList.selectedIndex = typeId
    dropDownList.isDisabled = isDisabled

    dropDownList.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            onSelect?.invoke(dropDownList.selectedIndex)
        }
    })

    table.add(dropDownList).width(150f).padTop(10f).colspan(2)
    table.row()
    return dropDownList
}

fun drawIdEditText(table: Table, skin: Skin, id: String): TextField {
    val idTextField = TextField(id, skin)
    idTextField.alignment = Align.center
    table.add(idTextField).width(150f).colspan(2).padTop(10f)
    table.row()
    return idTextField
}

fun drawText(table: Table, skin: Skin, text: String, scale: Float = 1.5f): Label {
    val cellName = Label(text, skin)
    cellName.fontScaleX = scale
    cellName.fontScaleY = scale
    table.add(cellName).colspan(2).padTop(20f).align(Align.left)
    table.row()
    return cellName
}

fun createAngleSlider(table: Table, skin: Skin, defaultValue: Float = 0f, onRotate: (Float) -> Unit, onSlideFinish: (Float) -> Unit): Slider {
    // Создаем горизонтальную группу для ползунка и значения
    val sliderGroup = Table()

    // Создаем ползунок
    val slider = Slider(0f, 360f, 0.5f, false, skin)
    slider.value = defaultValue

    // Создаем Label для отображения значения
    val valueLabel = Label(String.format("%.0f°", slider.value), skin)
    var isDragging = false
    // Обновляем Label при изменении ползунка
    slider.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            // Проверяем, есть ли активное касание
            if (!Gdx.input.isTouched) {
                if (isDragging) {
                    onSlideFinish.invoke(slider.value)
                    isDragging = false
                }
            } else {
                isDragging = true
            }
            valueLabel.setText(String.format("%.0f°", slider.value))
            onRotate(slider.value)
        }
    })

    // Добавляем элементы в горизонтальную группу
    sliderGroup.add(slider).width(100f).padRight(10f).padLeft(10f)
    sliderGroup.add(valueLabel).width(30f)

    // Добавляем группу в основную таблицу
    table.add(sliderGroup).colspan(2).width(150f).padTop(10f).align(Align.right)
    table.row()

    return slider
}

fun drawLinkList(table: Table, skin: Skin, texts: List<String>) {
    texts.forEach {
        val cellName = Label(it, skin)
        table.add(cellName).colspan(2).padTop(5f).align(Align.left)
        table.row()
    }
}

fun drawCheckBoxes(table: Table, skin: Skin): Triple<CheckBox, CheckBox, CheckBox> {
    val checkBoxCellLink = CheckBox("Cell Link", skin)
    checkBoxCellLink.isChecked = isShowPhysicalLink
    checkBoxCellLink.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            isShowPhysicalLink = checkBoxCellLink.isChecked
        }
    })
    table.add(checkBoxCellLink).colspan(2).padTop(10f).align(Align.left)
    table.row()

    val checkBoxNeuronLink = CheckBox("Neuron Link", skin)
    checkBoxNeuronLink.isChecked = isShowNeuronLink
    checkBoxNeuronLink.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            isShowNeuronLink = checkBoxNeuronLink.isChecked
        }
    })
    table.add(checkBoxNeuronLink).colspan(2).padTop(10f).align(Align.left)
    table.row()

    val checkBoxCell = CheckBox("Cell", skin)
    checkBoxCell.isChecked = isShowCell
    checkBoxCell.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            isShowCell = checkBoxCell.isChecked
        }
    })
    table.add(checkBoxCell).colspan(2).padTop(10f).align(Align.left)
    table.row()
    return Triple(checkBoxCellLink, checkBoxNeuronLink, checkBoxCell)
}
