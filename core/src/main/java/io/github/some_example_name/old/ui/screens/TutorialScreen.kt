package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.*
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.editor.ui.dialog.cellsType
import io.github.some_example_name.old.systems.render.texturePaths
import io.github.some_example_name.old.ui.dialogs.CellDescription


class TutorialScreen(
    val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {

    private lateinit var stage: Stage
    val gridTable = VisTable()
    private val panelSize = 280f
    private val panelsList = mutableListOf<VisTable>()

    val cellsToImage: Map<String, Int> = mapOf(
        "Leaf" to 0,
        "Fat" to 1,
        "Bone" to 2,
        "Tail" to 3,
        "Neuron" to 4,
        "Muscle" to 5,
        "Sensor" to 6,
        "Sucker" to 7,
        "Mike" to 8,
        "Excreta" to 9,
        "SuctionCup" to 10,
        "Sticky" to 11,
        "Pumper" to 12,
        "Chameleon" to 13,
        "Eye" to 14,
        "Compass" to 15,
        "Controller" to 16,
        "TouchTrigger" to 17,
        "Zygote" to 18,
        "Producer" to 19,
        "Breakaway" to 20,
        "Vascular" to 21,
        "PheromoneEmitter" to 22,
        "PheromoneSensor" to 23,
        "Punisher" to 24,
        "Not_cell" to 25
    )

    override fun show() {
        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        val table = VisTable()
        table.setFillParent(true)
        TableUtils.setSpacingDefaults(table)
        stage.addActor(table)

        gridTable.defaults().pad(10f * Gdx.graphics.density)

        val actualPanelWidth = (panelSize * 1.35f) * Gdx.graphics.density
        val panelHeight = panelSize * Gdx.graphics.density

        val panelSpacing = 20f * Gdx.graphics.density

        val countRows = (Gdx.graphics.width / (actualPanelWidth + panelSpacing)).toInt().coerceAtLeast(1)

        var i = 0
//        val whatSkip = cellsType.count() / countRows

        for (cell in cellsType) {
            val panel = VisTable()
            panel.background = VisUI.getSkin().getDrawable("window")
            panel.pad(20f)

            val iconName = texturePaths[cellsToImage[cell] ?: 25]

            val texture = Texture(Gdx.files.internal(iconName))
            val cellImage = VisImage(texture)
            cellImage.setScaling(Scaling.fit)

            panel.add(cellImage).center().pad(10f)

            panel.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                    CellDescription(game, bundle, cell, iconName).show(stage)
                }
            })

            val cellName = VisLabel(cell)
            game.applyCustomFont(cellName)
            panel.add(cellName).center()

            gridTable.add(panel).uniformX().uniformY().fill().width(actualPanelWidth).height(panelHeight)
            i++
            if (i % countRows == 0) {
                gridTable.row()
            }

            panelsList.add(panel)
        }


//            val parametr = VisLabel(element.key.name)
//            game.applyCustomFont(parametr)
//            parametr.setAlignment(Align.left)
//
//            val splitter = VisLabel(":")
//            game.applyCustomFont(splitter)
//
//            val input = VisTextField(element.value.toString())
//            table.add(parametr).left()
//            table.add(splitter)
//            table.add(input).padLeft(25f).row()
//
//            values.addLast(element.key.name to input)

        val buttonsTable = VisTable()
        buttonsTable.defaults().pad(10f)

        val menuButton = VisTextButton(bundle.get("button.back"))
        game.applyCustomFont(menuButton)
        menuButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                game.screen = MenuScreen(game, multiPlatformFileProvider)
            }
        })
        buttonsTable.add(menuButton).height(60f * Gdx.graphics.density)

        val scrollTable = VisScrollPane(gridTable)
        scrollTable.setFadeScrollBars(false);
        scrollTable.setScrollingDisabled(true, false);

        table.add(scrollTable).expand().center().row()
        table.add(buttonsTable).padBottom(20f).center()
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        gridTable.clearChildren()

        val actualPanelWidth = (panelSize * 1.35f) * Gdx.graphics.density
        val panelHeight = panelSize * Gdx.graphics.density
        val cellSpacing = 20f * Gdx.graphics.density

        val countColumns = (width / (actualPanelWidth + cellSpacing)).toInt().coerceAtLeast(1)

        var i = 0
        for (panel in panelsList) {
            gridTable.add(panel)
                .uniformX()
                .uniformY()
                .fill()
                .width(actualPanelWidth)
                .height(panelHeight)

            i++
            if (i % countColumns == 0) {
                gridTable.row()
            }
        }

        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
    }
}
