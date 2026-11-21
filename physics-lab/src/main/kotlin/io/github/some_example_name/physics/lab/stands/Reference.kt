package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.SUBSTEPS
import java.util.Locale
import kotlin.math.abs

/**
 * СВЕРКА БЫСТРОГО РЕШАТЕЛЯ С ЭТАЛОННЫМ.
 *
 * Та же сцена прогоняется ещё раз, и вместо шага игры мир шагает эталонным решателем
 * (physics/reference/ReferenceSolver.kt): один поток, без блоков, сна и запомненных пар. Сцена
 * собирается и ведётся теми же вызовами — тела, рост, мышцы, мышь, зажимы; меняется только шаг мира.
 * Сверяются крупные величины по контрольным тикам (набросок сцены, Symmetry.kt): где центр масс
 * каждого тела, как оно повёрнуто, где свободные частицы, сколько связей порвано и клеток погибло.
 * Побитового совпадения нет и быть не может: решатели разные.
 *
 * ДВА ПРОГОНА ЭТАЛОНА, И ОТВЕЧАЮТ ОНИ НА РАЗНЫЕ ВОПРОСЫ (замер 16.11.2026, семейство ударов в пустоте:
 * эталон на 6 подшагах сошёлся с быстрым в 174 сценах из 216 спокойных, на 64 — в 38).
 *
 *  1. ТОТ ЖЕ ШАГ, что у игры (SUBSTEPS). Шаг по времени одинаков, поэтому всё расхождение — это
 *     ускорения и приёмы быстрого решателя: блоки, сон, широкая фаза, обрезка по времени удара,
 *     касание «0.8 за подшаг», кости, которых не двигают предел и ориентация. Это и судит правило
 *     «сверка с эталонным решателем»: сломанное ускорение здесь видно сразу.
 *
 *  2. МЕЛКИЙ ШАГ (64 подшага). Показывает, насколько шаг игры груб для сцены: сильный удар двух
 *     мягких тел при 6 подшагах гасит на десятую больше энергии, чем при 64, и тела разлетаются
 *     иначе. Это не ошибка быстрого решателя, а цена его шага, поэтому не судится, а считается:
 *     число подшагов игры меняет «упругость» тел, и менять его без оглядки на эти числа нельзя.
 *
 * Прогон, в котором шаг эталона проскочил удар (ReferenceSolver.missedHits), не сверяется: у эталона
 * нет обрезки по времени удара, и на шаге игры быстрые тела у него проходят друг в друга. На мелком
 * шаге такого почти не бывает.
 *
 * Сцена, которая не повторила свой же прогон, хаотична и не судится: два решателя заведомо разойдутся
 * в ней так же, как она расходится сама с собой. Контрольных прогонов три:
 *  - малый сдвиг всей сцены (Control.calm) — чувствительность к округлению;
 *  - малый разнобой тел (Control.phase) — общий сдвиг не меняет зазоров, а итог удара зубчатых торцов
 *    решает то, в какой точке подшага они коснулись;
 *  - клетки в обратном порядке (Control.order) — у сцены из одного тела первые два возмущения ничего
 *    не меняют, а итог сжатия мышцы или роста зависит от порядка обхода связей (замер 16.11.2026: из
 *    1095 сцен, разошедшихся с эталоном, 652 расходились и сами с собой при перенумерации).
 */

/** Допуск сверки: расхождение положений не больше стольких связей плюс доли пройденного телом пути. */
const val REFERENCE_TIGHT = 0.5
/**
 * Доля пути. Решатели отличаются законом касания (эталон снимает перекрытие целиком, быстрый — 0.8 за
 * подшаг, и его отскок от 0.10 до 0.18 вместо 0.10), поэтому после удара скорости тел расходятся на
 * проценты, а дальше расхождение растёт с путём.
 */
const val REFERENCE_SHARE = 0.05
/** Счёт порванных связей и погибших клеток сходится, если разница не больше стольких штук или такой доли. */
const val REFERENCE_DAMAGE_SLACK = 3
const val REFERENCE_DAMAGE_SHARE = 0.25
/**
 * Сцена дороже этого (частиц × тиков) на мелком шаге не прогоняется: эталон на 64 подшагах медленнее
 * быстрого в десятки раз. На том же шаге — вдесятеро больше. Больше — -PreferenceBudget.
 */
val REFERENCE_BUDGET: Long = System.getProperty("stands.referenceBudget")?.toLongOrNull() ?: 400_000L

