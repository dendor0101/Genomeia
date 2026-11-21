package io.github.some_example_name.physics.lab.stands.viewer

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.physics.REFERENCE_SUBSTEPS
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.stands.Catalog
import io.github.some_example_name.physics.lab.stands.GAME_LINK
import io.github.some_example_name.physics.lab.stands.Scenario
import io.github.some_example_name.physics.lab.stands.StandRun
import io.github.some_example_name.physics.lab.stands.Variation
import io.github.some_example_name.render.demo.DemoRenderer
import java.io.File

/*
 * ОКНО СТЕНДОВ ФИЗИКИ: gradlew --no-daemon :physics-lab:stands
 *
 * Слева — список сцен по семействам и группам с итогом каждой, посередине — сцена, справа — что
 * на ней должно происходить и таблица правил монитора. Сцены сочиняла машина, и часть из них
 * поставлена неверно; окно нужно затем, чтобы человек быстро пролистал их глазами: описание
 * «что должно быть» стоит рядом с картинкой, а нарушение правила обведено кольцом прямо на
 * сцене и к нему можно перемотать одной клавишей.
 *
 * Одна строка списка — одна сцена во всех её вариациях (длины связей, радиусы клеток): вариацию
 * переключает Tab, и выбранная держится при листании. Сцену можно пометить (M — вид пометки,
 * T — заметка словами): пометки сразу ложатся в docs/physics/stands-marks.tsv, по ним сцены
 * потом разбирают.
 *
 * Сцена в окне — тот же StandRun, что в пакетном прогоне: те же шаги и тот же монитор, окно
 * только рисует. Вмешательство мышью итог сцены не портит молча — он помечается «не в счёт».
 *
 * Ту же сцену можно посмотреть эталонным решателем (physics/reference/ReferenceSolver.kt): E —
 * кто считает сцену (быстрый решатель, эталон на шаге игры, эталон на 64 подшагах), S — «тень»:
 * второй прогон той же сцены другим решателем, нарисованный поверх белым контуром и идущий тик в
 * тик. Так расхождение двух решателей видно глазом, а справа написано, на сколько связей
 * разошлись тела. Итог эталонного прогона в список сцен не идёт: список — про быстрый решатель.
 *
 * Свойства запуска: -Pstart=<номер сцены>; снимки без человека — -Pshots=<номера через
 * запятую> -PshotTick=<тик | end | first> -PshotDir=<папка>; самопроверка окна — -PselfTest=1.
 */

private const val LIST_WIDTH = 450f
private const val INFO_WIDTH = 460f
private const val BAR_HEIGHT = 136f
private const val ROW_HEIGHT = 21f
private const val TICKS_PER_SECOND = 30.0

/** Куда пишутся пометки человека — в папке ветки, чтобы их можно было прочитать и разобрать. */
internal const val MARKS_FILE = "docs/physics/stands-marks.tsv"

private val SPEEDS = doubleArrayOf(0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 0.0)
private val SPEED_NAMES = arrayOf("0.1×", "0.25×", "0.5×", "1×", "2×", "4×", "8×", "без ограничения")

/** Сцена списка: одна строка — все вариации одной сцены. variants — номер сцены каталога по вариации, -1 — такой нет. */
internal class Base(val index: Int, val baseId: String, val family: String, val group: String, val title: String) {
    val variants = IntArray(Variation.values().size) { -1 }
    val count: Int get() = variants.count { it >= 0 }
    fun has(v: Variation): Boolean = variants[v.ordinal] >= 0
    /** Сцена в вариации [v], а если у этой сцены такой нет — в первой, какая есть. */
    fun scene(v: Variation): Int = if (variants[v.ordinal] >= 0) variants[v.ordinal] else variants.first { it >= 0 }
    fun variation(v: Variation): Variation = if (has(v)) v else Variation.values().first { has(it) }
}

/** Строка списка: заголовок семейства, заголовок группы (count — сколько сцен под ним) или сцена. */
internal class Row(val kind: Int, val family: String, val group: String, val base: Int, val count: Int) {
    companion object { const val FAMILY = 0; const val GROUP = 1; const val SCENE = 2 }
}

/** Сводка итогов по набору сцен: сколько прошло, не прошло и помечено. */
internal class Tally { var ok = 0; var bad = 0; var marked = 0 }

