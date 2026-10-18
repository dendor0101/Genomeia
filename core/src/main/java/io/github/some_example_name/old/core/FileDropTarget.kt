package io.github.some_example_name.old.core

import com.badlogic.gdx.files.FileHandle

/**
 * Экран, который принимает файлы, перетащенные в окно игры (ПК, см. MyGame.filesDropped).
 * Сейчас это главное меню (карты, а геномы оно передаёт экрану «Геномы») и сам экран «Геномы»
 * (импорт геномов). Остальные экраны перетаскивание не замечают.
 */
interface FileDropTarget {
    /** Поток игры. Файлы только читаются. */
    fun onFilesDropped(files: List<FileHandle>)
}