enum class ReferenceVerdict(val title: String) {
    CLOSE("сошлось"), APART("разошлось"), DAMAGE("рвётся иначе"), CHAOTIC("хаотична"), SKIPPED("не сверяется")
}

/** Правила монитора о самой физике — без сна, повтора и постановки сцены: по ним видно, кто из двух решателей что нарушил. */
val PHYSICS_RULES: List<Rule> = listOf(Rule.NUMBERS, Rule.ENERGY, Rule.SPEED_CAP, Rule.FOREIGN, Rule.CROSS, Rule.SELF, Rule.TERRAIN,
    Rule.TEARS, Rule.DEATHS, Rule.INVERSION, Rule.BIRTH, Rule.BONES, Rule.JITTER, Rule.SHAPE)

private fun rulesOf(failed: String): List<Rule> =
    failed.split(',').filter { it.isNotEmpty() }.map { Rule.valueOf(it) }.filter { it in PHYSICS_RULES }

private fun sameDamage(a: Int, b: Int): Boolean = abs(a - b) <= maxOf(REFERENCE_DAMAGE_SLACK.toDouble(), REFERENCE_DAMAGE_SHARE * maxOf(a, b))

private fun sameDamage(d: Deviation): Boolean = sameDamage(d.tornBase, d.tornOther) && sameDamage(d.diedBase, d.diedOther)

/** Контрольный прогон повторил исходный: не упал, положения в допуске, тот же счёт повреждений. */
private fun steady(d: Deviation): Boolean = d.crash.isEmpty() && d.close && sameDamage(d)

/** Один прогон эталона против быстрого. */
class ReferenceRun(val substeps: Int) {
    var verdict = ReferenceVerdict.SKIPPED
    /** Почему сцена не сверяется. */
    var reason = ""
    var deviation: Deviation? = null
    var seconds = 0.0
    /** Какие правила физики нарушил эталон. */
    var failedRules: List<Rule> = emptyList()
    val failed: Boolean get() = verdict == ReferenceVerdict.APART || verdict == ReferenceVerdict.DAMAGE

    fun describe(): String {
        val d = deviation
        return when (verdict) {
            ReferenceVerdict.SKIPPED -> reason
            ReferenceVerdict.DAMAGE -> "у быстрого порвано связей ${d!!.tornBase} и погибло клеток ${d.diedBase}, у эталона ${d.tornOther} и ${d.diedOther}"
            else -> if (d == null || d.score == 0.0) "" else String.format(Locale.ROOT, "%s разошлось с эталоном на %.2f связи к тику %d при допуске %.2f", d.what, d.score, d.tick, d.allowed)
        }
    }
}

/** Итог сверки одной сцены: эталон на шаге игры (судится) и на мелком шаге (считается). */
class ReferenceResult(val same: ReferenceRun, val fine: ReferenceRun?) {
    /** Какие правила физики нарушил быстрый решатель. */
    var fastFailed: List<Rule> = emptyList()
}

private fun referenceRun(s: Scenario, substeps: Int, control: Control, budget: Long, particles: Int): ReferenceRun {
    val run = ReferenceRun(substeps)
    val base = control.base
    if (particles.toLong() * base.ticks > budget) {
        run.reason = "сцена велика для эталона: $particles частиц × ${base.ticks} тиков"
        return run
    }
    val t0 = System.nanoTime()
    val other = try { sketchedRun(s, Frame.SCENE, substeps, base.ticks) } catch (t: Throwable) {
        run.reason = "с эталоном сцена не собралась: $t"
        return run
    }
    run.seconds = (System.nanoTime() - t0) / 1e9
    if (other.crash.isNotEmpty()) {
        // Сцена, которая сама управляет сном, эталону не подходит: сна он не знает.
        run.reason = if ("не знает сна" in other.crash) "сцена управляет сном, а эталон сна не знает" else "эталон упал: ${other.crash}"
        return run
    }
    if (other.missedHits > 0) {
        // Без обрезки по времени удара эталон на крупном шаге пропускает быстрые тела друг в друга (замер
        // 16.11.2026: две частицы навстречу на 6 связях за тик проходят насквозь, энергия +32%). Судить таким
        // прогоном быстрый решатель нельзя — и считать по нему, кто нарушил правила, тоже.
        run.reason = "удар быстрее шага эталона: касаний, заставших пару на глубине гибели или по другую сторону, ${other.missedHits}"
        return run
    }
    val d = compareSketches(base, other, REFERENCE_TIGHT, REFERENCE_SHARE)
    run.deviation = d
    run.failedRules = rulesOf(other.failed)
    run.verdict = when {
        !control.calm || !steady(control.phase) || !steady(control.order) -> ReferenceVerdict.CHAOTIC
        !sameDamage(d) -> ReferenceVerdict.DAMAGE
        !d.close -> ReferenceVerdict.APART
        else -> ReferenceVerdict.CLOSE
    }
    return run
}

