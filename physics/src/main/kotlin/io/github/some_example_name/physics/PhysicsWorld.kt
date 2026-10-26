package io.github.some_example_name.physics

/**
 * МИР ФИЗИКИ — ВХОД В БИБЛИОТЕКУ. Этап A1 плана docs/physics/library-integration-plan-2026-10-25.md.
 *
 * Клиент (стенд, позже игра через адаптер) создаёт мир, загружает тела, на каждом тике
 * выставляет входы и зовёт [step], а результат читает из массивов. Всё, что меняет
 * будущие тики, проходит через методы мира; прямой доступ к [state] остаётся только
 * стендам и проверкам — для разбора по стадиям, рисования и диагностики.
 *
 * Тела добавляются, растут по клетке и удаляются на ходу (этап A2); к телу клиент
 * обращается по номеру тела (addBody), частицы тела — bodyFirst + смещение. Фасад ничего
 * не пересчитывает и не копирует: тот же решатель, те же массивы, те же биты.
 *
 * Массивы ([x], [y], [vx], [vy], [organismOf]) — живые массивы решателя, только для
 * чтения по договору. Ссылки на них стабильны между тиками, пока тела не добавлены,
 * не удалены с уплотнением и мир не загружен заново — после этого их берут заново.
 */
/**
 * Доля частиц удалённых тел, после которой мир уплотняется сам (PhysicsWorld.removeBody).
 * Уплотнение — проход порядка мира; мёртвые до него только занимают память и место в
 * обходах (спящие и мёртвые решатель пропускает). Четверть: мир растёт не больше чем на
 * треть сверх живого.
 */
const val COMPACT_DEAD_SHARE = 0.25

