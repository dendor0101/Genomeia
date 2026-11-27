package io.github.some_example_name.old.features.ecosystem

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.old.core.ui.setupTitleSize
import kotlin.collections.iterator
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class CellSettings(
    val cell: Map.Entry<String, CellSettings>,
    val settings: GlobalSimulationSettings
) : VisDialog("Cells Settings") {

    val paramsFields = mutableListOf<Pair<String, VisTextField>>()

    init {
        val density = Gdx.graphics.density
        setupTitleSize(DIGameGlobalContainer.game)

        contentTable.defaults().left().pad(6f * density)

        val cellTitleLabel = VisLabel(cell.key)
        DIGameGlobalContainer.game.applyCustomFontMedium(cellTitleLabel)
        cellTitleLabel.setFontScale(1.5f)
        contentTable.add(cellTitleLabel).expandX().center().colspan(2)
        contentTable.row()

        //val params = cell.value.toMapJava()
        val params= settings.cellsSettings[cell.key]!!.toMapJava()
        for (param in params) {
            val paramText = VisLabel(param.key)
            DIGameGlobalContainer.game.applyCustomFontMedium(paramText)

            val paramValue = VisTextField(param.value.toString())

            contentTable.add(paramText)
            contentTable.add(paramValue).row()

            paramsFields.add(param.key to paramValue)
        }

        val saveButton = VisTextButton(
            DIGameGlobalContainer.bundle.get("button.save"),
            DIGameGlobalContainer.roundStyle
        )

        saveButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                super.clicked(event, x, y)
                for (param in paramsFields) {
//                    setProperty(settings.cellsSettings, param.component1(), param.component2().text)
//                    println(param.component1())
                    //setProperty(settings.cellsSettings[cell.key], param.component1(), param.component2().text)
                    settings.cellsSettings[cell.key]?.let { cellObj ->
                        setProperty(cellObj, param.component1(), param.component2().text)
                    }
                }

            }
        })

        contentTable.add(saveButton).colspan(2).center().row()

        closeOnEscape()
        pack()
    }

    fun setProperty(obj: Any, propName: String, value: String) {
        val prop = obj::class.memberProperties.find { it.name == propName }
        prop?.let {
            it.isAccessible = true
            val convertedValue = when (it.returnType.classifier) {
                String::class -> value
                Int::class -> value.toInt()
                Boolean::class -> value.toBoolean()
                Float::class -> value.toFloat()
                Double::class -> value.toDouble()
                Long::class -> value.toLong()
                else -> throw IllegalArgumentException("Unsupported type: ${it.returnType}")
            }

            if (it is KMutableProperty<*>) {
                it.setter.call(obj, convertedValue)
            }
        }
    }

    fun <T : Any> T.toMapJava(): Map<String, Any?> {
        return this.javaClass.declaredFields.associate { field ->
            field.isAccessible = true // Разрешаем доступ к private полям
            field.name to field.get(this)
        }
    }
}
