package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** Все изменяемые массивы одного решателя. Порядок индексов не меняется при разделении.
 * На частицу px/py/invMass занимают 24 байта: 240 КБ на 10k и 2.46 МБ на 102400.
 * Целиком это больше L1/L2 одного ядра; обход ограничений должен сохранять небольшое
 * окно соседних индексов. Потоковые массивы связей лежат отдельно от этих обращений.
 * Открытые массивы нужны инспектору и адаптеру стенда, а не для перестановки между стадиями.
 */
class BodyState(val config: PhysicsConfig = PhysicsConfig()) : Cloneable {
    internal val boundaryLinkLimits = BoundaryLinkLimits()
    internal var constraintBlocks: ConstraintBlocks? = null
    val CONTACT_MAX_STEP = config.CONTACT_MAX_STEP
    val LINK_MAX_STEP = config.LINK_MAX_STEP
    val MAXLEN_EARLY = config.MAXLEN_EARLY
    val DRAG_ACCEL = config.DRAG_ACCEL
    val DRAG_SPEED_LIMIT = config.DRAG_SPEED_LIMIT
    val KILL_ON_DEEP_OVERLAP = config.KILL_ON_DEEP_OVERLAP

    /**
     * Воркеры параллельных стадий; null — всё в вызывающем потоке. Результат от их
     * числа не зависит побитово (см. ParallelBlocks). Игра может подставить свой пул.
     */
    var workers: PhysicsWorkers? = if (config.WORKERS > 1) PhysicsWorkers.shared(config.WORKERS) else null

    /**
     * Воркеры, которыми стадии пользуются в этом тике: на малом мире — никакими.
     * Замер 25.10: два тела (1.9k частиц) на 7 воркерах 5.4 → 6.4 мс — у каждой стадии
     * подшага работы на единицы микросекунд, и барьеры дороже её. На 11k уже 2.5x.
     */
    internal val activeWorkers: PhysicsWorkers?
        get() = if ((if (scopeParticles >= 0) scopeParticles else n) >= config.PARALLEL_MIN) workers else null
    /**
     * Частиц в области подшагов мира после островов (IslandRun.kt); -1 — островов нет, область — весь мир. Мир, от
     * которого острова забрали почти всё, считается в одном потоке: раздача по воркерам на каждой стадии дороже его
     * работы (замер 25.11: мир из ~9 тел после 86 островов платил за раздачу 8 мс). Биты от числа воркеров не зависят.
     */
    internal var scopeParticles = -1
    /**
     * Счётчик изменений топологии: разрыв, обновление контура, пересборка. По нему
     * параллельные стадии узнают, что их раскладка по организмам устарела (OrgBuckets).
     */
    internal var structureVersion = 0

    /** Параллельные стадии и число воркеров, под которое они собраны. См. ParallelBlocks. */
    internal var parallelPhysicsState: ParallelPhysics? = null
    internal var parallelPhysicsWorkers = 0

    /** Куски поштучных проходов, общие для воркеров и одного потока: см. PassChunks, passChunks(). */
    internal val passChunkCache = PassChunks()
    /** Куски текущей области тика (острова): куски мира, обрезанные по области. См. TickScope.kt. */
    internal var scopeChunkCache = PassChunks()

    /** «Острова» (TickScope.kt, IslandRun.kt, docs/physics/islands.md): ключ ISLANDS=1. */
    /** Режим островов (IslandChooser.kt): ISLANDS=0 / 1 / auto; без ключа — ISLANDS_DEFAULT. */
    internal val islandsMode = when (config["ISLANDS"]) {
        "0" -> ISLANDS_OFF; "1" -> ISLANDS_ON; "auto" -> ISLANDS_AUTO; else -> ISLANDS_DEFAULT
    }
    /** Острова в этом тике: по режиму, а в режиме auto — по выбору замера ([islandChooser]). */
    internal var islandsOn = islandsMode == ISLANDS_ON
    internal val islandChooser = IslandChooser()
    /**
     * Проверка снимка острова (IslandsTest, стенды): ISLANDS_ROLLBACK_ALL=1 — каждый остров после всех своих подшагов
     * откатывается и идёт с миром. Сумма мира обязана совпасть с «без островов»: снимок покрывает всё, что тень пишет.
     */
    internal val islandsRollbackAll = config["ISLANDS_ROLLBACK_ALL"] == "1"
    internal val islandScratch = IslandScratch()
    internal var islandOrgScope = IntArray(0)
    internal var islandVertexScope = IntArray(0)
    internal var islandCount = 0
    /** Сколько островов было в последнем тике — для стендов (PhysicsWorld.islandsLastTick). */
    var islandsLastTick = 0; internal set
    /** Сколько клеток было в островах в последнем тике (без откатившихся) — для стендов. */
    var islandCellsLastTick = 0; internal set
    /** Почему тела последнего тика не стали островами: [0] неподвижные или спящие клетки, под мышью, с липучкой; [1] группа тел
     *  крупнее ISLAND_MAX_CELLS; [2] (это острова) — в общем острове с соседом. Для стендов и подбора порогов. */
    val islandRejects = IntArray(3)
    /** Сколько островов откатилось (событие топологии в тени) за всё время — для проверок и стендов. */
    var islandAborts = 0L; internal set
    /** Списки островов этого тика: блоки, диапазоны, организмы, вершины, кости. См. IslandRun.kt. */
    internal val islandPlan = IslandPlan()
    /** Комплекты воркеров островов (тень состояния и её черновики), по номеру воркера. См. IslandRun.kt. */
    internal var islandKits: Array<IslandKit?> = emptyArray()
    /**
     * Не null — это ТЕНЬ мира, на которой воркер считает остров (IslandRun.kt): массивы общие, черновики свои. Тень не
     * исполняет событий топологии: перед разрывом, гибелью клетки или пробоем остров откатывается и идёт с миром.
     */
    internal var islandRun: IslandKit? = null
    /** Номер тика островов: тень воркера заводится заново, когда он сменился (IslandKit.beginTick). */
    internal var islandTick = 0L
    /** Таблица сумм кусков запаса среды в одном потоке (comSumsSerial): одна на состояние, у тени острова — своя. */
    internal var comSumsScratch: TickMomentum.GroupSums? = null
    /**
     * Запасы широкой фазы островов между тиками (contacts/IslandBroadphase.kt): по первой частице острова; ключи тика и
     * прошлого тика; пул запасов ушедших островов; места вершин в списках островов (сетки с местными номерами);
     * номер тика физики (каждый тик, с островами и без: годность запаса — «остров был и в прошлом тике»).
     */
    internal var islandBp = arrayOfNulls<IslandBroadphase>(0)
    internal var islandBpKeys = IslandKeyList()
    internal var islandBpKeysNext = IslandKeyList()
    internal val islandBpPool = ArrayDeque<IslandBroadphase>()
    internal var islandVertexSlot = IntArray(0)
    internal var islandPhysTick = 0L
    /** Островов с запасом широкой фазы с прошлого тика и собранных заново, за всё время — для проверок и стендов. */
    var islandBpReuses = 0L; internal set
    var islandBpRebuilds = 0L; internal set

    /** Мелкая копия: все поля — те же ссылки. Только для тени острова (IslandKit.beginTick). */
    internal fun shallowCopy(): BodyState = clone() as BodyState

    override fun clone(): Any = super.clone()

    lateinit var body: BodyDefinition

    /**
     * Постоянные масштаба мира (см. WorldScale): заданы до загрузки или взяты из неё.
     * [linkScale] — их средняя длина связи, опорный размер всех порогов решателя.
     */
    var worldScale: WorldScale? = null
    var linkScale = 1f
        internal set

    // --- частицы (SoA) ---
    var n = 0

    lateinit var px: FloatArray

    lateinit var py: FloatArray

    lateinit var prevX: FloatArray

    lateinit var prevY: FloatArray

    lateinit var vx: FloatArray

    lateinit var vy: FloatArray

    lateinit var invMass: FloatArray

    /** Обратная масса позы покоя: из радиуса клетки, см. buildMasses(). */
    lateinit var restInvMass: FloatArray

    // --- самоконтакт границы ---
    var contacts: BoundaryContacts? = null

    /** Связи ЦЕЛОГО тела: снимается один раз и переживает все пересборки. */
    var everLinked: LinkedPairs? = null

    var contactsOn = CONTACTS_ON

    lateinit var radius: FloatArray

    /** Клетка без единой связи — свободная частица, см. FREE_PARTICLES. */
    lateinit var isFree: BooleanArray

    /**
     * Клетка участвует в контактах СВОИМ радиусом: либо ни с кем не связана, либо ткань
     * её не закрывает. См. buildProbes.
     */
    var probe = BooleanArray(0)

    var probeAngle = FloatArray(0)

    /** Зонды по признаку угла можно выключить: RB_PROBE=0 — только свободные клетки. */
    var probeOn = config["RB_PROBE"]?.let { it != "0" } ?: PROBE_UNCOVERED

