package io.github.some_example_name.old.features.settings

import io.github.some_example_name.old.commands.GameCommand
import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.PlatformWindow
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import kotlinx.serialization.Serializable
import java.util.Locale

enum class SettingsTab {
    SOUND, GRAPHICS, LANGUAGE
}

/** Что игрок хочет сделать в настройках. */
@Serializable
sealed interface SettingsIntent : GameCommand {

    @Serializable
    data class SelectTab(val tab: SettingsTab) : SettingsIntent {
        override val name get() = "SelectTab"
        override val detail get() = tab.name
    }

    @Serializable
    data class SetMusicVolume(val value: Int) : SettingsIntent {
        override val name get() = "SetMusicVolume"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetSoundVolume(val value: Int) : SettingsIntent {
        override val name get() = "SetSoundVolume"
        override val detail get() = value.toString()
    }

    /** Язык — тегом (ru, en, ...): Locale сам по себе в журнал не пишется. */
    @Serializable
    data class SetLanguage(val languageTag: String) : SettingsIntent {
        override val name get() = "SetLanguage"
        override val detail get() = languageTag
    }

    @Serializable
    data class SetRenderMode(val renderMode: Int) : SettingsIntent {
        override val name get() = "SetRenderMode"
        override val detail get() = renderMode.toString()
    }

    /** Подсказки при наведении (на телефоне — при зажатии) на элементы редактора генома. */
    @Serializable
    data class SetShowHints(val visible: Boolean) : SettingsIntent {
        override val name get() = "SetShowHints"
        override val detail get() = visible.toString()
    }

    /** Полный экран на ПК. Вид окна, на мир не влияет; при повторе бага окно не трогаем. */
    @Serializable
    data class SetFullscreen(val enabled: Boolean) : SettingsIntent {
        override val name get() = "SetFullscreen"
        override val detail get() = enabled.toString()
    }

    @Serializable
    data object OpenGithub : SettingsIntent {
        override val name get() = "OpenGithub"
    }
}

class SettingsViewModel {

    /**
     * Выбранная вкладка живёт здесь, а не в экране: рекомпозиция пересобирает вёрстку
     * целиком, и всё, что должно её пережить, обязано лежать вне экрана.
     */
    var currentTab: SettingsTab = SettingsTab.SOUND
        private set

    val musicVolume: Int get() = GlobalSettings.MUSIC_VOLUME
    val soundVolume: Int get() = GlobalSettings.SOUND_VOLUME

    /** Экран подписывается сюда, чтобы перерисоваться после смены языка. */
    var onLanguageChanged: (() -> Unit)? = null

    fun handle(intent: SettingsIntent) = SessionJournal.execute(CommandSource.SETTINGS, intent) { apply(it) }

    private fun apply(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SelectTab -> currentTab = intent.tab

            is SettingsIntent.SetMusicVolume -> {
                GlobalSettings.MUSIC_VOLUME = intent.value
                game.currentMusic.volume = intent.value / 100f
                UserPreferences.save()
            }

            is SettingsIntent.SetSoundVolume -> {
                GlobalSettings.SOUND_VOLUME = intent.value
                UserPreferences.save()
            }

            is SettingsIntent.SetLanguage -> {
                GlobalSettings.currentLanguageTag = intent.languageTag
                DIGameGlobalContainer.setLanguage(Locale.forLanguageTag(intent.languageTag))
                UserPreferences.save()
                onLanguageChanged?.invoke()
            }

            is SettingsIntent.SetRenderMode -> {
                GlobalSettings.RENDER_MODE = intent.renderMode
                UserPreferences.save()
            }

            is SettingsIntent.SetShowHints -> {
                GlobalSettings.SHOW_HINTS = intent.visible
                UserPreferences.save()
            }

            is SettingsIntent.SetFullscreen -> if (!BugReplay.isActive) PlatformWindow.setFullscreen(intent.enabled)

            // При повторе бага браузер разработчику ни к чему.
            is SettingsIntent.OpenGithub -> if (!BugReplay.isActive) Gdx.net.openURI(GITHUB_URL)
        }
    }

    /**
     * Надёжное получение доступных языков.
     * Не использует list() — он не работает с internal-файлами.
     */
    fun getAvailableLanguages(): List<Locale> {
        // Список всех языков, которые ты поддерживаешь (добавляй сюда новые)
        val candidates = listOf(
            Locale.ENGLISH,
            Locale.forLanguageTag("ru"),
            Locale.forLanguageTag("uk"),
            Locale.forLanguageTag("de"),
            Locale.forLanguageTag("fr"),
            Locale.forLanguageTag("es"),
            Locale.forLanguageTag("pl"),
            Locale.forLanguageTag("pt"),
            Locale.forLanguageTag("tr"),
            Locale.forLanguageTag("id"),
        )

        val available = mutableListOf<Locale>()

        for (locale in candidates) {
            val fileName = buildPropertiesFileName(locale)
            if (Gdx.files.internal(fileName).exists()) {
                available.add(locale)
            }
        }

        // Если ничего не нашли — возвращаем системный
        return available.ifEmpty { listOf(Locale.getDefault()) }
    }

    /** Формирует имя файла properties по Locale */
    private fun buildPropertiesFileName(locale: Locale): String {
        val base = "ui/i18n/MyBundle"

        // Корневой файл (без суффикса) обычно соответствует английскому
        if (locale.language.isEmpty() || locale == Locale.ENGLISH || locale.language == "en") {
            // Сначала пробуем корневой, потом _en
            if (Gdx.files.internal("$base.properties").exists()) {
                return "$base.properties"
            }
            return "${base}_en.properties"
        }

        // Обычный случай: MyBundle_ru.properties, MyBundle_uk.properties и т.д.
        val tag = locale.toLanguageTag().replace('-', '_')   // ru → ru, ru-RU → ru_RU
        return "${base}_$tag.properties"
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/dendor0101/Genomeia"
    }
}

// === Глобальные настройки ===
object GlobalSettings {
    var MUSIC_VOLUME = 0
    var SOUND_VOLUME = 50
    var RENDER_MODE = 0

    /**
     * Общий флаг всплывающих подсказок интерфейса (core/ui/hint). Только вид: на игру и
     * повтор .bug не влияет.
     */
    var SHOW_HINTS = true
    var currentLanguageTag: String = Locale.getDefault().toLanguageTag()
}