class PhysicsWorld(
    val config: PhysicsConfig = PhysicsConfig(),
    workers: PhysicsWorkers? = if (config.WORKERS > 1) PhysicsWorkers.shared(config.WORKERS) else null,
    /** Постоянные масштаба (см. WorldScale); null — из первой загрузки. */
    scale: WorldScale? = null,
) {
    /** Состояние решателя — для стендов и проверок. Клиенту-игре не нужен. */
    val state = BodyState(config).also { it.workers = workers; it.worldScale = scale }
    private val simulation = BodySimulation(state)

    /** Наблюдатель подшагов (история для интерполяции рисования); без окна — null. */
    var observer: StepObserver?
        get() = simulation.observer
        set(value) { simulation.observer = value }

    var workers: PhysicsWorkers?
        get() = state.workers
        set(value) { state.workers = value }

    // --- тела ---
    //
    // НОМЕР ТЕЛА, А НЕ НОМЕР ЧАСТИЦЫ. Удалённые тела выбрасываются уплотнением (compact),
    // и частицы тел после них сдвигаются вниз. Клиент держит номер тела и смещение клетки
    // в нём: частица = bodyFirst(тело) + смещение. Номер тела не меняется, пока тело живо,
    // и не выдаётся повторно. Мышцы тела так же: bodyMuscleFirst(тело) + номер мышцы в теле.

    private var bodyFirstOf = IntArray(8)
    private var bodyCountOf = IntArray(8)
    private var bodyMuscleFirstOf = IntArray(8)
    private var bodyMuscleCountOf = IntArray(8)
    /** 0 — тела нет (уплотнено или не было), 1 — живо, 2 — удалено и ждёт уплотнения. */
    private var bodyStateOf = ByteArray(8)
    /** Сколько номеров тел выдано за жизнь мира. */
    var bodyIds = 0; private set
    /** Частиц удалённых тел, которые ещё не выброшены уплотнением. */
    var removedParticles = 0; private set

    /** Загрузить набор тел целиком — одно тело с номером 0. Прежнее содержимое мира пропадает. */
    fun load(definition: BodyDefinition) {
        simulation.load(definition)
        bodyIds = 0; removedParticles = 0
        java.util.Arrays.fill(bodyStateOf, 0)
        record(0, definition.count, 0, definition.muscleClusters.size)
    }

    private fun record(first: Int, count: Int, muscleFirst: Int, muscleCount: Int): Int {
        val id = bodyIds++
        if (id == bodyFirstOf.size) {
            val cap = id * 2
            bodyFirstOf = bodyFirstOf.copyOf(cap); bodyCountOf = bodyCountOf.copyOf(cap)
            bodyMuscleFirstOf = bodyMuscleFirstOf.copyOf(cap); bodyMuscleCountOf = bodyMuscleCountOf.copyOf(cap)
            bodyStateOf = bodyStateOf.copyOf(cap)
        }
        bodyFirstOf[id] = first; bodyCountOf[id] = count
        bodyMuscleFirstOf[id] = muscleFirst; bodyMuscleCountOf[id] = muscleCount
        bodyStateOf[id] = 1
        return id
    }

    /**
     * Добавить тело в живой мир: первое — загрузка, следующие дописываются после прежних
     * (BodyState.appendBody — без пересчёта прежних тел). Тело ставится в позу покоя из
     * [definition] с нулевыми скоростями; [born] — какие клетки рождены (рост, activate).
     * Возвращает НОМЕР ТЕЛА; его частицы — bodyFirst(номер) + смещение в [definition].
     */
    fun addBody(definition: BodyDefinition, born: BooleanArray? = null): Int {
        if (state.n == 0) {
            require(born == null) { "первое тело — целиком; растущее дописывается в непустой мир" }
            load(definition); reset(); return 0
        }
        val first = state.n
        val muscles = state.muscleTarget.size
        state.appendBody(definition, born)
        return record(first, definition.count, muscles, definition.muscleClusters.size)
    }

    /** Первая частица тела [body]; частицы тела идут подряд. */
    fun bodyFirst(body: Int): Int = bodyFirstOf[checkBody(body)]
    fun bodyCount(body: Int): Int = bodyCountOf[checkBody(body)]
    /** Первая мышца тела [body] в общей нумерации мышц мира (setMuscleTarget). */
    fun bodyMuscleFirst(body: Int): Int = bodyMuscleFirstOf[checkBody(body)]
    fun bodyMuscleCount(body: Int): Int = bodyMuscleCountOf[checkBody(body)]
    /** Живо ли тело: добавлено и ещё не удалено. */
    fun isBodyAlive(body: Int): Boolean = body in 0 until bodyIds && bodyStateOf[body].toInt() == 1

    private fun checkBody(body: Int): Int {
        require(isBodyAlive(body)) { "тела $body нет в мире" }
        return body
    }

    /**
     * Удалить тело [body]: гибель на месте, без пересчёта мира (BodyState.removeBody). Когда
     * частиц удалённых тел набирается COMPACT_DEAD_SHARE мира, мир уплотняется ([compact]):
     * решение зависит только от состояния мира, повтор его воспроизводит.
     */
    fun removeBody(body: Int) {
        checkBody(body)
        state.removeBody(bodyFirstOf[body], bodyCountOf[body])
        bodyStateOf[body] = 2
        removedParticles += bodyCountOf[body]
        if (removedParticles >= COMPACT_DEAD_SHARE * state.n) compact()
    }

    /**
     * Уплотнить мир: выбросить удалённые тела и сдвинуть остальные вниз с сохранением
     * порядка (WorldCompaction). Номера живых тел не меняются, их bodyFirst и
     * bodyMuscleFirst — меняются. Производные структуры собираются заново, как при
     * загрузке, — даже если удалять нечего. Зовётся между тиками.
     */
    fun compact() {
        val removed = BooleanArray(state.n)
        for (id in 0 until bodyIds) if (bodyStateOf[id].toInt() == 2)
            java.util.Arrays.fill(removed, bodyFirstOf[id], bodyFirstOf[id] + bodyCountOf[id], true)
        state.compactWorld(removed)
        var particlesBefore = 0; var musclesBefore = 0
        for (id in 0 until bodyIds) when (bodyStateOf[id].toInt()) {
            1 -> { bodyFirstOf[id] -= particlesBefore; bodyMuscleFirstOf[id] -= musclesBefore }
            2 -> {
                particlesBefore += bodyCountOf[id]; musclesBefore += bodyMuscleCountOf[id]
                bodyStateOf[id] = 0; bodyFirstOf[id] = -1
            }
        }
        removedParticles = 0
    }

    /**
     * Родить клетку растущего тела (addBody с born): частица оживает в точке ([x], [y]) со
     * скоростью ([vx], [vy]) вместе с рёбрами к рождённым соседям и треугольниками.
     */
    fun activate(cell: Int, x: Double, y: Double, vx: Double = 0.0, vy: Double = 0.0) =
        state.activateCell(cell, x, y, vx, vy)

    /**
     * Где родиться клетке [cell] в нынешней позе рождённых соседей (поворот и масштаб позы
     * покоя): x, y, vx, vy в [out]. См. BodyState.birthPlacement.
     */
    fun birthPlacement(cell: Int, out: DoubleArray = DoubleArray(4)): DoubleArray {
        state.birthPlacement(cell, out); return out
    }

    /** Родить клетку там, где ей место сейчас (birthPlacement). */
    fun activate(cell: Int) {
        val p = birthPlacement(cell)
        state.activateCell(cell, p[0], p[1], p[2], p[3])
    }

    /** Ждёт ли клетка рождения. */
    fun isUnborn(cell: Int): Boolean = state.cellUnborn[cell]

    /**
     * Вернуть тела мира к позе покоя, целыми, с нулевыми скоростями. Удалённые тела
     * остаются удалёнными: сначала мир уплотняется.
     */
    fun reset() {
        if (removedParticles > 0) compact()
        simulation.reset()
    }

    /**
     * Один тик: мышцы доезжают к своим целям за [dt], новорождённая ткань — на шаг к своим
     * длинам покоя, затем шаг решателя (внутри — SUBSTEPS подшагов). [dt] другой, чем DT, — только у стендов с ускоренными мышцами.
     */
    fun step(dt: Double = DT) {
        state.updateMuscles(dt)
        state.easeNewborn()
        simulation.step()
    }

    /**
     * Шаг решателя без движения мышц: активации остаются, где были. Для стендов, которые
     * собирают сцену сами (раскол тела в DemoScenes), — игре не нужен.
     */
    fun stepSolverOnly() = simulation.step()

    // --- входы тика ---

    val muscleCount: Int get() = state.muscleTarget.size

    /** Цель сокращения мышцы [m]: 0 — расслаблена, 1 — сокращена полностью. */
    fun setMuscleTarget(m: Int, value: Double) { state.muscleTarget[m] = value }
    fun muscleTarget(m: Int): Double = state.muscleTarget[m]
    fun setAllMuscleTargets(value: Double) { state.muscleTarget.fill(value) }

    /** Схваченная клетка или -1. */
    val grabbed: Int get() = state.dragId

    /** Схватить клетку: она тянется к точке [dragTo], её вес в позе кости — DRAG_MATCH_WEIGHT. */
    fun grab(cell: Int) {
        state.dragId = cell
        state.matchWeight[cell] = DRAG_MATCH_WEIGHT
    }

    /** Куда тянуть схваченную клетку. */
    fun dragTo(x: Double, y: Double) { state.mouseX = x; state.mouseY = y }

    /** Отпустить схваченную клетку. */
    fun release() {
        val i = state.dragId
        if (i < 0) return
        state.matchWeight[i] = 1.0
        state.dragId = -1
    }

    /**
     * Назначить скорость частице мимо решателя (удар, выстрел). Спящий организм такую
     * скорость обнулит — разбудить его [wake] (одного вызова на организм достаточно).
     */
    fun setVelocity(i: Int, vx: Double, vy: Double) { state.vx[i] = vx; state.vy[i] = vy }

    /** Переставить частицу в точку без скорости от переноса (prev там же). */
    fun place(i: Int, x: Double, y: Double) {
        state.px[i] = x; state.py[i] = y
        state.prevX[i] = x; state.prevY[i] = y
    }

    /** Разбудить организм частицы [i] в начале следующего подшага. */
    fun wake(i: Int) = state.sleep.requestWake(i)

    /** Сон тел: спящие организмы заморожены и пропускаются решателем. */
    var sleepOn: Boolean
        get() = state.sleep.on
        set(value) = state.setSleepOn(value)

    // --- выходы ---

    val particleCount: Int get() = state.n
    val x: DoubleArray get() = state.px
    val y: DoubleArray get() = state.py
    val vx: DoubleArray get() = state.vx
    val vy: DoubleArray get() = state.vy
    val organismOf: IntArray get() = state.organismOf
    val organismCount: Int get() = state.organismCount
    val meanLinkLength: Float get() = state.linkScale
}
