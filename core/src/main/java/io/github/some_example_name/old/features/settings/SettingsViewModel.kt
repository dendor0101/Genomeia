package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.log.ActionLog
import java.util.Locale

enum class SettingsTab {
    SOUND, GRAPHICS, LANGUAGE
}

/** Что игрок хочет сделать в настройках. */
sealed interface SettingsIntent {
    val name: String
    val detail: String get() = ""

    data class SelectTab(val tab: SettingsTab) : SettingsIntent {
        override val name get() = "SelectTab"
        override val detail get() = tab.name
    }

    data class SetMusicVolume(val value: Int) : SettingsIntent {
        override val name get() = "SetMusicVolume"
        override val detail get() = value.toString()
    }

    data class SetSoundVolume(val value: Int) : SettingsIntent {
        override val name get() = "SetSoundVolume"
        override val detail get() = value.toString()
    }

    data class SetLanguage(val locale: Locale) : SettingsIntent {
        override val name get() = "SetLanguage"
        override val detail get() = locale.toLanguageTag()
    }

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

    fun handle(intent: SettingsIntent) {
        ActionLog.record(LOG_SOURCE, intent.name, intent.detail)

        when (intent) {
            is SettingsIntent.SelectTab -> currentTab = intent.tab

            is SettingsIntent.SetMusicVolume -> {
                GlobalSettings.MUSIC_VOLUME = intent.value
                game.currentMusic.volume = intent.value / 100f
            }

            is SettingsIntent.SetSoundVolume -> {
                GlobalSettings.SOUND_VOLUME = intent.value
            }

            is SettingsIntent.SetLanguage -> {
                GlobalSettings.currentLanguageTag = intent.locale.toLanguageTag()
                DIGameGlobalContainer.setLanguage(intent.locale)
                onLanguageChanged?.invoke()
            }

            is SettingsIntent.OpenGithub -> Gdx.net.openURI(GITHUB_URL)
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
        const val LOG_SOURCE = "Settings"
        const val GITHUB_URL = "https://github.com/dendor0101/Genomeia"
    }
}

// === Глобальные настройки ===
object GlobalSettings {
    var MUSIC_VOLUME = 0
    var SOUND_VOLUME = 50
    var currentLanguageTag: String = Locale.getDefault().toLanguageTag()
}
