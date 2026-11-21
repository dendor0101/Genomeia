package io.github.some_example_name.physics.lab.stands.viewer

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.physics.lab.stands.Rule
import io.github.some_example_name.physics.lab.stands.SPEED_CAP
import io.github.some_example_name.physics.lab.stands.Scenario
import io.github.some_example_name.physics.lab.stands.StandRun
import io.github.some_example_name.physics.lab.stands.Variation
import java.util.Locale

/** Всё, что панелям нужно знать об окне на этот кадр. Панели только рисуют: состояние меняет StandViewer. */
internal class PanelState(
    val scenes: List<Scenario>, val bases: List<Base>, val rows: List<Row>, val selected: Int, val variation: Variation, val scenario: Scenario,
    val listScroll: Int, val infoScroll: Float, val verdicts: Verdicts, val marks: Marks, val tallies: Map<String, Tally>, val groupTitle: Map<String, String>,
    val run: StandRun?, val setupError: String, val paused: Boolean, val speed: String,
    val tainted: Boolean, val freeRun: Boolean, val finding: Boolean, val seeking: Boolean, val autoShow: Boolean,
    val onlyFailed: Boolean, val onlyMarked: Boolean, val filter: String, val typing: Int, val noteText: String, val notice: String,
    val collapsed: Set<String>, val openGroups: Set<String>, val mouseX: Float, val mouseY: Float, val preview: Boolean,
    /** Кто считает сцену — словами; [reference] — это эталонный решатель; [shadow] — строка о тени (пусто — тени нет). */
    val solver: String, val reference: Boolean, val shadow: String, val referenceNote: String,
)

/**
 * ПАНЕЛИ ОКНА: список сцен слева, описание и правила справа, подсказки и легенда поверх сцены.
 * Раскладка считается заново каждый кадр, поэтому попадание мыши в строку списка — простая
 * арифметика от верха списка ([rowAt]).
 */
internal class Panels {
    private var listTop = 0f
    private var rowHeight = 21f
    /** Сколько строк списка помещается на экране. */
    var listRows = 30
        private set
    /** На сколько пикселей описание справа не помещается в окно — предел прокрутки. */
    var infoOverflow = 0f
        private set

    /** Плашки вариаций в описании: где нарисованы на этом кадре — для щелчка мышью. */
    private val chipX = FloatArray(Variation.values().size)
    private val chipW = FloatArray(Variation.values().size)
    private val chipY = FloatArray(Variation.values().size)
    private var chipH = 0f

    fun rowAt(mouseY: Float, scroll: Int, count: Int): Int {
        if (mouseY > listTop) return -1
        val k = scroll + ((listTop - mouseY) / rowHeight).toInt()
        return if (k in 0 until count) k else -1
    }

    /** Вариация, по плашке которой щёлкнули, или null. */
    fun variationAt(mouseX: Float, mouseY: Float): Variation? {
        for (v in Variation.values()) {
            val k = v.ordinal
            if (chipW[k] > 0f && mouseX >= chipX[k] && mouseX <= chipX[k] + chipW[k] && mouseY <= chipY[k] && mouseY >= chipY[k] - chipH) return v
        }
        return null
    }

    fun draw(ui: Ui, s: PanelState, width: Float, height: Float, listWidth: Float, infoWidth: Float, barHeight: Float, rowH: Float) {
        rowHeight = rowH
        drawSceneOverlay(ui, s, listWidth, width - infoWidth, height, barHeight)
        drawList(ui, s, listWidth, height)
        drawInfo(ui, s, width - infoWidth, infoWidth, height)
    }

    private fun dot(v: Verdict?): Color = if (v == null) Palette.WAIT else if (v.passed) Palette.OK else if (v.vacant) Palette.WARN else Palette.FAIL

    // ------------------------------------------------------------------ список сцен