/**
 * Сверить сцену [s] с эталоном: на шаге игры и, если [fineSubsteps] больше него, ещё и на мелком.
 * [control] — её исходный и контрольные прогоны, [particles] — сколько в ней частиц (цена прогона).
 */
fun checkReference(s: Scenario, fineSubsteps: Int, control: Control, particles: Int): ReferenceResult {
    val same = referenceRun(s, SUBSTEPS, control, REFERENCE_BUDGET * 10, particles)
    val fine = if (fineSubsteps > SUBSTEPS) referenceRun(s, fineSubsteps, control, REFERENCE_BUDGET, particles) else null
    return ReferenceResult(same, fine).also { it.fastFailed = rulesOf(control.base.failed) }
}

// --- отчёт пакетного прогона ---

private fun count(runs: List<ReferenceRun>, v: ReferenceVerdict): Int = runs.count { it.verdict == v }

private fun line(title: String, runs: List<ReferenceRun>): String =
    String.format(Locale.ROOT, "  %-28s %s; время эталона %.0f с", title,
        ReferenceVerdict.values().joinToString(", ") { "${it.title} ${count(runs, it)}" }, runs.sumOf { it.seconds })

/** Короткая сводка для консоли. */
fun referenceSummary(outcomes: List<Outcome>): String {
    val checked = outcomes.mapNotNull { it.reference }
    val fine = checked.mapNotNull { it.fine }
    return "ЭТАЛОН: сцен ${checked.size}\n" + line("тот же шаг ($SUBSTEPS подшагов):", checked.map { it.same }) +
        (if (fine.isEmpty()) "" else "\n" + line("мелкий шаг (${fine[0].substeps} подшагов):", fine))
}

private fun whoBreaksRules(sb: StringBuilder, checked: List<Outcome>, pick: (ReferenceResult) -> ReferenceRun?) {
    val compared = checked.filter { pick(it.reference!!)?.deviation != null }
    sb.append("По сценам, где эталон дошёл до конца (${compared.size}).\n\n| Правило | Только быстрый | Оба | Только эталон |\n|---|---:|---:|---:|\n")
    for (rule in PHYSICS_RULES) {
        var fast = 0; var both = 0; var slow = 0
        for (o in compared) {
            val f = rule in o.reference!!.fastFailed; val e = rule in pick(o.reference!!)!!.failedRules
            if (f && e) both++ else if (f) fast++ else if (e) slow++
        }
        if (fast + both + slow > 0) sb.append("| ${rule.title} | $fast | $both | $slow |\n")
    }
}

private fun listScenes(sb: StringBuilder, checked: List<Outcome>, v: ReferenceVerdict, limit: Int, pick: (ReferenceResult) -> ReferenceRun?) {
    val list = checked.filter { pick(it.reference!!)?.verdict == v }.sortedByDescending { pick(it.reference!!)!!.deviation?.excess ?: 0.0 }
    sb.append("\n### ${v.title.replaceFirstChar { it.uppercase() }}: ${list.size}")
    if (list.size > limit) sb.append(" (показаны $limit самых далёких)")
    sb.append("\n\n")
    for (o in list.take(limit)) {
        val r = o.reference!!; val run = pick(r)!!
        sb.append("- `${o.scenario.id}` — ${o.scenario.title}: ${run.describe()}")
        val onlyFast = r.fastFailed - run.failedRules.toSet(); val onlySlow = run.failedRules - r.fastFailed.toSet()
        if (onlyFast.isNotEmpty()) sb.append("; только быстрый нарушил: ${onlyFast.joinToString(", ") { it.title }}")
        if (onlySlow.isNotEmpty()) sb.append("; только эталон нарушил: ${onlySlow.joinToString(", ") { it.title }}")
        sb.append('\n')
    }
}

private fun familyTable(sb: StringBuilder, checked: List<Outcome>, pick: (ReferenceResult) -> ReferenceRun?) {
    sb.append("| Семейство | Сцен | Сошлось | Разошлось | Рвётся иначе | Хаотична | Не сверяется |\n|---|---:|---:|---:|---:|---:|---:|\n")
    for ((family, group) in checked.groupBy { it.scenario.family }) {
        val runs = group.mapNotNull { pick(it.reference!!) }
        sb.append("| $family | ${runs.size} | ${ReferenceVerdict.values().joinToString(" | ") { count(runs, it).toString() }} |\n")
    }
}