    /**
     * УМЕРШИЕ КЛЕТКИ: центр зашёл внутрь радиуса чужой. См. killOnDeep в контактах.
     *
     * Смерть тут окончательная и означает три вещи: все связи клетки объявлены
     * мёртвыми, из контактного множества она исключена, и её не рисуют. Физически
     * она остаётся в массивах — выкидывать клетку из середины значило бы
     * перенумеровать всё тело, а на это завязаны кости, мышцы и организмы.
     */
    lateinit var cellDead: BooleanArray

    /**
     * НЕРОЖДЁННАЯ КЛЕТКА растущего тела (этап A2.4): мертва (cellDead) до activateCell, но
     * не погибла — её место в организме ждёт её. Коррекции импульса (TickMomentum,
     * InternalMomentum) не считают её закреплённой частицей: иначе одна нерождённая
     * клетка снимала бы коррекцию со всего растущего организма. Погибшая клетка
     * закрепляет свою группу, как и раньше.
     */
    var cellUnborn = BooleanArray(0)

    var deadCount = 0

    /** Убивает ли глубокое проникновение — см. PhysicsWorld.deepKillOn; контакты берут его при сборке. */
    var deepKillOn = KILL_ON_DEEP_OVERLAP

    /**
     * Клетки с дорастающей связью этого шага: growStamp[i] == growStampNow (BodyGrowth.
     * markGrowingCells). Выводится из списка дорастающих рёбер, поэтому в снимок не входит.
     */
    var growStamp = IntArray(0)
    var growStampNow = 0

    /**
     * Мягкий старт касаний новорождённой: доля расстояния упора 0..1 по клетке, 1 — обычная
     * (BodyGrowth.rampNewbornContacts, BoundaryContacts.contactRamp). Выводится из дорастающих
     * связей, поэтому в снимок не входит; [rampCells] — клетки с долей меньше 1.
     */
    var contactRamp = FloatArray(0)
    var rampCells = IntArray(16)
    var rampN = 0

    /** Максимальная длина каждой связи: LINK_MAX_STRETCH * длина покоя. */
    /**
     * ПРЕДЕЛА ДЛИНЫ КАК МАССИВА БОЛЬШЕ НЕТ.
     *
     * Он всегда был равен LINK_MAX_STRETCH * conRest: так его ставила сборка, так же его
     * обновляла пластичность, когда меняла длину покоя. То есть восемь байт на связь и
     * отдельный поток из кэша тратились на значение, которое считается одним умножением.
     * У body-export это 39 КБ, которые читались 144 раза за тик — стадия предела длины
     * зовётся чаще всех. Осталось умножение на месте.
     */
    lateinit var matchWeight: FloatArray

    lateinit var inContact: BooleanArray

    // --- связи ---
    var conCount = 0

    internal var distanceSweepOrder: DistanceSweepOrder? = null

    lateinit var conA: IntArray

    lateinit var conB: IntArray

    lateinit var conRest: FloatArray

    lateinit var conMuscle: IntArray
    /** Версия структуры при последней подготовке контактов: см. skipCandidatesNow. */
    var contactsVersion = -1
    /** Идут ли площади и изгиб на этом подшаге: см. SLOW_CONSTRAINT_PERIOD. */
    var slowConstraintsNow = true
    /** Частицы, чью скорость меняет отскок этого подшага: см. FusedPasses, velocityFastAllowed. */
    var velTouchStamp = IntArray(0)
    var velTouchList = IntArray(0)
    var velBoneStamp = IntArray(0)
    var velTouchPass = 0
    var velTouchN = 0

    // --- граничные рёбра (омываемая поверхность) ---
    // --- изгиб граничного контура ---
    /** Пары «через одну» вдоль границы и расстояние между ними в позе покоя. */
    var bendCount = 0

    lateinit var bendA: IntArray

    lateinit var bendB: IntArray

    lateinit var bendRest: FloatArray

    /** Индекс в BEND_LEVELS. 0 — выключено. Переключается клавишей E. */
    var bendLevel = 0

    var boundCount = 0

    /**
     * ВНУТРЕННЯЯ ВЕРШИНА каждого граничного ребра, или -1 у голого ребра.
     *
     * Ею задаётся внешняя нормаль контура. Без знака контакт между ЦЕНТРАМИ клеток
     * слеп: стоит центру чужой клетки перейти линию контура, и отталкивание от центров
     * мембраны начинает гнать её ВГЛУБЬ, а не назад. Замер по подшагам на стенде охоты
     * показал ровно это. См. BoundaryContacts.solveContourSide.
     */
    lateinit var boundOpp: IntArray

    lateinit var boundA: IntArray

    lateinit var boundB: IntArray

    /**
     * ОДИН вектор скорости увлечённой среды на организм. Не на ребро.
     *
     * ИСТОРИЯ ЭТОГО ПОЛЯ — ЭТО ИСТОРИЯ ПОИСКА ИСТОЧНИКА БЛУЖДАНИЯ, поэтому она здесь.
     * Замер, который всё решает: тело жёстко вращают вокруг центра масс, решатель
     * отключён, работает только сопротивление среды. Сила от среды в системе тела при
     * этом ПОСТОЯННА, в мировой крутится вместе с телом, значит импульс за полный оборот
     * обязан вернуться в ноль. Что получалось:
     *
     *     памяти среды нет вовсе                        ->  0.001   (эталон)
     *     скаляр «вдоль нормали ребра»  (было сначала)  ->  870
     *     вектор в мировых осях, но у каждого ребра      ->  308
     *     один общий запас, заряжаемый ОТ РЁБЕР         ->  815
     *     один общий запас, заряжаемый ОТ СКОРОСТИ ЦМ   ->  0.089  (стоит сейчас)
     *
     * Импульс 870 на массе 370 — это 2.35 единицы скорости ИЗ НИОТКУДА, разово, за один
     * поворот тела. Вот откуда было «тело вдруг поехало не туда»: организм повернулся,
     * и накопитель среды выдал ему толчок в стороне, никак не связанной с гребком.
     *
     * ЧЕТВЁРТАЯ СТРОКА — САМАЯ ПОУЧИТЕЛЬНАЯ. Сделать запас общим НЕ ПОМОГАЕТ: 815 против
     * 833. Значит дело было не в том, что запасов много, а в том, ОТ ЧЕГО они заряжаются.
     * Пока запас питается импульсами на рёбрах, вращение в него попадает, потому что
     * рёбра при вращении движутся.
     *
     * ЧТО РАБОТАЕТ. Заряжать запас от скорости ЦЕНТРА МАСС. Тогда:
     *   - при чистом вращении скорость ЦМ равна нулю, запас не заряжается ВООБЩЕ, и
     *     поворотного толчка нет ПО ПОСТРОЕНИЮ, а не по удачному подбору констант;
     *   - запас всегда сонаправлен с уже имеющимся движением тела, поэтому подтолкнуть
     *     тело он может только ВПЕРЁД по его же курсу. Это накат, а не блуждание:
     *     «в случайную сторону» тут физически неоткуда взяться;
     *   - эффект, ради которого память среды и вводилась, сохраняется полностью —
     *     разогнанное тело какое-то время несёт разогнанная им же среда. Доля скорости,
     *     сохраняемая после прекращения гребков, выросла с 0.148 до 0.649.
     *
     * ПОЧЕМУ ТЯГА НЕ СТРАДАЕТ от вычитания общего вектора: тяга живёт на ЛОКАЛЬНЫХ
     * скоростях плавника (0.24..1.9), а вычитается величина порядка скорости корпуса
     * (около 0.01) — на два порядка меньше.
     *
     * ЧТО ЕЩЁ ПРОБОВАЛОСЬ И ОТВЕРГНУТО: присоединённая порция среды у каждого ребра
     * с массой и строго симметричным обменом. Наивная версия расходится в NaN (отношение
     * масс 90:1), через приведённую массу устойчива, но разовый толчок оказался
     * ПРОПОРЦИОНАЛЕН массе порции (70 при массе 10, 250 при 40, 1068 при 400), а
     * повторяемость гребка развалилась с 1% до 40..55%.
     */
    /**
     * К какому организму принадлежит частица. Связные компоненты графа связей.
     *
     * Нужны, потому что запас увлечённой среды обязан быть СВОИМ у каждого тела.
     * Пока организм был один, разницы не существовало и здесь стоял один скаляр на
     * весь мир — и это оказалось прямой ошибкой: два тела делили один запас, поэтому
     * движение одного толкало другое НА ЛЮБОМ РАССТОЯНИИ, а тяга у обоих падала,
     * потому что запас следовал за средней скоростью пары, а не за своей.
     *
     * В движке это ровно те же острова, которые понадобятся для сна и для разбиения
     * по аренам.
     */
    lateinit var organismOf: IntArray

    var organismCount = 0

    lateinit var organismSize: IntArray

    /** Размеры и массы организмов уже посчитаны для нынешней разметки. */
    var organismsCounted = false

    lateinit var organismMass: FloatArray

    lateinit var flowVX: FloatArray

    lateinit var flowVY: FloatArray

    /** Номера организмов и их запас ДО перенумерации — по ним куски наследуют накат. */
    var orgPrev = IntArray(0)

    var orgSeed = IntArray(0)

    var orgFlowX = FloatArray(0)

    var orgFlowY = FloatArray(0)

    /** Черновик под сумму скоростей организма. Заводится один раз, в цикле не аллоцируется. */
    lateinit var comAccX: FloatArray

