package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.core.DIGameGlobalContainer
import java.util.Locale

/**
 * Настройки игрока, которые переживают перезапуск: звук, язык, вид, подсказки.
 *
 * Лежат в libGDX Preferences (на десктопе — ~/.prefs/genomeia, на Android — SharedPreferences),
 * а не рядом с игрой, чтобы не зависеть от того, откуда запущена сборка.
 *
 * При повторе бага (см. BugReplay) не читаем и не пишем: игра играет настройками игрока из .bug,
 * а личные настройки разработчика не должны ни влиять на повтор, ни перезаписываться им.
 */
object UserPreferences {

    private const val NAME = "genomeia"
    private const val MUSIC_VOLUME = "musicVolume"
    private const val SOUND_VOLUME = "soundVolume"
    private const val LANGUAGE = "language"
    private const val RENDER_MODE = "render_mode"
    private const val SHOW_HINTS = "show_hints"

    private val prefs by lazy { Gdx.app.getPreferences(NAME) }

    /** После создания контейнеров и до первого экрана. */
    fun load() {
        if (BugReplay.isActive) return

        GlobalSettings.MUSIC_VOLUME = prefs.getInteger(MUSIC_VOLUME, GlobalSettings.MUSIC_VOLUME).coerceIn(0, 100)
        GlobalSettings.SOUND_VOLUME = prefs.getInteger(SOUND_VOLUME, GlobalSettings.SOUND_VOLUME).coerceIn(0, 100)

        GlobalSettings.RENDER_MODE = prefs.getInteger(RENDER_MODE, GlobalSettings.RENDER_MODE)
        GlobalSettings.SHOW_HINTS = prefs.getBoolean(SHOW_HINTS, GlobalSettings.SHOW_HINTS)

        val tag = prefs.getString(LANGUAGE, "")
        if (tag.isNotBlank()) {
            GlobalSettings.currentLanguageTag = tag
            DIGameGlobalContainer.setLanguage(Locale.forLanguageTag(tag))
        }
    }

    /** Пишет текущие значения. Файл крошечный, поэтому сбрасываем на диск сразу. */
    fun save() {
        if (BugReplay.isActive) return

        prefs.putInteger(MUSIC_VOLUME, GlobalSettings.MUSIC_VOLUME)
        prefs.putInteger(SOUND_VOLUME, GlobalSettings.SOUND_VOLUME)
        prefs.putString(LANGUAGE, GlobalSettings.currentLanguageTag)
        prefs.putInteger(RENDER_MODE, GlobalSettings.RENDER_MODE)
        prefs.putBoolean(SHOW_HINTS, GlobalSettings.SHOW_HINTS)
        prefs.flush()
    }
}