    private fun drawList(ui: Ui, s: PanelState, w: Float, height: Float) {
        ui.rect(0f, 0f, w, height, Palette.PANEL)
        ui.rect(w - 1f, 0f, 1f, height, Palette.PANEL_EDGE)
        val all = s.tallies[""]
        val known = (all?.ok ?: 0) + (all?.bad ?: 0)
        var y = height - 10f
        ui.text("СТЕНДЫ ФИЗИКИ", 12f, y, Palette.TEXT, large = true)
        y -= ui.bigLineHeight + 4f
        ui.text(ui.fit("сцен ${s.bases.size}, с вариациями ${s.scenes.size} · прошло ${all?.ok ?: 0} · не прошло ${all?.bad ?: 0}" +
            (if (known < s.scenes.size) " · без итога ${s.scenes.size - known}" else ""), w - 24f), 12f, y, Palette.DIM)
        y -= ui.lineHeight + 3f
        val source = if (s.verdicts.running) "проверяю в фоне: ${s.verdicts.progress} из ${s.verdicts.goal}" else s.verdicts.source
        ui.text(ui.fit(source, w - 24f), 12f, y, if (s.verdicts.running || s.verdicts.stale) Palette.WARN else Palette.DIM)
        y -= ui.lineHeight + 3f
        val search = when {
            s.typing == 1 -> "Поиск: ${s.filter}_   (Enter — готово, Esc — сброс)"
            s.filter.isNotEmpty() -> "Поиск: «${s.filter}»   (Esc — сброс)"
            else -> "/ — поиск по названию и номеру"
        }
        val picks = (if (s.onlyFailed) "   · только не прошедшие (F)" else "") + (if (s.onlyMarked) "   · только помеченные (G)" else "") +
            (if ((all?.marked ?: 0) > 0 && !s.onlyMarked) "   · помечено ${all?.marked}" else "")
        ui.text(ui.fit(search + picks, w - 24f), 12f, y, if (s.typing == 1 || s.filter.isNotEmpty() || s.onlyFailed || s.onlyMarked) Palette.WARN else Palette.DIM)
        y -= ui.lineHeight + 8f
        listTop = y
        listRows = maxOf(1, (y / rowHeight).toInt())

        for (k in 0 until listRows) {
            val index = s.listScroll + k
            if (index >= s.rows.size) break
            val row = s.rows[index]
            val top = listTop - k * rowHeight
            val bottom = top - rowHeight
            val hover = s.mouseX < w && s.mouseY <= top && s.mouseY > bottom
            when (row.kind) {
                Row.FAMILY -> {
                    ui.rect(0f, bottom, w - 1f, rowHeight - 1f, if (hover) Palette.ROW_HOVER else Palette.FAMILY_ROW)
                    arrow(ui, 14f, bottom + rowHeight / 2, row.family in s.collapsed, Palette.HEAD)
                    val t = s.tallies["f:" + row.family]
                    val tail = tail(t)
                    val tailWidth = ui.width(tail)
                    ui.text(ui.fit(row.family + "  (${row.count})", w - 40f - tailWidth), 26f, top - 4f, Palette.HEAD)
                    if (tail.isNotEmpty()) ui.text(tail, w - 10f - tailWidth, top - 4f, if ((t?.bad ?: 0) > 0) Palette.FAIL else Palette.OK)
                }
                Row.GROUP -> {
                    if (hover) ui.rect(0f, bottom, w - 1f, rowHeight, Palette.ROW_HOVER)
                    ui.rect(8f, top - 0.5f, w - 17f, 1f, Palette.PANEL_EDGE)
                    val open = row.group == s.bases[s.selected].group || row.group in s.openGroups || s.onlyFailed || s.onlyMarked || s.filter.isNotEmpty()
                    arrow(ui, 24f, bottom + rowHeight / 2, !open, GROUP_TEXT)
                    val t = s.tallies["g:" + row.group]
                    val tail = tail(t)
                    val tailWidth = ui.width(tail)
                    ui.text(ui.fit((s.groupTitle[row.group] ?: row.group) + "  (${row.count})", w - 52f - tailWidth), 36f, top - 4f, GROUP_TEXT)
                    if (tail.isNotEmpty()) ui.text(tail, w - 10f - tailWidth, top - 4f, if ((t?.bad ?: 0) > 0) FAIL_TEXT else Palette.OK)
                }
                else -> {
                    val b = s.bases[row.base]
                    val current = row.base == s.selected
                    if (current) ui.rect(0f, bottom, w - 1f, rowHeight, Palette.ROW_SELECTED)
                    else if (hover) ui.rect(0f, bottom, w - 1f, rowHeight, Palette.ROW_HOVER)
                    val shown = s.scenes[b.scene(s.variation)]
                    val v = s.verdicts[shown.id]
                    ui.disc(34f, bottom + rowHeight / 2, 4.5f, dot(v))
                    // Справа — по квадратику на вариацию и флажок пометки: видно, в какой вариации сцена не проходит.
                    var right = w - 10f
                    if (b.count > 1) for (var_ in Variation.values().reversed()) {
                        val i = b.variants[var_.ordinal]
                        if (i < 0) continue
                        right -= 8f
                        ui.rect(right, bottom + rowHeight / 2 - 3f, 6f, 6f, dot(s.verdicts[s.scenes[i].id]))
                        if (var_ == s.variation) ui.rect(right, bottom + 2f, 6f, 1.5f, Palette.TEXT)
                    }
                    var flag: MarkKind? = null
                    for (i in b.variants) if (i >= 0) s.marks[s.scenes[i].id]?.let { flag = it.kind }
                    flag?.let { right -= 12f; ui.rect(right, bottom + 4f, 8f, rowHeight - 8f, it.color) }
                    // В раскрытой группе начало названия, общее со всей группой, не повторяется.
                    val head = s.groupTitle[row.group]
                    val name = if (head != null && b.title.startsWith("$head: ")) b.title.substring(head.length + 2) else b.title
                    ui.text(ui.fit(name, right - 50f), 46f, top - 4f, if (current) Palette.TEXT else if (v != null && !v.passed) FAIL_TEXT else LIST_TEXT)
                }
            }
        }
        // Полоса прокрутки: где мы в списке.
        if (s.rows.size > listRows) {
            val track = listTop - 4f
            val size = maxOf(18f, track * listRows / s.rows.size)
            val at = (track - size) * s.listScroll / maxOf(1, s.rows.size - listRows)
            ui.rect(w - 6f, listTop - at - size, 4f, size, Palette.WAIT)
        }
    }

