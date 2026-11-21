package io.github.some_example_name.physics.lab.stands.viewer

import com.badlogic.gdx.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Что человек хочет сказать о сцене. Порядок — тот, в котором клавиша M перебирает пометки. */
internal enum class MarkKind(val id: String, val title: String, val color: Color) {
    DISAGREE("disagree", "не согласен с правилом или порогом", Color.valueOf("FFB74DFF")),
    FIX("fix", "сцена поставлена неверно — доработать", Color.valueOf("4FC3F7FF")),
    DELETE("delete", "удалить сцену", Color.valueOf("B0BEC5FF")),
    BUG("bug", "настоящий баг физики — чинить", Color.valueOf("FF5252FF")),
    LOOK("look", "посмотреть ещё раз", Color.valueOf("CE93D8FF"));

    companion object { fun byId(id: String): MarkKind? = values().firstOrNull { it.id == id } }
}

/** Пометка сцены: вид, слова человека и на чём он стоял, когда её ставил. */
internal class SceneMark(val id: String, var kind: MarkKind, var note: String, var tick: Int, var time: String, var verdict: String, var title: String)

/**
 * ПОМЕТКИ СЦЕН. Человек смотрит сцену и одной клавишей говорит, что с ней не так: не согласен с
 * правилом, сцена поставлена неверно, удалить, настоящий баг. Пометки сразу пишутся в файл
 * (docs/physics/stands-marks.tsv в папке ветки): по нему сцены потом разбирают — что
 * поправить в правилах, что переставить, что убрать.
 *
 * Файл — текст с табуляцией, одна пометка на строку: номер сцены (с вариацией), вид пометки,
 * её смысл словами, заметка, тик, на котором стояла сцена, время, итог правил в тот момент и
 * название сцены. Переписывается целиком при каждой правке: пометок десятки, а не тысячи.
 */
internal class Marks(private val file: File) {
    private val byId = LinkedHashMap<String, SceneMark>()

    val size: Int get() = byId.size
    operator fun get(id: String): SceneMark? = byId[id]

    fun load() {
        if (!file.isFile) return
        for (line in file.readLines().drop(1)) {
            val f = line.split('\t')
            if (f.size < 8) continue
            val kind = MarkKind.byId(f[1]) ?: continue
            byId[f[0]] = SceneMark(f[0], kind, f[3], f[4].toIntOrNull() ?: 0, f[5], f[6], f[7])
        }
    }

    /** Следующая пометка по кругу: нет → первая → … → последняя → нет. */
    fun cycle(id: String, tick: Int, verdict: String, title: String) {
        val m = byId[id]
        if (m == null) { byId[id] = SceneMark(id, MarkKind.values()[0], "", tick, now(), verdict, title); save(); return }
        val next = m.kind.ordinal + 1
        // Пометка с заметкой по кругу не снимается: слова человека так не теряются (снять — Shift+M).
        if (next >= MarkKind.values().size) { if (m.note.isEmpty()) byId.remove(id) else m.kind = MarkKind.values()[0] }
        else m.kind = MarkKind.values()[next]
        byId[id]?.let { it.tick = tick; it.time = now(); it.verdict = verdict }
        save()
    }

    fun note(id: String, text: String, tick: Int, verdict: String, title: String) {
        val m = byId[id] ?: SceneMark(id, MarkKind.LOOK, "", tick, now(), verdict, title).also { byId[id] = it }
        m.note = text.replace('\t', ' ').replace('\n', ' ')
        m.tick = tick; m.time = now(); m.verdict = verdict
        save()
    }

    fun remove(id: String) { if (byId.remove(id) != null) save() }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

    private fun save() {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("id\tmark\tmeaning\tnote\ttick\ttime\tverdict\ttitle\n")
        for (m in byId.values) sb.append(m.id).append('\t').append(m.kind.id).append('\t').append(m.kind.title).append('\t').append(m.note).append('\t')
            .append(m.tick).append('\t').append(m.time).append('\t').append(m.verdict.replace('\t', ' ').replace('\n', ' ')).append('\t').append(m.title).append('\n')
        file.writeText(sb.toString())
    }
}