/** Отчёт сверки: итоги на шаге игры и на мелком шаге, по семействам, кто какие правила нарушил, списки сцен. */
fun referenceReport(outcomes: List<Outcome>): String {
    val checked = outcomes.filter { it.reference != null }
    val fineRuns = checked.mapNotNull { it.reference!!.fine }
    val sb = StringBuilder()
    sb.append("# Стенды физики: сверка с эталонным решателем\n\n")
    sb.append("Каждая сцена прогнана быстрым решателем и эталонным — простым: один поток, без блоков, сна и запомненных пар. ")
    sb.append("Сверяются центр масс и поворот каждого тела и место каждой свободной частицы на девяти контрольных тиках, счёт порванных связей и погибших клеток.\n\n")
    sb.append(String.format(Locale.ROOT, "Допуск: %.2f связи плюс %.0f%% пройденного телом пути; счёт разрывов и гибели — разница до %d штук или %.0f%%. ",
        REFERENCE_TIGHT, REFERENCE_SHARE * 100, REFERENCE_DAMAGE_SLACK, REFERENCE_DAMAGE_SHARE * 100))
    sb.append("Хаотичная сцена — та, что не повторила свой же прогон со сдвигом на треть клетки, с разнобоем тел в сотые доли клетки или с клетками в обратном порядке; она не судится.\n\n")

    sb.append("## Тот же шаг: эталон на $SUBSTEPS подшагах, как у игры\n\n")
    sb.append("Шаг по времени одинаков, поэтому расхождение — это ускорения и приёмы быстрого решателя. Этот прогон судит правило «сверка с эталонным решателем».\n\n")
    sb.append("| Итог | Сцен |\n|---|---:|\n")
    val sameRuns = checked.map { it.reference!!.same }
    for (v in ReferenceVerdict.values()) sb.append("| ${v.title} | ${count(sameRuns, v)} |\n")
    sb.append('\n')
    familyTable(sb, checked) { it.same }
    sb.append("\n### Кто нарушает правила физики\n\n«Только быстрый» — простой решатель с тем же шагом сцену проходит: правило нарушено приёмами быстрого. ")
    sb.append("«Оба» — дело в шаге, в модели или в постановке сцены.\n\n")
    whoBreaksRules(sb, checked) { it.same }
    listScenes(sb, checked, ReferenceVerdict.APART, 60) { it.same }
    listScenes(sb, checked, ReferenceVerdict.DAMAGE, 60) { it.same }

    if (fineRuns.isNotEmpty()) {
        sb.append("\n## Мелкий шаг: эталон на ${fineRuns[0].substeps} подшагах\n\n")
        sb.append("Показывает, насколько шаг игры груб для сцены. Не судится: это не ошибка быстрого решателя, а цена его шага. ")
        sb.append("Сцена дороже $REFERENCE_BUDGET частиц × тиков на мелком шаге не прогоняется.\n\n")
        sb.append("| Итог | Сцен |\n|---|---:|\n")
        for (v in ReferenceVerdict.values()) sb.append("| ${v.title} | ${count(fineRuns, v)} |\n")
        sb.append('\n')
        familyTable(sb, checked) { it.fine }
        sb.append("\n### Кто нарушает правила физики\n\n«Только быстрый» — на мелком шаге правило выполняется: его нарушает грубый шаг или приёмы быстрого решателя. ")
        sb.append("«Оба» — дело в самой модели или в постановке сцены: мелкий шаг не помогает.\n\n")
        whoBreaksRules(sb, checked) { it.fine }
        listScenes(sb, checked, ReferenceVerdict.APART, 40) { it.fine }
        listScenes(sb, checked, ReferenceVerdict.DAMAGE, 40) { it.fine }
    }

    val skipped = checked.filter { it.reference!!.same.verdict == ReferenceVerdict.SKIPPED }
    sb.append("\n## Не сверяется на шаге игры: ${skipped.size}\n\n| Причина | Сцен |\n|---|---:|\n")
    for ((reason, group) in skipped.groupBy { it.reference!!.same.reason.substringBefore(':') }.entries.sortedByDescending { it.value.size })
        sb.append("| $reason | ${group.size} |\n")
    return sb.toString()
}