    private fun tail(t: Tally?): String {
        if (t == null) return ""
        val marked = if (t.marked > 0) "помечено ${t.marked} · " else ""
        return marked + if (t.bad > 0) "не прошло ${t.bad}" else if (t.ok > 0) "все прошли" else ""
    }

    private fun arrow(ui: Ui, cx: Float, cy: Float, closed: Boolean, color: Color) {
        if (closed) ui.triangle(cx - 3f, cy + 5f, cx - 3f, cy - 5f, cx + 4f, cy, color)
        else ui.triangle(cx - 5f, cy + 3f, cx + 5f, cy + 3f, cx, cy - 4f, color)
    }

    // ------------------------------------------------------------------ описание и правила

    private fun drawInfo(ui: Ui, s: PanelState, x0: Float, w: Float, height: Float) {
        ui.rect(x0, 0f, w, height, Palette.PANEL)
        ui.rect(x0, 0f, 1f, height, Palette.PANEL_EDGE)
        val x = x0 + 16f
        val inner = w - 32f
        val sc = s.scenario
        val start = height - 12f + s.infoScroll
        var y = start

        y = ui.paragraph(sc.title, x, y, inner, Palette.TEXT, large = true) - 2f
        ui.text(ui.fit(sc.id + "  ·  " + sc.family, inner), x, y, Palette.DIM); y -= ui.lineHeight + 8f

        // --- вариации сцены: плашки с итогом каждой, выбранная выделена ---
        java.util.Arrays.fill(chipW, 0f)
        val base = s.bases[s.selected]
        if (base.count > 1) {
            ui.text("ВАРИАЦИЯ  (Tab — следующая)", x, y, Palette.HEAD); y -= ui.lineHeight + 5f
            chipH = ui.lineHeight + 8f
            var cx = x
            for (v in Variation.values()) {
                val i = base.variants[v.ordinal]
                if (i < 0) continue
                val label = SHORT[v.ordinal]
                val cw = ui.width(label) + 26f
                if (cx + cw > x + inner) { cx = x; y -= chipH + 4f }
                val on = v == s.variation
                ui.rect(cx, y - chipH + 3f, cw, chipH, if (on) Palette.ROW_SELECTED else CHIP_BG)
                ui.disc(cx + 10f, y - chipH / 2 + 3f, 4f, dot(s.verdicts[s.scenes[i].id]))
                ui.text(label, cx + 19f, y - 2f, if (on) Palette.TEXT else Palette.DIM)
                chipX[v.ordinal] = cx; chipW[v.ordinal] = cw; chipY[v.ordinal] = y + 3f
                cx += cw + 5f
            }
            y -= chipH + 5f
            y = ui.paragraph(s.variation.about, x, y, inner, Palette.DIM) - 6f
        }

        // --- пометка человека ---
        val mark = s.marks[sc.id]
        if (s.typing == 2) {
            ui.rect(x - 4f, y - ui.lineHeight - 8f, inner + 8f, ui.lineHeight + 12f, CHIP_BG)
            y = ui.paragraph("Заметка: ${s.noteText}_", x, y - 2f, inner, Palette.WARN) - 2f
            ui.text("Enter — сохранить, Esc — отмена", x, y, Palette.DIM); y -= ui.lineHeight + 8f
        } else if (mark != null) {
            ui.rect(x, y - ui.lineHeight + 2f, 10f, ui.lineHeight - 1f, mark.kind.color)
            y = ui.paragraph("ПОМЕТКА: " + mark.kind.title + (if (mark.note.isNotEmpty()) " — «${mark.note}»" else ""), x + 16f, y, inner - 16f, mark.kind.color) - 6f
        }

        ui.text("ЧТО НА СЦЕНЕ", x, y, Palette.HEAD); y -= ui.lineHeight + 4f
        y = ui.paragraph(sc.about, x, y, inner, Palette.TEXT) - 8f
        ui.text("ЧТО ДОЛЖНО ПРОИЗОЙТИ", x, y, Palette.HEAD); y -= ui.lineHeight + 4f
        y = ui.paragraph(sc.expect, x, y, inner, Palette.TEXT) - 8f

        val run = s.run
        if (run == null) {
            y = ui.paragraph(s.setupError.ifEmpty { "сцена не собрана" }, x, y, inner, Palette.FAIL)
            infoOverflow = maxOf(0f, start - y - height + 24f)
            return
        }
        val m = run.monitor
        val state = when {
            run.crash != null -> "решатель упал"
            s.finding -> "ищу первое нарушение…"
            s.seeking -> "перематываю…"
            s.freeRun -> if (s.paused) "свободный ход, пауза" else "свободный ход (правила не считаются)"
            run.settledEarly -> "закончена досрочно: всё уснуло"
            run.done -> "закончена"
            s.paused -> "пауза"
            else -> "идёт"
        }
        y = ui.paragraph("Тик ${run.tick} из ${sc.ticks}  ·  $state  ·  скорость ${s.speed}", x, y, inner, Palette.TEXT) - 1f
        y = ui.paragraph("Допуски: «${sc.limits.name}»  ·  частиц ${run.w.world.particleCount}", x, y, inner, Palette.DIM) - 1f
        y = ui.paragraph("Считает: ${s.solver}" + if (s.reference) " — итог в список не идёт (E — сменить)" else "", x, y, inner, if (s.reference) Palette.WARN else Palette.DIM) - 1f
        if (s.shadow.isNotEmpty()) y = ui.paragraph(s.shadow, x, y, inner, Palette.TEXT) - 1f
        if (s.referenceNote.isNotEmpty()) y = ui.paragraph(s.referenceNote, x, y, inner, Palette.WARN) - 1f
        y -= 5f

        val failed = !run.passed
        // Вхолостую: не случилось то, ради чего сцена поставлена. Остальные правила при этом ничего не значат.
        val vacant = run.done && m.results[Rule.PREMISE]!!.failed
        val verdict = when {
            vacant -> "ВХОЛОСТУЮ — сцена ничего не проверила"
            run.done && failed -> "НЕ ПРОШЛА"
            run.done -> "ПРОШЛА"
            failed -> "идёт — уже есть нарушения"
            else -> "идёт — нарушений нет"
        }
        ui.text(verdict, x, y, if (vacant) Palette.WARN else if (failed) Palette.FAIL else if (run.done) Palette.OK else Palette.DIM, large = true)
        y -= ui.bigLineHeight + 2f
        if (s.tainted) { ui.text("в сцену вмешались мышью — итог не в счёт (R — заново)", x, y, Palette.WARN); y -= ui.lineHeight + 2f }
        run.crash?.let { y = ui.paragraph(it.toString(), x, y, inner, Palette.FAIL) }
        y -= 6f

        // --- что в сцене обязано случиться ---
        ui.text("ЧТО ОБЯЗАНО СЛУЧИТЬСЯ", x, y, Palette.HEAD); y -= ui.lineHeight + 4f
        run.w.idleReason?.let { y = ui.paragraph("ничего: $it", x, y, inner, Palette.DIM) - 2f }
        for (p in run.w.premises) {
            val color = if (p.forbidden) (if (p.happened) Palette.FAIL else Palette.OK) else (if (p.happened) Palette.OK else if (run.done) Palette.WARN else Palette.WAIT)
            ui.disc(x + 5f, y - 6f, 4.5f, color)
            val note = when {
                p.forbidden && p.happened -> " — случилось на тике ${p.tick}, а не должно"
                p.forbidden -> " — не должно, и не случилось"
                p.happened -> " — тик ${p.tick}"
                run.done -> " — НЕ СЛУЧИЛОСЬ"
                else -> " — пока нет"
            }
            y = ui.paragraph(p.text + note, x + 18f, y, inner - 18f, if (color == Palette.FAIL || color == Palette.WARN) color else Palette.TEXT) - 2f
        }
        y -= 6f

        // --- таблица правил ---
        for (r in m.results.values) {
            if (!r.applies) continue
            val color = if (r.failed) Palette.FAIL else if (r.pending) Palette.WAIT else Palette.OK
            ui.disc(x + 5f, y - 6f, 4.5f, color)
            val label = r.rule.title + if (r.failed) "" else if (r.pending) "  — судится в конце" else ""
            ui.text(label, x + 18f, y, if (r.failed) Palette.FAIL else if (r.pending) Palette.DIM else Palette.TEXT)
            y -= ui.lineHeight + 3f
            if (r.failed) {
                val ring = SceneView.markColor(r.rule)
                if (r.rule in MARKED) ui.rect(x + 18f, y - 9f, 10f, 3f, ring)
                y = ui.paragraph("с тика ${r.firstTick}, раз ${r.violations}: ${r.text}", x + (if (r.rule in MARKED) 34f else 18f), y, inner - 34f, FAIL_TEXT) - 2f
            }
        }
        y -= 6f

        // --- числа прогона ---
        ui.text("ЧИСЛА", x, y, Palette.HEAD); y -= ui.lineHeight + 4f
        val lines = ArrayList<String>()
        val tornLimit = if (m.allowedTorn == Int.MAX_VALUE) "без ограничения" else "${m.allowedTorn}"
        val deadLimit = if (m.allowedDead == Int.MAX_VALUE) "без ограничения" else "${m.allowedDead}"
        lines.add("порвано связей ${m.torn} из ${m.linksTotal} (допуск $tornLimit)")
        lines.add("погибло клеток ${m.died} из ${m.cellsTotal} (допуск $deadLimit)  ·  не спит ${m.awake}")
        if (run.w.scriptedTorn > 0 || run.w.scriptedDead > 0)
            lines.add("по сценарию разрезано связей ${run.w.scriptedTorn}, убито клеток ${run.w.scriptedDead} — не в счёт")
        lines.add(String.format(Locale.ROOT, "наибольшая скорость %.2f связи за тик (потолок %.0f)", m.maxSpeed, SPEED_CAP))
        lines.add("срабатываний потолка скорости ${m.capHits}")
        val closed = m.results[Rule.ENERGY]!!.applies
        if (closed) lines.add(String.format(Locale.ROOT, "энергия к разрешённой, пик %.2f", m.peakEnergy))
        lines.add("клетко-тиков в чужой ткани ${m.foreignTicks}  ·  в своей ${m.selfTicks}  ·  в рельефе ${m.terrainTicks}")
        lines.add("дрожь: клетко-тиков ${m.jitterTicks}, дольше всего ${m.jitterLongest} тиков подряд")
        if (m.boneWorst > 0.0 || m.shapeWorst > 0.0 || m.poseWorst > 0.0)
            lines.add(String.format(Locale.ROOT, "кость до %.1f%%  ·  связи в конце %.1f%%  ·  поза %.1f%%", m.boneWorst * 100, m.shapeWorst * 100, m.poseWorst * 100))
        for (line in lines) { ui.text(ui.fit(line, inner), x, y, Palette.TEXT); y -= ui.lineHeight + 3f }
        for ((name, value) in run.w.probes) {
            val v = try { run.inVariation { value() } } catch (t: Throwable) { Double.NaN }
            ui.text(ui.fit(String.format(Locale.ROOT, "%s: %.3f", name, v), inner), x, y, Palette.DIM); y -= ui.lineHeight + 3f
        }
        y -= 6f

        // --- графики ---
        val last = minOf(run.tick, sc.ticks)
        y = graph(ui, "Смещение за тик, связей", m.speed, last, x, y, inner, 0.0, Palette.OK)
        // У сцены с мышью, мышцами или ростом энергию вносят извне: отношение к «разрешённой» там ничего не значит.
        if (closed) y = graph(ui, "Энергия к разрешённой (красная черта — предел)", m.energy, last, x, y, inner, 1.0, Palette.WARN)

        // --- что сказал пакетный прогон ---
        val v = s.verdicts[sc.id]
        if (v != null) {
            ui.text("ИТОГ В СПИСКЕ", x, y, Palette.HEAD); y -= ui.lineHeight + 4f
            y = if (v.passed) ui.paragraph("прошла", x, y, inner, Palette.OK)
            else if (v.vacant) ui.paragraph("вхолостую: " + v.why, x, y, inner, Palette.WARN)
            else ui.paragraph("не прошла: " + v.why.ifEmpty { "причина не записана" }, x, y, inner, FAIL_TEXT)
        }
        infoOverflow = maxOf(0f, start - y - height + 24f)
    }

