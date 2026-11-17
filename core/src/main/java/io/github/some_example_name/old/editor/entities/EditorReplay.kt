package io.github.some_example_name.old.editor.entities

interface EditorReplay {
    fun reset()
    fun copy()

    /** Сколько байт сейчас занимают массивы записи (по ёмкости, а не по заполнению). */
    fun byteSize(): Long
}