    lateinit var comAccY: FloatArray

    /**
     * Положения бодрствующих частиц в начале тика, на последнем подшаге — дрожь
     * скорости (мгновенная минус средняя за тик). См. TickMotion.
     */
    var tickJitterX = FloatArray(0)
    var tickJitterY = FloatArray(0)

    /**
     * Место, где началась серия покоя организма, и наибольшее удаление его клеток от
     * этого места (в квадрате) на этом тике. См. SLEEP_DRIFT_CELLS.
     */
    var sleepAnchorX = FloatArray(0)
    var sleepAnchorY = FloatArray(0)
    var orgSleepDrift2 = FloatArray(0)

    /** Сон организмов: см. BodySleep. Выключается RB_SLEEP=0. */
    var sleep = BodySleep(config["RB_SLEEP"]?.let { it != "0" } ?: SLEEP_ON)
        internal set

    // ================================================================
    //  ТОПОЛОГИЯ ХРАНИТСЯ СВОЯ, А НЕ ЧИТАЕТСЯ ИЗ BodyDefinition.
    //
    //  BodyDefinition — это ВЫГРУЗКА, снимок из редактора, и он неизменен. Ткань же
    //  рвётся: связи и треугольники исчезают, контур перестраивается, тело может
    //  распасться надвое. Поэтому здесь лежат СОБСТВЕННЫЕ изменяемые копии, а из
    //  body берутся только позы покоя, радиусы и кластеры.
    //
    //  В движке это ровно та же развилка: топология живая и меняется, а выгрузка
    //  редактора остаётся исходником.
    // ================================================================
    lateinit var lnkA: IntArray

    lateinit var lnkB: IntArray

    var lnkCount = 0

    lateinit var triA: IntArray

    lateinit var triB: IntArray

    lateinit var triC: IntArray

    lateinit var triRestArea2: FloatArray

    var triCount = 0

    /**
     * Запечённая топология целого тела и что в ней ещё живо. Живые списки выше — её
     * сжатые копии в исходном порядке; рядом с каждым лежит номер в запечённой. См.
     * BakedTopology и rebuildAfterTear.
     */
    lateinit var baked: BakedTopology

    internal lateinit var fractureOrientation: FractureOrientation

    /** Ребро каждой связи полного списка. */
    var lnkEdge = IntArray(0)

    /** Номер каждого живого треугольника в выгрузке. */
    var triId = IntArray(0)

    /** Ребро каждой рабочей связи решателя. */
    var conEdge = IntArray(0)

    /** Рабочая связь на ребре, -1 — нет. Отрисовке порванного и разбору журнала. */
    var conOfEdge = IntArray(0)

    /** Кандидаты касания в позе покоя: запекаются один раз на тело. */
    var restPairs: RestPairs? = null

    // --- треугольники ---
    lateinit var triMuscle: IntArray

    lateinit var triInverted: BooleanArray

    var invertedNow = 0

    var invertedPeak = 0

    // --- кости ---
    /**
     * Только кластеры, которые могут быть жёсткими. Кластер из одной клетки отбрасывается:
     * поворот по единственной точке не определён, проекция стала бы тождеством, а строка
     * в списке создавала бы ложное впечатление, что кость есть.
     */
    lateinit var rigidBones: Array<IntArray>

    /**
     * КОСТЬ ВКЛЮЧАЕТСЯ ПО КЛЕТКЕ (клиент-игра: клетка становится костью мутацией на своей стадии,
     * решение 27.10 — вариант «заморозить форму»). boneClusterOf — в какой кости клетка окажется
     * (кластер описания), boneOf — включена ли уже (-1 — нет). Не включённая клетка — обычная
     * мягкая ткань: вес в кости 0, связи с костью — обычные; setBone (activateBone) вводит её в
     * кость, связи с уже включёнными клетками той же кости уходят из решателя (их держит
     * проекция), поза кости — нынешнее положение её включённых клеток. Тела стенда заводятся со
     * всеми костями включёнными и позой из описания — прежний путь.
     */
    var boneClusterOf = IntArray(0)

    var degenerateBones = 0

    /** Номер кости у вершины, -1 если не в жёсткой кости. */
    lateinit var boneOf: IntArray

    /**
     * Поза покоя каждой кости — смещения от её центроида, предпосчитанные один раз.
     *
     * Это то самое r0, которое в движке надо запечь в BakedLayout. Здесь оно не только
     * экономит работу: сумма w*q0 досажена в НОЛЬ ровно во float, и без этого зачёт
     * поправки в projectBone не имел бы смысла — см. её комментарий.
     */
    lateinit var boneRestQx: Array<FloatArray>

    lateinit var boneRestQy: Array<FloatArray>

    /** Черновик поправок самой большой кости. Заводится один раз, в горячем цикле не аллоцируется. */
    lateinit var boneDx: FloatArray

    lateinit var boneDy: FloatArray

    /** Кости, чей состав надо пересмотреть между тиками: погибла клетка или внутренняя связь. См. BoneSplit.kt. */
    internal var boneDirty = IntArray(8)
    internal var boneDirtyN = 0
    internal var boneDirtyMark = BooleanArray(0)
    /** Черновик разделения кости: место клетки в списке кости + 1. 4 байта на частицу, заводится при первом разделении. */
    internal var boneLocal = IntArray(0)
    /** Делились ли кости с загрузки: сброс мира возвращает их к описанию (restoreSplitBones). */
    internal var bonesSplit = false

    // --- мышцы ---
    lateinit var muscleOf: IntArray

    lateinit var muscleActivation: FloatArray

    /**
     * Куда каждая мышца едет в этом кадре: 1 — сокращаться, 0 — распрямляться.
     *
     * Отдельно от muscleActivation, потому что источников команды теперь три —
     * наведение мышью, цифровые клавиши и автоматический гребок, — и они должны
     * складываться, а не перебивать друг друга.
     */
    lateinit var muscleTarget: FloatArray

    var bonesRigid = true

    var dragId = -1

    var mouseX = 0.0f

    var mouseY = 0.0f

    var orgQueue = IntArray(0)

    var conNextForEdge = IntArray(0)

    lateinit var localBoundary: LocalBoundaryConstraints

    lateinit var localComponents: LocalComponents

    lateinit var localSurface: LocalSurface

    /**
     * УГОЛ ЛОСКУТА: единственное, что мешает тонкому отростку сложиться сам на себя.
     *
     * ЗАЧЕМ ОТДЕЛЬНАЯ СТАДИЯ, А НЕ ИЗГИБ. Изгиб по умолчанию выключен (bendLevel = 0
     * даёт податливость -1, и solveBend выходит на первой строке), да и строится он
     * только для клеток ровно с двумя граничными рёбрами, то есть развилку контура
     * пропускает. А связь ЛОСКУТА не входит ни в один треугольник, поэтому её угол не
     * держит и площадь. В итоге не держит вообще ничто.
     *
     * ЧТО ЭТО ЛОМАЛО. Замер на оторвавшемся куске: клетки #939 и #940, у каждой по
     * одной связи, обе в одну и ту же клетку #566, треугольников на этих связях ноль.
     * Лоскуты свободно сложились друг на друга и сошлись на 0.014 средней связи, то
     * есть перекрылись на 99% контактного диаметра. Дальше контакт упирался в свой
     * предохранительный потолок, расталкивал их каждый подшаг на 4 клетки за тик,
     * связи тянули обратно на 7.7, и кусок дребезжал вечно, а среда превращала эту
     * пилу в тягу. Это же и есть «маятник, который от касания резко отскакивает»:
     * делить контактную поправку такой клетке не с кем.
     *
     * ОГРАНИЧЕНИЕ ОДНОСТОРОННЕЕ. Оно мешает лоскутам СХОДИТЬСЯ ближе, чем в позе
     * покоя, и не мешает им расходиться. Поэтому тело не становится жёстче нигде:
     * там, где ткань цела, лоскутов нет вовсе, а где есть — они просто не могут
     * сложиться в точку.
     *
     * Пар тут единицы: нужны ДВЕ связи одной клетки, и обе без треугольника.
     */

    /** Идёт пересборка после разрыва: печатать нельзя, она бывает десятки раз за тик. */
    var rebuilding = false

    /** Списки голых соседей: степени, начала, сами соседи. Заводятся один раз. */
    var flapDeg = IntArray(0)

    var flapStart = IntArray(0)

    var flapNb = IntArray(0)

    var flapCount = 0

    var flapA = IntArray(0)

    var flapB = IntArray(0)

    var flapRest = FloatArray(0)

    /**
     * Вершина створки: клетка, при которой угол между голыми связями к flapA и flapB (BlockConstraintKernels.
     * solveBlockFlaps). Производное от топологии, как flapA и flapB: строится вместе с ними, в снимок не входит.
     */
    var flapV = IntArray(0)

    var bendNb0 = IntArray(0)

    var bendNb1 = IntArray(0)

    var bendDeg = IntArray(0)

    /** Сколько рёбер контура порвано пробоем. См. applyPierce. */
    var piercedCount = 0

