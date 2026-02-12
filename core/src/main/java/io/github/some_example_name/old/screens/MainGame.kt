package io.github.some_example_name.old.screens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.ScrollableTextArea
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisCheckBox.VisCheckBoxStyle
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisRadioButton
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextButton.VisTextButtonStyle
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisValidatableTextField
import io.github.some_example_name.old.good_one.pikSounds
import io.github.some_example_name.old.screens.GlobalSettings.MSAA
import io.github.some_example_name.old.screens.GlobalSettings.MUSIC_VOLUME
import io.github.some_example_name.old.screens.GlobalSettings.UI_SCALE
import io.github.some_example_name.old.platform_flag.FileProvider
import kotlin.math.max

interface KeyBoardListener {
    fun showNativeInput(default: String, callback: (Float) -> Unit)
}
var openKeyBoardListenerGlobal: KeyBoardListener? = null

class MyGame(val multiPlatformFileProvider: FileProvider, val openKeyBoardListener: KeyBoardListener? = null) : Game() {

    private val trackFiles = listOf(
        "track1.ogg",
        "track2.ogg",
        "track3.ogg",
        "track4.ogg",
        "track5.ogg"
    )

    lateinit var currentMusic: Music
    private val trackQueue = mutableListOf<String>()

    lateinit var extraLargeFont: BitmapFont  // Кастомный большой шрифт для передачи в экраны
    lateinit var largeFont: BitmapFont  // Кастомный большой шрифт для передачи в экраны
    lateinit var mediumFont: BitmapFont  // Кастомный большой шрифт для передачи в экраны
    lateinit var smallFont: BitmapFont  // Кастомный большой шрифт для передачи в экраны

    override fun create() {
        VisUI.load()  // Загружаем дефолтный VisUI

        // Генерация шрифта с большим размером (адаптировано под DPI)
        val generator = FreeTypeFontGenerator(Gdx.files.internal("fonts/Roboto-Regular.ttf"))
        val parameter = FreeTypeFontGenerator.FreeTypeFontParameter()
        parameter.genMipMaps = true
        parameter.minFilter = TextureFilter.MipMapLinearLinear
        parameter.magFilter = TextureFilter.Linear
        parameter.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"  // Добавляем кириллицу для русского текста

        val MIN_GEN_SIZE = 15

        // Medium font
        val desiredMediumSize = (16 * Gdx.graphics.density).toInt()
        parameter.size = max(MIN_GEN_SIZE, desiredMediumSize)
        mediumFont = generator.generateFont(parameter)
        if (desiredMediumSize < MIN_GEN_SIZE) {
            mediumFont.data.setScale(desiredMediumSize.toFloat() / MIN_GEN_SIZE.toFloat())
        }

        // Small font
        val desiredSmallSize = (8 * Gdx.graphics.density).toInt()
        parameter.size = max(MIN_GEN_SIZE, desiredSmallSize)
        smallFont = generator.generateFont(parameter)
        if (desiredSmallSize < MIN_GEN_SIZE) {
            smallFont.data.setScale(desiredSmallSize.toFloat() / MIN_GEN_SIZE.toFloat())
        }

        // Large font
        val desiredLargeSize = (24 * Gdx.graphics.density).toInt()
        parameter.size = max(MIN_GEN_SIZE, desiredLargeSize)
        largeFont = generator.generateFont(parameter)
        if (desiredLargeSize < MIN_GEN_SIZE) {
            largeFont.data.setScale(desiredLargeSize.toFloat() / MIN_GEN_SIZE.toFloat())
        }

        // Extra large font
        val desiredExtraLargeSize = (32 * Gdx.graphics.density).toInt()
        parameter.size = max(MIN_GEN_SIZE, desiredExtraLargeSize)
        extraLargeFont = generator.generateFont(parameter)
        if (desiredExtraLargeSize < MIN_GEN_SIZE) {
            extraLargeFont.data.setScale(desiredExtraLargeSize.toFloat() / MIN_GEN_SIZE.toFloat())
        }

        generator.dispose()

        val density = Gdx.graphics.density
        val sliderStyle = SliderStyle(VisUI.getSkin().get("default-horizontal", SliderStyle::class.java))
        sliderStyle.knob.minWidth = 10f * density  // Размер ручки (knob)
        sliderStyle.knob.minHeight = 25f * density
        sliderStyle.background.minHeight = 10f * density  // Высота фона ползунка
        // Опционально: масштабируйте другие drawables, если нужно (knobDown, knobOver)
        sliderStyle.knobDown?.minWidth = 10f * density
        sliderStyle.knobDown?.minHeight = 25f * density
        sliderStyle.knobOver?.minWidth = 10f * density
        sliderStyle.knobOver?.minHeight = 25f * density

        // Локальный стиль для чекбоксов (копия дефолтного)
        val checkBoxStyle = VisCheckBoxStyle(VisUI.getSkin().get("default", VisCheckBoxStyle::class.java))
        val checkBoxSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f
        checkBoxStyle.checkBackground.minWidth = checkBoxSize * density  // Размер квадрата в on/off
        checkBoxStyle.checkBackground.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minHeight = checkBoxSize * density
        checkBoxStyle.tick.minWidth = checkBoxSize * density
        checkBoxStyle.tick.minHeight = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minWidth = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minHeight = checkBoxSize * density
        checkBoxStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) mediumFont else largeFont

