package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Json
import io.github.some_example_name.old.core.GlobalSimulationSettings
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import com.badlogic.gdx.utils.JsonValue
import io.github.some_example_name.old.core.DIGameGlobalContainer
import java.lang.reflect.Modifier

class SettingsViewModel {


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

    fun saveSettings(settings: GlobalSettings, fileName: String) {
        val json = Json()
        json.setSerializer(GlobalSettings::class.java, KotlinObjectSerializer(GlobalSettings))

        val jsonString = json.toJson(GlobalSettings)
        println("Saving JSON:\n$jsonString")

        val fileHandle = Gdx.files.local(fileName)
        fileHandle.parent().mkdirs()
        fileHandle.writeString(jsonString, false)
    }

    companion object {
        fun loadSettings(fileName: String) {
            val file = Gdx.files.local(fileName)
            if (!file.exists()) return

            val json = Json()
            json.setIgnoreUnknownFields(true)

            json.setSerializer(GlobalSettings::class.java, KotlinObjectSerializer(GlobalSettings))

            try {
                val jsonData = com.badlogic.gdx.utils.JsonReader().parse(file.readString())

                json.readValue(GlobalSettings::class.java, jsonData)

                println("настрйки загружены")

                val savedLocale = Locale.forLanguageTag(GlobalSettings.currentLanguageTag)
                DIGameGlobalContainer.setLanguage(savedLocale)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}

object GlobalSettings {
    var MUSIC_VOLUME = 0
    var SOUND_VOLUME = 50
    var currentLanguageTag: String = Locale.getDefault().toLanguageTag()
}

class KotlinObjectSerializer<T : Any>(private val instance: T) : Json.Serializer<T> {

    override fun write(json: Json, obj: T, knownType: Class<*>?) {
        json.writeObjectStart()

        val clazz = instance.javaClass
        val fields = clazz.declaredFields

        for (field in fields) {
            if (field.name == "INSTANCE") continue

            try {
                field.isAccessible = true
                val value = field.get(null)
                json.writeValue(field.name, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        json.writeObjectEnd()
    }

    override fun read(json: Json, jsonData: JsonValue, type: Class<*>?): T {
        val clazz = instance.javaClass
        val fields = clazz.declaredFields

        for (field in fields) {
            if (field.name == "INSTANCE") continue

            if (jsonData.has(field.name)) {
                try {
                    field.isAccessible = true
                    val value = json.readValue(field.type, jsonData.get(field.name))
                    field.set(null, value)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return instance
    }
}