    /**
     * ИСКАТЬ ПРОБОЙ ПОСЛЕ КАЖДОЙ ИТЕРАЦИИ, А НЕ ТОЛЬКО ПО БАЛЛИСТИКЕ.
     *
     * prevX ставится в начале подшага, а prepare зовётся после ПЕРВОГО прохода стадий,
     * значит смещения итераций 1..3 правило не видит вовсе. Именно так клетка #1796 из
     * журнала игрока попала внутрь: её завёл решатель между двумя prepare, а на
     * следующем подшаге она уже «была внутри», и знаковый фильтр по построению отказался.
     */
    val pierceEachIter = config["RB_PIERCE_ITER"] == "1"

    val surfaceOne = IntArray(1)

    // =================================================================
    //  РЕШАТЕЛЬ  (тот же, что в SoftBodyWithBoneDemo)
    // =================================================================

    /**
     * Во сколько раз сжат кластер мышцы: 1 в покое, MUSCLE_CONTRACTION при полном
     * сокращении. -1 (не мышца, шов между кластерами) — всегда 1.
     *
     * Именно ЭТО и есть вся модель мышцы: меняется длина покоя ограничения, а не сила.
     * Жёсткость остаётся прежней, поэтому мышца тянет ровно с той силой, которая нужна,
     * чтобы дотащить ткань до новой длины, и упирается, если ей мешают. Прямой аналог
     * degreeOfShortening в движке.
     */
    /**
     * МЫШЕЧНЫЕ ПАРАМЕТРЫ ЖИВУТ ПЕРЕМЕННЫМИ, а не только константами.
     *
     * Подбор идёт на зеркале (`swimTune`), а зеркало рвать не умеет, поэтому найденное
     * обязательно проверяется на настоящем теле с разрывом — стенд `PERF_GAIT`. Чтобы
     * прогнать кандидата, не пересобирая проект, значения берутся из окружения:
     * RB_CONTRACTION, RB_RATE_CONTRACT, RB_RATE_RELAX, RB_PERIOD, RB_DUTY.
     * Без переменных это ровно константы выше.
     */
    var muscleContraction = config["RB_CONTRACTION"]?.toFloatOrNull() ?: MUSCLE_CONTRACTION

    var muscleRateContract = config["RB_RATE_CONTRACT"]?.toFloatOrNull() ?: MUSCLE_RATE_CONTRACT

    var muscleRateRelax = config["RB_RATE_RELAX"]?.toFloatOrNull() ?: MUSCLE_RATE_RELAX

    var gaitPeriod = config["RB_PERIOD"]?.toIntOrNull() ?: GAIT_PERIOD

    var gaitDuty = config["RB_DUTY"]?.toFloatOrNull() ?: GAIT_DUTY

    /** Захватывает ли мышца край: 0/1/2. См. MUSCLE_TOUCH. Окружение RB_TOUCH. */
    var muscleTouch = config["RB_TOUCH"]?.toIntOrNull() ?: MUSCLE_TOUCH

    /**
     * Во сколько раз сжат каждый кластер мышцы — считается РАЗ В ПОДШАГ.
     *
     * Связей и треугольников с мышцей тысячи, и каждая спрашивала это на каждой из 64
     * итераций решателя, поэтому значение считается заранее. Кластеров при этом единицы,
     * так что подшаг вместо тика ничего не стоит.
     *
     * ПОЧЕМУ ПОДШАГ, А НЕ ТИК. updateMuscles двигает активацию раз в тик, и длина покоя
     * прыгала ступенькой: весь шаг мышцы решатель отрабатывал в ПЕРВОМ же подшаге, а
     * updateVelocities делит смещение на h = DT/16. Скорость выходила вшестнадцатеро
     * больше настоящей, и эта вспышка успевала уйти в среду настоящим импульсом —
     * рывок при отпускании мышцы как раз оттуда. Теперь активация проходит свой шаг
     * РОВНО за тик, но по подшагам: доля f = (step + 1) / SUBSTEPS. За тик изменение то
     * же самое, только размазано, как оно и происходит на самом деле.
     */
    var muscleScaleOf = FloatArray(0)

    /**
     * МЫШЦА ПО КЛЕТКЕ (клиент-игра: у каждого миоцита своя активация, сокращение и расслабление).
     * cellScaleTarget — команда: 0.5..1 задаёт сокращение, 1..1.5 повышает податливость на растяжение.
     * cellScale — нынешнее значение, подходит к цели с темпом мышцы
     * стенда (advanceCellScales: удаление от 1 — muscleRateContract, возвращение — Relax).
     * Скачок длины покоя за тик XPBD выполняет почти сразу — ткань рвало (standParityCheck,
     * 27.10: 15–21 разрыв за 240 тиков гребка; с темпом — ни одного).
     *
     * МЫШЦА ЗАХВАТЫВАЕТ СОСЕДЕЙ НА ОДНО РЕБРО, как мышца стенда (growMuscles, MUSCLE_TOUCH 1:
     * треугольник с мышечной вершиной отдаёт мышце все три клетки). cellScaleEff — действующий
     * множитель клетки: сильнейший (дальше от 1, при равном удалении — меньший) из своего и
     * множителей соседей по живому треугольнику. Связь берёт слабейший действующий множитель
     * концов — сокращается, только если оба конца в кольце мышцы (связь из кольца наружу — нет,
     * как у стенда); треугольник — слабейший из трёх вершин, с костной вершиной не сокращается
     * (кость держит сторону, площадь уронила бы вершину на кость — см. muscleOfTri). Целевая площадь при сокращении —
     * исходная площадь на квадрат множителя; при расслаблении она остаётся исходной. Без захвата (сильнейшая мышца концов, 27.10) A-11 плыл 7.5 связи за 600
     * тиков гребка против 22.8 у стенда. В ядрах масштаб = масштаб кластера мышцы × множитель
     * связи; при всех множителях 1 — те же биты: x · 1 = x.
     */
    var cellScale = FloatArray(0)
    var cellScaleTarget = FloatArray(0)
    var cellScaleEff = FloatArray(0)
    /** Производное: клетка, чей множитель — действующий (cellScaleEff); её волокно — волокно ткани вокруг (MuscleFiber.kt). */
    var cellScaleSrc = IntArray(0)
    /** Черновик activateCell: ожившие треугольники до старта их дорастания. */
    var bornTriScratch = IntArray(16)
    var conCellScale = FloatArray(0)
    var triCellScale = FloatArray(0)
    /** Множители связей и треугольников — заново целиком (после перестройки структуры). */
    var cellScalesDirty = false
    /** Есть клетки, чей множитель ещё не дошёл до цели. */
    var cellScalesMoving = false

    /**
     * ПЛАВНОСТЬ МЫШЦЫ КЛЕТКИ ПО ПОДШАГАМ (MuscleDriver.applyCellScaleLerp): связи (по ребру) и
     * треугольники (по постоянному номеру), чей множитель сдвинул шаг мышцы, с «было» и «станет».
     * Рабочие данные одного шага: собираются в advanceCellScales, к концу шага пусты — в снимок
     * не входят. Метки — номер шага: клетка, ребро и треугольник пересчитываются за шаг мышц один раз
     * (MuscleDriver.refreshMovedRings), поэтому и захватываются один раз.
     */
    var lerpEdgeIds = IntArray(64); var lerpEdgeFrom = FloatArray(64); var lerpEdgeTo = FloatArray(64); var lerpEdgeN = 0
    /** Клетки, сдвинувшие множитель в этом шаге мышц, — кольца им пересчитываются после шага всех (advanceCellScales). */
    internal var cellScaleMoved = IntArray(64)
    /** Клетки, у которых в этом шаге мышц сменился действующий множитель (и все сдвинувшиеся): им пересчитываются связи и треугольники. */
    internal var cellScaleChanged = IntArray(64)
    var lerpTriIds = IntArray(64); var lerpTriFrom = FloatArray(64); var lerpTriTo = FloatArray(64); var lerpTriN = 0
    var lerpEdgeStamp = IntArray(0); var lerpTriStamp = IntArray(0); var lerpStampNow = 0
    internal var lerpCellStamp = IntArray(0)

    /**
     * ПЛАВНОСТЬ ДОРАСТАНИЯ ПО ПОДШАГАМ (BodyGrowth.applyGrowthLerp): молодые связи (по ребру; у ребра с несколькими
     * связями — запись на каждую, в порядке цепочки conNextForEdge) и молодые треугольники (по постоянному номеру) с
     * длиной и площадью покоя «было» и «станет». Рабочие данные одного тика: собираются в easeNewborn, к концу тика
     * пусты — в снимок не входят.
     */
    internal var growLerpEdge = IntArray(64); internal var growLerpFrom = FloatArray(64); internal var growLerpTo = FloatArray(64)
    internal var growLerpN = 0
    internal var growLerpTri = IntArray(64); internal var growLerpTriFrom = FloatArray(64); internal var growLerpTriTo = FloatArray(64)
    internal var growLerpTriN = 0

    /**
     * Клиент задавал множители клеток (хоть один не 1). Пока нет — ядра идут прежним путём,
     * без чтения множителей: стенд (мышцы кластерами) не платит за мышцу по клетке. Флаг не
     * гаснет: однажды включённый, он входит в снимок и переживает уплотнение.
     */
    var cellScalesOn = false

