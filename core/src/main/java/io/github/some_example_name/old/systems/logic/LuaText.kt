package io.github.some_example_name.old.systems.logic

import org.squiddev.cobalt.LuaString
import org.squiddev.cobalt.ValueFactory.valueOf

/**
 * Текст между Kotlin и Lua.
 *
 * Строка Lua — это байты. Скрипт приходит в машину как UTF-8 (String.byteInputStream), а
 * Cobalt переводит LuaString в String и обратно по одному символу на байт (Latin-1): русский
 * текст из objective("…") без этого приходил на экран кракозябрами, а текст из бандла в
 * скрипт — вопросами (символы выше 255 Cobalt заменяет на «?»).
 */
internal fun LuaString.utf8(): String {
    val bytes = ByteArray(length())
    copyTo(bytes, 0)
    return String(bytes, Charsets.UTF_8)
}

internal fun luaUtf8(text: String): LuaString = valueOf(text.toByteArray(Charsets.UTF_8))

/** Сообщение Cobalt (ошибка компиляции или выполнения) с кусками скрипта — обратно в UTF-8. */
internal fun luaMessageUtf8(message: String): String =
    String(message.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
