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

/**
 * Наименьший блок пула свободных частиц (PhysicsWorld.addFree). Следующий блок — размером со
 * весь прежний пул: дописываний тела-пула логарифм от числа частиц. Нерождённый слот стоит
 * памяти частицы, но не времени решателя.
 */
const val FREE_POOL_BLOCK = 256

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
        // Тела загружаются целыми: временных связей роста у них нет (TempLinks.kt).
        simulation.load(definition.forBorn(null))
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
        // Временные связи роста, которые рождённые клетки уже сняли бы, в мир не идут: взрослое тело — без них (TempLinks.kt).
        val definition = body.forBorn(born)
        if (state.n == 0) {
            if (born == null && boneOn == null) { load(definition); reset(); return 0 }
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
        compactions++
        state.clearDeathLogs()
    }

    /**
     * Сколько раз мир уплотнялся: после уплотнения номера частиц сдвинуты, и клиент, который
     * держит их у себя (игра: ParticleEntity.physicsIndex), пересчитывает их по номерам тел.
     */
    var compactions = 0
        private set

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

    /**
     * Родить клетку делением живой клетки [parent] того же тела: почти в родителе, дальше её
     * выталкивают дорастающие связи (BodyState.divisionPlacement, DIVISION_START_FRACTION).
     */
    fun activateFromParent(cell: Int, parent: Int) {
        val p = DoubleArray(4)
        state.divisionPlacement(cell, parent, p)
        state.activateCell(cell, p[0], p[1], p[2], p[3])
    }

    // --- события последнего шага (этап B2) ---

    /** Сколько рёбер погибло за последний шаг (разрыв, пробой, гибель клетки), в порядке гибели. */
    val edgeDeathCount: Int get() = state.edgeDeathN
    /** Концы [k]-го погибшего ребра — частицы мира. */
    fun edgeDeathA(k: Int): Int = state.edgeDeathLog[2 * k]
    fun edgeDeathB(k: Int): Int = state.edgeDeathLog[2 * k + 1]
    /** Сколько клеток погибло за последний шаг и какие ([cellDeath]). */
    val cellDeathCount: Int get() = state.cellDeathN
    fun cellDeath(k: Int): Int = state.cellDeathLog[k]

    /**
     * Рёбра контура — то, что омывается средой (края дыр и голые связи тоже), как зелёная граница
     * стенда. Для отладочной отрисовки клиента; концы [k]-го ребра — частицы мира.
     */
    val boundaryEdgeCount: Int get() = state.boundCount
    fun boundaryEdgeA(k: Int): Int = state.boundA[k]
    fun boundaryEdgeB(k: Int): Int = state.boundB[k]

    /**
     * Сталкивается ли клетка [i]: живая, и у неё есть ребро контура (живое ребро не больше чем
     * в одном живом треугольнике), щель в веере треугольников или она одна. Тот же набор, по
     * которому строятся касания мира физики. Игра берёт отсюда «клетка на краю» (сетка касаний).
     */
    fun isSurfaceCell(i: Int): Boolean = state.localSurface.active[i]

    /**
     * Клетки, у которых [isSurfaceCell] сменился с прошлого [clearSurfaceChanges], — номерами
     * частиц, каждая не больше раза. Пишут и шаг, и рождение или гибель между шагами. После
     * уплотнения ([compactions]) и загрузки снимка журнал начат заново — сверять все клетки.
     */
    val surfaceChangeCount: Int get() = state.localSurface.activeChangedN
    fun surfaceChange(k: Int): Int = state.localSurface.activeChanged[k]
    fun clearSurfaceChanges() = state.localSurface.clearActiveChanged()

    /**
     * Сделать клетку [cell] костью (клиент-игра: мутация в кость; тело дописано с boneOn): она
     * входит в свою кость описания, поза кости — нынешняя форма её включённых клеток. Без кости
     * в описании, мёртвой или уже включённой — ничего. Между тиками.
     * Новорождённая, которая ещё выходит из родителя (дорастают все её связи), костью не
     * становится — false, клиент повторяет вызов в следующих тиках. true — клетка теперь кость.
     */
    fun setBone(cell: Int): Boolean = state.activateBone(cell)

    /** Спит ли организм частицы [i] (BodySleep: заморожен до толчка). */
    fun isAsleep(i: Int): Boolean = state.sleep.isAsleep(state.organismOf[i])

    /** Включена ли кость клетки. */
    fun isBone(cell: Int): Boolean = state.boneOf[cell] >= 0

    /** Ждёт ли клетка рождения. */
    fun isUnborn(cell: Int): Boolean = state.cellUnborn[cell]

    /**
     * Вернуть тела мира к позе покоя, целыми, с нулевыми скоростями. Удалённые тела
     * остаются удалёнными: сначала мир уплотняется.
     */
    fun reset() {
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
    var freeCount = 0; private set

    /**
     * Добавить свободную частицу в точку ([x], [y]) со скоростью ([vx], [vy]), радиусом
     * [radius], массой [mass] (NaN — как у клетки того же радиуса) и множителем сопротивления
     * среды [drag] (1 — как среда мира). Возвращает номер
     * частицы. Цена — по соседям, кроме редкого роста пула (дописывание тела-пула).
     */
    fun addFree(x: Double, y: Double, radius: Double, vx: Double = 0.0, vy: Double = 0.0, mass: Double = Double.NaN,
                drag: Double = 1.0): Int {
        if (freeStackN == 0) growFreePool()
        val id = freeStack[--freeStackN]
        state.activateFree(freeParticle(id), x, y, vx, vy, radius, mass, drag)
        freeLive[id] = true; freeCount++
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
    fun addFixed(x: Double, y: Double, radius: Double): Int = addFree(x, y, radius, mass = Double.POSITIVE_INFINITY)

    /** Убрать свободную частицу [id] (живую или убитую физикой): её слот снова ждёт рождения. */
    fun removeFree(id: Int) {
        require(isFreeInUse(id)) { "свободной частицы $id нет" }
        // Слот займёт следующая частица — связи этой с ней уйти не должны.
        state.dropJointsOf(freeParticle(id))
        state.deactivateFree(freeParticle(id))
        freeLive[id] = false; freeCount--
        freeStack[freeStackN++] = id
    }

    /** Номер занят (addFree, ещё не removeFree). */
    fun isFreeInUse(id: Int): Boolean = id in 0 until freeSlots && freeLive[id]

    /**
     * Частица жива: номер занят, и физика её не убила (глубокий контакт, давление — как
     * клетку). Убитую клиент убирает removeFree, как и живую.
     */
    fun isFreeAlive(id: Int): Boolean = isFreeInUse(id) && !state.cellDead[freeParticle(id)]

    /** Частица мира свободной частицы [id]. */
    fun freeParticle(id: Int): Int {
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
        val side = kotlin.math.ceil(kotlin.math.sqrt(k.toDouble())).toInt()
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
        require(state.n > 0) { "пустой мир не пишется" }
        compact()     // и журналы событий: номера частиц в них — до уплотнения
        out.writeInt(SNAPSHOT_MAGIC); out.writeInt(SNAPSHOT_VERSION)
        out.writeUTF(materialKey())
        val scale = state.worldScale!!
        out.writeInt(java.lang.Float.floatToRawIntBits(scale.meanLinkLength))
        out.writeLong(java.lang.Double.doubleToRawLongBits(scale.meanCellArea))
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
        require(state.n == 0) { "снимок читается в новый мир" }
        require(input.readInt() == SNAPSHOT_MAGIC) { "это не снимок мира физики" }
        val version = input.readInt()
        require(version in SNAPSHOT_OLDEST_READABLE..SNAPSHOT_VERSION) {
            "снимок версии $version, мир читает $SNAPSHOT_OLDEST_READABLE..$SNAPSHOT_VERSION"
        }
        val material = input.readUTF()
        require(material == materialKey()) { "снимок записан с другим материалом мира" }
        state.worldScale = WorldScale(java.lang.Float.intBitsToFloat(input.readInt()),
            java.lang.Double.longBitsToDouble(input.readLong()))
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
    private fun materialKey(): String = config.snapshot().entries.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Один тик: мышцы доезжают к своим целям за [dt], новорождённая ткань — на шаг к своим
     * длинам покоя, затем шаг решателя (внутри — SUBSTEPS подшагов). [dt] другой, чем DT, — только у стендов с ускоренными мышцами.
     * Пустой мир (клиент-игра до первой подсадки) не шагает: решатель ещё не заведён.
     */
    fun step(dt: Double = DT) {
        if (state.n == 0) return
        state.clearDeathLogs()
        state.updateMuscles(dt)
        if (state.cellScalesMoving) state.advanceCellScales(dt)
        state.easeNewborn()
        state.prepareJoints()
        if (state.cellScalesDirty) state.recomputeCellScales()
        simulation.step()
        if (state.boundsOn) state.clampToBounds()
    }

    /**
     * Шаг решателя без движения мышц: активации остаются, где были. Для стендов, которые
     * собирают сцену сами (раскол тела в DemoScenes), — игре не нужен.
     */
    fun stepSolverOnly() = simulation.step()

    /**
     * Тик ЭТАЛОННОГО решателя вместо быстрого (ReferenceSolver): те же входы тика — мышцы, рост,
     * временные связи, — затем свой простой конвейер на [substeps] подшагов. Мир для эталона
     * собирается с выключенным сном. Игре не нужен: им стенды сверяют быстрый решатель.
     */
    fun stepReference(substeps: Int = REFERENCE_SUBSTEPS) {
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
    fun setMuscleTarget(m: Int, value: Double) { state.muscleTarget[m] = value }
    fun muscleTarget(m: Int): Double = state.muscleTarget[m]
    fun setAllMuscleTargets(value: Double) { state.muscleTarget.fill(value) }

    /**
     * Команда мышцы клетки [cell]: 0.5 — сократить длину покоя вдвое, 1 — покой,
     * 1.5 — максимальная податливость на растяжение под внешней нагрузкой.
     * Действующее значение плавно подходит к цели в advanceCellScales.
     * Изменённая цель будит организм, как мышечный кластер (wakeByInput): спящее тело иначе
     * так и стояло бы с прежними длинами.
     */
    fun setCellScale(cell: Int, command: Float) {
        require(command > 0f) { "множитель клетки должен быть больше нуля" }
        val scale = weakenedCommand(command) // ВРЕМЕННО (опыт 19.11.2026, MX_STRENGTH)
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
    fun setSoftness(cell: Int, softness: Float) {
        require(softness > 0f) { "мягкость клетки должна быть больше нуля" }
        val st = state
        if (st.cellSoftness[cell] == softness) return
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
    fun setCellFiber(cell: Int, x: Float, y: Float) {
        val st = state
        if (st.cellFiberX[cell] == x && st.cellFiberY[cell] == y) return
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
    fun setContactless(cell: Int, on: Boolean) {
        val st = state
        if (st.cellNoContact[cell] == on) return
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
    fun setGrip(cell: Int, grip: Float) {
        val g = grip.coerceIn(0f, 1f)
        val st = state
        if (st.cellGrip[cell] == g) return
        st.cellGrip[cell] = g
        st.gripsDirty = true
    }

    fun grip(cell: Int): Float = state.cellGrip[cell]

    /**
     * Пружина частицы [cell] к точке ([x], [y]) с податливостью [compliance] (0 — снять): мягкое
     * ограничение к неподвижному месту, решается вместе с хваткой (Grip.kt). Террейн: точка на
     * карте, плитка проминается и возвращается в форму. Внешняя сила, как пол.
     */
    fun setHome(cell: Int, x: Double, y: Double, compliance: Double) {
        require(compliance >= 0.0) { "податливость не меньше нуля" }
        val st = state
        st.cellHomeX[cell] = x; st.cellHomeY[cell] = y
        if (st.cellHomeCompliance[cell] != compliance) { st.cellHomeCompliance[cell] = compliance; st.gripsDirty = true }
    }

    /**
     * Группа касаний частицы [cell] ([group], 0 — без группы): частицы одной ненулевой группы не
     * сталкиваются (BodyState.cellGroup; террейн — плитки одной группы, их держат швы). Цена смены —
     * пересборка широкой фазы при ближайшем подшаге.
     */
    fun setContactGroup(cell: Int, group: Int) {
        val st = state
        if (st.cellGroup[cell] == group) return
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
    fun addJoint(a: Int, b: Int, rest: Double, compliance: Double, wakeStrain: Double = 0.0, wake: Boolean = true): Int {
        val st = state
        require(a != b && !st.cellDead[a] && !st.cellDead[b]) { "связь — между двумя живыми частицами" }
        require(rest >= 0.0 && compliance >= 0.0) { "длина покоя и податливость не меньше нуля" }
        if (wake && !st.isFixed(a)) st.sleep.requestWake(a)
        if (wake && !st.isFixed(b)) st.sleep.requestWake(b)
        return st.allocJoint(a, b, rest, compliance, wakeStrain)
    }

    fun removeJoint(id: Int) = state.freeJoint(id)

    /** Связь [id] жива: заведена и не снята — ни клиентом, ни гибелью конца. */
    fun isJointLive(id: Int): Boolean = id in 0 until state.jointSlots && state.jointLive[id]

    fun setJointCompliance(id: Int, compliance: Double) { state.jointCompliance[id] = compliance }

    /** Концы связи — номера частиц мира (уплотнение их перенумеровывает). */
    fun jointA(id: Int): Int = state.jointA[id]
    fun jointB(id: Int): Int = state.jointB[id]
    fun jointRest(id: Int): Double = state.jointRest[id]

    /** Нынешняя длина связи в единицах физики. */
    fun jointLength(id: Int): Double {
        val a = state.jointA[id]; val b = state.jointB[id]
        return kotlin.math.hypot(x[a] - x[b], y[a] - y[b])
    }

    /**
     * ДИАГНОСТИКА: сколько действующих множителей клеток, связей и треугольников расходится с
     * полным пересчётом (их ведут по месту — шаг мышцы, рождение, гибель ребра). 0 — пересчёт
     * по месту ничего не пропустил. Значения остаются полного пересчёта.
     */
    fun cellScaleMismatches(): Int {
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
    fun setMass(cell: Int, mass: Double) {
        require(mass > 0.0) { "масса должна быть больше нуля" }
        val st = state
        val alive = !st.cellDead[cell]
        if (alive) st.localComponents.removeCell(cell)
        st.restInvMass[cell] = 1.0 / mass
        if (alive) { st.invMass[cell] = st.restInvMass[cell]; st.localComponents.addCell(cell) }
    }

    /** Множитель изотропного сопротивления среды частицы [cell]: 1 — как среда мира. */
    fun setDrag(cell: Int, scale: Double) {
        require(scale >= 0.0) { "множитель сопротивления не может быть меньше нуля" }
        state.dragScale[cell] = scale
    }

    /**
     * Глубокое проникновение (центр внутри чужого радиуса, KILL_ON_DEEP_OVERLAP) убивает обе
     * клетки. Выключает клиент для отладки (игра — галочка «без смерти от проникновения»):
     * касание тогда только расталкивает. В снимок не входит — клиент выставляет каждый тик.
     */
    var deepKillOn: Boolean
        get() = state.deepKillOn
        set(value) { state.deepKillOn = value; state.contacts?.killOnDeep = value }

    /**
     * Сколько живых связей у клетки [cell]: 0 — одиночная (зигота без соседей, оторвавшаяся,
     * свободная частица). Рёбер контура у такой нет, и сопротивление среды ей даёт только
     * изотропная часть — его клиент задаёт [setDrag].
     */
    fun liveDegree(cell: Int): Int {
        val b = state.baked
        var d = 0
        for (q in b.vertStart[cell] until b.vertStart[cell + 1]) if (!b.edgeDead[b.vertEdge[q]]) d++
        return d
    }

    /** Соседи клетки [cell] по описанию тела — с живыми и погибшими связями: [neighbor] k < [neighborCount]. */
    fun neighborCount(cell: Int): Int = state.baked.vertStart[cell + 1] - state.baked.vertStart[cell]
    fun neighbor(cell: Int, k: Int): Int = state.baked.vertOther[state.baked.vertStart[cell] + k]

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
    fun setEnvironment(gravityY: Double, mediumScale: Double, edgeDragScale: Double, softness: Double, frictionScale: Double) {
        require(mediumScale >= 0.0 && edgeDragScale >= 0.0 && softness > 0.0 && frictionScale >= 0.0) { "среда мира вне допустимого" }
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
    fun wakeAll() {
        val st = state
        val s = st.sleep
        if (s.asleepCount == 0) return
        for (o in 0 until st.organismCount) {
            if (!s.asleep[o]) continue
            for (i in s.first[o]..s.last[o]) {
                if (st.organismOf[i] != o) continue
                if (st.restInvMass[i] > 0.0) s.requestWake(i)
                break
            }
        }
    }

    /** Рамка мира: частицы не выходят за прямоугольник (игра — границы сетки). */
    fun setBounds(minX: Double, minY: Double, maxX: Double, maxY: Double) {
        require(maxX > minX && maxY > minY) { "пустая рамка мира" }
        val st = state
        st.boundsOn = true; st.boundsMinX = minX; st.boundsMinY = minY; st.boundsMaxX = maxX; st.boundsMaxY = maxY
    }

    fun clearBounds() { state.boundsOn = false }

    /**
     * Убить клетку [cell] по причине клиента (голод, поедание): связи гаснут, организм
     * делится, клетка замерзает и выходит из контактов. Зовётся между тиками.
     */
    fun kill(cell: Int) = state.killCell(cell)

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
        state.matchWeight[i] = state.restMatchWeight(i)
        state.dragId = -1
    }

    /**
     * Назначить скорость частице мимо решателя (удар, выстрел). Спящий организм такую
     * скорость обнулит — разбудить его [wake] (одного вызова на организм достаточно).
     */
    fun setVelocity(i: Int, vx: Double, vy: Double) { state.vx[i] = vx; state.vy[i] = vy }

    /** Разовая тяга за точку тела. Кость получает общий импульс и момент в точке приложения. */
    fun pullToward(i: Int, x: Double, y: Double) {
        wake(i)
        state.pullToward(i, x, y, DT)
    }

    /** Переставить частицу в точку без скорости от переноса (prev там же). */
    fun place(i: Int, x: Double, y: Double) {
        state.px[i] = x; state.py[i] = y
        state.prevX[i] = x; state.prevY[i] = y
    }

    /** Разбудить организм частицы [i] в начале следующего подшага. */
    fun wake(i: Int) = state.sleep.requestWake(i)

    /**
     * Усыпить организм частицы [i] сейчас (BodyState.sleepNow): клиент знает, что он в покое по
     * построению (террейн при сборке мира). Между тиками; при выключенном сне — ничего.
     */
    fun sleepNow(i: Int) = state.sleepNow(i)

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