    private fun graph(ui: Ui, title: String, values: FloatArray, last: Int, x: Float, top: Float, w: Float, limit: Double, color: Color): Float {
        var peak = 0f
        for (t in 1..last) if (values[t] > peak) peak = values[t]
        ui.text(ui.fit(title + String.format(Locale.ROOT, "  ·  пик %.3f", peak), w), x, top, Palette.DIM)
        val h = 52f
        val y0 = top - ui.lineHeight - 4f - h
        ui.rect(x, y0, w, h, GRAPH_BG)
        val scale = maxOf(peak, (limit * 1.15).toFloat(), 1e-6f)
        if (limit > 0.0) { val ly = y0 + (limit / scale * (h - 4f)).toFloat() + 2f; ui.rect(x, ly, w, 1f, Palette.FAIL) }
        if (last >= 2) {
            val points = minOf(last, (w / 2f).toInt())
            var px = x; var py = y0 + 2f + values[1] / scale * (h - 4f)
            for (k in 1..points) {
                val t = 1 + ((last - 1).toLong() * k / points).toInt()
                val nx = x + w * k / points; val ny = y0 + 2f + minOf(1f, values[t] / scale) * (h - 4f)
                ui.line(px, py, nx, ny, 1.4f, color)
                px = nx; py = ny
            }
        }
        return y0 - 10f
    }