    /**
     * ВОЛОКНО МЫШЦЫ ПО КЛЕТКЕ (PhysicsWorld.setCellFiber; игра — направление мотонейрона кластера, стенды — длинная
     * ось куска мышц). cellFiberX/Y — единичное направление в позе покоя тела (BodyDefinition.x/y); ноль — волокна
     * нет, мышца сжимается по площади, во все стороны, как раньше. Закон — config/FiberParameters.kt, как он ложится
     * на связи и треугольники — solver/MuscleFiber.kt: сжатие s < 1 — вдоль волокна × s, поперёк × s^−k
     * (k = fiberThicken), площадь × s^(1−k), в ядрах решателя (conFiberC2, triFiberById); расслабление s > 1 длин
     * не меняет: мягче на растяжение только вдоль волокна (тоже в ядрах), а запас на разрыв — у всей мышцы.
     *
     * Волокно связи и треугольника — у клетки, чей множитель выбран (cellScaleSrc): кольцо мышцы
     * сжимается по её волокну. Источник выбирается, а не считается, поэтому результат от порядка
     * обхода клеток не зависит, как и сами множители.
     */
    var cellFiberX = FloatArray(0)
    var cellFiberY = FloatArray(0)
    /**
     * Клиент задавал волокна (хоть одно не ноль). Пока нет — мир идёт прежним путём побитово. Как softnessOn: однажды
     * включённый, не гаснет и входит в снимок.
     */
    var fibersOn = false
    /** Производное: cos² угла связи в позе покоя к волокну клетки, задающей ей множитель; 1 — без волокна. */
    var conFiberC2 = FloatArray(0)
    /** Производное: треугольник сокращается по волокну — по постоянному номеру треугольника. */
    var triFiberById = BooleanArray(0)
    /** Показатель утолщения поперёк волокна k — настройка мира FIBER_THICKEN (config/FiberParameters.kt). */
    val fiberThicken: Float = config["FIBER_THICKEN"]?.toFloatOrNull() ?: FIBER_THICKEN

    /**
     * МЯГКОСТЬ ТКАНИ ПО КЛЕТКЕ (клиент-игра: жир мягче листа, PhysicsWorld.setSoftness).
     * cellSoftness — множитель податливости клетки (1 — обычная ткань, 2.5 — в 2.5 раза мягче).
     * Связь берёт среднее концов — две половинки пружины подряд: податливости складываются;
     * треугольник — среднее трёх вершин. В ядрах alpha = SOFT_COMPLIANCE (AREA_COMPLIANCE) ×
     * множитель. При шаге 1/960 податливость связи (alpha/h² ≈ 9) больше массы в знаменателе
     * поправки (w ≈ 1–2), поэтому жёсткость задаёт в основном она: множитель 2.5 даёт связь жир–жир
     * в 2.43 раза мягче обычной (SoftnessTest), у площади (знаменатель ~3e-3) — почти ровно 2.5.
     * Изгиб (выключен, bendLevel = 0) мягкость не трогает.
     *
     * conSoftness/triSoftness ведутся теми же путями, что conCellScale/triCellScale (полный
     * пересчёт после перестройки структуры, кольцо клетки — refreshCellScaleRing): значение
     * связи зависит только от её концов. 4 байта на связь и треугольник, читаются подряд по
     * номеру — потоком, не случайно.
     */
    var cellSoftness = FloatArray(0)
    var conSoftness = FloatArray(0)
    var triSoftness = FloatArray(0)

    /**
     * Клиент задавал мягкость (хоть одна клетка не 1). Пока нет — ядра идут прежним путём, без
     * чтения множителей. Как cellScalesOn: однажды включённый, не гаснет и входит в снимок.
     */
    var softnessOn = false

    /**
     * КЛЕТКА БЕЗ КАСАНИЙ (клиент-игра: присоска, Sucker — isCollidable = false, PhysicsWorld.setContactless):
     * в контактах не участвует вовсе — ни с чужими телами, ни с едой, ни со стенкой, как в прежней
     * физике игры, где такую пару не расталкивали. Связи, площади и среда — как у всех. Контакты
     * читают флаг только при перестройке набора клеток касания (BoundaryContacts.reconfigure,
     * updateSurface), в горячем цикле пар его нет. Входит в снимок.
     */
    var cellNoContact = BooleanArray(0)

    /**
     * ГРУППА КАСАНИЙ по частице: две частицы одной ненулевой группы не сталкиваются (BoundaryContacts.bonded,
     * в широкой фазе — при перекладке вершины, не в каждом подшаге). Клиент-игра: плитки террейна —
     * одна группа; соседние плитки перекрываются по шву и держатся связями (Joints.kt), касание
     * расталкивало бы шов, и плитки не засыпали бы никогда. 0 — без группы. Входит в снимок.
     */
    var cellGroup = IntArray(0)

    /** Хватка по частице 0..1 (присоска-чашка) — см. Grip.kt. Входит в снимок. */
    var cellGrip = FloatArray(0)
    /**
     * Пружина к своему месту (террейн: точка на карте) — податливость по частице, 0 — нет пружины;
     * место — cellHomeX/Y. Решается вместе с хваткой (Grip.kt). Входит в снимок.
     */
    var cellHomeCompliance = FloatArray(0)
    var cellHomeX = FloatArray(0); var cellHomeY = FloatArray(0)
    var gripN = 0
    /** Список [gripIds] устарел: смена хватки, уплотнение, снимок. */
    var gripsDirty = false

    /** Временные связи между частицами разных тел (липучка) — см. Joints.kt. Слоты пула, входят в снимок. */
    var jointA = IntArray(0); var jointB = IntArray(0)
    var jointRest = FloatArray(0); var jointCompliance = FloatArray(0)
    var jointLive = BooleanArray(0)
    /** Порог пробуждения соседа по связи, доля длины покоя; 0 — будит всегда (липучка), см. prepareJoints. */
    var jointWakeStrain = FloatArray(0)
    /** Связи, у которых хоть один конец движется, — на этот тик (prepareJoints); в снимок не входят. */
    var jointActive = IntArray(0); var jointActiveN = 0
    var jointSlots = 0; var jointLiveCount = 0
    var jointFree = IntArray(0); var jointFreeN = 0

    /**
     * Множитель изотропного сопротивления среды по частице (1 — как среда мира). Клиент-игра:
     * у субстанции и у клетки своё сопротивление (ParticleEntity.dragCoefficient; клетке
     * 0.003 за тик — это и есть MEDIUM_DRAG · DT). Считается только у частиц с множителем
     * не 1: остальные — прежняя арифметика, те же биты.
     */
    var dragScale = FloatArray(0)

    /**
     * РАМКА МИРА (клиент-игра: границы сетки мира). Включена — частица не выходит за неё:
     * интегрирование ставит её на стенку с отступом в свой радиус, скорость к стенке гаснет
     * сама (prev). Выключена — без стенок, как стенд.
     */
    var boundsOn = false
    internal var wallHits = BoneWallHits(0)
    var boundsMinX = 0.0f
    var boundsMinY = 0.0f
    var boundsMaxX = 0.0f
    var boundsMaxY = 0.0f

    /** Активация на НАЧАЛО тика — между ней и текущей идёт разгон по подшагам. */
    var muscleActivationPrev = FloatArray(0)

    /**
     * ПЛАСТИЧЕСКОЕ ТЕЧЕНИЕ ТКАНИ. Зовётся РАЗ В ТИК, см. PLASTIC_RATE.
     *
     * Деформация сверх предела текучести медленно переезжает в длину покоя, то есть
     * перестаёт быть деформацией. Ниже предела не делается ничего — ткань упругая.
     *
     * ДЕФОРМАЦИЯ СЧИТАЕТСЯ ОТ ТЕКУЩЕЙ ЦЕЛИ, а не от conRest: у мышцы цель короче на
     * muscleScale, и мерить от conRest значило бы считать сокращение мышцы пластикой.
     * Тогда сокращённая мышца за несколько секунд «забывала» бы свою длину покоя и
     * переставала тянуть вовсе.
     *
     * Площади текут ВМЕСТЕ с длинами и отдельно от них. Отпустить только длины нельзя:
     * несжимаемая площадь удержит смятый треугольник в прежнем виде, и несовместимость
     * останется — просто переедет со связей на площади.
     */
    var conHold = ByteArray(0)

    /**
     * ДОРАСТАНИЕ НОВОРОЖДЁННОЙ ТКАНИ (рост тела, BodyGrowth.kt). Родившись, клетка встаёт
     * туда, где ей место, но её связи и треугольники хотели бы своих длин и площадей покоя
     * сразу, а у сжатой мышцы соседи растянуты: мгновенное рождение давало рывок. Поэтому
     * длина покоя новой связи сначала равна фактической и за BIRTH_GROW_TICKS тиков
     * подходит к цели; площадь треугольника — так же (easeNewborn).
     *
     * conGrowTarget — цель по рабочему номеру связи (0 — не дорастает), переезжает при
     * уплотнении вместе с conRest. triGrowTarget — цель по постоянному номеру
     * треугольника. growEdges/growTris — кого обходить в тике: рёбра и постоянные номера
     * треугольников, в порядке рождения. Всё это влияет на будущие тики — состояние мира.
     */
    var conGrowTarget = FloatArray(0)
    /** Возраст дорастания связи в тиках (мягкость молодой связи, easeNewborn); переезжает с conGrowTarget. */
    var conGrowAge = ByteArray(0)
    var triGrowTarget = FloatArray(0)
    var growEdges = IntArray(16)
    var growEdgeN = 0
    var growTris = IntArray(16)
    var growTriN = 0

