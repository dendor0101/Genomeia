package io.github.some_example_name.old.screens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisWindow

fun VisWindow.setupTitleSize(game: MyGame) {
    if (Gdx.app.type != Application.ApplicationType.Android) {
        addCloseButton()
        return
    }
    // Увеличиваем заголовок (title label): применяем custom font и scale
    val titleLabel = getTitleLabel()
    game.applyCustomFontMedium(titleLabel)  // Предполагаем, что это устанавливает больший шрифт

    val titleTable = getTitleTable()
    titleTable.pad(5f * Gdx.graphics.density)  // Увеличьте внутренние отступы
    titleTable.padTop(5f * Gdx.graphics.density).padBottom(5f * Gdx.graphics.density)  // Вертикальные паддинги

    addCloseButton()  // Добавляем крестик после настройки label (чтобы prefHeight учел изменения)

    // Получаем closeButton из titleTable (индекс 1, так как 0 - titleLabel)
    val closeButton = titleTable.children.get(1) as VisImageButton

    // Увеличиваем крестик (close button): устанавливаем больший размер
    closeButton.setSize(30f * Gdx.graphics.density, 30f * Gdx.graphics.density)  // Размер кнопки
    closeButton.image.setScaling(Scaling.fit)  // Масштабируем иконку внутри кнопки
    closeButton.imageCell.size(24f * Gdx.graphics.density)  // Размер иконки (подберите)

    // Настраиваем cells для увеличения высоты titleTable
    titleTable.getCell(titleLabel)
        .minHeight(10f * Gdx.graphics.density)
        .prefHeight(10f * Gdx.graphics.density)

    val closeButtonSize = if (Gdx.app.type == Application.ApplicationType.Android) 20f else 30f

    titleTable.getCell(closeButton)
        .size(closeButtonSize * Gdx.graphics.density, closeButtonSize * Gdx.graphics.density)

    // Пересчитываем prefHeight и обновляем padTop окна для большей верхней панели
    titleTable.invalidateHierarchy()  // Пересчитать layout titleTable
    padTop(titleTable.getPrefHeight())  // Обновить top padding окна новым prefHeight
}


