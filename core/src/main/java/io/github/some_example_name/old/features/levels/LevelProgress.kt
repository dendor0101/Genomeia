package io.github.some_example_name.old.features.levels

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.ObjectMap
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.core.GameFiles

/**
 * Пройденные уровни для меню кампании.
 *
 * Ключ — LevelInfo.id, значение — true. Файл лежит рядом с игрой в
 * campaign/levels/levels.json; старый код писал туда же через Gdx.files.local, но
 * читал из assets, поэтому отметки не доходили до списка.
 */
object LevelProgress {

    private val json = Json()
    private val file get() = GameFiles.gameDir.child("campaign").child("levels").child("levels.json")

    fun completedIds(): Set<Int> {
        val values = read()
        if (values.isEmpty) return emptySet()
        return values.keys().mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun isCompleted(id: Int): Boolean = read()[id.toString()] == true

    /** Записать прохождение. При повторе .bug файл игрока не трогаем. */
    fun markCompleted(id: Int) {
        if (BugReplay.isActive) return
        val values = read()
        values.put(id.toString(), true)
        runCatching {
            file.parent().mkdirs()
            file.writeString(json.prettyPrint(values), false, "UTF-8")
        }.onFailure { Gdx.app?.error("LevelProgress", "не удалось сохранить прогресс", it) }
    }

    private fun read(): ObjectMap<String, Boolean> =
        runCatching {
            if (!file.exists()) ObjectMap()
            else json.fromJson(ObjectMap::class.java, java.lang.Boolean::class.java, file)
                as ObjectMap<String, Boolean>
        }.getOrElse { ObjectMap() }
}