    var triHold = ByteArray(0)

    /**
     * ЩЕЛЬ В МЕМБРАНЕ: НАСКОЛЬКО ОНА ВООБЩЕ ПРОХОДИМА. Считается при `dbgSealOn`.
     *
     * Круги контакта двух соседей по контуру перекрываются, пока ребро короче суммы их
     * радиусов. Растянутое ребро открывает коридор шириной L - (ra + rb). Чужая клетка
     * радиуса r сквозь него НЕ пройдёт, пока коридор уже 2r: её центр не может миновать
     * обе запретные зоны. Значит мембрана дырява ровно тогда, когда
     *
     *     L > ra + rb + 2 * rМИН,   rМИН — наименьший радиус контакта в сцене.
     *
     * Здесь копится, как далеко дело заходило: наибольшее отношение коридора к 2*rМИН и
     * сколько раз оно переваливало за единицу. Если единицу оно не переходит никогда, то
     * ткань протыкают не через щель, и рвать связь по этому признаку бессмысленно.
     */
    var dbgSealOn = config["RB_SEAL"] != null

    var dbgSealMinR = 0.0f

    /** Сколько раз предел длины связи сработал с последнего сброса. Видно в HUD. */
    var linkCapHits = 0

    /** Разбор: журнал разрывов — кто, когда и почему. Включает читатель, см. PR_TEARS. */
    var dbgTearLog: StringBuilder? = null

    /** Только PERF_GAIT читает эти экстремумы; обычному тику два деления на связь не нужны. */
    var dbgLinkExtremaOn = false

    /** Разбор при dbgLinkExtremaOn: самая смятая и растянутая связь за прогон, в долях покоя. */
    var dbgWorstCrush2 = 1.0f

    val dbgWorstCrush: Float get() = sqrt(dbgWorstCrush2)

    var dbgWorstStretch2 = 1.0f

    val dbgWorstStretch: Float get() = sqrt(dbgWorstStretch2)

    /** Разбор: чем порвано — сжатием или растяжением, и сколько из этого мышечные связи. */
    var dbgTornCrush = 0

    var dbgTornCrushMuscle = 0

    var dbgTornPull = 0

    var dbgTornPullMuscle = 0

    /** Разбор: наибольший недобор связи сверх предела, в долях покоя. Сбрасывает читатель. */
    var dbgMaxOverStrain = 0.0f

    /** После смерти клетки скоростной проход ждёт следующего обновления списка пар. */
    var contactsStale = false

    /** Цена пересборки после разрыва: суммарное время и число вызовов. */
    var dbgRebuildNs = 0L

    var dbgRebuildN = 0

    /** Связь хоть раз требовала растяжения сверх порога разрыва. См. LINK_TEAR_STRAIN. */
    var linkTorn = BooleanArray(0)

    // ================================================================
    //  НАСТОЯЩИЙ РАЗРЫВ ТКАНИ [X]
    //
    //  До этого связь при перегрузке лишь КРАСИЛАСЬ синим, а держалась вечно. Из-за
    //  этого оставался открытым вопрос, который и заставил всё это сделать: может ли
    //  тело войти в мягкую ткань БЕЗ разрыва. С вечными связями ответ было не
    //  получить — ткань обязана была выдержать что угодно.
    //
    //  Перегруженная связь и её треугольники гаснут прямо в подшаге. Перед следующей
    //  стадией обновляются только затронутые вершины контура, контакты и компоненты.
    //  Плотные рабочие списки упаковываются ОДИН РАЗ в конце тика.
    //
    //  ПРИ ПЕРЕНОСЕ: производные структуры уже локальны, но упаковка рабочих массивов
    //  ещё линейная. Её нужно согласовать с аренами организмов основной симуляции.
    // ================================================================
    var tearingOn = TEARING_DEFAULT

    lateinit var conDead: BooleanArray

    var tearsPending = false

    /**
     * ПЕРЕСБОРКА ОДНА НА ТИК, А НЕ НА КАЖДЫЙ РАЗРЫВ.
     *
     * Было так: порвалась связь — сразу полная пересборка. На ударе это выходило до
     * СЕМНАДЦАТИ пересборок за один тик (замер стенда PERF_GAIT при глубоком сокращении),
     * каждая по всему телу, и ровно это видно как рывок. Теперь связь перестаёт тянуть
     * сразу (conDead), треугольник перестаёт держать площадь (triAlive), а контур,
     * организмы и контакты обновляются по очереди затронутых вершин и рёбер.
     *
     * Поэтому разрыв теперь СРАЗУ гасит ребро в запечённой топологии: треугольники на нём
     * умирают тут же, и стадия площадей их пропускает по triAlive. Номер ребра падает в
     * очередь для локальной проверки компонент. В конце тика очередь задаёт число
     * новых разрывов, после чего плотные рабочие списки упаковываются один раз.
     *
     * Широкая фаза сбрасывается при локальной смене поверхности, чтобы новая граничная
     * клетка участвовала в контактах уже в ближайшем проходе, а не через целый тик.
     */
    var tearQueue = IntArray(64)

    var tearQueueN = 0

    var tornTotal = 0

    /** Сколько рёбер очереди сняты тихо (временные связи роста, TempLinks.kt): в счёт разрывов они не идут. */
    var tearQueueQuiet = 0

    /**
     * ВРЕМЕННЫЕ СВЯЗИ РОСТА (topology/TempLinks.kt): рёбра, которые снимает рождение клетки, — по клетке мира (CSR):
     * tempRetireEdge[tempRetireStart[i] until tempRetireStart[i + 1]]. Пусто — временных связей в мире нет.
     * Производное от описания и номеров рёбер (rebuildTempRetire), в снимок не входит.
     */
    var tempRetireStart = IntArray(0)
    var tempRetireEdge = IntArray(0)
    /** Вторая клетка пары: −1 — клетка ложится на связь сама; иначе связь снимет ожившая пересекающая связь с ней. */
    var tempRetireOther = IntArray(0)
    /** Признак временного ребра по номеру ребра — для пробоя (applyPierce). Производное, как указатель выше. */
    var tempEdge = BooleanArray(0)
    /**
     * Временные рёбра, на которые ткань уже легла, но снять их — оторвать клетку от тела (ребро — мост): ждут обходного
     * пути, проверяются на каждом рождении (TempLinks.kt). Производное: строится заново вместе с указателем.
     */
    var tempPending = IntArray(0)
    var tempPendingN = 0
    /** Метки и очередь обхода «есть ли путь в обход ребра» — рабочие, без выделений на рождение. */
    var tempStamp = IntArray(0)
    var tempStampId = 0
    var tempQueue = IntArray(0)

    /**
     * СОБЫТИЯ ДЛЯ КЛИЕНТА (игра, этап B2): что погибло за последний шаг мира, в порядке гибели.
     * Рёбра — парами частиц (номер ребра меняет уплотнение, частицы между шагами — нет),
     * клетки — номером частицы. Пишут killEdge (разрыв, пробой, гибель клетки; удаление тела
     * целиком — нет) и killIncidentEdges (любая гибель клетки: глубокий контакт, давление,
     * раздавливание, причина клиента). Однопоточно, как tearQueue. Чистит PhysicsWorld:
     * в начале шага, при уплотнении и записи снимка — номера там уже другие или прочитаны.
     */
    var edgeDeathLog = IntArray(64)
    var edgeDeathN = 0
    var cellDeathLog = IntArray(32)
    var cellDeathN = 0

    fun clearDeathLogs() { edgeDeathN = 0; cellDeathN = 0 }

    /** Разбор цены пересборки по частям: списки, контур, изгиб+лоскуты, организмы, контакты. */
    val dbgRebuildSec = LongArray(5)

    /** То же, но НАКОПИТЕЛЬНО за весь прогон: хронология обнуляет посекундные. */
    val dbgRebuildTot = LongArray(5)

    /**
     * Разбор уплотнения мира (WorldCompaction), наносекунды последнего вызова: 0 разрывы,
     * 1 карты и описание, 2 массивы и списки, 3 топология, 4 контур и поверхность,
     * 5 сон и пределы, 6 контакты, 7 решатель (блоки, план).
     */
    val dbgCompactNs = LongArray(8)

    var dbgRebuildCount = 0L

    /**
     * ПОТОЛОК СКОРОСТИ — см. MAX_SPEED_CELLS_PER_TICK.
     *
     * Не физика, а гарантия для коллизий: путь за подшаг обязан оставаться конечным,
     * иначе DDA упрётся в размер буфера, а оценка «широкую фазу можно строить раз в
     * тик» перестанет быть верхней границей.
     */
    /** Диагностика: выключить потолок скорости. Он правит частицы ПО ОДНОЙ. */
    var dbgNoClamp = false