    // ------------------------------------------------------------------ поверх сцены

    private fun drawSceneOverlay(ui: Ui, s: PanelState, left: Float, right: Float, height: Float, barHeight: Float) {
        // Подсказки по клавишам — полосой под сценой.
        ui.rect(left, 0f, right - left, barHeight, Palette.PANEL)
        ui.rect(left, barHeight - 1f, right - left, 1f, Palette.PANEL_EDGE)
        val w = right - left - 20f
        val step = ui.lineHeight + 3f
        val hints = arrayOf(
            "Пробел: пауза   Вправо: шаг   R: заново   V: к первому нарушению   End: досчитать   - и +: скорость",
            "Вверх и вниз: сцена   PgUp и PgDn: группа   [ и ]: семейство   Tab: вариация   N: следующая не прошедшая",
            "M: пометить (ещё раз — другой вид, Shift+M — снять)   T: заметка словами   G: только помеченные   F: только не прошедшие   /: поиск",
            "A: автопоказ${if (s.autoShow) " (включён)" else ""}   B: проверить семейство (Shift+B — всё)   C: камера   L: связи   K: клетки   P: превью взрослого тела${if (s.preview) "" else " (выключено)"}",
            "E: кто считает — быстрый решатель или эталон (6 или 64 подшага)   S: тень другого решателя белым контуром",
            "Мышь: левой кнопкой тянуть клетку, колесо — масштаб, за пустое место — сдвиг; щелчок по плашке вариации справа — выбрать её",
        )
        for (k in hints.indices) ui.text(ui.fit(hints[k], w), left + 10f, barHeight - 7f - k * step, Palette.DIM)

        // Легенда — на подложке: под ней может оказаться рельеф или тело.
        var x = left + 12f
        val y = height - 12f
        ui.rect(left, height - 2 * ui.lineHeight - 24f, right - left, 2 * ui.lineHeight + 24f, LEGEND_BG)
        fun item(color: Color, label: String) {
            ui.disc(x + 5f, y - 6f, 5f, color)
            x += 14f
            x += ui.text(label, x, y, Palette.DIM) + 14f
        }
        item(Palette.SOFT[0], "ткань"); item(Palette.BONE, "кость"); item(Palette.MUSCLE[0], "мышца"); item(SceneView.GRIP, "присоска держит")
        item(Palette.BALL, "свободная частица"); item(Palette.ROCK, "рельеф")
        ui.text("темнее — спит", x, y, Palette.DIM)
        x = left + 12f
        val y2 = y - ui.lineHeight - 5f
        fun ring(color: Color, label: String) {
            ui.rect(x, y2 - 8f, 10f, 3f, color)
            x += 14f
            x += ui.text(label, x, y2, Palette.DIM) + 12f
        }
        ui.text("кольца:", x, y2, Palette.DIM); x += ui.width("кольца:") + 8f
        ring(SceneView.MARK_FOREIGN, "в чужой ткани или сквозь неё"); ring(SceneView.MARK_SELF, "в своей"); ring(SceneView.MARK_TERRAIN, "в рельефе")
        ring(SceneView.MARK_DEATH, "гибель"); ring(SceneView.MARK_INVERSION, "выворот"); ring(SceneView.MARK_BONE, "кость смята"); ring(SceneView.MARK_JITTER, "дрожь")
        if (s.shadow.isNotEmpty()) { x += 10f; ring(SceneView.SHADOW, "тень другого решателя") }
        if (s.notice.isNotEmpty()) ui.text(s.notice, left + 12f, barHeight + 12f + ui.lineHeight, Palette.WARN)
    }

    companion object {
        private val LIST_TEXT: Color = Color.valueOf("C3CCD9FF")
        private val GROUP_TEXT: Color = Color.valueOf("AEB9C9FF")
        private val FAIL_TEXT: Color = Color.valueOf("FF9D9DFF")
        private val GRAPH_BG: Color = Color.valueOf("0B0F16FF")
        private val LEGEND_BG: Color = Color.valueOf("0B0F16C8")
        private val CHIP_BG: Color = Color.valueOf("1B2330FF")
        /** Короткие названия вариаций для плашек — по порядку Variation. */
        private val SHORT = arrayOf("ровная", "длинные связи", "разные длины", "разные радиусы", "мелкие клетки", "длины + радиусы")
        /** Правила, нарушение которых обводится на сцене кольцом своего цвета. */
        private val MARKED = setOf(Rule.FOREIGN, Rule.CROSS, Rule.SELF, Rule.TERRAIN, Rule.DEATHS, Rule.INVERSION, Rule.BIRTH, Rule.BONES, Rule.SHAPE, Rule.STILL, Rule.JITTER)
    }
}
