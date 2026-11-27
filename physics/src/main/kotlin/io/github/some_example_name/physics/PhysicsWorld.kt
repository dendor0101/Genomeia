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
const val COMPACT_DEAD_SHARE = 0.25f

/**
 * Наименьший блок пула свободных частиц (PhysicsWorld.addFree). Следующий блок — размером со
 * весь прежний пул: дописываний тела-пула логарифм от числа частиц. Нерождённый слот стоит
 * памяти частицы, но не времени решателя.
 */
const val FREE_POOL_BLOCK = 256

/** Что взять в зеркало после правки мира с движком Rust (PhysicsWorld.mirrored): ничего или всё; ≥ 0 — одна частица. */
private const val PULL_NONE = -1
private const val PULL_ALL = -2

class PhysicsWorld(
    val config: PhysicsConfig = PhysicsConfig(),
    workers: PhysicsWorkers? = if (config.WORKERS > 1) PhysicsWorkers.shared(config.WORKERS) else null,
    /** Постоянные масштаба (см. WorldScale); null — из первой загрузки. */
    scale: WorldScale? = null,
    /** Только Kotlin, без движка и тени Rust: временный мир разбора снимка у движка Rust ([read]). */
    kotlinOnly: Boolean = false,
) {
    /** Состояние решателя — для стендов и проверок. Клиенту-игре не нужен. */
    val state = BodyState(config).also { it.workers = workers; it.worldScale = scale }
    private val simulation = BodySimulation(state)

    /**
     * ПЕРЕНОС НА RUST (RUST_PORT.md): теневой мир на Rust, который повторяет все методы этого мира и после каждого
     * тика сверяется с ним побитово (RustShadow). null — тени нет: она включается только
     * `-Dgenomeia.physics.rust=shadow`, без него этот код не исполняется (одно сравнение с null на вызов метода).
     */
    private var shadow: io.github.some_example_name.physics.rust.RustWorld? =
        if (!kotlinOnly && RustShadow.requested && !RustEngine.active) RustShadow.create(this, state, scale) else null
    private var shadowDepth = 0
    private var shadowTick = 0L

    /**
     * ФИЗИКА НА RUST В ИГРЕ (RustEngine.kt): мир на Rust шагает вместо Kotlin, этот — фасад; null — шагает Kotlin. Состояние
     * Kotlin тогда — только зеркало ([mirror]) массивов, которые читает игра; остальное спрашивается у мира Rust.
     */
    private val engine: io.github.some_example_name.physics.rust.RustWorld? =
        if (!kotlinOnly && RustEngine.active) io.github.some_example_name.physics.rust.RustWorld(state, scale) else null
    private val mirror: RustMirror? =
        engine?.let { RustEngine.registerClose(this, it); RustMirror(state, it).also { m -> m.pullAll() } }

    /** Шагает ли мир на Rust (RustEngine). */
    val isRustEngine: Boolean get() = engine != null

    /**
     * Освободить мир Rust (движок Rust): игра бросает мир при новой сессии, а Cleaner на Android до 13 нет. С Kotlin —
     * ничего. После close мир не используется.
     */
    fun close() { engine?.close() }

    /** Параметры, которые с движком Rust не хранит состояние Kotlin (их геттеры читает игра). */
    private var engineSleepOn = state.sleep.on
    private var engineDeepKillOn = state.deepKillOn

    /** Двойник мира на Rust (тень, `-Dgenomeia.physics.rust=shadow`) — для замеров Kotlin против Rust; иначе null. */
    val rustTwin: io.github.some_example_name.physics.rust.RustWorld? get() = shadow

    /**
     * Замер Kotlin против Rust (RustGenomeBench): шаг мира не шагает двойника и не сверяет его — стенд шагает двойника
     * сам, под своим таймером, и сверяет отпечатки. Входы тика (методы мира) доходят до двойника как обычно.
     */
    var rustTwinSeparateStep = false

    /**
     * Выполнить метод в Kotlin и повторить его в тени. Вложенные вызовы (пул частиц зовёт addBody изнутри, среда —
     * wakeAll) в тень не уходят: тень сама делает ту же вложенную работу своим методом.
     *
     * С движком Rust ([engine]) метод выполняет только мир Rust, а зеркало берёт то, что метод мог поменять: [pull] —
     * номер частицы (правка одной клетки), [PULL_NONE] (зеркальных массивов метод не трогает) или [PULL_ALL].
     */
    private inline fun mirrored(rust: (io.github.some_example_name.physics.rust.RustWorld) -> Unit, pull: Int = PULL_ALL,
                                kotlin: () -> Unit) {
        val e = engine
        if (e != null) {
            rust(e)
            when {
                pull >= 0 -> mirror!!.pullCell(pull)
                pull == PULL_ALL -> mirror!!.pullAll()
            }
            return
        }
        val sh = shadow
        if (sh == null || shadowDepth > 0) return kotlin()
        shadowDepth++
        try {
            sh.syncParams(state)
            // checkOps: правки стенда в обход методов — до операции, сверка — строго после неё.
            if (RustShadow.checkOps) RustShadow.prepare(state, sh, shadowTick)
            kotlin()
            rust(sh)
            if (RustShadow.checkOps) RustShadow.checkOp(state, sh, shadowTick)
        } finally {
            shadowDepth--
        }
    }

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
    fun load(definition: BodyDefinition) = mirrored({ it.load(definition) }) {
        // Тела загружаются целыми: временных связей роста у них нет (TempLinks.kt). Связи и треугольники — «волнами»
        // (WaveOrder.kt): соседние в памяти не делят частиц, биты те же.
        loadOrdered(definition.forBorn(null).inWaveOrder())
    }

    /** Загрузка описания, уже приведённого к порядку мира (forBorn, inWaveOrder). */
    private fun loadOrdered(definition: BodyDefinition) {
        simulation.load(definition)
        bodyIds = 0; removedParticles = 0
        java.util.Arrays.fill(bodyStateOf, 0)
        record(0, definition.count, 0, definition.muscleClusters.size)
        freeBlockN = 0; freeSlots = 0; freeStackN = 0; freeCount = 0
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
    fun addBody(body: BodyDefinition, born: BooleanArray? = null, boneOn: BooleanArray? = null): Int {
        engine?.let { e -> val id = e.addBody(body, born, boneOn); mirror!!.pullAll(); return id }
        var id = -1
        mirrored({ check(it.addBody(body, born, boneOn) == id) { "тень Rust: другой номер тела" } }) {
            id = addBodyKotlin(body, born, boneOn)
        }
        return id
    }

    private fun addBodyKotlin(body: BodyDefinition, born: BooleanArray?, boneOn: BooleanArray?): Int {
        // Временные связи роста, которые рождённые клетки уже сняли бы, в мир не идут: взрослое тело — без них (TempLinks.kt).
        // Связи и треугольники — «волнами» (WaveOrder.kt).
        val definition = body.forBorn(born).inWaveOrder()
        if (state.n == 0) {
            if (born == null && boneOn == null) { loadOrdered(definition); reset(); return 0 }
            // Растущее тело дописывается в непустой мир (загрузка строит тело целиком). Клиент-
            // игра начинает с пустого мира и зиготы: первым телом встаёт пул свободных частиц
            // (ему нужен WorldScale) — он игре всё равно нужен под субстанции.
            growFreePool()
        }
        val first = state.n
        val muscles = state.muscleTarget.size
        state.appendBody(definition, born, boneOn)
        return record(first, definition.count, muscles, definition.muscleClusters.size)
    }

    /** Первая частица тела [body]; частицы тела идут подряд. */
    fun bodyFirst(body: Int): Int = engine?.bodyFirst(body) ?: bodyFirstOf[checkBody(body)]
    fun bodyCount(body: Int): Int = engine?.bodyCount(body) ?: bodyCountOf[checkBody(body)]
    /** Первая мышца тела [body] в общей нумерации мышц мира (setMuscleTarget). */
    fun bodyMuscleFirst(body: Int): Int = engine?.bodyMuscleFirst(body) ?: bodyMuscleFirstOf[checkBody(body)]
    fun bodyMuscleCount(body: Int): Int = engine?.bodyMuscleCount(body) ?: bodyMuscleCountOf[checkBody(body)]
    /** Живо ли тело: добавлено и ещё не удалено. */
    fun isBodyAlive(body: Int): Boolean =
        engine?.isBodyAlive(body) ?: (body in 0 until bodyIds && bodyStateOf[body].toInt() == 1)

    private fun checkBody(body: Int): Int {
        require(isBodyAlive(body)) { "тела $body нет в мире" }
        return body
    }

    /**
     * Удалить тело [body]: гибель на месте, без пересчёта мира (BodyState.removeBody). Когда
     * частиц удалённых тел набирается COMPACT_DEAD_SHARE мира, мир уплотняется ([compact]):
     * решение зависит только от состояния мира, повтор его воспроизводит.
     */
    fun removeBody(body: Int) = mirrored({ it.removeBody(body) }) {
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
    fun compact() = mirrored({ it.compact() }) {
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
        compactions++
        state.clearDeathLogs()
    }

    /**
     * Сколько раз мир уплотнялся: после уплотнения номера частиц сдвинуты, и клиент, который
     * держит их у себя (игра: ParticleEntity.physicsIndex), пересчитывает их по номерам тел.
     */
    var compactions = 0
        get() = engine?.compactions ?: field
        private set

    /**
     * Родить клетку растущего тела (addBody с born): частица оживает в точке ([x], [y]) со
     * скоростью ([vx], [vy]) вместе с рёбрами к рождённым соседям и треугольниками.
     */
    fun activate(cell: Int, x: Float, y: Float, vx: Float = 0.0f, vy: Float = 0.0f) =
        mirrored({ it.activate(cell, x, y, vx, vy) }, cell) { state.activateCell(cell, x, y, vx, vy) }

    /**
     * Где родиться клетке [cell] в нынешней позе рождённых соседей (поворот и масштаб позы
     * покоя): x, y, vx, vy в [out]. См. BodyState.birthPlacement.
     */
    fun birthPlacement(cell: Int, out: FloatArray = FloatArray(4)): FloatArray {
        state.birthPlacement(cell, out); return out
    }

    /** Родить клетку там, где ей место сейчас (birthPlacement). */
    fun activate(cell: Int) = mirrored({ it.activate(cell) }, cell) {
        val p = birthPlacement(cell)
        state.activateCell(cell, p[0], p[1], p[2], p[3])
    }

    /**
     * Родить клетку делением живой клетки [parent] того же тела: почти в родителе, дальше её
     * выталкивают дорастающие связи (BodyState.divisionPlacement, DIVISION_START_FRACTION).
     */
    fun activateFromParent(cell: Int, parent: Int) = mirrored({ it.activateFromParent(cell, parent) }, cell) {
        val p = FloatArray(4)
        state.divisionPlacement(cell, parent, p)
        state.activateCell(cell, p[0], p[1], p[2], p[3])
    }

    // --- события последнего шага (этап B2) ---

    /** Сколько рёбер погибло за последний шаг (разрыв, пробой, гибель клетки), в порядке гибели. */
    val edgeDeathCount: Int get() = engine?.edgeDeathCount ?: state.edgeDeathN
    /** Концы [k]-го погибшего ребра — частицы мира. */
    fun edgeDeathA(k: Int): Int = engine?.edgeDeathA(k) ?: state.edgeDeathLog[2 * k]
    fun edgeDeathB(k: Int): Int = engine?.edgeDeathB(k) ?: state.edgeDeathLog[2 * k + 1]
    /** Сколько клеток погибло за последний шаг и какие ([cellDeath]). */
    val cellDeathCount: Int get() = engine?.cellDeathCount ?: state.cellDeathN
    fun cellDeath(k: Int): Int = engine?.cellDeath(k) ?: state.cellDeathLog[k]

    /**
     * Рёбра контура — то, что омывается средой (края дыр и голые связи тоже), как зелёная граница
     * стенда. Для отладочной отрисовки клиента; концы [k]-го ребра — частицы мира.
     */
    val boundaryEdgeCount: Int get() = engine?.boundaryEdgeCount ?: state.boundCount
    fun boundaryEdgeA(k: Int): Int = engine?.boundaryEdgeA(k) ?: state.boundA[k]
    fun boundaryEdgeB(k: Int): Int = engine?.boundaryEdgeB(k) ?: state.boundB[k]

    /**
     * Сталкивается ли клетка [i]: живая, и у неё есть ребро контура (живое ребро не больше чем
     * в одном живом треугольнике), щель в веере треугольников или она одна. Тот же набор, по
     * которому строятся касания мира физики. Игра берёт отсюда «клетка на краю» (сетка касаний).
     */
    fun isSurfaceCell(i: Int): Boolean = engine?.isSurfaceCell(i) ?: state.localSurface.active[i]

    /**
     * Клетки, у которых [isSurfaceCell] сменился с прошлого [clearSurfaceChanges], — номерами
     * частиц, каждая не больше раза. Пишут и шаг, и рождение или гибель между шагами. После
     * уплотнения ([compactions]) и загрузки снимка журнал начат заново — сверять все клетки.
     */
    val surfaceChangeCount: Int get() = engine?.surfaceChangeCount ?: state.localSurface.activeChangedN
    fun surfaceChange(k: Int): Int = engine?.surfaceChange(k) ?: state.localSurface.activeChanged[k]
    fun clearSurfaceChanges() = mirrored({ it.clearSurfaceChanges() }, PULL_NONE) { state.localSurface.clearActiveChanged() }

    /**
     * Сделать клетку [cell] костью (клиент-игра: мутация в кость; тело дописано с boneOn): она
     * входит в свою кость описания, поза кости — нынешняя форма её включённых клеток. Без кости
     * в описании, мёртвой или уже включённой — ничего. Между тиками.
     * Новорождённая, которая ещё выходит из родителя (дорастают все её связи), костью не
     * становится — false, клиент повторяет вызов в следующих тиках. true — клетка теперь кость.
     */
    fun setBone(cell: Int): Boolean {
        // Кость включает все свои клетки сразу: зеркало — целиком.
        engine?.let { e -> val k = e.setBone(cell); mirror!!.pullAll(); return k }
        var k = false
        mirrored({ check(it.setBone(cell) == k) { "тень Rust: setBone($cell)" } }) { k = state.activateBone(cell) }
        return k
    }

    /** Спит ли организм частицы [i] (BodySleep: заморожен до толчка). */
    fun isAsleep(i: Int): Boolean {
        // С движком Rust — зеркало после шага: игра спрашивает это из потока рисования, вызов в мир Rust там — гонка.
        val m = mirror
        return if (m != null) i < m.asleep.size && m.asleep[i] else state.sleep.isAsleep(state.organismOf[i])
    }

    /** Включена ли кость клетки. */
    fun isBone(cell: Int): Boolean = state.boneOf[cell] >= 0

    /** Ждёт ли клетка рождения. */
    fun isUnborn(cell: Int): Boolean = state.cellUnborn[cell]

    /**
     * Вернуть тела мира к позе покоя, целыми, с нулевыми скоростями. Удалённые тела
     * остаются удалёнными: сначала мир уплотняется.
     */
    fun reset() = mirrored({ it.reset() }) {
        if (removedParticles > 0) compact()
        simulation.reset()
        // Сброс возвращает все клетки целыми, в том числе слоты пула: снова нерождённые.
        if (freeSlots > 0) {
            for (b in 0 until freeBlockN) {
                val first = bodyFirstOf[freeBlockBody[b]]
                for (k in 0 until bodyCountOf[freeBlockBody[b]]) state.deactivateFree(first + k, refresh = false)
            }
            state.refreshSurface()
            freeLive.fill(false); freeCount = 0
            freeStackN = 0
            for (id in freeSlots - 1 downTo 0) freeStack[freeStackN++] = id
        }
    }

    // --- свободные частицы (субстанции), этап A3 ---
    //
    // Частица рождается в слоте пула — тела из одиночных нерождённых клеток (см.
    // FreeParticles.kt): дешево, без дописывания тела. Номер частицы стабилен, пока она
    // жива; частица мира — freeParticle(номер), после уплотнения он другой. Номер
    // удалённой частицы выдаётся снова (наименьший свободный — первым).

    /** Тела пула и номер их первого слота; слоты пронумерованы подряд по блокам. */
    private var freeBlockBody = IntArray(4)
    private var freeBlockStart = IntArray(4)
    private var freeBlockN = 0
    private var freeSlots = 0
    private var freeStack = IntArray(0)
    private var freeStackN = 0
    private var freeLive = BooleanArray(0)
    /** Сколько номеров свободных частиц занято (добавлены и не убраны, живые и убитые физикой). */
    var freeCount = 0
        get() = engine?.freeCount ?: field
        private set

    /**
     * Добавить свободную частицу в точку ([x], [y]) со скоростью ([vx], [vy]), радиусом
     * [radius], массой [mass] (NaN — как у клетки того же радиуса) и множителем сопротивления
     * среды [drag] (1 — как среда мира). Возвращает номер
     * частицы. Цена — по соседям, кроме редкого роста пула (дописывание тела-пула).
     */
    fun addFree(x: Float, y: Float, radius: Float, vx: Float = 0.0f, vy: Float = 0.0f, mass: Float = Float.NaN,
                drag: Float = 1.0f): Int {
        engine?.let { e ->
            val id = e.addFree(x, y, radius, vx, vy, mass, drag)
            // Пул вырос (новое тело) — зеркало целиком, иначе ожила одна частица.
            if (e.particleCount != state.n) mirror!!.pullAll() else mirror!!.pullCell(e.freeParticle(id))
            return id
        }
        var id = -1
        mirrored({ check(it.addFree(x, y, radius, vx, vy, mass, drag) == id) { "тень Rust: другой номер частицы" } }) {
            if (freeStackN == 0) growFreePool()
            id = freeStack[--freeStackN]
            state.activateFree(freeParticle(id), x, y, vx, vy, radius, mass, drag)
            freeLive[id] = true; freeCount++
        }
        return id
    }

    /**
     * Добавить НЕПОДВИЖНУЮ частицу (стенка мира, рельеф) в точку ([x], [y]) радиусом [radius]:
     * масса бесконечна — решатель её не двигает, контакт отдаёт всю поправку партнёру, гибели
     * нет (глубокое касание убивает только партнёра, как у кости), касание её не будит
     * (BodyState.isFixed). Слот и номер — из пула свободных частиц: убирается [removeFree],
     * в снимок входит вместе с ними. Цена в шаге — почти ноль: в широкой фазе она лежит в
     * неподвижной сетке, поштучные проходы пропускают её по нулю обратной массы, а при
     * включённом сне через SLEEP_TICKS она засыпает насовсем.
     */
    fun addFixed(x: Float, y: Float, radius: Float): Int = addFree(x, y, radius, mass = Float.POSITIVE_INFINITY)

    /** Убрать свободную частицу [id] (живую или убитую физикой): её слот снова ждёт рождения. */
    fun removeFree(id: Int) = mirrored({ it.removeFree(id) }, engine?.freeParticle(id) ?: PULL_NONE) {
        require(isFreeInUse(id)) { "свободной частицы $id нет" }
        // Слот займёт следующая частица — связи этой с ней уйти не должны.
        state.dropJointsOf(freeParticle(id))
        state.deactivateFree(freeParticle(id))
        freeLive[id] = false; freeCount--
        freeStack[freeStackN++] = id
    }

    /** Номер занят (addFree, ещё не removeFree). */
    fun isFreeInUse(id: Int): Boolean = engine?.isFreeInUse(id) ?: (id in 0 until freeSlots && freeLive[id])

    /**
     * Частица жива: номер занят, и физика её не убила (глубокий контакт, давление — как
     * клетку). Убитую клиент убирает removeFree, как и живую.
     */
    fun isFreeAlive(id: Int): Boolean = engine?.isFreeAlive(id) ?: (isFreeInUse(id) && !state.cellDead[freeParticle(id)])

    /** Частица мира свободной частицы [id]. */
    fun freeParticle(id: Int): Int {
        engine?.let { return it.freeParticle(id) }
        require(id in 0 until freeSlots) { "номера свободной частицы $id нет" }
        var lo = 0; var hi = freeBlockN - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (freeBlockStart[mid] <= id) lo = mid else hi = mid - 1 }
        return bodyFirstOf[freeBlockBody[lo]] + (id - freeBlockStart[lo])
    }

    /**
     * Пул вырос: новое тело из одиночных нерождённых клеток, вдвое больше прежнего пула
     * (не меньше FREE_POOL_BLOCK). Позы покоя слотов разнесены, чтобы между ними не
     * запеклось ни одной пары покоя; где слот лежит до рождения, не важно — он мёртв.
     */
    private fun growFreePool() {
        // Масштаб мира (пороги, массы) берётся из первого тела; у пула связей нет.
        require(state.n > 0 || state.worldScale != null) { "пустому миру со свободными частицами нужен WorldScale" }
        val k = maxOf(FREE_POOL_BLOCK, freeSlots)
        // Радиус слота — масштаба мира: по наибольшему радиусу считается ячейка сетки контактов.
        val r0 = 0.5f * (if (state.n > 0) state.linkScale else state.worldScale!!.meanLinkLength)
        val side = kotlin.math.ceil(kotlin.math.sqrt(k.toFloat())).toInt()
        val step = 8f * r0
        val def = BodyDefinition(
            x = FloatArray(k) { (it % side) * step }, y = FloatArray(k) { (it / side) * step },
            radius = FloatArray(k) { r0 }, isBone = BooleanArray(k), isMuscle = BooleanArray(k),
            linkA = IntArray(0), linkB = IntArray(0), triA = IntArray(0), triB = IntArray(0), triC = IntArray(0),
            triRestArea2 = FloatArray(0), boneClusters = emptyArray(), muscleClusters = emptyArray(),
            scale = if (state.n > 0) state.body.scale else 1f,
        )
        val body: Int
        if (state.n == 0) {
            // Пустой мир: пул — первое тело (загрузка целиком), слоты гасятся следом.
            body = addBody(def)
            for (i in 0 until k) state.deactivateFree(i, refresh = false)
            state.refreshSurface()
        } else {
            body = addBody(def, BooleanArray(k))
        }
        if (freeBlockN == freeBlockBody.size) {
            freeBlockBody = freeBlockBody.copyOf(freeBlockN * 2); freeBlockStart = freeBlockStart.copyOf(freeBlockN * 2)
        }
        freeBlockBody[freeBlockN] = body; freeBlockStart[freeBlockN] = freeSlots; freeBlockN++
        val total = freeSlots + k
        freeLive = freeLive.copyOf(total)
        freeStack = freeStack.copyOf(total)
        for (id in total - 1 downTo freeSlots) freeStack[freeStackN++] = id
        freeSlots = total
    }

    // --- снимок, этап A4 ---

    /**
     * Записать ПОЛНЫЙ СНИМОК мира — всё, что влияет на следующие тики (WorldSnapshot.kt).
     * Зовётся между тиками. Сначала мир уплотняется: производное (контур, контакты с
     * широкой фазой, блоки решателя) пересобирается так же, как при чтении снимка, —
     * поэтому и пишущий мир после записи идёт по этому каноническому производному.
     * Загруженный из снимка мир (read) дальше побитово совпадает с этим.
     */
    fun write(out: java.io.DataOutput) {
        // Движок Rust пишет снимок сам (те же байты, что Kotlin) и уплотняет мир — зеркало целиком.
        engine?.let { e ->
            require(state.n > 0) { "пустой мир не пишется" }
            out.write(e.write(materialKey())); mirror!!.pullAll(); return
        }
        val sh = shadow
        if (sh == null || shadowDepth > 0) { writeKotlin(out); return }
        // Тень пишет свой снимок (с тем же уплотнением); байты обязаны совпасть.
        val buf = java.io.ByteArrayOutputStream()
        sh.syncParams(state)
        RustShadow.prepare(state, sh, shadowTick)
        shadowDepth++
        try { writeKotlin(java.io.DataOutputStream(buf)) } finally { shadowDepth-- }
        val bytes = buf.toByteArray()
        check(bytes.contentEquals(sh.write(materialKey()))) { "тень Rust: снимок мира отличается от Kotlin" }
        RustShadow.check(state, sh, shadowTick, "write")
        out.write(bytes)
    }

    private fun writeKotlin(out: java.io.DataOutput) {
        require(state.n > 0) { "пустой мир не пишется" }
        compact()     // и журналы событий: номера частиц в них — до уплотнения
        out.writeInt(SNAPSHOT_MAGIC); out.writeInt(SNAPSHOT_VERSION)
        out.writeUTF(materialKey())
        val scale = state.worldScale!!
        out.writeInt(java.lang.Float.floatToRawIntBits(scale.meanLinkLength))
        out.writeLong(java.lang.Double.doubleToRawLongBits(scale.meanCellArea.toDouble()))
        out.writeDefinition(state.body)
        out.writeInt(bodyIds)
        out.writeInts(bodyFirstOf, bodyIds); out.writeInts(bodyCountOf, bodyIds)
        out.writeInts(bodyMuscleFirstOf, bodyIds); out.writeInts(bodyMuscleCountOf, bodyIds)
        out.writeBytes(bodyStateOf, bodyIds)
        out.writeInt(freeBlockN); out.writeInts(freeBlockBody, freeBlockN); out.writeInts(freeBlockStart, freeBlockN)
        out.writeInt(freeSlots); out.writeInt(freeCount)
        out.writeInts(freeStack, freeStackN); out.writeBooleans(freeLive, freeSlots)
        state.writeCore(out)
    }

    /**
     * Прочитать снимок (write) в НОВЫЙ мир с тем же материалом (PhysicsConfig.snapshot):
     * постоянные мира, тела, свободные частицы и всё состояние — как в мире, который
     * снимок записал. Мир до чтения пуст.
     */
    fun read(input: java.io.DataInput) {
        engine?.let { e ->
            // Где снимок кончается, знает только разбор: его делает временный мир Kotlin (загрузка карты — редкое
            // событие), байты — миру Rust. Тот же приём, что у тени (RustShadow.RecordingInput).
            val rec = RustShadow.RecordingInput(input)
            PhysicsWorld(config, workers = null, scale = state.worldScale, kotlinOnly = true).readKotlin(rec)
            e.read(rec.bytes.toByteArray(), materialKey())
            mirror!!.pullAll()
            return
        }
        val sh = shadow
        if (sh == null || shadowDepth > 0) { readKotlin(input); return }
        // Тень читает те же байты: Kotlin читает через запоминающую обёртку.
        val rec = RustShadow.RecordingInput(input)
        shadowDepth++
        try { readKotlin(rec) } finally { shadowDepth-- }
        sh.read(rec.bytes.toByteArray(), materialKey())
        RustShadow.check(state, sh, shadowTick, "read")
    }

    private fun readKotlin(input: java.io.DataInput) {
        require(state.n == 0) { "снимок читается в новый мир" }
        require(input.readInt() == SNAPSHOT_MAGIC) { "это не снимок мира физики" }
        val version = input.readInt()
        require(version in SNAPSHOT_OLDEST_READABLE..SNAPSHOT_VERSION) {
            "снимок версии $version, мир читает $SNAPSHOT_OLDEST_READABLE..$SNAPSHOT_VERSION"
        }
        val material = input.readUTF()
        require(material == materialKey()) { "снимок записан с другим материалом мира" }
        state.worldScale = WorldScale(java.lang.Float.intBitsToFloat(input.readInt()),
            java.lang.Double.longBitsToDouble(input.readLong()).toFloat())
        simulation.load(input.readDefinition(version))
        bodyIds = input.readInt()
        bodyFirstOf = input.readInts(8); bodyCountOf = input.readInts(8)
        bodyMuscleFirstOf = input.readInts(8); bodyMuscleCountOf = input.readInts(8)
        bodyStateOf = input.readBytes(8)
        removedParticles = 0
        freeBlockN = input.readInt()
        freeBlockBody = input.readInts(4); freeBlockStart = input.readInts(4)
        freeSlots = input.readInt(); freeCount = input.readInt()
        freeStack = input.readInts(0).let { freeStackN = it.size; it.copyOf(freeSlots) }
        freeLive = input.readBooleans(freeSlots)
        state.readCore(input, version)
    }

    /** Отпечаток материала: снимок читается только миром с теми же параметрами решателя. */
    internal fun materialKey(): String = config.snapshot().entries.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Один тик: мышцы доезжают к своим целям за [dt], новорождённая ткань — на шаг к своим
     * длинам покоя, затем шаг решателя (внутри — SUBSTEPS подшагов). [dt] другой, чем DT, — только у стендов с ускоренными мышцами.
     * Пустой мир (клиент-игра до первой подсадки) не шагает: решатель ещё не заведён.
     */
    fun step(dt: Float = DT) {
        if (state.n == 0) return
        engine?.let { e ->
            // Движок Rust: в мир — то, что мост написал прямо в зеркало; шаг; зеркало — целиком. Параметры решателя
            // (syncParams) не переносятся: игра меняет их только методами мира, а они уже дошли до Rust.
            mirror!!.pushInputs()
            e.step(dt)
            mirror.pullAll()
            return
        }
        // Тень получает параметры, которые стенд мог поменять прямо в state, ДО шага Kotlin: оба шага идут от одних входов.
        val sh = if (shadowDepth == 0 && !rustTwinSeparateStep) shadow else null
        if (sh != null) { sh.syncParams(state); RustShadow.prepare(state, sh, shadowTick) }
        state.clearDeathLogs()
        state.updateMuscles(dt)
        state.tick0()
        if (state.cellScalesMoving) state.advanceCellScales(dt)
        state.tick1(21)
        state.easeNewborn()
        state.prepareJoints()
        if (state.cellScalesDirty) state.recomputeCellScales()
        simulation.step()
        if (state.boundsOn) state.clampToBounds()
        if (sh != null) {
            sh.step(dt)
            RustShadow.check(state, sh, ++shadowTick, "step")
        }
    }

    /**
     * Шаг решателя без движения мышц: активации остаются, где были. Для стендов, которые
     * собирают сцену сами (раскол тела в DemoScenes), — игре не нужен.
     */
    fun stepSolverOnly() {
        engine?.let { e -> mirror!!.pushInputs(); e.stepSolverOnly(); mirror.pullAll(); return }
        val sh = if (shadowDepth == 0) shadow else null
        if (sh != null) { sh.syncParams(state); RustShadow.prepare(state, sh, shadowTick) }
        simulation.step()
        if (sh != null) {
            sh.stepSolverOnly()
            RustShadow.check(state, sh, ++shadowTick, "stepSolverOnly")
        }
    }

    /**
     * Тик ЭТАЛОННОГО решателя вместо быстрого (ReferenceSolver): те же входы тика — мышцы, рост,
     * временные связи, — затем свой простой конвейер на [substeps] подшагов. Мир для эталона
     * собирается с выключенным сном. Игре не нужен: им стенды сверяют быстрый решатель.
     */
    fun stepReference(substeps: Int = REFERENCE_SUBSTEPS) {
        check(engine == null) { "эталонного решателя в Rust нет (RUST_PORT.md): движок Rust — только для игры" }
        // Эталонный решатель — инструмент стендов, в Rust не переносится (RUST_PORT.md): мир дальше идёт без тени,
        // и итог прогона это называет (RustShadow.detach).
        if (shadow != null) { RustShadow.detach("stepReference"); shadow = null }
        if (state.n == 0) return
        state.clearDeathLogs()
        state.updateMuscles(DT)
        if (state.cellScalesMoving) state.advanceCellScales(DT)
        state.easeNewborn()
        state.prepareJoints()
        if (state.cellScalesDirty) state.recomputeCellScales()
        val solver = reference?.takeIf { it.substeps == substeps } ?: ReferenceSolver(state, substeps).also { reference = it }
        solver.tick()
    }

    /** Эталонный решатель этого мира: заводится первым вызовом [stepReference]; null — мир шагает быстрым. */
    var reference: ReferenceSolver? = null
        private set

    // --- входы тика ---

    val muscleCount: Int get() = state.muscleTarget.size

    /** Цель сокращения мышцы [m]: 0 — расслаблена, 1 — сокращена полностью. */
    fun setMuscleTarget(m: Int, value: Float) = mirrored({ it.setMuscleTarget(m, value) }, PULL_NONE) { state.muscleTarget[m] = value }
    fun muscleTarget(m: Int): Float = state.muscleTarget[m]
    fun setAllMuscleTargets(value: Float) = mirrored({ it.setAllMuscleTargets(value) }, PULL_NONE) { state.muscleTarget.fill(value) }

    /**
     * Команда мышцы клетки [cell]: 0.5 — сократить длину покоя вдвое, 1 — покой,
     * 1.5 — максимальная податливость на растяжение под внешней нагрузкой.
     * Действующее значение плавно подходит к цели в advanceCellScales.
     * Изменённая цель будит организм, как мышечный кластер (wakeByInput): спящее тело иначе
     * так и стояло бы с прежними длинами.
     */
    fun setCellScale(cell: Int, command: Float) = mirrored({ it.setCellScale(cell, command) }, cell) {
        require(command > 0f) { "множитель клетки должен быть больше нуля" }
        val scale = command
        if (state.cellScaleTarget[cell] != scale) {
            state.cellScaleTarget[cell] = scale
            state.cellScalesOn = true
            state.sleep.requestWake(cell)
        }
        if (state.cellScale[cell] != scale) state.cellScalesMoving = true
    }

    /** Действующая команда мышцы клетки после сглаживания цели setCellScale. */
    fun cellScale(cell: Int): Float = state.cellScale[cell]

    /**
     * Мягкость ткани клетки [cell]: множитель податливости её связей и треугольников (1 —
     * обычная ткань, 2.5 — в 2.5 раза мягче; связь берёт среднее концов — см.
     * BodyState.cellSoftness). Длины покоя не меняются, поэтому спящее тело не будится. Первый
     * вызов с не-единицей включает чтение множителей в ядрах — один полный пересчёт на мир;
     * дальше — кольцо клетки.
     */
    fun setSoftness(cell: Int, softness: Float) = mirrored({ it.setSoftness(cell, softness) }, cell) {
        require(softness > 0f) { "мягкость клетки должна быть больше нуля" }
        val st = state
        if (st.cellSoftness[cell] == softness) return@mirrored
        st.cellSoftness[cell] = softness
        if (!st.softnessOn) { st.softnessOn = true; st.cellScalesDirty = true }
        else st.refreshCellScaleRing(cell)
    }

    fun softness(cell: Int): Float = state.cellSoftness[cell]

    /**
     * Волокно мышцы клетки [cell] (BodyState.cellFiberX; игра — направление мотонейрона кластера,
     * стенды — длинная ось куска мышц): единичное направление ([x], [y]) в позе покоя тела, (0, 0) —
     * мышца сжимается по площади, во все стороны. Закон — config/FiberParameters.kt: сжатие s < 1 —
     * вдоль волокна × s, поперёк × s^−k (k — настройка мира FIBER_THICKEN, по умолчанию 0: поперёк
     * ткань держит размер), площадь × s^(1−k); расслабление s > 1 — мягче только вдоль волокна, запас
     * на разрыв — у всей мышцы. Длины
     * покоя меняются только у сокращённой мышцы, поэтому тело не будится. Первое волокно включает
     * его в счёт множителей — один полный пересчёт на мир; дальше — связи вокруг клетки и её соседей
     * по кольцу. Игра задаёт волокна раз, при подсадке тела, стенды — при постановке тела.
     */
    fun setCellFiber(cell: Int, x: Float, y: Float) = mirrored({ it.setCellFiber(cell, x, y) }, PULL_NONE) {
        val st = state
        if (st.cellFiberX[cell] == x && st.cellFiberY[cell] == y) return@mirrored
        require((x == 0f && y == 0f) || kotlin.math.abs(x * x + y * y - 1f) < 1e-3f) { "волокно — единичный вектор или ноль" }
        st.cellFiberX[cell] = x
        st.cellFiberY[cell] = y
        if (!st.fibersOn) { st.fibersOn = true; st.cellScalesDirty = true }
        else st.refreshCellFiberRing(cell)
    }

    fun cellFiberX(cell: Int): Float = state.cellFiberX[cell]
    fun cellFiberY(cell: Int): Float = state.cellFiberY[cell]

    /**
     * Клетка [cell] без касаний ([on]): ни с чем не сталкивается — ни с чужими телами, ни с едой,
     * ни со стенкой (BodyState.cellNoContact; игра — присоска, isCollidable = false). Связи и среда
     * как у всех. Цена смены — обновление набора касания по одной клетке; без смены — ничего.
     */
    fun setContactless(cell: Int, on: Boolean) = mirrored({ it.setContactless(cell, on) }, cell) {
        val st = state
        if (st.cellNoContact[cell] == on) return@mirrored
        st.cellNoContact[cell] = on
        st.baked.touch(cell)
        st.refreshSurface()
    }

    fun isContactless(cell: Int): Boolean = state.cellNoContact[cell]

    /**
     * Хватка частицы [cell] — трение о дно 0..1 (Grip.kt; игра — присоска-чашка): доля смещения,
     * которую частица теряет за подшаг. 1 — сдвинуть нельзя ничем, 0.95 — очень тяжело, 0 — нет.
     * Цена смены — пересборка списка проходом по миру при ближайшем подшаге; без смены — ничего.
     */
    fun setGrip(cell: Int, grip: Float) = mirrored({ it.setGrip(cell, grip) }, cell) {
        val g = grip.coerceIn(0f, 1f)
        val st = state
        if (st.cellGrip[cell] == g) return@mirrored
        st.cellGrip[cell] = g
        st.gripsDirty = true
    }

    fun grip(cell: Int): Float = state.cellGrip[cell]

    /**
     * Пружина частицы [cell] к точке ([x], [y]) с податливостью [compliance] (0 — снять): мягкое
     * ограничение к неподвижному месту, решается вместе с хваткой (Grip.kt). Террейн: точка на
     * карте, плитка проминается и возвращается в форму. Внешняя сила, как пол.
     */
    fun setHome(cell: Int, x: Float, y: Float, compliance: Float) = mirrored({ it.setHome(cell, x, y, compliance) }, PULL_NONE) {
        require(compliance >= 0.0f) { "податливость не меньше нуля" }
        val st = state
        st.cellHomeX[cell] = x; st.cellHomeY[cell] = y
        if (st.cellHomeCompliance[cell] != compliance) { st.cellHomeCompliance[cell] = compliance; st.gripsDirty = true }
    }

    /**
     * Группа касаний частицы [cell] ([group], 0 — без группы): частицы одной ненулевой группы не
     * сталкиваются (BodyState.cellGroup; террейн — плитки одной группы, их держат швы). Цена смены —
     * пересборка широкой фазы при ближайшем подшаге.
     */
    fun setContactGroup(cell: Int, group: Int) = mirrored({ it.setContactGroup(cell, group) }, PULL_NONE) {
        val st = state
        if (st.cellGroup[cell] == group) return@mirrored
        st.cellGroup[cell] = group
        st.contacts?.invalidateBroadphase()
    }

    // --- временные связи между телами (липучка, Joints.kt) ---

    /**
     * Связать частицы [a] и [b] (разных тел; клетка, свободная частица, неподвижная) мягким
     * ограничением расстояния: длина покоя [rest], податливость [compliance] (как SOFT_COMPLIANCE
     * у связи тела; больше — мягче). Номер — слот, стабилен до [removeJoint]. Обе частицы живы и
     * рождены. Будит спящий конец. Связь с погибшей частицей снимается сама при шаге мира.
     * [wakeStrain] — порог пробуждения соседа (доля длины покоя): 0 — спящий конец будится, как
     * только другой не спит (липучка); больше нуля — только когда связь растянута или сжата сверх
     * порога (шов террейна, Joints.prepareJoints). [wake] = false — концы не будить: связь заведена
     * в покое (шов террейна при сборке мира, который тут же усыпляется — PhysicsWorld.sleepNow).
     */
    fun addJoint(a: Int, b: Int, rest: Float, compliance: Float, wakeStrain: Float = 0.0f, wake: Boolean = true): Int {
        engine?.let { return it.addJoint(a, b, rest, compliance, wakeStrain, wake) }
        var id = -1
        mirrored({ check(it.addJoint(a, b, rest, compliance, wakeStrain, wake) == id) { "тень Rust: другой номер связи" } }) {
            val st = state
            require(a != b && !st.cellDead[a] && !st.cellDead[b]) { "связь — между двумя живыми частицами" }
            require(rest >= 0.0f && compliance >= 0.0f) { "длина покоя и податливость не меньше нуля" }
            if (wake && !st.isFixed(a)) st.sleep.requestWake(a)
            if (wake && !st.isFixed(b)) st.sleep.requestWake(b)
            id = st.allocJoint(a, b, rest, compliance, wakeStrain)
        }
        return id
    }

    fun removeJoint(id: Int) = mirrored({ it.removeJoint(id) }, PULL_NONE) { state.freeJoint(id) }

    /** Связь [id] жива: заведена и не снята — ни клиентом, ни гибелью конца. */
    fun isJointLive(id: Int): Boolean = engine?.isJointLive(id) ?: (id in 0 until state.jointSlots && state.jointLive[id])

    fun setJointCompliance(id: Int, compliance: Float) =
        mirrored({ it.setJointCompliance(id, compliance) }, PULL_NONE) { state.jointCompliance[id] = compliance }

    /** Концы связи — номера частиц мира (уплотнение их перенумеровывает). */
    fun jointA(id: Int): Int = state.jointA[id]
    fun jointB(id: Int): Int = state.jointB[id]
    fun jointRest(id: Int): Float = state.jointRest[id]

    /** Нынешняя длина связи в единицах физики. Корень из суммы квадратов, а не hypot: см. StickyJoints. */
    fun jointLength(id: Int): Float {
        val a = state.jointA[id]; val b = state.jointB[id]
        val dx = x[a] - x[b]; val dy = y[a] - y[b]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * ДИАГНОСТИКА: сколько действующих множителей клеток, связей и треугольников расходится с
     * полным пересчётом (их ведут по месту — шаг мышцы, рождение, гибель ребра). 0 — пересчёт
     * по месту ничего не пропустил. Значения остаются полного пересчёта.
     */
    fun cellScaleMismatches(): Int {
        // Проверка не только считает, но и оставляет мир полного пересчёта — тень делает тот же пересчёт.
        var bad = 0
        mirrored({ it.recomputeCellScalesLikeCheck() }, PULL_NONE) { bad = cellScaleMismatchesKotlin() }
        return bad
    }

    private fun cellScaleMismatchesKotlin(): Int {
        val st = state
        if (!(st.cellScalesOn || st.softnessOn) || st.cellScaleEff.size != st.n) return 0
        val eff = st.cellScaleEff.copyOf(); val con = st.conCellScale.copyOf(st.conCount); val tri = st.triCellScale.copyOf(st.triCount)
        val conSoft = st.conSoftness.copyOf(st.conCount); val triSoft = st.triSoftness.copyOf(st.triCount)
        st.recomputeCellScales()
        var bad = 0
        for (v in 0 until st.n) if (eff[v] != st.cellScaleEff[v]) bad++
        for (c in 0 until st.conCount) if (con[c] != st.conCellScale[c]) bad++
        for (t in 0 until st.triCount) if (tri[t] != st.triCellScale[t]) bad++
        if (st.softnessOn) {
            for (c in 0 until st.conCount) if (conSoft[c] != st.conSoftness[c]) bad++
            for (t in 0 until st.triCount) if (triSoft[t] != st.triSoftness[t]) bad++
        }
        return bad
    }

    /**
     * ДИАГНОСТИКА: сколько рёбер контура расходится с топологией. Край по топологии — живое ребро
     * не больше чем с одним живым треугольником; рабочий список контура (boundA/boundB — по нему
     * сопротивление среды, ориентация и контакты) ведут по месту. Лишние в списке плюс недостающие;
     * 0 — список верный.
     */
    fun boundaryMismatches(): Int {
        val st = state
        val b = st.baked
        val listed = BooleanArray(b.edgeCount)
        var bad = 0
        for (k in 0 until st.boundCount) {
            val e = b.edgeOf(st.boundA[k], st.boundB[k])
            if (e < 0 || listed[e] || b.edgeDead[e] || b.triUse[e] > 1) { bad++; continue }
            listed[e] = true
        }
        for (e in 0 until b.edgeCount) if (!listed[e] && !b.edgeDead[e] && b.triUse[e] <= 1) bad++
        return bad
    }

    /**
     * Масса клетки [cell] (по умолчанию — по радиусу, как у всех клеток мира). У живой клетки
     * масса её организма пересчитывается сразу; у нерождённой — вступит при рождении.
     */
    fun setMass(cell: Int, mass: Float) = mirrored({ it.setMass(cell, mass) }, PULL_NONE) {
        require(mass > 0.0f) { "масса должна быть больше нуля" }
        val st = state
        val alive = !st.cellDead[cell]
        if (alive) st.localComponents.removeCell(cell)
        st.restInvMass[cell] = 1.0f / mass
        if (alive) { st.invMass[cell] = st.restInvMass[cell]; st.localComponents.addCell(cell) }
    }

    /** Множитель изотропного сопротивления среды частицы [cell]: 1 — как среда мира. */
    fun setDrag(cell: Int, scale: Float) = mirrored({ it.setDrag(cell, scale) }, PULL_NONE) {
        require(scale >= 0.0f) { "множитель сопротивления не может быть меньше нуля" }
        state.dragScale[cell] = scale
    }

    /**
     * Глубокое проникновение (центр внутри чужого радиуса, KILL_ON_DEEP_OVERLAP) убивает обе
     * клетки. Выключает клиент для отладки (игра — галочка «без смерти от проникновения»):
     * касание тогда только расталкивает. В снимок не входит — клиент выставляет каждый тик.
     */
    var deepKillOn: Boolean
        get() = if (engine != null) engineDeepKillOn else state.deepKillOn
        set(value) {
            engineDeepKillOn = value
            mirrored({ it.setDeepKillOn(value) }, PULL_NONE) { state.deepKillOn = value; state.contacts?.killOnDeep = value }
        }

    /**
     * Сколько живых связей у клетки [cell]: 0 — одиночная (зигота без соседей, оторвавшаяся,
     * свободная частица). Рёбер контура у такой нет, и сопротивление среды ей даёт только
     * изотропная часть — его клиент задаёт [setDrag].
     */
    fun liveDegree(cell: Int): Int {
        engine?.let { return it.liveDegree(cell) }
        val b = state.baked
        var d = 0
        for (q in b.vertStart[cell] until b.vertStart[cell + 1]) if (!b.edgeDead[b.vertEdge[q]]) d++
        return d
    }

    /** Соседи клетки [cell] по описанию тела — с живыми и погибшими связями: [neighbor] k < [neighborCount]. */
    fun neighborCount(cell: Int): Int =
        engine?.neighborCount(cell) ?: (state.baked.vertStart[cell + 1] - state.baked.vertStart[cell])
    fun neighbor(cell: Int, k: Int): Int = engine?.neighbor(cell, k) ?: state.baked.vertOther[state.baked.vertStart[cell] + k]

    /**
     * СРЕДА МИРА от клиента (игра: «Настройки мира»), раз в тик до шага, как [deepKillOn]: в снимок
     * не входит. [gravityY] — гравитация по y, единиц мира в с²; остальное — множители к материалу
     * (1 — как подбирали на стендах; тогда счёт побитово прежний, см. WorldEnvironment):
     * [mediumScale] — изотропная среда, [edgeDragScale] — сопротивление рёбер контура (тяга и
     * торможение гребка), [softness] — податливость связей и площадей ткани, [frictionScale] —
     * кулоново трение касания (CONTACT_FRICTION).
     *
     * Сменилась гравитация — спящие просыпаются: их покой держался без неё ([wakeAll]).
     */
    fun setEnvironment(gravityY: Float, mediumScale: Float, edgeDragScale: Float, softness: Float, frictionScale: Float) =
        mirrored({ it.setEnvironment(gravityY, mediumScale, edgeDragScale, softness, frictionScale) }, PULL_NONE) {
        require(mediumScale >= 0.0f && edgeDragScale >= 0.0f && softness > 0.0f && frictionScale >= 0.0f) { "среда мира вне допустимого" }
        val env = state.environment
        if (env.gravityY != gravityY) { env.gravityY = gravityY; wakeAll() }
        env.mediumDrag = MEDIUM_DRAG * mediumScale
        env.normalDrag = NORMAL_DRAG * edgeDragScale
        env.normalDragQuadratic = NORMAL_DRAG_QUADRATIC * edgeDragScale
        env.softCompliance = SOFT_COMPLIANCE * softness
        env.areaCompliance = AREA_COMPLIANCE * softness
        val friction = CONTACT_FRICTION * frictionScale
        env.contactFriction = friction
        state.contacts?.friction = friction
    }

    /**
     * Разбудить все спящие организмы (в начале следующего подшага). Неподвижные — стенку и
     * террейн (обратная масса покоя 0) — не трогает: им двигаться некуда, а проснувшись, они
     * SLEEP_TICKS тиков стояли бы в проходах по бодрствующим. Проход по организмам; у спящего
     * диапазон частиц может быть шире настоящего (BodySleep.first), поэтому берётся первая частица
     * именно этого организма. Для редкого события клиента (сменилась гравитация), не для тика.
     */
    fun wakeAll() = mirrored({ it.wakeAll() }, PULL_NONE) {
        val st = state
        val s = st.sleep
        if (s.asleepCount == 0) return@mirrored
        for (o in 0 until st.organismCount) {
            if (!s.asleep[o]) continue
            for (i in s.first[o]..s.last[o]) {
                if (st.organismOf[i] != o) continue
                if (st.restInvMass[i] > 0.0f) s.requestWake(i)
                break
            }
        }
    }

    /** Рамка мира: частицы не выходят за прямоугольник (игра — границы сетки). */
    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) = mirrored({ it.setBounds(minX, minY, maxX, maxY) }, PULL_NONE) {
        require(maxX > minX && maxY > minY) { "пустая рамка мира" }
        val st = state
        st.boundsOn = true; st.boundsMinX = minX; st.boundsMinY = minY; st.boundsMaxX = maxX; st.boundsMaxY = maxY
    }

    fun clearBounds() = mirrored({ it.clearBounds() }, PULL_NONE) { state.boundsOn = false }

    /**
     * Убить клетку [cell] по причине клиента (голод, поедание): связи гаснут, организм
     * делится, клетка замерзает и выходит из контактов. Зовётся между тиками.
     */
    fun kill(cell: Int) = mirrored({ it.kill(cell) }, cell) { state.killCell(cell) }

    /** Схваченная клетка или -1. */
    val grabbed: Int get() = state.dragId

    /** Схватить клетку: она тянется к точке [dragTo], её вес в позе кости — DRAG_MATCH_WEIGHT. */
    fun grab(cell: Int) = mirrored({ it.grab(cell) }, PULL_NONE) {
        state.dragId = cell
        state.matchWeight[cell] = DRAG_MATCH_WEIGHT
    }

    /** Куда тянуть схваченную клетку. */
    fun dragTo(x: Float, y: Float) = mirrored({ it.dragTo(x, y) }, PULL_NONE) { state.mouseX = x; state.mouseY = y }

    /** Отпустить схваченную клетку. */
    fun release() = mirrored({ it.release() }, PULL_NONE) {
        val i = state.dragId
        if (i < 0) return@mirrored
        state.matchWeight[i] = state.restMatchWeight(i)
        state.dragId = -1
    }

    /**
     * Назначить скорость частице мимо решателя (удар, выстрел). Спящий организм такую
     * скорость обнулит — разбудить его [wake] (одного вызова на организм достаточно).
     */
    fun setVelocity(i: Int, vx: Float, vy: Float) = mirrored({ it.setVelocity(i, vx, vy) }, i) { state.vx[i] = vx; state.vy[i] = vy }

    /** Разовая тяга за точку тела. Кость получает общий импульс и момент в точке приложения. */
    fun pullToward(i: Int, x: Float, y: Float) = mirrored({ it.pullToward(i, x, y) }, i) {
        wake(i)
        state.pullToward(i, x, y, DT)
    }

    /** Переставить частицу в точку без скорости от переноса (prev там же). */
    fun place(i: Int, x: Float, y: Float) = mirrored({ it.place(i, x, y) }, i) {
        state.px[i] = x; state.py[i] = y
        state.prevX[i] = x; state.prevY[i] = y
    }

    /** Разбудить организм частицы [i] в начале следующего подшага. */
    fun wake(i: Int) = mirrored({ it.wake(i) }, PULL_NONE) { state.sleep.requestWake(i) }

    /**
     * Усыпить организм частицы [i] сейчас (BodyState.sleepNow): клиент знает, что он в покое по
     * построению (террейн при сборке мира). Между тиками; при выключенном сне — ничего.
     */
    fun sleepNow(i: Int) = mirrored({ it.sleepNow(i) }, PULL_NONE) { state.sleepNow(i) }

    /** Сон тел: спящие организмы заморожены и пропускаются решателем. */
    var sleepOn: Boolean
        get() = if (engine != null) engineSleepOn else state.sleep.on
        set(value) {
            engineSleepOn = value
            mirrored({ it.setSleepOn(value) }, PULL_NONE) { state.setSleepOn(value) }
        }

    // --- выходы ---

    val particleCount: Int get() = state.n
    val x: FloatArray get() = state.px
    val y: FloatArray get() = state.py
    val vx: FloatArray get() = state.vx
    val vy: FloatArray get() = state.vy
    val organismOf: IntArray get() = state.organismOf
    val organismCount: Int get() = state.organismCount
    val meanLinkLength: Float get() = state.linkScale
}