    /** Сколько раз потолок скорости сработал. Видно в HUD. */
    var speedCapHits = 0

    /** Наибольшая увиденная скорость в квадрате, ДО обрезки. Замер, а не гадание. */
    var peakSpeed2 = 0.0f

    var dragShiftX = 0.0f

    var dragShiftY = 0.0f

    /**
     * Направление обхода связей в solveConstraints. Переворачивается каждый подшаг.
     *
     * ЭТО ЛЕЧИТ ВТОРОЙ ИСТОЧНИК БЛУЖДАНИЯ — паразитный МОМЕНТ, который к точности
     * счёта отношения не имеет вовсе.
     *
     * Откуда он берётся. Отдельно взятое ограничение расстояния момента не создаёт
     * никогда: поправка идёт вдоль связи, а плечо у неё — сама связь, и векторное
     * произведение равно нулю ТОЧНО. Но Гаусс-Зейдель обходит связи подряд, и каждая
     * следующая видит позиции, уже сдвинутые предыдущими. Плечо, относительно которого
     * поправка была безмоментной, к этому времени уже уехало. Ошибка второго порядка по
     * величине поправки, но она СИСТЕМАТИЧЕСКАЯ — знак у неё задаёт порядок нумерации
     * связей, а он от кадра к кадру один и тот же.
     *
     * ЗАМЕР, который это доказывает. Мышца сокращена и удерживается 2000 кадров,
     * внешних сил нет вовсе, всё в double (чтобы округление не мешало):
     *
     *     обход вперёд   ->  L = +4.4269
     *     обход назад    ->  L = -4.3953      знак ПЕРЕВЕРНУЛСЯ
     *     чередуя        ->  L = +0.0813      в 54 раза меньше
     *
     * Знак переворачивается вместе с порядком — значит дело именно в порядке, а не в
     * физике и не во float. Отсюда и лекарство: чередовать. Это симметричный
     * Гаусс-Зейдель, стандартный приём, а не подпорка; стоит он ровно ноль — цикл тот
     * же, просто индекс идёт в другую сторону.
     *
     * ЧТО БЫЛО БЕЗ ЭТОГО: омега 4.4e-2 рад/с, то есть тело само разворачивалось на
     * полный круг за 144 секунды симуляции. На ускорении это минута с небольшим.
     *
     * АЛЬТЕРНАТИВА, которая тоже работает, но дороже: поднять число подшагов. Замер
     * (double, обход вперёд): 4 -> L 4.43, 8 -> 1.15, 16 -> 0.29, 32 -> 0.017. То есть
     * восемь подшагов вместо чередования дадут вчетверо худший результат при вдвое
     * большей цене. Чередование лучше по обоим счётам.
     */
    var sweepBackwards = false

    /**
     * Как часто менять направление обхода: 0 — никогда, 1 — раз в подшаг, 2 — каждый
     * проход решателя. Переменная окружения RB_SWEEP. См. SWEEP_FLIP.
     */
    var sweepFlip = config["RB_SWEEP"]?.toIntOrNull() ?: SWEEP_FLIP

    /**
     * Убирает смещение центра масс, накопленное ВНУТРЕННИМИ стадиями подшага.
     *
     * [beforeX]/[beforeY] — сумма координат до них. Внутренние ограничения обязаны
     * сохранять центр масс точно, поэтому вся разница (за вычетом честного вклада
     * мыши) — это округление float32, и её можно вычесть без всякой физики.
     */
    // --- ЗАМЕР ПО СТАДИЯМ: кто сколько двигает позиции и во сколько скорости ---
    //
    // Смысл в одном: скорость здесь не интегрируется, а восстанавливается из
    // позиций как (px - prevX) / h. Значит любая позиционная поправка, чья угодно,
    // умножается на 480 и становится скоростью. Замер отвечает, чья именно.
    // Выключен по умолчанию: стоит копирования позиций на каждую стадию.
    var dbgStages = false

    /**
     * Стадии: 0 связи, 1 площади, 2 изгиб, 3 кость, 4 предел длины, 5 контакты,
     * 6 широкая фаза+CCD, 7 отскок, 8 мышь+вязкость, 9 лоскуты, 10 анизотропная среда,
     * 11 изотропная среда+пол+потолок скорости, 12 снятие дрейфа.
     */

    val dbgStageMove = FloatArray(DBG_STAGES)

    /**
     * Сколько МОМЕНТА ИМПУЛЬСА внесла стадия. Считается как сумма m * (r x dp) / h.
     *
     * Парная поправка вдоль линии между клетками даёт тождественный ноль, и то же
     * верно для площади: сумма r x grad по трём вершинам треугольника сокращается
     * тождественно. Поэтому ненулевое число здесь сразу называет виновника, и гадать
     * по списку стадий больше не нужно.
     */
    val dbgStageL = FloatArray(DBG_STAGES)

    /** Сколько ЛИНЕЙНОГО импульса внесла стадия, вектором. Тяга берётся отсюда. */
    val dbgStagePx = FloatArray(DBG_STAGES)

    val dbgStagePy = FloatArray(DBG_STAGES)

    val dbgStageHits = IntArray(DBG_STAGES)

    /** Сумма модулей смещений стадии по замеряемым клеткам — сколько она РАБОТАЕТ. */
    val dbgStageAbs = FloatArray(DBG_STAGES)

    /** Если задан, замер идёт только по этим клеткам. */
    var dbgOnly: BooleanArray? = null

    var dbgSnapX = FloatArray(0)

    var dbgSnapY = FloatArray(0)

    /**
     * ЗАМЕР ВРЕМЕНИ ПО СТАДИЯМ: включается на время опыта, см. PerfBench.
     *
     * Отдельно от замера по импульсу (dbgStages): тот отвечает на вопрос «кто добавил
     * движение», этот — «кто съел время». Имена стадий в dbgTimeNames.
     */
    var dbgTimeOn = false

    val dbgTimeNs = LongArray(25)

    // «Команды мышц» — шаг множителей клеток раз в тик (advanceCellScales: кольца, связи и треугольники движущихся
    // мышц), «плавность мышц» — ведение их множителей по подшагам (applyCellScaleLerp). До 24.11 обе шли «вне таймеров»,
    // а на настоящих геномах с работающими мышцами это 22–48% тика (находка 40).
    val dbgTimeNames = arrayOf(
        "связи", "лоскуты", "предел длины", "площади", "изгиб", "кость",
        "подготовка контактов", "решатель контактов", "снятие момента",
        "скорости и среда", "ловушка", "пересборка", "локальная поверхность", "контактные кости",
        "ориентация контура", "интегрирование", "учёт импульса",
        "восстановление скоростей", "пластичность", "давление и инверсии", "сон",
        "команды мышц", "плавность мышц", "острова", "разбиение на острова")

    var dbgTimeT0 = 0L

    var dbgTimeSurface0 = 0L

    /**
     * КАКАЯ СТАДИЯ ДВИГАЕТ КЛЕТКУ. Знак среды RB_WATCH=номер клетки.
     *
     * Вопрос игрока: «какая-то стадия в обход контактов выталкивает частицы за другие
     * частицы — возможно площади». Площади замером отвергнуты (без них ХУЖЕ: 596
     * оставшихся внутри против 461), значит надо смотреть по стадиям поимённо, а не
     * гадать. Здесь копится путь, который клетке дала каждая стадия за тик, в связях.
     */
    var watchCell = config["RB_WATCH"]?.toIntOrNull() ?: -1
        set(v) { field = v; if (v >= 0) dbgStages = true }

    val watchStage = FloatArray(12)

    val watchNames = arrayOf("связи", "площади", "изгиб", "кость", "предел",
        "контакт", "подготовка", "контакт2", "прочее", "лоскуты", "обход", "пересборка")

    var dbgSnapVx = FloatArray(0)

    var dbgSnapVy = FloatArray(0)

    /**
     * СНЯТИЕ ИМПУЛЬСА И МОМЕНТА ВНУТРЕННИХ СТАДИЙ — см. InternalMomentum, там и замеры.
     * Контакты копят свои смещения в extDx/extDy, и из снимаемого они вычитаются.
     */
    var internalMom: InternalMomentum? = null
    internal var tickMomentumState: TickMomentum? = null
    /** Учёт импульса за тик; под нынешнее число частиц — после дописывания тела заводится заново. */
    val tickMomentum: TickMomentum
        get() {
            val t = tickMomentumState
            if (t != null && t.n == n) return t
            return TickMomentum(n, SPIN_MIN_ARM * linkScale).also { tickMomentumState = it }
        }

    /** Включено ли снятие. Переменной, а не константой, — ради A/B на журнале игрока. */
    var cancelSpinOn = CANCEL_INTERNAL_SPIN

    /** Сколько раз потолок кости сработал с последнего сброса. Показывается в HUD. */
    var boneCapHits = 0

