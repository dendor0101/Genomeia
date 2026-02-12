package io.github.some_example_name.old.good_one

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisRadioButton
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.genome_editor.dialog.actionButton
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.setupTitleSize

class GenomeListDialog(
    val genomesList: List<String>,
    val selectedGenomeIndex: Int?,
    title: String,
    val new: String,
    val select: String,
    val import: String,
    val onNew: () -> Unit,
    val onNext: (String) -> Unit,
    val onRestart: () -> Unit,
    val game: MyGame,
    val onResize: (() -> Unit) -> Unit,
    val isMenu: Boolean
) : VisDialog(title) {
    var selectedIndex = selectedGenomeIndex ?: 0

    val scrollPane: ScrollPane

    init {
        isModal = true
        isMovable = true

        val scrollContentTable = VisTable()

        setupTitleSize(game)

        setupUI(scrollContentTable)
        // Оборачиваем в ScrollPane
        scrollPane = ScrollPane(scrollContentTable).apply {
            setFadeScrollBars(false)      // полоска прокрутки всегда видна
            setScrollingDisabled(false, false)
            setForceScroll(false, true)
            setFlickScroll(true)
            setOverscroll(false, true)
        }

        // Добавляем ScrollPane в диалог с ограничением по высоте
        contentTable.add(scrollPane)
            .grow()  // растягиваем на доступное место
            .maxHeight(Gdx.graphics.height * 0.8f)

        contentTable.row()

        closeOnEscape()

        onResize.invoke {
            centerWindow()
        }

        pack()  // Пересчитываем размеры после изменений
        centerWindow()
    }

    private fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density
        val group = ButtonGroup<VisRadioButton>()
        group.setMinCheckCount(1) // можно ничего не выбирать
        group.setMaxCheckCount(1) // только один выбран одновременно

        // Используем стиль "radio" для круглых иконок (вместо "default", который для чекбоксов квадратных)
        val radioStyle = VisCheckBox.VisCheckBoxStyle(
            VisUI.getSkin().get("radio", VisCheckBox.VisCheckBoxStyle::class.java)
        )
        val iconSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f  // Базовый размер иконки (подберите)

        // Устанавливаем размеры для круглой иконки: checkBackground - off (пустой круг), tick - on (точка внутри)
        // checkboxOff не существует; используйте checkBackground и tick из VisCheckBoxStyle
        radioStyle.checkBackground.minWidth = iconSize * density
        radioStyle.checkBackground.minHeight = iconSize * density

        radioStyle.tick.minWidth = iconSize * density  // Размер точки (on state); подберите, чтобы соответствовал background
        radioStyle.tick.minHeight = iconSize * density

        // Для состояний over/down/disabled, если они заданы
        if (radioStyle.checkBackgroundOver != null) {
            radioStyle.checkBackgroundOver.minWidth = iconSize * density
            radioStyle.checkBackgroundOver.minHeight = iconSize * density
        }
        if (radioStyle.checkBackgroundDown != null) {
            radioStyle.checkBackgroundDown.minWidth = iconSize * density
            radioStyle.checkBackgroundDown.minHeight = iconSize * density
        }
        if (radioStyle.tickDisabled != null) {
            radioStyle.tickDisabled.minWidth = iconSize * density
            radioStyle.tickDisabled.minHeight = iconSize * density
        }

        // Шрифт, как в оригинале
        radioStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.mediumFont else game.largeFont

        val content = VisTable(true)

        genomesList.forEachIndexed { index, string ->
            val rb = VisRadioButton(string, radioStyle)
            game.applyCustomFont(rb)
            group.add(rb)
            content.add(rb).left().row()
            if (selectedIndex == index) rb.isChecked = true

            // Обрабатываем именно клик
            rb.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (rb.isChecked) {
                        selectedIndex = index
                        if (selectedIndex != -1) {
                            onNext.invoke(genomesList[selectedIndex])
                        }
                        fadeOut()
                    }
                }
            })
        }

        scrollContentTable.add(content).pad(10f * density).row()

        val bottomButtonTable = VisTable()

        // Левое пространство (пустая ячейка)
        bottomButtonTable.add().growX().uniformX()

        actionButton(new, game) {
            onNew.invoke()
            fadeOut()
        }.also {
            game.applyCustomFont(it)
            bottomButtonTable.add(it).padBottom(5f * density).padRight(8f * density)
        }

        // Среднее пространство
        bottomButtonTable.add().growX().uniformX()

        actionButton(select, game) {
            if (selectedIndex != -1) {
                onNext.invoke(genomesList[selectedIndex])
            }
            fadeOut()
        }.also {
            game.applyCustomFont(it)
            bottomButtonTable.add(it).padBottom(5f * density).padRight(if (Gdx.app.type == Application.ApplicationType.Android) 8f * density else 0f)
        }

        if (Gdx.app.type == Application.ApplicationType.Android && isMenu) {
            actionButton(import, game) {
                game.multiPlatformFileProvider.importGenome { fileHandle ->
                    onRestart.invoke()
                    fadeOut()
                }
            }.also {
                game.applyCustomFont(it)
                bottomButtonTable.add(it).padBottom(5f * density)
            }
        }

        // Правое пространство
        bottomButtonTable.add().growX().uniformX()

        // Добавляем таблицу в родителя с растяжкой по ширине
        scrollContentTable.add(bottomButtonTable).growX()
    }

    override fun close() {
        super.close()
        onResize.invoke { /* пустая лямбда, чтобы сбросить */ }
    }
}