        openKeyBoardListenerGlobal = openKeyBoardListener
        shuffleTracks()
        playNextTrack()
        setScreen(MenuScreen(this, multiPlatformFileProvider))

        pikSounds = listOf<Sound>(
            Gdx.audio.newSound(Gdx.files.internal("pik1.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik2.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik3.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik4.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik5.mp3"))
        )

        UI_SCALE = 1f//((if (Gdx.app.type == Application.ApplicationType.Android) Gdx.graphics.density / 2f else Gdx.graphics.density * 1.5f) * 10).toInt() / 10f
        MSAA = if (Gdx.app.type == Application.ApplicationType.Android) 1 else 2
    }

    private fun shuffleTracks() {
        trackQueue.clear()
        trackQueue.addAll(trackFiles.shuffled())
    }

    private fun playNextTrack() {
        if (trackQueue.isEmpty()) shuffleTracks()

        val nextTrack = trackQueue.removeAt(0)
        currentMusic = Gdx.audio.newMusic(Gdx.files.internal(nextTrack))
        currentMusic.volume = MUSIC_VOLUME / 100f
        currentMusic.isLooping = false

        currentMusic.setOnCompletionListener {
            currentMusic.dispose()
            playNextTrack()
        }

        currentMusic.play()
    }

    override fun dispose() {
        currentMusic.dispose()
        screen.dispose()
        largeFont.dispose()
        mediumFont.dispose()
        smallFont.dispose()
        pikSounds.forEach {
            it.dispose()
        }
        VisUI.dispose()
        super.dispose()
        Gdx.app.exit()
    }
}

//VisRadioButton
fun MyGame.applyCustomFont(button: VisTextButton) {
    val newStyle = VisTextButtonStyle(button.style as VisTextButtonStyle)  // Копируем текущий стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont   // Применяем большой шрифт
    button.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFont(scrollableTextArea: ScrollableTextArea) {
    val textArea = scrollableTextArea // Получаем внутренний VisTextArea
    val oldStyle = scrollableTextArea.style
    val newStyle = VisTextField.VisTextFieldStyle(oldStyle)  // Копируем стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) {
        this.mediumFont
    } else {
        this.largeFont
    }
    textArea.style = newStyle
}

fun MyGame.applyCustomFont(selectBox: VisSelectBox<String>) {
    val newStyle =
        SelectBox.SelectBoxStyle(selectBox.style as SelectBox.SelectBoxStyle)  // Копируем текущий стиль
    val customFont = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont
    newStyle.font = customFont  // Применяем большой шрифт для выбранного элемента
    newStyle.listStyle.font = customFont  // Применяем большой шрифт для элементов списка (dropdown)
    selectBox.style = newStyle  // Устанавливаем стиль обратно
    selectBox.invalidateHierarchy()  // Пересчитываем layout, чтобы учесть изменения размера
}

fun MyGame.applyCustomFont(button: VisTextField) {
    val newStyle = VisTextField.VisTextFieldStyle(button.style as VisTextField.VisTextFieldStyle)  // Копируем текущий стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont   // Применяем большой шрифт
    button.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFont(button: VisValidatableTextField) {
    val newStyle = VisTextField.VisTextFieldStyle(button.style as VisTextField.VisTextFieldStyle)  // Копируем текущий стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont   // Применяем большой шрифт
    button.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFont(label: VisLabel) {
    val newStyle = Label.LabelStyle(label.style)  // Копируем текущий стиль (используем стандартный LabelStyle из scene2d.ui)
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.largeFont else this.extraLargeFont  // Применяем большой шрифт
    label.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFontMedium(label: VisLabel) {
    val newStyle = Label.LabelStyle(label.style)  // Копируем текущий стиль (используем стандартный LabelStyle из scene2d.ui)
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont  // Применяем большой шрифт
    label.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFontMedium(label: Label) {
    val newStyle = Label.LabelStyle(label.style)  // Копируем текущий стиль (используем стандартный LabelStyle из scene2d.ui)
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont  // Применяем большой шрифт
    label.style = newStyle  // Устанавливаем стиль обратно
}

fun MyGame.applyCustomFont(radioButton: VisRadioButton) {
    val newStyle = VisCheckBox.VisCheckBoxStyle(radioButton.style as VisCheckBox.VisCheckBoxStyle)  // Копируем текущий стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont   // Применяем большой шрифт
    radioButton.style = newStyle  // Устанавливаем стиль обратно
}


fun MyGame.applyCustomFont(radioButton: VisCheckBox) {
    val newStyle = VisCheckBox.VisCheckBoxStyle(radioButton.style as VisCheckBox.VisCheckBoxStyle)  // Копируем текущий стиль
    newStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) this.mediumFont else this.largeFont   // Применяем большой шрифт
    radioButton.style = newStyle  // Устанавливаем стиль обратно
}