internal class StandViewer(private val startId: String, private val shots: List<String>,
                           private val shotTick: String, private val shotDir: String,
                           private val selfTest: Boolean = false) : ApplicationAdapter() {
    private val scenes: List<Scenario> = Catalog.all()
    private val bases: List<Base>
    private val families: List<String>
    private val familyBases = LinkedHashMap<String, ArrayList<Int>>()
    private val groupTitle = HashMap<String, String>()
    private val verdicts = Verdicts(scenes)
    // Самопроверка окна ставит и снимает пометки в своём файле: настоящие пометки человека она не трогает.
    private val marks = Marks(File(if (selfTest) "physics-lab/build/reports/stands/selftest-marks.tsv" else MARKS_FILE))

    init {
        val byId = LinkedHashMap<String, Base>()
        scenes.forEachIndexed { i, s ->
            val b = byId.getOrPut(s.baseId) { Base(byId.size, s.baseId, s.family, s.baseId.substringBeforeLast('/'), s.title) }
            b.variants[s.variation.ordinal] = i
        }
        bases = byId.values.toList()
        for (b in bases) familyBases.getOrPut(b.family) { ArrayList() }.add(b.index)
        families = familyBases.keys.toList()
        // Название группы — общее начало названий её сцен до двоеточия («квадрат 6: покой» → «квадрат 6»), иначе номер группы.
        for ((group, list) in bases.groupBy { it.group }) {
            val heads = list.map { it.title.substringBefore(": ", "") }.distinct()
            groupTitle[group] = if (heads.size == 1 && heads[0].isNotEmpty()) heads[0] else group
        }
    }

    private lateinit var renderer: DemoRenderer
    private lateinit var ui: Ui
    private val projection = Matrix4()
    private val scene = SceneView()
    private val panels = Panels()

    // --- что показываем ---
    private var selected = 0
    /** Вариация, которую просит человек; у сцены без неё показывается первая, какая есть. */
    private var wanted = Variation.BASE
    private var run: StandRun? = null
    /** Кто считает сцену: 0 — быстрый решатель, 1 — эталонный на шаге игры, 2 — эталонный на мелком шаге. */
    private var solver = 0
    /** Тень — та же сцена другим решателем: 0 — нет; при быстром прогоне 1 и 2 — эталон на шаге игры и на мелком шаге, при эталонном 1 — быстрый. */
    private var shadowKind = 0
    private var shadow: StandRun? = null
    private var setupError = ""
    private var paused = false
    private var speedIndex = 3
    private var accumulator = 0.0
    /** Человек тянул клетку мышью: итог правил этой сцены не в счёт. */
    private var tainted = false
    /** Сцена закончилась, а мир продолжают крутить — правила уже не считаются. */
    private var freeRun = false
    private var seekTick = -1
    private var finding = false
    /** Досчитать текущую сцену до конца без ограничения скорости (End); выбранную скорость не меняет. */
    private var rush = false
    /** Удерживаемая клавиша перехода по списку и время до её следующего повтора. */
    private var heldKey = 0
    private var heldFor = 0f
    private var autoShow = false
    private var doneFor = 0f
    private var recorded = false
    private var notice = ""

    // --- список ---
    private val collapsed = HashSet<String>()
    private val openGroups = HashSet<String>()
    private var onlyFailed = false
    private var onlyMarked = false
    private var filter = ""
    /** Что сейчас набирают с клавиатуры: 0 — ничего, 1 — поиск, 2 — заметку к сцене. */
    private var typing = 0
    private var noteText = ""
    private var rows: List<Row> = emptyList()
    private var listScroll = 0
    private var infoScroll = 0f
    private val tallies = HashMap<String, Tally>()
    private var talliedVersion = -1
    private var talliedMarks = -1

    // --- мышь ---
    private var userGrab = false
    private var panning = false
    private var lastX = 0; private var lastY = 0

    // --- снимки ---
    private var shotIndex = 0
    private var shotFramesLeft = -1

    private val sceneIndex: Int get() = bases[selected].scene(wanted)
    private val scenario: Scenario get() = scenes[sceneIndex]

    override fun create() {
        val chars = HashSet<Char>()
        for (s in scenes) for (text in arrayOf(s.title, s.about, s.expect, s.family, s.limits.name)) for (ch in text) chars.add(ch)
        for (v in Variation.values()) for (ch in v.title + v.about) chars.add(ch)
        for (m in MarkKind.values()) for (ch in m.title) chars.add(ch)
        val extra = chars.joinToString("")
        renderer = DemoRenderer(Ui.font(15, extra))
        ui = Ui(renderer, renderer.text, renderer.textCanvas(Ui.font(19, extra)))
        Gdx.input.inputProcessor = input
        marks.load()
        verdicts.load(File("physics-lab/build/reports/stands/verdicts.tsv"))
        resize(Gdx.graphics.width, Gdx.graphics.height)
        selectScene(if (shots.isNotEmpty()) indexOf(shots[0]) else indexOf(startId))
    }

    /** Номер сцены каталога по номеру или его части; ничего не нашлось — первая. */
    private fun indexOf(id: String): Int {
        if (id.isEmpty()) return 0
        return scenes.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: scenes.indexOfFirst { it.id.contains(id) }.takeIf { it >= 0 } ?: 0
    }

    override fun resize(width: Int, height: Int) {
        projection.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        scene.place(LIST_WIDTH, BAR_HEIGHT, maxOf(100f, width - LIST_WIDTH - INFO_WIDTH), maxOf(100f, height - BAR_HEIGHT))
    }

    // ------------------------------------------------------------------ выбор и ход сцены

    /** Выбрать сцену каталога: её строку списка и её вариацию. */
    private fun selectScene(index: Int) {
        val s = scenes[index.coerceIn(0, scenes.size - 1)]
        wanted = s.variation
        select(bases.indexOfFirst { it.baseId == s.baseId }.coerceAtLeast(0))
    }

    private fun select(base: Int) {
        selected = base.coerceIn(0, bases.size - 1)
        collapsed.remove(bases[selected].family)
        restart()
        run?.let { scene.fit(it.w) }
        infoScroll = 0f
        rebuildRows()
        keepSelectedVisible()
    }

    private fun restart() {
        scene.marks.clear()
        userGrab = false; tainted = false; freeRun = false; recorded = false
        accumulator = 0.0; doneFor = 0f; notice = ""; rush = false
        setupError = ""
        run = try {
            StandRun(scenario, reference = substepsOf(solver))
        } catch (t: Throwable) {
            setupError = "сцена не собралась: $t"
            null
        }
        // Тень собирается той же сценой с нуля, чтобы оба прогона шли тик в тик.
        shadow = if (shadowKind == 0 || run == null) null else try {
            StandRun(scenario, reference = if (solver == 0) substepsOf(shadowKind) else 0)
        } catch (t: Throwable) { null }
    }

    /** Число подшагов эталонного решателя для вида [kind]; 0 — считает быстрый. */
    private fun substepsOf(kind: Int): Int = when (kind) { 1 -> SUBSTEPS; 2 -> REFERENCE_SUBSTEPS; else -> 0 }

    private fun solverName(kind: Int): String = when (kind) {
        1 -> "эталонный решатель на шаге игры ($SUBSTEPS подшагов)"
        2 -> "эталонный решатель на мелком шаге ($REFERENCE_SUBSTEPS подшага)"
        else -> "быстрый решатель ($SUBSTEPS подшагов)"
    }

    private fun stepOnce(r: StandRun) {
        if (!r.done) { r.step(); scene.collect(r) }
        else if (freeRun) r.stepFree()
        // Тень идёт следом: столько же тиков, сколько прошла сцена.
        shadow?.let { s -> while (s.crash == null && s.tick < r.tick) { if (!s.done) s.step() else s.stepFree() } }
    }

    /** Сменить, кто считает сцену ([step] — вперёд или назад по кругу), и начать её заново. */
    private fun switchSolver(step: Int) {
        solver = Math.floorMod(solver + step, 3)
        if (solver != 0 && shadowKind > 1) shadowKind = 1
        restart(); paused = false; seekTick = -1; finding = false
        notice = "сцену считает ${solverName(solver)}" + if (solver != 0) " — её итог в список не идёт" else ""
    }

    /** Включить, сменить или выключить тень и начать сцену заново. */
    private fun switchShadow() {
        shadowKind = (shadowKind + 1) % (if (solver == 0) 3 else 2)
        restart(); paused = false; seekTick = -1; finding = false
        notice = if (shadowKind == 0) "тень выключена" else "тень (белый контур): ${solverName(if (solver == 0) shadowKind else 0)}"
    }

    /** На сколько связей разошлись центры тел сцены и её тени: наибольшее по телам; -1 — тени нет. */
    private fun shadowGap(r: StandRun): Double {
        val s = shadow ?: return -1.0
        var worst = 0.0
        for (k in 0 until minOf(r.w.bodies.size, s.w.bodies.size)) {
            val a = r.inVariation { r.w.centerOf(r.w.bodies[k]) }; val b = s.inVariation { s.w.centerOf(s.w.bodies[k]) }
            worst = maxOf(worst, Math.hypot(a[0] - b[0], a[1] - b[1]) / GAME_LINK)
        }
        return worst
    }

    private fun firstViolation(r: StandRun): Int =
        r.monitor.results.values.filter { it.failed && it.firstTick >= 0 }.minOfOrNull { it.firstTick } ?: -1

    private fun advance(delta: Float) {
        val r = run ?: return
        val budget = System.nanoTime() + 14_000_000L
        if (finding) {
            while (!r.done && System.nanoTime() < budget) stepOnce(r)
            if (r.done) {
                finding = false
                val first = firstViolation(r)
                if (first < 0) { paused = true; notice = "нарушений нет: сцена прошла" }
                else { restart(); seekTick = maxOf(0, first - 12); notice = "первое нарушение на тике $first" }
            }
            return
        }
        if (rush) {
            while (!r.done && System.nanoTime() < budget) stepOnce(r)
            if (r.done) rush = false
            return
        }
        if (seekTick >= 0) {
            val now = run ?: return
            while (!now.done && now.tick < seekTick && System.nanoTime() < budget) stepOnce(now)
            if (now.done || now.tick >= seekTick) { seekTick = -1; paused = true; speedIndex = minOf(speedIndex, 2) }
            return
        }
        if (paused) return
        if (r.done && !freeRun) return
        val speed = SPEEDS[speedIndex]
        if (speed == 0.0) {
            while ((!r.done || freeRun) && System.nanoTime() < budget) stepOnce(r)
            return
        }
        accumulator += delta * TICKS_PER_SECOND * speed
        while (accumulator >= 1.0) {
            accumulator -= 1.0
            stepOnce(r)
            if (System.nanoTime() > budget) { accumulator = 0.0; break }
        }
    }

    /** Что правила говорят о сцене прямо сейчас — одной строкой, для итога в списке и для пометки. */
    private fun verdictText(r: StandRun): String {
        val broken = r.monitor.results.values.filter { it.failed }
        return (broken.map { "${it.rule.title}: с тика ${it.firstTick} — ${it.text}" } +
            listOfNotNull(r.crash?.let { "решатель упал: $it" })).joinToString(" | ")
    }

    /** Сцена досмотрена без вмешательства — её итог идёт в список. */
    private fun recordVerdict(r: StandRun) {
        // Список сцен — про быстрый решатель: эталонный прогон свой итог туда не пишет.
        if (recorded || !r.done || tainted || solver != 0) return
        recorded = true
        // Что окно судит само, оно пишет заново; нарушения повтора и прочих сверок пакетного прогона остаются (Verdicts.record).
        verdicts.record(scenario.id, r.passed, firstViolation(r), verdictText(r))
    }

    // ------------------------------------------------------------------ список

    private fun anyVariant(b: Base, test: (Scenario) -> Boolean): Boolean {
        for (i in b.variants) if (i >= 0 && test(scenes[i])) return true
        return false
    }

    private fun matches(base: Int): Boolean {
        val b = bases[base]
        if (onlyFailed && !anyVariant(b) { verdicts[it.id]?.passed == false }) return false
        if (onlyMarked && !anyVariant(b) { marks[it.id] != null }) return false
        if (filter.isNotEmpty() && !(b.baseId.contains(filter, true) || b.title.contains(filter, true))) return false
        return true
    }

    private fun rebuildRows() {
        val list = ArrayList<Row>()
        // С поиском и отборами группы раскрыты все: человек ищет сцену, а не группу.
        val flat = onlyFailed || onlyMarked || filter.isNotEmpty()
        val here = bases[selected].group
        for (family in families) {
            val members = familyBases[family]!!.filter { matches(it) }
            if (members.isEmpty()) continue
            list.add(Row(Row.FAMILY, family, "", -1, members.size))
            if (family in collapsed) continue
            var k = 0
            while (k < members.size) {
                val group = bases[members[k]].group
                var end = k
                while (end < members.size && bases[members[end]].group == group) end++
                // Группа из одной сцены заголовка не получает; группа выбранной сцены всегда раскрыта.
                if (end - k > 1) list.add(Row(Row.GROUP, family, group, -1, end - k))
                if (flat || end - k == 1 || group == here || group in openGroups) for (m in k until end) list.add(Row(Row.SCENE, family, group, members[m], 0))
                k = end
            }
        }
        rows = list
    }

    /** Сводки итогов по семействам и группам для заголовков списка: пересчитываются, когда пришёл новый итог или пометка. */
    private fun refreshTallies() {
        if (talliedVersion == verdicts.version && talliedMarks == marks.size) return
        talliedVersion = verdicts.version; talliedMarks = marks.size
        tallies.clear()
        val all = tallies.getOrPut("") { Tally() }
        for (s in scenes) {
            val family = tallies.getOrPut("f:" + s.family) { Tally() }
            val group = tallies.getOrPut("g:" + s.baseId.substringBeforeLast('/')) { Tally() }
            val v = verdicts[s.id]
            if (v != null) { if (v.passed) { all.ok++; family.ok++; group.ok++ } else { all.bad++; family.bad++; group.bad++ } }
            if (marks[s.id] != null) { all.marked++; family.marked++; group.marked++ }
        }
        if (onlyFailed || onlyMarked) rebuildRows()
    }

    private fun visibleBases(): List<Int> = bases.indices.filter { matches(it) }

    private fun keepSelectedVisible() {
        val at = rows.indexOfFirst { it.kind == Row.SCENE && it.base == selected }
        if (at < 0) return
        val fit = panels.listRows
        if (at < listScroll + 2) listScroll = maxOf(0, at - 2)
        if (at >= listScroll + fit - 1) listScroll = at - fit + 2
    }

    private fun move(step: Int) {
        val list = visibleBases()
        if (list.isEmpty()) return
        val at = list.indexOf(selected)
        val next = if (at < 0) (if (step > 0) list.firstOrNull { it > selected } ?: list.first() else list.lastOrNull { it < selected } ?: list.last())
        else list[(at + step).coerceIn(0, list.size - 1)]
        select(next)
    }

    private fun navigate(keycode: Int, shift: Boolean) {
        when (keycode) {
            Input.Keys.DOWN -> move(1)
            Input.Keys.UP -> move(-1)
            Input.Keys.PAGE_DOWN -> moveGroup(1, family = shift)
            Input.Keys.PAGE_UP -> moveGroup(-1, family = shift)
        }
    }

    /** К первой сцене следующей или предыдущей группы (или семейства). */
    private fun moveGroup(step: Int, family: Boolean) {
        val list = visibleBases()
        if (list.isEmpty()) return
        fun key(i: Int) = if (family) bases[i].family else bases[i].group
        val here = key(selected)
        val at = list.indexOf(selected).let { if (it < 0) 0 else it }
        var k = at
        if (step > 0) { while (k < list.size && key(list[k]) == here) k++; if (k >= list.size) return }
        else {
            while (k >= 0 && key(list[k]) == here) k--
            if (k < 0) return
            val group = key(list[k])
            while (k > 0 && key(list[k - 1]) == group) k--
        }
        select(list[k])
    }

    /** К следующей (или предыдущей) не прошедшей сцене — в любой вариации: вариация переключается сама. */
    private fun moveFailed(step: Int) {
        var k = sceneIndex
        for (n in scenes.indices) {
            k = Math.floorMod(k + step, scenes.size)
            val v = verdicts[scenes[k].id]
            if (v != null && !v.passed && (filter.isEmpty() || scenes[k].baseId.contains(filter, true) || scenes[k].title.contains(filter, true))) {
                selectScene(k); return
            }
        }
        notice = "не прошедших сцен в списке нет"
    }

    /** Следующая или предыдущая вариация этой сцены; выбранная держится и при листании списка. */
    private fun switchVariation(step: Int) {
        val b = bases[selected]
        val have = Variation.values().filter { b.has(it) }
        if (have.size < 2) { notice = "у этой сцены одна вариация: тело генома приходит со своими длинами связей и радиусами"; return }
        val at = have.indexOf(b.variation(wanted))
        pickVariation(have[Math.floorMod(at + step, have.size)])
    }

    private fun pickVariation(v: Variation) {
        wanted = v
        restart()
        run?.let { scene.fit(it.w) }
        paused = false; seekTick = -1; finding = false
    }

    // ------------------------------------------------------------------ пометки

    private fun markCycle() {
        val r = run
        marks.cycle(scenario.id, r?.tick ?: 0, r?.let { if (it.passed) "нарушений нет" else verdictText(it) } ?: setupError, scenario.title)
        val m = marks[scenario.id]
        notice = if (m == null) "пометка снята" else "пометка: ${m.kind.title}"
    }

    private fun saveNote() {
        val r = run
        marks.note(scenario.id, noteText.trim(), r?.tick ?: 0, r?.let { if (it.passed) "нарушений нет" else verdictText(it) } ?: setupError, scenario.title)
        notice = "заметка сохранена в docs/physics/stands-marks.tsv"
    }

    // ------------------------------------------------------------------ кадр

    override fun render() {
        val delta = minOf(Gdx.graphics.deltaTime, 0.1f)
        if (shots.isNotEmpty()) { renderShot(); return }
        if (selfTest) selfTestStep()
        // Удержанная стрелка листает список сама: libGDX повторяет только набор текста, а не нажатия.
        if (heldKey != 0) {
            if (!Gdx.input.isKeyPressed(heldKey) || typing != 0) heldKey = 0
            else {
                heldFor -= delta
                if (heldFor <= 0f) { heldFor = 0.08f; navigate(heldKey, shift = false) }
            }
        }
        if (userGrab) run?.let { r ->
            r.w.dragTo(scene.worldX(Gdx.input.x.toFloat()), scene.worldY((Gdx.graphics.height - Gdx.input.y).toFloat()))
        }
        advance(delta)
        run?.let { r ->
            recordVerdict(r)
            if (r.done && !freeRun && autoShow && !finding && seekTick < 0) {
                doneFor += delta
                if (doneFor > 1.2f) { val before = selected; move(1); if (selected == before) autoShow = false }
            }
        }
        drawFrame()
    }

    private fun drawFrame() {
        val width = Gdx.graphics.width.toFloat(); val height = Gdx.graphics.height.toFloat()
        renderer.beginFrame(Palette.BACKGROUND, projection)
        refreshTallies()
        val r = run
        val mx = Gdx.input.x.toFloat(); val my = height - Gdx.input.y
        val mouse = if (scene.contains(mx, my)) doubleArrayOf(scene.worldX(mx), scene.worldY(my)) else null
        if (r != null) {
            scene.track(r.w, snap = shots.isNotEmpty())
            scene.draw(renderer.canvas, r, mouse)
            shadow?.let { scene.drawShadow(renderer.canvas, it) }
        }
        val shadowRun = shadow
        val shadowText = when {
            shadowRun == null || r == null -> ""
            shadowRun.crash != null -> "тень (${solverName(if (solver == 0) shadowKind else 0)}) упала: ${shadowRun.crash}"
            else -> String.format(java.util.Locale.ROOT, "Тень — %s: тела разошлись на %.2f связи; у тени порвано связей %d, погибло клеток %d (S — сменить)",
                solverName(if (solver == 0) shadowKind else 0), shadowGap(r), shadowRun.monitor.torn, shadowRun.monitor.died)
        }
        // Эталон без обрезки по времени удара на крупном шаге проскакивает быстрые удары — тогда ему верить нельзя.
        val missed = maxOf(r?.w?.world?.reference?.missedHits ?: 0, shadowRun?.w?.world?.reference?.missedHits ?: 0)
        val referenceNote = if (missed > 0) "Эталон проскочил ударов: $missed — на этом шаге он сцене не судья" else ""
        val state = PanelState(
            scenes = scenes, bases = bases, rows = rows, selected = selected, variation = bases[selected].variation(wanted), scenario = scenario,
            listScroll = listScroll, infoScroll = infoScroll, verdicts = verdicts, marks = marks, tallies = tallies, groupTitle = groupTitle,
            run = r, setupError = setupError, paused = paused, speed = SPEED_NAMES[speedIndex],
            tainted = tainted, freeRun = freeRun, finding = finding, seeking = seekTick >= 0, autoShow = autoShow,
            onlyFailed = onlyFailed, onlyMarked = onlyMarked, filter = filter, typing = typing, noteText = noteText, notice = notice,
            collapsed = collapsed, openGroups = openGroups, mouseX = mx, mouseY = my, preview = scene.showPreview,
            solver = solverName(solver), reference = solver != 0, shadow = shadowText, referenceNote = referenceNote,
        )
        panels.draw(ui, state, width, height, LIST_WIDTH, INFO_WIDTH, BAR_HEIGHT, ROW_HEIGHT)
        infoScroll = infoScroll.coerceIn(0f, panels.infoOverflow)
        ui.flush(projection)
    }

    // ------------------------------------------------------------------ снимки без человека

    /** Одна сцена за кадр: досчитать до нужного тика, нарисовать, сохранить — и следующая. */
    private fun renderShot() {
        if (shotFramesLeft < 0) {
            selectScene(indexOf(shots[shotIndex]))
            val r = run
            if (r != null) {
                when (shotTick) {
                    "end" -> while (!r.done) stepOnce(r)
                    "first" -> {
                        while (!r.done) r.step()
                        val first = firstViolation(r)
                        if (first >= 0) {
                            restart()
                            val again = run
                            if (again != null) while (!again.done && again.tick < first) stepOnce(again)
                        }
                    }
                    else -> { val target = shotTick.toIntOrNull() ?: 0; while (!r.done && r.tick < target) stepOnce(r) }
                }
            }
            paused = true
            run?.let { recordVerdict(it) }
            // Два кадра на прорисовку: первый после выбора сцены ещё не видел новых размеров панелей.
            shotFramesLeft = 2
        }
        drawFrame()
        shotFramesLeft--
        if (shotFramesLeft > 0) return
        val dir = File(shotDir).also { it.mkdirs() }
        val name = scenario.id.replace('/', '_') + "@" + shotTick + ".png"
        renderer.saveScreenshot(File(dir, name).absolutePath)
        println("снимок: ${File(dir, name).path} (тик ${run?.tick ?: -1})")
        shotIndex++
        shotFramesLeft = -1
        if (shotIndex >= shots.size) Gdx.app.exit()
    }

    // ------------------------------------------------------------------ самопроверка окна

    private var testFrame = 0

    /**
     * Самопроверка окна без человека (-PselfTest=1): по кадрам подаёт те же нажатия клавиш и
     * мыши, что подал бы человек, — листание, вариации, поиск, отборы, пометки, пауза, шаг,
     * перемотка к нарушению, захват клетки, прокрутка. Проверяет одно: ни одно действие не
     * роняет окно. Пометки пишутся в отдельный файл и за собой убираются. В конце — снимок и
     * выход; упавшее действие выходит с ошибкой и ненулевым кодом.
     */
    private fun selfTestStep() {
        val w = Gdx.graphics.width; val h = Gdx.graphics.height
        fun key(code: Int) { input.keyDown(code); input.keyUp(code) }
        when (testFrame++) {
            2 -> { key(Input.Keys.DOWN); key(Input.Keys.DOWN); key(Input.Keys.UP) }
            4 -> { key(Input.Keys.PAGE_DOWN); key(Input.Keys.PAGE_DOWN); key(Input.Keys.PAGE_UP) }
            6 -> { key(Input.Keys.RIGHT_BRACKET); key(Input.Keys.LEFT_BRACKET) }
            8 -> { key(Input.Keys.TAB); key(Input.Keys.TAB); key(Input.Keys.TAB) }
            10 -> key(Input.Keys.N)
            12 -> key(Input.Keys.V)
            40 -> { key(Input.Keys.SPACE); key(Input.Keys.RIGHT); key(Input.Keys.RIGHT); key(Input.Keys.SPACE) }
            44 -> { key(Input.Keys.MINUS); key(Input.Keys.EQUALS); key(Input.Keys.EQUALS); key(Input.Keys.NUM_1) }
            46 -> key(Input.Keys.END)
            70 -> { key(Input.Keys.SPACE); key(Input.Keys.SPACE) }                       // свободный ход и пауза
            72 -> key(Input.Keys.R)
            74 -> { key(Input.Keys.F); key(Input.Keys.F); key(Input.Keys.G); key(Input.Keys.G) }
            76 -> { key(Input.Keys.SLASH); for (c in "звезда") input.keyTyped(c); input.keyTyped('\b'); key(Input.Keys.ENTER) }
            78 -> { key(Input.Keys.DOWN); key(Input.Keys.ESCAPE) }
            80 -> { key(Input.Keys.SLASH); for (c in "нет такой сцены") input.keyTyped(c); key(Input.Keys.ENTER); key(Input.Keys.DOWN); key(Input.Keys.N); key(Input.Keys.ESCAPE) }
            82 -> { input.touchDown(60, 300, 0, Input.Buttons.LEFT); input.touchUp(60, 300, 0, Input.Buttons.LEFT) }   // строка списка
            84 -> { input.touchDown(60, 120, 0, Input.Buttons.LEFT); input.touchUp(60, 120, 0, Input.Buttons.LEFT) }
            86 -> { input.scrolled(0f, 3f); input.scrolled(0f, -5f) }
            88 -> {                                                    // захват клетки и сдвиг камеры
                key(Input.Keys.R)
                val r = run
                if (r != null && r.w.bodies.isNotEmpty()) {
                    val p = r.w.bodies[0].particle(0)
                    val sx = scene.screenX(r.w.gameX(p)).toInt(); val sy = h - scene.screenY(r.w.gameY(p)).toInt()
                    input.touchDown(sx, sy, 0, Input.Buttons.LEFT); input.touchDragged(sx + 40, sy + 10, 0)
                }
            }
            96 -> { input.touchUp(w / 2, h / 2, 0, Input.Buttons.LEFT) }
            98 -> { input.touchDown(w / 2, 150, 0, Input.Buttons.RIGHT); input.touchDragged(w / 2 + 30, 170, 0); input.touchUp(w / 2 + 30, 170, 0, Input.Buttons.RIGHT) }
            100 -> { key(Input.Keys.C); key(Input.Keys.L); key(Input.Keys.K); key(Input.Keys.L); key(Input.Keys.K); key(Input.Keys.P); key(Input.Keys.P); key(Input.Keys.A); key(Input.Keys.A) }
            102 -> { select(bases.size - 1); key(Input.Keys.DOWN); key(Input.Keys.PAGE_DOWN); key(Input.Keys.TAB); select(0); key(Input.Keys.UP); key(Input.Keys.PAGE_UP) }
            104 -> {                                                   // пометка, заметка и их снятие
                val had = marks[scenario.id] != null
                key(Input.Keys.M); key(Input.Keys.M)
                key(Input.Keys.T); for (c in "проба заметки") input.keyTyped(c); key(Input.Keys.ENTER)
                if (!had) marks.remove(scenario.id)
            }
            106 -> { key(Input.Keys.B) }
            // Эталонный прогон и тень: каждый вид по очереди, с шагами сцены между переключениями.
            108 -> { key(Input.Keys.S) }
            112 -> { key(Input.Keys.S) }
            116 -> { key(Input.Keys.E) }
            120 -> { key(Input.Keys.S) }
            124 -> { key(Input.Keys.E); key(Input.Keys.E); key(Input.Keys.S); key(Input.Keys.S) }
            130 -> {
                verdicts.stop()
                val dir = File(shotDir).also { it.mkdirs() }
                renderer.saveScreenshot(File(dir, "selftest.png").absolutePath)
                println("самопроверка окна: все действия прошли без падений; снимок ${File(dir, "selftest.png").path}")
                Gdx.app.exit()
            }
        }
    }

    override fun dispose() {
        verdicts.stop()
        renderer.dispose()
    }

    // ------------------------------------------------------------------ ввод

    private val input = object : InputAdapter() {
        override fun keyTyped(character: Char): Boolean {
            if (typing == 0) return false
            val text = if (typing == 1) filter else noteText
            val next = if (character == '\b') text.dropLast(1)
            else if (character >= ' ' && character != 127.toChar() && !(typing == 1 && character == '/')) text + character
            else return true
            if (typing == 1) { filter = next; rebuildRows(); listScroll = 0 } else noteText = next.take(400)
            return true
        }

        override fun keyDown(keycode: Int): Boolean {
            if (typing != 0) {
                when (keycode) {
                    Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> { if (typing == 2) saveNote(); typing = 0 }
                    Input.Keys.ESCAPE -> { if (typing == 1) { filter = ""; rebuildRows() }; typing = 0 }
                }
                return true
            }
            val shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
            when (keycode) {
                Input.Keys.DOWN, Input.Keys.UP, Input.Keys.PAGE_DOWN, Input.Keys.PAGE_UP -> {
                    navigate(keycode, shift); heldKey = keycode; heldFor = 0.4f
                }
                Input.Keys.RIGHT_BRACKET -> moveGroup(1, family = true)
                Input.Keys.LEFT_BRACKET -> moveGroup(-1, family = true)
                Input.Keys.TAB -> switchVariation(if (shift) -1 else 1)
                Input.Keys.N -> moveFailed(if (shift) -1 else 1)
                Input.Keys.F -> { onlyFailed = !onlyFailed; rebuildRows(); listScroll = 0; keepSelectedVisible() }
                Input.Keys.G -> { onlyMarked = !onlyMarked; rebuildRows(); listScroll = 0; keepSelectedVisible() }
                Input.Keys.SLASH -> { typing = 1; filter = ""; rebuildRows() }
                Input.Keys.ESCAPE -> if (filter.isNotEmpty()) { filter = ""; rebuildRows(); keepSelectedVisible() }
                Input.Keys.M -> if (shift) { marks.remove(scenario.id); notice = "пометка снята" } else markCycle()
                Input.Keys.T -> { typing = 2; noteText = marks[scenario.id]?.note.orEmpty(); paused = true }
                Input.Keys.SPACE -> {
                    val r = run
                    if (r != null && r.done && !freeRun) { freeRun = true; paused = false; notice = "свободный ход: сцена закончена, правила больше не считаются" }
                    else paused = !paused
                }
                Input.Keys.RIGHT, Input.Keys.PERIOD -> { paused = true; run?.let { stepOnce(it) } }
                Input.Keys.R -> { restart(); paused = false; seekTick = -1; finding = false }
                Input.Keys.V -> { restart(); finding = true; seekTick = -1; notice = "ищу первое нарушение…" }
                Input.Keys.END -> { paused = false; rush = true; seekTick = -1; finding = false }
                Input.Keys.MINUS, Input.Keys.NUMPAD_SUBTRACT -> speedIndex = maxOf(0, speedIndex - 1)
                Input.Keys.EQUALS, Input.Keys.PLUS, Input.Keys.NUMPAD_ADD -> speedIndex = minOf(SPEEDS.size - 1, speedIndex + 1)
                Input.Keys.NUM_1 -> speedIndex = 3
                Input.Keys.A -> { autoShow = !autoShow; doneFor = 0f }
                Input.Keys.C -> run?.let { scene.fit(it.w) }
                Input.Keys.L -> scene.showLinks = !scene.showLinks
                Input.Keys.K -> scene.showCells = !scene.showCells
                Input.Keys.P -> scene.showPreview = !scene.showPreview
                Input.Keys.E -> switchSolver(if (shift) -1 else 1)
                Input.Keys.S -> switchShadow()
                Input.Keys.B -> {
                    // B — сцены этого семейства во всех вариациях, Shift+B — весь каталог, начиная с этого семейства.
                    val family = bases[selected].family
                    val mine = scenes.filter { it.family == family }
                    if (shift) verdicts.runAll(mine + scenes.filter { it.family != family }, "весь каталог")
                    else verdicts.runAll(mine, "семейство «$family»")
                    notice = if (shift) "проверяю в фоне весь каталог: ${scenes.size} сцен" else "проверяю в фоне семейство: ${mine.size} сцен"
                }
                else -> return false
            }
            return true
        }

        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            val mx = Gdx.input.x.toFloat(); val my = (Gdx.graphics.height - Gdx.input.y).toFloat()
            when {
                mx < LIST_WIDTH -> listScroll = (listScroll + (amountY * 3).toInt()).coerceIn(0, maxOf(0, rows.size - 3))
                mx > Gdx.graphics.width - INFO_WIDTH -> infoScroll = (infoScroll + amountY * 40f).coerceIn(0f, panels.infoOverflow)
                scene.contains(mx, my) -> scene.zoomAt(mx, my, if (amountY > 0) 1 / 1.15 else 1.15)
            }
            return true
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            val mx = screenX.toFloat(); val my = (Gdx.graphics.height - screenY).toFloat()
            lastX = screenX; lastY = screenY
            if (mx < LIST_WIDTH) {
                if (button != Input.Buttons.LEFT) return true
                val row = panels.rowAt(my, listScroll, rows.size)
                if (row >= 0) {
                    val r = rows[row]
                    when (r.kind) {
                        Row.FAMILY -> { if (!collapsed.remove(r.family)) collapsed.add(r.family); rebuildRows() }
                        Row.GROUP -> { if (!openGroups.remove(r.group)) openGroups.add(r.group); rebuildRows() }
                        else -> select(r.base)
                    }
                }
                return true
            }
            if (mx > Gdx.graphics.width - INFO_WIDTH) {
                // Щелчок по плашке вариации в описании — та же Tab, только сразу к нужной.
                if (button == Input.Buttons.LEFT) panels.variationAt(mx, my)?.let { pickVariation(it) }
                return true
            }
            if (!scene.contains(mx, my)) return true
            val r = run
            if (button == Input.Buttons.LEFT && r != null) {
                val hit = scene.pick(r.w, scene.worldX(mx), scene.worldY(my), 1.2)
                if (hit != null) {
                    r.w.grab(r.w.bodies[hit.first], hit.second)
                    userGrab = true; tainted = true
                    if (r.done && !freeRun) { freeRun = true; notice = "свободный ход: сцена закончена, правила больше не считаются" }
                    paused = false
                    return true
                }
            }
            panning = true
            return true
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (panning) scene.pan((screenX - lastX).toFloat(), -(screenY - lastY).toFloat())
            lastX = screenX; lastY = screenY
            return true
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (userGrab) { run?.w?.release(); userGrab = false }
            panning = false
            return true
        }
    }
}

fun main() {
    val start = System.getProperty("stands.start").orEmpty()
    val shots = System.getProperty("stands.shots").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val shotTick = System.getProperty("stands.shotTick") ?: "end"
    val shotDir = System.getProperty("stands.shotDir") ?: "physics-lab/build/reports/stands/shots"
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Стенды физики Genomeia")
        setWindowedMode(1760, 960)
        useVsync(true)
        setForegroundFPS(60)
    }
    Lwjgl3Application(StandViewer(start, shots, shotTick, shotDir, selfTest = System.getProperty("stands.selfTest") == "1"), config)
}