    /**
     * Анизотропное сопротивление среды на границе тела — то, что и создаёт тягу.
     *
     * Для каждого граничного ребра берётся скорость его середины, из неё выделяется
     * составляющая ВДОЛЬ НОРМАЛИ к ребру, и гасится только она. Тангенциальная остаётся
     * нетронутой: ребро, скользящее вдоль себя, среду не гребёт.
     *
     * Знак нормали не важен: выражение (v·n)·n не меняется при n -> -n, поэтому обходить
     * контур и выяснять, где у тела «наружу», не требуется.
     *
     * Сила пропорциональна ДЛИНЕ ребра: это площадь омываемой поверхности, и без неё
     * мелкие рёбра гребли бы наравне с крупными.
     *
     * Это ВНЕШНЯЯ сила, она осознанно не сохраняет импульс — среда его и уносит.
     */
    /** Диагностика: выключить анизотропное сопротивление среды, то есть тягу. */
    var dbgDragOff = false

    /** Во сколько раз крепче на разрыв связь мышцы с костью. См. TENDON_TEAR_FACTOR. */
    var tendonTear = config["RB_TENDON"]?.toFloatOrNull() ?: TENDON_TEAR_FACTOR

    /** Запас до разрыва сверх предела длины, в долях покоя. См. LINK_TEAR_STRAIN. */
    var linkTearStrain = config["RB_TEAR"]?.toFloatOrNull() ?: LINK_TEAR_STRAIN

    /** Доля касательного трения от нормального. См. TANGENT_DRAG. */
    var tangentDrag = config["RB_TANGENT"]?.toFloatOrNull() ?: TANGENT_DRAG

    /** Среда мира, которую клиент меняет на ходу (гравитация, среда, мягкость, трение). См. WorldEnvironment. */
    val environment = WorldEnvironment()

    /**
     * Изотропное сопротивление среды. Нормировано на h, поэтому от числа подшагов
     * не зависит. Тягу не создаёт — см. комментарий у MEDIUM_DRAG.
     */
    /** Давление контактов на клетку за текущий тик, мировые единицы смещения. Обнуляется в начале тика. */
    var contactPressure = FloatArray(0)

    /** Сколько толчков контакта получила клетка за тик. Пара к contactPressure. */
    var contactPressureN = IntArray(0)

    /** Разбор: зовётся в конце каждого подшага с его номером. */
    var dbgSubstepHook: ((Int) -> Unit)? = null

    /** Диагностика: выключить и изотропное сопротивление среды. */
    var dbgIsoDragOff = false

    /**
     * КЛЕТКА ЛОПАЕТСЯ ОТ ДАВЛЕНИЯ: если контакты за тик толкали её сильнее порога, она
     * умирает, как смерть от проникновения — связи рвутся, из контактов и отрисовки она
     * уходит. Кости бессмертны. Раз в тик, чтобы пересборка была одна на всех погибших.
     * Порог в связях смещения за тик, см. PRESSURE_BURST_LINKS; ноль — выключено.
     */
    /** Опыт: стадия площадей. Выключается RB_AREAS=0. См. место вызова. */
    var areasOn = config["RB_AREAS"] != "0"

    var pressureBurst = config["RB_BURST"]?.toFloatOrNull() ?: PRESSURE_BURST_LINKS

    var pressureBurstCount = 0

    /** Клетки, у которых на этом тике связь порвалась на сжатии. См. CRUSH_BURST. */
    var crushedCell = BooleanArray(0)

    var crushBurstCount = 0

    /** Связь лежит на контуре тела. См. MEMBRANE_TEAR_STRAIN. */
    var conBoundary = BooleanArray(0)

    /** Связь мышцы с костью — сухожилие. См. TENDON_TEAR_FACTOR. */
    var conTendon = BooleanArray(0)

    /** Длина, при которой щель контура становится проходимой. См. membranePassTear. */
    var membraneTearCount = 0

    var membraneTearStrain = config["RB_MEMBRANE"]?.toFloatOrNull() ?: MEMBRANE_TEAR_STRAIN

    var crushBurstOn = CRUSH_BURST && config["RB_CRUSH_OFF"] == null

    /**
     * ЗАСТРЯВШАЯ В ЧУЖОЙ ТКАНИ КЛЕТКА ЛОПАЕТСЯ: центр внутри живого треугольника другого
     * организма столько тиков подряд. 0 — не убивать, меньше нуля — не искать вовсе.
     */
    var trapKillTicks = config["RB_TRAP"]?.toIntOrNull() ?: TRAP_KILL_TICKS

    /**
     * РВАТЬ МЕМБРАНУ, КОГДА ЩЕЛЬ СТАЛА ПРОХОДИМОЙ. Переменная окружения RB_PASS.
     *
     * Круги контакта двух соседей по контуру перекрываются, пока ребро короче суммы их
     * радиусов. Растянутое ребро открывает коридор L - (ra + rb), и чужая клетка радиуса
     * r пройдёт в него, когда коридор шире 2r: её центр минует обе запретные зоны.
     * Рвать надо ровно тогда, когда это стало возможно: дырявая мембрана уже не мембрана,
     * а пролезшую клетку потом приходится ловить отдельным обходом.
     *
     * Радиус контакта — половина САМОГО ДЛИННОГО граничного ребра клетки, поэтому у
     * клетки с короткими соседними рёбрами он мал, и её длинное ребро открывается
     * задолго до порога разрыва. Замер на стенде столкновений (RB_SEAL=1, только живые
     * связи): проходимы 2 574 630 проверок из 742 919 534, худший коридор впятеро шире
     * нужного при растяжении всего 1.299 — то есть щель успевала открыться, а связь
     * ещё держалась.
     *
     * И ВСЁ ЖЕ ПО УМОЛЧАНИЮ ВЫКЛЮЧЕНО. Разбивка тех же проверок по состоянию разрыва:
     * при ВКЛЮЧЁННОМ разрыве проходимых 0 из 1 795 104 проходов, все 5 113 084 случая —
     * в сценах стенда, где разрыв намеренно выключён, чтобы мерить одни контакты. На
     * журнале игрока то же самое: худший коридор 0.378 от нужного, то есть и близко нет.
     * Значит связь всегда успевает порваться раньше, чем щель станет проходимой, и
     * заменить этим отлов застрявших (scanTrapped) НЕЛЬЗЯ: клетки попадают внутрь не
     * через щель, а прошивая мембрану на скорости — в разборе журнала 8 клеток за тик
     * при радиусе 0.5. Лечится это в CCD, а не в пороге разрыва.
     */
    var membranePassTear = config["RB_PASS"]?.let { it != "0" } ?: MEMBRANE_PASS_TEAR

    var membranePassCount = 0

    var trapKillCount = 0

    /** Сколько тиков подряд центр клетки в чужой ткани. См. scanTrapped. */
    var trapTicks = IntArray(0)

    /** В чьём теле сидит застрявшая клетка. Нужно разбору и правилу пробоя. */
    var trapOrg = IntArray(0)

    var trapGridStart = IntArray(0)

    var trapTriBox = IntArray(0)

    var trapGridItems = IntArray(0)

    var trapGridCount = IntArray(0)

    /** Разбор последнего обхода: в чужой ткани, из них в треугольнике без граничных вершин, костей, своих складок. */
    var dbgTrapNow = 0

    var dbgTrapDeep = 0

    var dbgTrapBone = 0

    var dbgTrapFold = 0

    var dbgTrapNs = 0L

    /** Разбор: сколько тиков клетка пробыла в чужой ткани, прежде чем вышла сама. Корзины — dbgTrapEdges. */
    val dbgTrapExit = IntArray(8)

    val dbgTrapEdges = intArrayOf(1, 2, 5, 10, 30, 90, 300, Int.MAX_VALUE)

    val dbgFoldExit = IntArray(8)

    /** Складки своей ткани тоже лопаются. См. scanTrapped. */
    var trapKillFolds = config["RB_TRAP_FOLD"]?.let { it != "0" } ?: TRAP_KILL_FOLDS

    var foldKillCount = 0

    var foldTicks = IntArray(0)

    /**
     * ПАДАТЬ НА ПРОНИКНОВЕНИИ — отладочный режим охоты, а не лечение.
     *
     * Отлов застрявших (scanTrapped) как КОСТЫЛЬ убивал клетку через десять тиков и тем
     * самым скрывал причину: прорыв случился, его замяли, разбираться не с чем. Здесь он
     * работает наоборот — как утверждение в коде: любая клетка, чей центр оказался внутри
     * чужого треугольника, это ошибка, и симуляция немедленно останавливается с полным
     * описанием места. Дальше место воспроизводится тем же посевом и разбирается по
     * подшагам. См. TrapHunt.
     *
     * 0 — выключено, 1 — чужая ткань, 2 — ещё и свои складки.
     */
    var trapCrash = config["RB_TRAP_CRASH"]?.toIntOrNull() ?: 0

    var trapCrashCell = -1

    var trapCrashTri = -1

    var trapCrashFold = false

    /** Тиков с запуска окна. В отличие от logTick, сбросом не обнуляется. */
    var playTick = 0

    /** Идёт прогрев: печатать нечего, окна у этой копии нет. */
    var quiet = false

    fun isbendAInitialized() = ::bendA.isInitialized
    fun isboundAInitialized() = ::boundA.isInitialized
    fun isisFreeInitialized() = ::isFree.isInitialized
    fun isorganismOfInitialized() = ::organismOf.isInitialized
    fun isorganismSizeInitialized() = ::organismSize.isInitialized
    fun isradiusInitialized() = ::radius.isInitialized
}
