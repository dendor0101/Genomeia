package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** Число подшагов эталона за тик. У быстрого решателя — SUBSTEPS (с 24.11 — 20, то есть эталон мельче в 3.2 раза; при шести было вдесятеро). */
const val REFERENCE_SUBSTEPS = 64

/**
 * До скольких участников касаний эталон перебирает все пары; выше — та же проверка пар через сетку.
 * Перебор стоит участников² / 2 проверок на подшаг: при 64 участниках это 130 тысяч проверок за тик,
 * при 400 — уже 5 миллионов, и каталог стендов сверялся бы часами.
 */
const val REFERENCE_ALL_PAIRS = 64

/**
 * Сколько раз за подшаг эталон самое большее проходит по касаниям. При одновременном решении тело с k
 * касаниями снимает за проход лишь 1/k перекрытия, поэтому проходы идут до сходимости: пока самое
 * глубокое перекрытие не станет меньше REFERENCE_CONTACT_SLACK радиуса. Так касание одинаково жёстко
 * при любом числе подшагов.
 */
const val REFERENCE_CONTACT_SWEEPS = 64
const val REFERENCE_CONTACT_SLACK = 1e-3f

/** С какой точностью снимается лишняя скорость выталкивания старого перекрытия (restitution), связей за тик. */
const val REFERENCE_SPEED_SLACK = 1e-6f

/**
 * С какой глубины, в связях, первое касание пары считается проскоченным ударом
 * (ReferenceSolver.missedHits) — вдобавок к «глубже половины дистанции касания». Нужна из-за
 * новорождённых клеток: их дистанция касания первые тики — десятая связи, и без этой мерки
 * проскоченным выходило любое их касание.
 */
const val REFERENCE_MISSED_DEPTH = 0.25f

/**
 * ЭТАЛОННЫЙ РЕШАТЕЛЬ — ТА ЖЕ ФИЗИКА, НАПИСАННАЯ ПРОСТО И МЕДЛЕННО.
 *
 * Зачем. Быстрый решатель (BodySimulation.solveTick) набит ускорениями: блоки и швы, сон, широкая
 * фаза с запомненными парами, обрезка по времени удара, площади через подшаг, скорости «только
 * тронутым», воркеры. Каждое из них может тихо изменить саму физику. Эталон не знает ни одного:
 * один поток, все частицы каждый подшаг, 64 подшага, пары касания перебором. Быстрый сверяется с
 * ним по крупным величинам (где тело, как повёрнуто, цело ли) с допуском — стенды, -Preference=1.
 * Разошлись сильно — либо ускорение что-то сломало, либо шаг быстрого слишком груб для этой сцены;
 * оба ответа нужны.
 *
 * Он же — описание физики для переноса (Rust): каждая стадия ниже — один закон в десяток строк.
 *
 * ЧТО ОБЩЕЕ С БЫСТРЫМ. Только данные и учёт топологии: массивы BodyState, кто с кем связан и какая
 * связь жива, контур, состав костей, радиусы касания и исключённые пары (BoundaryContacts как
 * справочник, без его поиска пар и без его решения), рождение и гибель клеток, пластика и снятие
 * вывернутых треугольников раз в тик. Ни одной стадии быстрого решателя эталон не зовёт. Формулы закона волокна
 * мышцы (MuscleFiber.kt: цель длины связи, площадь, мягкость расслабления, длины для предела) — общие с быстрым:
 * это сам закон, а не способ его считать (внесены 24.11.2026, до того эталон волокна не знал).
 *
 * ЧЕМ ЗАКОНЫ ОТЛИЧАЮТСЯ — нарочно, там, где у быстрого закон привязан к его подшагу:
 *  - касание снимает перекрытие целиком, а не 0.8 за подшаг (CT_OMEGA_PAIR): круги не перекрываются;
 *  - перекрытие, которое уже было к началу подшага (тела поставили внахлёст, порвалась связь между
 *    сблизившимися клетками), расталкивает пару со скоростью «перекрытие за подшаг игры» при любом
 *    числе подшагов, а не «перекрытие за свой подшаг» — см. restitution;
 *  - касания решаются все разом, с делением массы тела между его касаниями: итог не зависит от
 *    того, в каком порядке они лежат в памяти (у жёсткого касания по очереди первое по списку
 *    брало бы весь удар на себя и закручивало тело);
 *  - кость в касании и в пределе длины отвечает как твёрдое тело, с массой и моментом инерции; у
 *    быстрого предел и ориентация контура костную клетку не двигают, а отдачу возвращает учёт импульса;
 *  - то, что у игры задано «за подшаг», пересчитано на свой подшаг, чтобы за тик выходило то же:
 *    хватка (1 − grip) и потолки возврата — связи к пределу длины (LINK_MAX_STEP) и треугольника к
 *    своей площади (AREA_MAX_STEP). Потолки — часть закона, а не страховка: без них положение,
 *    выставленное рывком (зажим стенда, вывернутый ударом треугольник), превращается в скорость
 *    «поправка / подшаг», и чем мельче подшаг, тем она больше. У эталона потолок — это скорость
 *    возврата: в него входит и то, что связь или треугольник уже прошли за подшаг сами (иначе
 *    поправка каждого подшага ложилась бы поверх набранной скорости, и за время одного подшага
 *    игры возврат разгонялся бы вчетверо сильнее, чем у неё). С потолком закон разрыва растяжением
 *    не зависит от подшага: связь рвётся, когда её тянут быстрее, чем предел успевает возвращать;
 *  - кость ставится в позу точно, без потолка шага (BONE_MAX_STEP);
 *  - рамка мира в учёте импульса за тик — опора, как неподвижная частица: тело, упёршееся в неё,
 *    в этом тике из учёта выходит. У быстрого опорой считается только пол (inContact), а отнятое
 *    рамкой учёт возвращает телу; скорость в стену гасит отдельный проход в конце шага (clampToBounds);
 *  - нет обрезки по времени удара. Поэтому на крупном шаге эталон быстрый удар проскакивает: пара,
 *    сближающаяся за подшаг больше чем на половину дистанции касания, оказывается сразу убитой
 *    глубоким проникновением или по другую сторону друг от друга. Такие касания он считает
 *    (missedHits), и сверка стендов прогон с ними не судит: это уже не закон, а случай;
 *  - нет гибели от давления касаний и пробоя ребра клеткой: это сторожа быстрого решателя.
 * Разрыв связи растяжением и сжатием, гибель при глубоком проникновении, потолок скорости, среда,
 * учёт импульса за тик (cancelMomentum) — те же правила.
 *
 * Сна эталон не знает: мир для него собирается с выключенным сном.
 */
class ReferenceSolver(private val st: BodyState, val substeps: Int = REFERENCE_SUBSTEPS) {
    init { require(substeps > 0) { "подшагов должно быть больше нуля" } }

    private var startX = FloatArray(0); private var startY = FloatArray(0)
    private var jitterX = FloatArray(0); private var jitterY = FloatArray(0)
    /** Направление обхода ограничений: меняется каждый подшаг, чтобы порядок не копил перекос. */
    private var backwards = false

    // Кости на подшаг: масса, момент инерции и центр масс включённых живых клеток (projectBones).
    private var boneMass = FloatArray(0); private var boneInertia = FloatArray(0)
    /** Квадрат наименьшего радиуса инерции, у которого есть поворот (SPIN_MIN_ARM в долях связи мира). */
    private val minArm2 = (SPIN_MIN_ARM * st.linkScale) * (SPIN_MIN_ARM * st.linkScale)
    private var boneCx = FloatArray(0); private var boneCy = FloatArray(0)

    // Касания подшага: пара, расстояние упора, скорость сближения до решения, нормаль.
    private var cI = IntArray(256); private var cJ = IntArray(256)
    private var cRest = FloatArray(256); private var cApproach = FloatArray(256)
    private var cNx = FloatArray(256); private var cNy = FloatArray(256)
    /** Наибольшая скорость расхождения, которую пара вправе вынести из касания (restitution); бесконечность — без предела. */
    private var cGoal = FloatArray(256)
    private var cN = 0
    private var members = IntArray(0)
    private var cellHead = IntArray(0); private var cellNext = IntArray(0); private var cellOf = IntArray(0)
    private var pairKeys = LongArray(256)
    private var kills = IntArray(16); private var killN = 0
    // Одновременное решение касаний: сколько касаний у частицы и у кости в этом проходе и что им набежало.
    private var touch = IntArray(0); private var accX = FloatArray(0); private var accY = FloatArray(0)
    private var dirty = IntArray(0); private var dirtyN = 0
    private var boneTouch = IntArray(0); private var boneJx = FloatArray(0); private var boneJy = FloatArray(0)
    private var boneTurn = FloatArray(0); private var dirtyBone = IntArray(0); private var dirtyBoneN = 0
    private var cActive = BooleanArray(256)

    private var orgX = FloatArray(0); private var orgY = FloatArray(0)
    // Учёт импульса за тик (cancelMomentum). На частицу: импульс и момент импульса, которые ей положены
    // (что было в начале тика плюс данное внешними стадиями), скорость до внешних стадий подшага, группа
    // тел (group — предок, корень — наименьший номер частицы) и «опирается на неподвижное».
    private var dueX = FloatArray(0); private var dueY = FloatArray(0); private var dueSpin = FloatArray(0)
    private var wasVx = FloatArray(0); private var wasVy = FloatArray(0)
    private var group = IntArray(0); private var propped = BooleanArray(0)
    private var firstOf = IntArray(0)
    // Суммы группы, по номеру её корня: масса, центр масс, лишний импульс, лишний момент импульса, момент инерции.
    private var gMass = FloatArray(0); private var gCx = FloatArray(0); private var gCy = FloatArray(0)
    private var gX = FloatArray(0); private var gY = FloatArray(0); private var gSpin = FloatArray(0)
    private var gInertia = FloatArray(0)

    /** Счётчики за всё время — для отчёта сверки. */
    var tornByStretch = 0; private set
    var tornByCrush = 0; private set
    var deepKills = 0; private set
    var contactsSolved = 0L; private set
    /**
     * Сколько раз касание застало пару, которая в начале подшага не касалась, уже убитой глубоким
     * проникновением либо далеко за точкой касания (глубже половины дистанции касания и глубже
     * REFERENCE_MISSED_DEPTH связи; в том числе по другую сторону друг от друга): шаг крупнее удара.
     * Замер 16.11.2026, две частицы навстречу на 6 связях за тик каждая, 6 подшагов: проходят друг
     * сквозь друга, энергия растёт на 32%; на 64 подшагах — обычный отскок. У быстрого решателя от
     * этого обрезка по времени удара.
     */
    var missedHits = 0; private set
    /** Наибольшее число касаний в одном подшаге и сколько подшагов искали пары сеткой. */
    var peakContacts = 0; private set
    var gridSubsteps = 0L; private set

    /** Один тик. Входы тика (мышцы, рост, временные связи) уже выставлены — см. PhysicsWorld.stepReference. */
    fun tick() {
        check(!st.sleep.on) { "эталон не знает сна: мир для него собирается с выключенным сном" }
        st.splitDirtyBones()
        if (st.tearsPending) st.refreshSurface()
        val n = st.n
        if (startX.size < n) { startX = FloatArray(n); startY = FloatArray(n); jitterX = FloatArray(n); jitterY = FloatArray(n) }
        System.arraycopy(st.px, 0, startX, 0, n); System.arraycopy(st.py, 0, startY, 0, n)
        if (st.crushedCell.size == n) java.util.Arrays.fill(st.crushedCell, false)
        if (st.contactPressure.size == n) java.util.Arrays.fill(st.contactPressure, 0.0f)
        val h = DT / substeps
        beginMomentum()
        for (step in 0 until substeps) {
            // Команда мышцы доезжает до цели за тик, а не прыгает в первом подшаге.
            st.updateMuscleScale((step + 1).toFloat() / substeps)
            st.applyCellScaleLerp(step, substeps)
            st.applyGrowthLerp(step, substeps)
            backwards = !backwards
            integrate(h)
            solveLinks(h)
            solveFlaps(h)
            if (st.areasOn) solveAreas(h)
            solveBend(h)
            projectBones()
            if (limitLinks()) projectBones()
            solveJoints(h)
            if (st.contactsOn && st.contacts != null) {
                findContacts()
                if (cN > 0) {
                    // Тела, коснувшиеся друг друга, до конца тика — одна группа в учёте импульса.
                    for (c in 0 until cN) bind(cI[c], cJ[c])
                    solveContacts(); projectBones()
                }
                if (orientBoundary()) projectBones()
            }
            updateVelocities(h)
            // Внешние стадии идут по скоростям, между снимком и учётом: их вклад в импульс законен.
            markExternal()
            grip(h)
            restitution(h)
            st.applyDragVelocity(h)
            // Среда — раз в тик: вязкость ткани, сопротивление контура, общее торможение.
            if (step == substeps - 1) { viscosity(DT); normalDrag(DT); mediumDrag(DT) }
            noteExternal()
            // Потолок — после учёта: срез скорости частицы — не сила среды, срезанное учёт вернёт телу целиком.
            clampSpeed()
        }
        cancelMomentum()
        // Поправка учёта тоже меняет скорости: потолок остаётся последним.
        clampSpeed()
        // Медленные законы материала и уборка топологии — общие с быстрым решателем, раз в тик.
        st.refreshSurface()
        st.applyPlasticity(DT)
        st.burstByPressure(); st.removePersistentInversions()
        if (st.tearsPending && st.compactionDue()) st.rebuildTimed()
    }

    // --- движение ---

    /** Предсказание: положение сдвигается на скорость × подшаг. Внешних сил (тяжести) в игре нет. */
    private fun integrate(h: Float) {
        val px = st.px; val py = st.py; val qx = st.prevX; val qy = st.prevY
        val vx = st.vx; val vy = st.vy; val w = st.invMass
        for (i in 0 until st.n) {
            qx[i] = px[i]; qy[i] = py[i]
            if (w[i] == 0.0f) continue
            px[i] += vx[i] * h; py[i] += vy[i] * h
        }
        if (st.boundsOn) keepInBounds()
    }

    /**
     * Рамка мира: мягкая клетка в неё упирается, кость сдвигается целиком, чтобы влезли все её клетки.
     * Упёршаяся частица помечается опёртой: рамка — внешняя сила, учёт импульса такое тело не трогает.
     */
    private fun keepInBounds() {
        val px = st.px; val py = st.py; val w = st.invMass; val r = st.radius
        val x0 = st.boundsMinX; val x1 = st.boundsMaxX; val y0 = st.boundsMinY; val y1 = st.boundsMaxY
        for (i in 0 until st.n) {
            if (w[i] == 0.0f || boneIndex(i) >= 0) continue
            val x = px[i].coerceIn(minOf(x0 + r[i], x1 - r[i]), maxOf(x0 + r[i], x1 - r[i]))
            val y = py[i].coerceIn(minOf(y0 + r[i], y1 - r[i]), maxOf(y0 + r[i], y1 - r[i]))
            if (x != px[i] || y != py[i]) { px[i] = x; py[i] = y; propped[i] = true }
        }
        if (!st.bonesRigid) return
        for (b in st.rigidBones.indices) {
            var lowX = Float.NEGATIVE_INFINITY; var highX = Float.POSITIVE_INFINITY
            var lowY = Float.NEGATIVE_INFINITY; var highY = Float.POSITIVE_INFINITY
            for (i in st.rigidBones[b]) {
                if (st.boneOf[i] != b || w[i] == 0.0f) continue
                lowX = maxOf(lowX, x0 + r[i] - px[i]); highX = minOf(highX, x1 - r[i] - px[i])
                lowY = maxOf(lowY, y0 + r[i] - py[i]); highY = minOf(highY, y1 - r[i] - py[i])
            }
            val dx = if (lowX > highX) (lowX + highX) / 2 else 0.0f.coerceIn(lowX, highX)
            val dy = if (lowY > highY) (lowY + highY) / 2 else 0.0f.coerceIn(lowY, highY)
            if (dx == 0.0f && dy == 0.0f) continue
            for (i in st.rigidBones[b]) if (st.boneOf[i] == b && w[i] > 0.0f) { px[i] += dx; py[i] += dy; propped[i] = true }
        }
    }

    // --- учёт импульса за тик ---

    private fun root(i: Int): Int {
        var r = i
        while (group[r] != r) { group[r] = group[group[r]]; r = group[r] }
        return r
    }

    private fun join(i: Int, j: Int) {
        val a = root(i); val b = root(j)
        if (a != b) group[maxOf(a, b)] = minOf(a, b)
    }

    /** Касание или временная связь: два тела — одна группа. Неподвижный участник — опора для другого. */
    private fun bind(i: Int, j: Int) {
        if (st.invMass[i] == 0.0f || st.invMass[j] == 0.0f) { propped[i] = true; propped[j] = true } else join(i, j)
    }

    /** Начало тика: что положено каждой частице; группы — по организмам, временная связь объединяет тела сразу. */
    private fun beginMomentum() {
        val n = st.n; val w = st.invMass
        if (group.size < n) {
            dueX = FloatArray(n); dueY = FloatArray(n); dueSpin = FloatArray(n)
            wasVx = FloatArray(n); wasVy = FloatArray(n)
            group = IntArray(n); propped = BooleanArray(n)
            gMass = FloatArray(n); gCx = FloatArray(n); gCy = FloatArray(n)
            gX = FloatArray(n); gY = FloatArray(n); gSpin = FloatArray(n); gInertia = FloatArray(n)
        }
        if (firstOf.size < st.organismCount) firstOf = IntArray(st.organismCount)
        java.util.Arrays.fill(firstOf, 0, st.organismCount, -1)
        for (i in 0 until n) {
            val m = if (w[i] > 0.0f) 1.0f / w[i] else 0.0f
            dueX[i] = m * st.vx[i]; dueY[i] = m * st.vy[i]
            dueSpin[i] = m * (st.px[i] * st.vy[i] - st.py[i] * st.vx[i])
            group[i] = i; propped[i] = w[i] == 0.0f
        }
        for (i in 0 until n) {
            // Погибшая клетка заморожена там, где погибла: она сама по себе и свой организм опёртым не делает.
            val o = st.organismOf[i]
            if (o < 0 || st.cellDead[i]) continue
            if (firstOf[o] < 0) firstOf[o] = i else join(firstOf[o], i)
        }
        for (q in 0 until st.jointActiveN) {
            val k = st.jointActive[q]
            if (st.jointLive[k]) bind(st.jointA[k], st.jointB[k])
        }
    }

    /** Скорости до внешних стадий подшага. */
    private fun markExternal() {
        System.arraycopy(st.vx, 0, wasVx, 0, st.n); System.arraycopy(st.vy, 0, wasVy, 0, st.n)
    }

    /** Что внешние стадии подшага (хватка, отскок, мышь, среда) дали частице, ей положено. */
    private fun noteExternal() {
        val w = st.invMass
        for (i in 0 until st.n) {
            if (w[i] == 0.0f) continue
            val fx = (st.vx[i] - wasVx[i]) / w[i]; val fy = (st.vy[i] - wasVy[i]) / w[i]
            dueX[i] += fx; dueY[i] += fy; dueSpin[i] += st.px[i] * fy - st.py[i] * fx
        }
    }

    /**
     * ИМПУЛЬС И МОМЕНТ ИМПУЛЬСА ГРУППЫ ТЕЛ ЗА ТИК МЕНЯЮТ ТОЛЬКО ВНЕШНИЕ СИЛЫ: среда, хватка, мышь, отскок.
     * Группа — организмы, которые в этом тике касались друг друга или держатся временной связью. Стадии
     * положений (связи, площади, кость, пределы, касания) внутри группы — силы внутренние, но импульс
     * они сохраняют лишь до ошибки шага по времени (сила × скорость × подшаг²). Лишнее, набежавшее за
     * тик, снимается со скоростей движением группы как целого — сдвигом и поворотом вокруг центра масс:
     * формы тел это не меняет. Закон и запись те же, что у быстрого решателя (TickMomentum).
     *
     * Без него нельзя: поправки положений на шаге игры раскручивали тело из ничего — тело целиком из
     * мышцы, сжатое вдвое, поворачивалось на 0.15 радиана за 30 тиков на 6 подшагах (ноль на 64).
     *
     * Группа, опёртая на неподвижное (рельеф, закреплённая клетка, рамка мира) или потерявшая в этом
     * тике клетку, из учёта выходит: её импульс законно меняет опора.
     *
     * ОТВЕРГНУТО 16.11.2026: сохранять момент импульса каждый подшаг, поворачивая ПОЛОЖЕНИЯ организма
     * после его ограничений и группы после касаний. Обрывок из двух-трёх сжатых клеток вращается на
     * десятки градусов за подшаг игры; поправка такого размера сама вносит ошибку, и следующая выходит
     * больше — за три подшага поворот вырос с 0.54 до 0.96 радиана, а через тик координаты ушли в
     * 1e40 (4 сцены каталога из 7761: gen/maggot/max и ещё три). У поправки скоростей раз в тик такой
     * петли нет, и потолок скорости стоит после неё.
     */
    private fun cancelMomentum() {
        val n = st.n; val px = st.px; val py = st.py; val vx = st.vx; val vy = st.vy; val w = st.invMass
        java.util.Arrays.fill(gMass, 0, n, 0.0f); java.util.Arrays.fill(gCx, 0, n, 0.0f); java.util.Arrays.fill(gCy, 0, n, 0.0f)
        java.util.Arrays.fill(gX, 0, n, 0.0f); java.util.Arrays.fill(gY, 0, n, 0.0f)
        java.util.Arrays.fill(gSpin, 0, n, 0.0f); java.util.Arrays.fill(gInertia, 0, n, 0.0f)
        for (i in 0 until n) {
            val g = root(i)
            // Опёртая частица и клетка, погибшая в этом тике (её масса из группы ушла), выводят группу из учёта.
            if (propped[i] || w[i] == 0.0f) { propped[g] = true; continue }
            val m = 1.0f / w[i]
            gMass[g] += m; gCx[g] += m * px[i]; gCy[g] += m * py[i]
            gX[g] += m * vx[i] - dueX[i]; gY[g] += m * vy[i] - dueY[i]
            gSpin[g] += m * (px[i] * vy[i] - py[i] * vx[i]) - dueSpin[i]
        }
        for (g in 0 until n) {
            if (gMass[g] == 0.0f) continue
            gCx[g] /= gMass[g]; gCy[g] /= gMass[g]
            // Момент считался вокруг начала координат: лишний момент группы — вокруг её центра масс.
            gSpin[g] -= gCx[g] * gY[g] - gCy[g] * gX[g]
            gX[g] /= gMass[g]; gY[g] /= gMass[g]
        }
        for (i in 0 until n) {
            if (w[i] == 0.0f) continue
            val g = root(i); val rx = px[i] - gCx[g]; val ry = py[i] - gCy[g]
            gInertia[g] += (rx * rx + ry * ry) / w[i]
        }
        for (i in 0 until n) {
            if (w[i] == 0.0f) continue
            val g = root(i)
            if (propped[g]) continue
            val turn = if (gInertia[g] > gMass[g] * minArm2) gSpin[g] / gInertia[g] else 0.0f
            vx[i] -= gX[g] - turn * (py[i] - gCy[g])
            vy[i] -= gY[g] + turn * (px[i] - gCx[g])
        }
    }

    /** Скорость — это пройденное за подшаг: всё, что сделали ограничения с положением, становится скоростью. */
    private fun updateVelocities(h: Float) {
        val px = st.px; val py = st.py; val qx = st.prevX; val qy = st.prevY; val w = st.invMass
        for (i in 0 until st.n) {
            if (w[i] == 0.0f) { st.vx[i] = 0.0f; st.vy[i] = 0.0f; continue }
            st.vx[i] = (px[i] - qx[i]) / h; st.vy[i] = (py[i] - qy[i]) / h
        }
    }

    /**
     * Ни одна частица не идёт быстрее MAX_SPEED_CELLS_PER_TICK связей за тик. Срабатывания считаются
     * в тот же счётчик, что у быстрого решателя: по нему судит правило стендов «потолок скорости
     * молчит». Пока эталон счётчик не вёл (до 16.11.2026), это правило для него загореться не могло.
     */
    private fun clampSpeed() {
        if (st.dbgNoClamp) return
        val max = MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        for (i in 0 until st.n) {
            val v2 = st.vx[i] * st.vx[i] + st.vy[i] * st.vy[i]
            if (v2 <= max * max) continue
            val k = max / sqrt(v2)
            st.vx[i] *= k; st.vy[i] *= k
            st.speedCapHits++
        }
    }

    // --- ткань: связи, площади, складки ---

    /** Команда связи: множитель группы мышц × команда клеток (1 — покой, 0.5 — сжатие, 1.5 — расслабление). */
    private fun commandOf(c: Int): Float {
        val m = st.conMuscle[c]
        val group = if (m < 0) 1.0f else st.muscleScaleOf[m]
        return if (st.cellScalesOn || st.softnessOn) group * st.conCellScale[c] else group
    }

    /** Насколько мышца расслаблена: 0 при команде ≤ 1, 1 при команде 1.5 и выше. */
    private fun relaxation(command: Float): Float = if (command <= 1.0f) 0.0f else minOf(1.0f, (command - 1.0f) * 2.0f)

    /** Наибольшая длина связи: 1.05 длины покоя, у расслабленной мышцы — до 1.25. */
    private fun maxLength(rest: Float, command: Float): Float = rest * (LINK_MAX_STRETCH + (1.25f - LINK_MAX_STRETCH) * relaxation(command))

    /**
     * Связь — пружина XPBD с податливостью SOFT_COMPLIANCE (× мягкость ткани). Сжатая мышца тянется к
     * длине покоя × команда, но не сильнее связи, растянутой на MUSCLE_MAX_PULL длины покоя: у мышцы
     * есть наибольшая сила. Расслабленная длины не меняет, но растягивается вчетверо легче, а
     * внутренняя связь (у которой нет стадии предела) не идёт дальше своей наибольшей длины.
     *
     * МЫШЦА С ВОЛОКНОМ (закон 20–21.11, MuscleFiber.kt; в эталон внесён 24.11.2026). Сжатая мышца сокращается вдоль
     * волокна (× команда), поперёк — × команда^−k: цель у каждой связи своя, по косинусу её угла к волокну в позе
     * покоя (conFiberC2; 1 — волокна нет). Расслабленная мягче только вдоль волокна. До 24.11 эталон волокна не
     * знал и сжимал мышцу во все стороны; пока ткань была мягкой и мышца почти не сжимала тело, разница пряталась
     * (0.4 связи на 44 связях пути), с жёсткой тканью вышла наружу: 1.4–1.7 связи при любом шаге, а без волокон —
     * 0.000 на шаге игры и 0.014 на 64 подшагах (reference.md, находка 40).
     */
    private fun solveLinks(h: Float) {
        val px = st.px; val py = st.py; val w = st.invMass
        val count = st.conCount
        val fiber = if (st.fibersOn) st.conFiberC2 else NO_FLOATS
        val fiberK = st.fiberThicken
        for (q in 0 until count) {
            val c = if (backwards) count - 1 - q else q
            if (st.conDead[c]) continue
            val i = st.conA[c]; val j = st.conB[c]
            val wsum = w[i] + w[j]
            if (wsum == 0.0f) continue
            var dx = px[i] - px[j]; var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val rest = st.conRest[c]; val command = commandOf(c)
            var alpha = SOFT_COMPLIANCE / (h * h)
            if (st.softnessOn) alpha *= st.conSoftness[c]
            val c2 = if (c < fiber.size) fiber[c] else 1.0f
            val lambda = when {
                // Сжатая мышца с волокном, связь не вдоль него: своя цель длины и тот же потолок тяги (MuscleFiber.kt).
                command < 1.0f && c2 < 1.0f -> fiberLinkCorrection(len, rest, command, wsum, alpha, c2, fiberK)
                // Мышца тянет отстающую ткань не сильнее связи, растянутой на MUSCLE_MAX_PULL длины покоя.
                command < 1.0f -> -minOf(len - rest * command, MUSCLE_MAX_PULL * rest) / (wsum + alpha)
                command <= 1.0f -> -(len - rest * command) / (wsum + alpha)
                len <= rest -> -(len - rest) / (wsum + alpha)
                else -> {
                    // Расслабленная мышца с волокном мягче только вдоль него; предел длины — по команде, как у всей мышцы.
                    val soft = if (c2 < 1.0f) fiberRelaxedSoftness(command, c2) else command
                    val elastic = -(len - rest) / (wsum + alpha * (1.0f + 3.0f * relaxation(soft)))
                    if (len <= LINK_MAX_STRETCH * rest || st.conBoundary[c]) elastic
                    else minOf(elastic, (maxLength(rest, command) - len) / wsum)
                }
            }
            px[i] += dx * lambda * w[i]; py[i] += dy * lambda * w[i]
            px[j] -= dx * lambda * w[j]; py[j] -= dy * lambda * w[j]
        }
    }

    /**
     * Складка контура: соседи через одну по контуру не сходятся ближе, чем в позе покоя (одностороннее). У
     * новорождённой — взрослый угол при нынешних длинах, не длиннее взрослой: закон быстрого решателя (solveBlockFlaps,
     * находка 30).
     */
    private fun solveFlaps(h: Float) {
        val alpha = FLAP_COMPLIANCE / (h * h)
        val ramp = if (st.rampN > 0) st.contactRamp else null
        for (c in 0 until st.flapCount) {
            val i = st.flapA[c]; val j = st.flapB[c]; val v = st.flapV[c]
            var rest = st.flapRest[c]
            if (ramp != null && (ramp[i] < 1f || ramp[j] < 1f || ramp[v] < 1f)) rest = minOf(rest, st.flapAngleRest(v, i, j))
            pairSpring(i, j, rest, alpha, onlyPush = true)
        }
    }

    /** Изгиб контура: выключен, пока уровень изгиба ноль (BEND_LEVELS). */
    private fun solveBend(h: Float) {
        val compliance = st.bendCompliance()
        if (compliance < 0.0f) return
        val alpha = compliance / (h * h)
        for (c in 0 until st.bendCount) pairSpring(st.bendA[c], st.bendB[c], st.bendRest[c], alpha, onlyPush = false)
    }

    private fun pairSpring(i: Int, j: Int, rest: Float, alpha: Float, onlyPush: Boolean) {
        val px = st.px; val py = st.py; val w = st.invMass
        val wsum = w[i] + w[j]
        if (wsum == 0.0f) return
        var dx = px[i] - px[j]; var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-12f || (onlyPush && len >= rest)) return
        dx /= len; dy /= len
        val lambda = -(len - rest) / (wsum + alpha)
        px[i] += dx * lambda * w[i]; py[i] += dy * lambda * w[i]
        px[j] -= dx * lambda * w[j]; py[j] -= dy * lambda * w[j]
    }

    /**
     * Площадь треугольника ткани держится пружиной XPBD с податливостью AREA_COMPLIANCE. У сжатой
     * мышцы площадь покоя сжимается вместе с длинами (× команда²). Чем ближе треугольник к
     * вырождению, тем он жёстче; вывернутый держится без податливости вовсе — ткань не выворачивается.
     */
    private fun solveAreas(h: Float) {
        val px = st.px; val py = st.py; val w = st.invMass; val qx = st.prevX; val qy = st.prevY
        val alive = st.baked.triAlive
        val count = st.triCount
        val areaStep = AREA_MAX_STEP * st.linkScale * SUBSTEPS / SLOW_CONSTRAINT_PERIOD / substeps
        for (q in 0 until count) {
            val t = if (backwards) count - 1 - q else q
            if (!alive[st.triId[t]]) continue
            val a = st.triA[t]; val b = st.triB[t]; val c = st.triC[t]
            val gax = py[b] - py[c]; val gay = px[c] - px[b]
            val gbx = py[c] - py[a]; val gby = px[a] - px[c]
            val gcx = py[a] - py[b]; val gcy = px[b] - px[a]
            val denom = w[a] * (gax * gax + gay * gay) + w[b] * (gbx * gbx + gby * gby) + w[c] * (gcx * gcx + gcy * gcy)
            if (denom < 1e-18f) continue
            val m = st.triMuscle[t]
            var command = if (m < 0) 1.0f else st.muscleScaleOf[m]
            if (st.cellScalesOn || st.softnessOn) command *= st.triCellScale[t]
            val squeeze = minOf(1.0f, command)
            // Мышца с волокном (MuscleFiber.kt): площадь идёт как команда^(1−k), а не как её квадрат.
            val id = st.triId[t]
            val alongFiber = command < 1.0f && st.fibersOn && id < st.triFiberById.size && st.triFiberById[id]
            val shrink = if (alongFiber) fiberAreaFactor(command, st.fiberThicken) else squeeze * squeeze
            val rest2 = st.triRestArea2[t].toFloat() * shrink
            val area2 = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            var alpha = AREA_COMPLIANCE / (h * h)
            if (st.softnessOn) alpha *= st.triSoftness[t]
            if (command > 1.0f && area2 > rest2) alpha *= 1.0f + 3.0f * relaxation(command)
            val whole = (area2 / rest2).coerceIn(0.0f, 1.0f)
            alpha = AREA_COMPLIANCE_INVERTED / (h * h) + (alpha - AREA_COMPLIANCE_INVERTED / (h * h)) * whole * whole
            var excess = area2 - rest2
            // У мышцы — не больше удвоенной доли площади покоя (MUSCLE_MAX_PULL): площадь идёт как квадрат длины.
            if (shrink < 1.0f) excess = minOf(excess, 2.0f * MUSCLE_MAX_PULL * st.triRestArea2[t])
            var lambda = -excess / (denom + alpha)
            // Потолок возврата — AREA_MAX_STEP связи за два подшага игры, в пересчёте на свой подшаг.
            // В него входит и то, насколько треугольник уже вернулся за этот подшаг сам, по инерции.
            val before = (qx[b] - qx[a]) * (qy[c] - qy[a]) - (qy[b] - qy[a]) * (qx[c] - qx[a])
            val root = sqrt(denom)
            val gone = (area2 - before) * (if (rest2 > area2) 1.0f else -1.0f) / root
            val room = (areaStep - maxOf(0.0f, gone)).coerceAtLeast(0.0f)
            val step = abs(lambda) * root
            if (step > room) lambda *= room / step
            px[a] += w[a] * lambda * gax; py[a] += w[a] * lambda * gay
            px[b] += w[b] * lambda * gbx; py[b] += w[b] * lambda * gby
            px[c] += w[c] * lambda * gcx; py[c] += w[c] * lambda * gcy
        }
    }

    // --- кости ---

    /** Номер включённой кости живой клетки или −1. */
    private fun boneIndex(i: Int): Int = if (st.bonesRigid && st.invMass[i] > 0.0f) st.boneOf[i] else -1

    /**
     * Кость — твёрдое тело: её клетки двигаются только вместе. После каждой стадии, двигавшей клетки
     * поодиночке, кость ставится в ближайшую твёрдую позу (поворот и перенос позы покоя с весами-
     * массами). Такая проекция не меняет ни центр масс, ни момент импульса. Здесь же считаются
     * масса, момент инерции и центр кости для касаний и пределов.
     */
    private fun projectBones() {
        if (!st.bonesRigid) return
        val bones = st.rigidBones
        if (boneMass.size < bones.size) {
            boneMass = FloatArray(bones.size); boneInertia = FloatArray(bones.size)
            boneCx = FloatArray(bones.size); boneCy = FloatArray(bones.size)
        }
        val px = st.px; val py = st.py; val w = st.invMass
        for (b in bones.indices) {
            val ids = bones[b]; val qx = st.boneRestQx[b]; val qy = st.boneRestQy[b]
            var m = 0.0f; var cx = 0.0f; var cy = 0.0f; var rx = 0.0f; var ry = 0.0f
            for (k in ids.indices) {
                val i = ids[k]
                if (st.boneOf[i] != b || w[i] == 0.0f) continue
                val mi = 1.0f / w[i]
                m += mi; cx += mi * px[i]; cy += mi * py[i]; rx += mi * qx[k]; ry += mi * qy[k]
            }
            boneMass[b] = m; boneInertia[b] = 0.0f
            if (m <= 0.0f) continue
            cx /= m; cy /= m; rx /= m; ry /= m
            boneCx[b] = cx; boneCy[b] = cy
            var dot = 0.0f; var cross = 0.0f; var inertia = 0.0f
            for (k in ids.indices) {
                val i = ids[k]
                if (st.boneOf[i] != b || w[i] == 0.0f) continue
                val mi = 1.0f / w[i]
                val ax = qx[k] - rx; val ay = qy[k] - ry; val bx = px[i] - cx; val by = py[i] - cy
                dot += mi * (ax * bx + ay * by); cross += mi * (ax * by - ay * bx); inertia += mi * (ax * ax + ay * ay)
            }
            boneInertia[b] = inertia
            val norm = sqrt(dot * dot + cross * cross)
            if (norm < 1e-12f) continue
            val cos = dot / norm; val sin = cross / norm
            for (k in ids.indices) {
                val i = ids[k]
                if (st.boneOf[i] != b || w[i] == 0.0f) continue
                val ax = qx[k] - rx; val ay = qy[k] - ry
                px[i] = cx + cos * ax - sin * ay; py[i] = cy + sin * ax + cos * ay
            }
        }
    }

    /** Обратная масса частицы вдоль направления (nx, ny): у клетки кости — всей кости в этой точке. */
    private fun inverseMass(i: Int, nx: Float, ny: Float): Float {
        val b = boneIndex(i)
        if (b < 0) return st.invMass[i]
        val m = boneMass[b]
        if (m <= 0.0f) return 0.0f
        val arm = (st.px[i] - boneCx[b]) * ny - (st.py[i] - boneCy[b]) * nx
        return 1.0f / m + if (boneInertia[b] > m * minArm2) arm * arm / boneInertia[b] else 0.0f
    }

    /**
     * Толчок в частицу: (jx, jy) — масса × сдвиг ([velocity] — масса × скорость). Свободная частица
     * сдвигается сама, кость — целиком: переносом и поворотом вокруг своего центра масс.
     */
    private fun push(i: Int, jx: Float, jy: Float, velocity: Boolean = false) {
        val x = if (velocity) st.vx else st.px; val y = if (velocity) st.vy else st.py
        val b = boneIndex(i)
        if (b < 0) { x[i] += jx * st.invMass[i]; y[i] += jy * st.invMass[i]; return }
        val m = boneMass[b]
        if (m <= 0.0f) return
        val cx = boneCx[b]; val cy = boneCy[b]
        val tx = jx / m; val ty = jy / m
        val turn = if (boneInertia[b] > m * minArm2) ((st.px[i] - cx) * jy - (st.py[i] - cy) * jx) / boneInertia[b] else 0.0f
        for (k in st.rigidBones[b]) {
            if (st.boneOf[k] != b || st.invMass[k] == 0.0f) continue
            val rx = st.px[k] - cx; val ry = st.py[k] - cy
            x[k] += tx - turn * ry; y[k] += ty + turn * rx
        }
        if (!velocity) { boneCx[b] = cx + tx; boneCy[b] = cy + ty }
    }

    // --- временные связи между телами (липучка) ---

    private fun solveJoints(h: Float) {
        val px = st.px; val py = st.py
        val tear = LINK_MAX_STRETCH + st.linkTearStrain
        for (q in 0 until st.jointActiveN) {
            val k = st.jointActive[q]
            if (!st.jointLive[k]) continue
            val i = st.jointA[k]; val j = st.jointB[k]
            var dx = px[i] - px[j]; var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len > tear * st.jointRest[k]) { st.freeJoint(k); continue }
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val wsum = inverseMass(i, dx, dy) + inverseMass(j, dx, dy)
            if (wsum == 0.0f) continue
            val lambda = -(len - st.jointRest[k]) / (wsum + st.jointCompliance[k] / (h * h))
            push(i, dx * lambda, dy * lambda); push(j, -dx * lambda, -dy * lambda)
        }
    }

    // --- предел длины и разрыв связей контура ---

    /**
     * Связь контура не длиннее своей наибольшей длины: от этого зависит, что между соседними клетками
     * контура нет щели. Перетянутую предел возвращает жёстко, но не быстрее LINK_MAX_STEP связи за
     * подшаг игры (1.5 связи за тик), считая и то, на сколько связь уже укоротилась за подшаг сама.
     * Тянут быстрее — связь уходит за предел всё дальше и, уйдя на
     * порог разрыва (LINK_TEAR_STRAIN длины покоя), рвётся. Сжата до LINK_CRUSH_RATIO длины покоя —
     * раздавлена и тоже рвётся. Дорастающая связь новорождённой клетки вне предела и разрыва.
     * Возвращает, двигал ли предел клетки.
     */
    private fun limitLinks(): Boolean {
        val px = st.px; val py = st.py; val w = st.invMass; val qx = st.prevX; val qy = st.prevY
        var moved = false
        var tore = false
        val count = st.conCount
        val back = LINK_MAX_STEP * st.linkScale * SUBSTEPS / substeps
        for (q in 0 until count) {
            val c = if (backwards) count - 1 - q else q
            if (st.conDead[c] || !st.conBoundary[c] || st.isGrowingLink(c)) continue
            val i = st.conA[c]; val j = st.conB[c]
            if (w[i] + w[j] == 0.0f) continue
            var dx = px[i] - px[j]; var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            val command = commandOf(c)
            // У мышцы с волокном длина для раздавливания и для предела — по закону волокна (limitShrink, limitRest в
            // MuscleFiber.kt): связь поперёк волокна не укорачивается, а при k > 0 — удлиняется. Без волокна — как прежде.
            if (st.tearingOn && len < LINK_CRUSH_RATIO * st.conRest[c] * st.limitShrink(c, command)) {
                tear(c); st.crushedCell[i] = true; st.crushedCell[j] = true
                tornByCrush++; tore = true
                continue
            }
            val rest = st.limitRest(c, st.conRest[c], command)
            val max = maxLength(rest, command)
            if (len <= max) continue
            val strain = if (st.conTendon[c]) st.linkTearStrain * st.tendonTear else st.linkTearStrain
            if (len - LINK_MAX_STRETCH * rest > strain * rest) {
                st.linkTorn[c] = true
                if (st.tearingOn) { tear(c); tornByStretch++; tore = true; continue }
            }
            dx /= len; dy /= len
            val wsum = inverseMass(i, dx, dy) + inverseMass(j, dx, dy)
            if (wsum == 0.0f) continue
            val before = sqrt((qx[i] - qx[j]) * (qx[i] - qx[j]) + (qy[i] - qy[j]) * (qy[i] - qy[j]))
            val room = back - maxOf(0.0f, before - len)
            if (room <= 0.0f) continue
            val lambda = -minOf(len - max, room) / wsum
            push(i, dx * lambda, dy * lambda); push(j, -dx * lambda, -dy * lambda)
            moved = true
        }
        if (tore) st.refreshSurface()
        return moved
    }

    private fun tear(c: Int) {
        st.linkTorn[c] = true; st.conDead[c] = true; st.tearsPending = true
        st.killEdgeNow(st.conEdge[c])
    }

    // --- касания ---

    private fun addContact(i: Int, j: Int, rest: Float) {
        if (cN == cI.size) {
            val cap = cN * 2
            cI = cI.copyOf(cap); cJ = cJ.copyOf(cap); cRest = cRest.copyOf(cap)
            cApproach = cApproach.copyOf(cap); cNx = cNx.copyOf(cap); cNy = cNy.copyOf(cap); cActive = cActive.copyOf(cap)
            cGoal = cGoal.copyOf(cap)
        }
        cI[cN] = i; cJ[cN] = j; cRest[cN] = rest; cN++
    }

    /** Пара касается, если она не связана тканью, хоть одна из двух подвижна и круги касания перекрылись. */
    private fun testPair(ct: BoundaryContacts, i: Int, j: Int) {
        if (st.invMass[i] == 0.0f && st.invMass[j] == 0.0f) return
        val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
        val reach = ct.contactRadiusOf(i) + ct.contactRadiusOf(j)
        if (dx * dx + dy * dy >= reach * reach || ct.isBonded(i, j)) return
        val rest = ct.contactDistanceOf(i, j)
        if (dx * dx + dy * dy < rest * rest) addContact(i, j, rest)
    }

    /**
     * Кто кого касается в этом подшаге. Участники — клетки контура и свободные частицы; пары —
     * перебором всех со всеми. Когда участников много, те же пары находит сетка с ячейкой в два
     * наибольших радиуса; список потом упорядочивается, поэтому итог от способа поиска не зависит
     * (ReferenceSolverTest сверяет оба способа).
     */
    private fun findContacts(forceGrid: Boolean = false, forceAll: Boolean = false) {
        val ct = st.contacts!!
        cN = 0
        if (members.size < st.n) members = IntArray(st.n)
        var m = 0; var reach = 0.0f
        for (i in 0 until st.n) {
            val r = ct.contactRadiusOf(i)
            if (r <= 0.0f || !ct.inContactSet(i)) continue
            members[m++] = i
            if (r > reach) reach = r
        }
        if (m >= 2) {
            if (forceAll || (!forceGrid && m <= REFERENCE_ALL_PAIRS)) {
                for (a in 0 until m) for (b in a + 1 until m) testPair(ct, members[a], members[b])
            } else {
                gridSubsteps++
                gridPairs(ct, m, 2.0f * reach)
                sortContacts()
            }
        }
        // Скорость сближения до решения — её доля вернётся отскоком.
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            var nx = st.px[i] - st.px[j]; var ny = st.py[i] - st.py[j]
            val d = sqrt(nx * nx + ny * ny)
            if (d > 1e-12f) { nx /= d; ny /= d } else { nx = 1.0f; ny = 0.0f }
            cNx[c] = nx; cNy[c] = ny
            cApproach[c] = (st.vx[i] - st.vx[j]) * nx + (st.vy[i] - st.vy[j]) * ny
        }
        if (cN > peakContacts) peakContacts = cN
    }

    private fun gridPairs(ct: BoundaryContacts, m: Int, cell: Float) {
        val px = st.px; val py = st.py
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (a in 0 until m) {
            val i = members[a]
            if (px[i] < x0) x0 = px[i]; if (px[i] > x1) x1 = px[i]
            if (py[i] < y0) y0 = py[i]; if (py[i] > y1) y1 = py[i]
        }
        // Разлетевшаяся сцена: ячейку крупнее, чтобы сетка осталась в пределах памяти. Пары те же, проверок больше.
        var size = cell
        while (((x1 - x0) / size + 1.0f) * ((y1 - y0) / size + 1.0f) > 4.0e6f) size *= 2.0f
        val cols = ((x1 - x0) / size).toInt() + 1; val rows = ((y1 - y0) / size).toInt() + 1
        if (cellHead.size < cols * rows) cellHead = IntArray(cols * rows)
        java.util.Arrays.fill(cellHead, 0, cols * rows, -1)
        if (cellNext.size < m) { cellNext = IntArray(m); cellOf = IntArray(m) }
        for (a in 0 until m) {
            val i = members[a]
            val k = ((py[i] - y0) / size).toInt() * cols + ((px[i] - x0) / size).toInt()
            cellOf[a] = k; cellNext[a] = cellHead[k]; cellHead[k] = a
        }
        for (a in 0 until m) {
            val k = cellOf[a]; val cx = k % cols; val cy = k / cols
            for (ny in maxOf(0, cy - 1)..minOf(rows - 1, cy + 1)) for (nx in maxOf(0, cx - 1)..minOf(cols - 1, cx + 1)) {
                var b = cellHead[ny * cols + nx]
                while (b >= 0) {
                    if (b > a) testPair(ct, members[a], members[b])
                    b = cellNext[b]
                }
            }
        }
    }

    /** Список касаний — в порядке (меньший номер, больший номер), как у полного перебора. */
    private fun sortContacts() {
        if (pairKeys.size < cN) pairKeys = LongArray(cN * 2)
        // Ключ пары и её место в списке — в одном числе: место нужно, чтобы забрать расстояние упора.
        val keyed = LongArray(cN) { (cI[it].toLong() shl 42) or (cJ[it].toLong() shl 21) or it.toLong() }
        check(cN < (1 shl 21) && st.n < (1 shl 21)) { "слишком много частиц для ключа пары" }
        keyed.sort()
        val i = IntArray(cN); val j = IntArray(cN); val rest = FloatArray(cN)
        for (k in 0 until cN) { val from = (keyed[k] and 0x1FFFFF).toInt(); i[k] = cI[from]; j[k] = cJ[from]; rest[k] = cRest[from] }
        System.arraycopy(i, 0, cI, 0, cN); System.arraycopy(j, 0, cJ, 0, cN); System.arraycopy(rest, 0, cRest, 0, cN)
    }

    /**
     * Круги касания не перекрываются: перекрытие снимается целиком, сдвигом вдоль линии центров,
     * поровну по обратным массам. Центр одной клетки зашёл внутрь радиуса другой — гибнут обе
     * (клетка кости бессмертна, делящиеся неуязвимы): проникновение не лечится, а наказывается.
     *
     * Все касания решаются разом, из одних и тех же положений. Тело, которого касаются в k местах,
     * делит свою массу между ними поровну: каждое касание видит его в k раз легче, а сдвиги потом
     * складываются. Так несколько касаний не снимают одно и то же перекрытие k раз, а итог не
     * зависит от порядка касаний в списке. Цена — сходимость: проходов несколько за подшаг.
     */
    private fun solveContacts() {
        val ct = st.contacts!!
        val px = st.px; val py = st.py; val qx = st.prevX; val qy = st.prevY
        killN = 0
        prepareGather()
        if (cActive.size < cN) cActive = BooleanArray(cI.size)
        for (sweep in 0 until REFERENCE_CONTACT_SWEEPS) {
            var active = 0; var deepest = 0.0f
            for (c in 0 until cN) {
                val i = cI[c]; val j = cJ[c]
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val d2 = dx * dx + dy * dy
                cActive[c] = d2 < cRest[c] * cRest[c]
                if (!cActive[c]) continue
                active++; countTouch(i); countTouch(j)
                val depth = 1.0f - sqrt(d2) / cRest[c]
                if (depth > deepest) deepest = depth
                if (sweep == 0) {
                    val deep = ct.killDepth * maxOf(ct.contactRadiusOf(i), ct.contactRadiusOf(j))
                    val kill = d2 < deep * deep && ct.killOnDeep && !immune(ct, i, j) && (boneIndex(i) < 0 || boneIndex(j) < 0)
                    if (kill) { if (boneIndex(i) < 0) addKill(i); if (boneIndex(j) < 0) addKill(j) }
                    // Удар, который шаг проскочил: в начале подшага пара не касалась (с запасом в процент —
                    // пара, стоявшая в упоре, не в счёт), а касание застало её уже убитой либо далеко за точкой
                    // касания: глубже половины своей дистанции касания и глубже четверти связи. Пара, оказавшаяся
                    // по другую сторону друг от друга, прошла всю дистанцию касания и ещё нынешнее расстояние.
                    // Мерка — дистанция касания самой пары, а не радиус клетки: у делящихся клеток она меньше
                    // обычной (первые тики — десятая связи), и по радиусу проскоченным выходило любое их касание
                    // (сотни ложных отметок в сценах роста).
                    val ox = qx[i] - qx[j]; val oy = qy[i] - qy[j]
                    if (ox * ox + oy * oy > 1.02f * cRest[c] * cRest[c]) {
                        val depth = if (ox * dx + oy * dy < 0.0f) cRest[c] + sqrt(d2) else cRest[c] - sqrt(d2)
                        if (kill || (depth > 0.5f * cRest[c] && depth > REFERENCE_MISSED_DEPTH * st.linkScale)) missedHits++
                    }
                }
            }
            // Сошлось: глубже допуска никто не перекрыт. Счётчики касаний этого прохода сбрасываются.
            if (active == 0 || (sweep > 0 && deepest < REFERENCE_CONTACT_SLACK)) { release(velocity = false); break }
            for (c in 0 until cN) {
                if (!cActive[c]) continue
                val i = cI[c]; val j = cJ[c]
                var nx = px[i] - px[j]; var ny = py[i] - py[j]
                val d = sqrt(nx * nx + ny * ny)
                if (d > 1e-12f) { nx /= d; ny /= d } else { nx = cNx[c]; ny = cNy[c] }
                val wsum = splitMass(i, nx, ny) + splitMass(j, nx, ny)
                if (wsum <= 0.0f) continue
                val lambda = (cRest[c] - d) / wsum
                gather(i, nx * lambda, ny * lambda); gather(j, -nx * lambda, -ny * lambda)
                contactsSolved++
            }
            release(velocity = false)
        }
        for (k in 0 until killN) {
            val v = kills[k]
            if (st.cellDead[v] || st.isFixed(v)) continue
            st.killCell(v); deepKills++
        }
    }

    private fun prepareGather() {
        val n = st.n
        if (touch.size < n) { touch = IntArray(n); accX = FloatArray(n); accY = FloatArray(n); dirty = IntArray(n) }
        val bones = st.rigidBones.size
        if (boneTouch.size < bones) {
            boneTouch = IntArray(bones); boneJx = FloatArray(bones); boneJy = FloatArray(bones)
            boneTurn = FloatArray(bones); dirtyBone = IntArray(bones)
        }
        dirtyN = 0; dirtyBoneN = 0
    }

    /** Ещё одно касание у тела частицы [i]: у клетки кости — у всей кости. */
    private fun countTouch(i: Int) {
        val b = boneIndex(i)
        if (b >= 0) { if (boneTouch[b]++ == 0) dirtyBone[dirtyBoneN++] = b }
        else if (touch[i]++ == 0) dirty[dirtyN++] = i
    }

    /** Обратная масса частицы для одного из её касаний: тело делит массу между касаниями поровну. */
    private fun splitMass(i: Int, nx: Float, ny: Float): Float {
        val b = boneIndex(i)
        return if (b >= 0) boneTouch[b] * inverseMass(i, nx, ny) else touch[i] * st.invMass[i]
    }

    /** Толчок (jx, jy) в частицу откладывается до конца прохода: все касания видят одни и те же положения. */
    private fun gather(i: Int, jx: Float, jy: Float) {
        val b = boneIndex(i)
        if (b < 0) { accX[i] += jx * st.invMass[i]; accY[i] += jy * st.invMass[i]; return }
        boneJx[b] += jx; boneJy[b] += jy
        boneTurn[b] += (st.px[i] - boneCx[b]) * jy - (st.py[i] - boneCy[b]) * jx
    }

    /** Отложенные толчки — в положения или в скорости; счётчики касаний обнуляются. */
    private fun release(velocity: Boolean) {
        val x = if (velocity) st.vx else st.px; val y = if (velocity) st.vy else st.py
        for (k in 0 until dirtyN) {
            val i = dirty[k]
            x[i] += accX[i]; y[i] += accY[i]
            accX[i] = 0.0f; accY[i] = 0.0f; touch[i] = 0
        }
        for (k in 0 until dirtyBoneN) {
            val b = dirtyBone[k]
            val m = boneMass[b]
            if (m > 0.0f) {
                val cx = boneCx[b]; val cy = boneCy[b]
                val tx = boneJx[b] / m; val ty = boneJy[b] / m
                val turn = if (boneInertia[b] > m * minArm2) boneTurn[b] / boneInertia[b] else 0.0f
                for (p in st.rigidBones[b]) {
                    if (st.boneOf[p] != b || st.invMass[p] == 0.0f) continue
                    val rx = st.px[p] - cx; val ry = st.py[p] - cy
                    x[p] += tx - turn * ry; y[p] += ty + turn * rx
                }
                if (!velocity) { boneCx[b] = cx + tx; boneCy[b] = cy + ty }
            }
            boneJx[b] = 0.0f; boneJy[b] = 0.0f; boneTurn[b] = 0.0f; boneTouch[b] = 0
        }
        dirtyN = 0; dirtyBoneN = 0
    }

    private fun immune(ct: BoundaryContacts, i: Int, j: Int): Boolean {
        val stamp = ct.immuneStamp
        return (i < stamp.size && stamp[i] == ct.immuneNow) || (j < stamp.size && stamp[j] == ct.immuneNow)
    }

    private fun addKill(v: Int) {
        if (killN == kills.size) kills = kills.copyOf(killN * 2)
        kills[killN++] = v
    }

    /**
     * Скорость пары после касания: отскок и старое перекрытие.
     *
     * ОТСКОК. После удара пара расходится со скоростью CONTACT_RESTITUTION от скорости сближения.
     * Само касание сближение гасит целиком; здесь возвращается его доля.
     *
     * СТАРОЕ ПЕРЕКРЫТИЕ. Пара, перекрытая уже к началу подшага (тела поставили внахлёст, порвалась
     * связь между сблизившимися клетками, касания прошлого подшага не сошлись), раздвигается до упора
     * сразу, а скорость от этого получает ту же, что на шаге игры: «перекрытие за подшаг игры».
     * Без этого правила она выходила «перекрытие за свой подшаг» — тем больше, чем мельче шаг. Замер
     * 16.11.2026, две частицы внахлёст на десятую связи, пустота: 0.6 связи за тик при 6 подшагах,
     * 1.2 при 12, 6.4 при 64 (у игры 0.48); два лоскута 6×5 — 0.10, 0.21 и 1.10. На 64 подшагах
     * эталон поэтому рвал тела, просто поставленные вплотную (сцены «касание в покое»: 58 из 141), и
     * столбец мелкого шага сверки показывал не грубость шага игры, а этот изъян. Это тот же случай,
     * что потолки возврата связи и площади (LINK_MAX_STEP, AREA_MAX_STEP): положение, выставленное
     * рывком, становится скоростью «поправка / подшаг».
     * Лишнее снимается только с того, что пара набрала сверх своей скорости расхождения до касания
     * (сверх доли отскока, если она сближалась): сближаться это правило пару не заставит. При
     * подшаге не мельче игрового поправки нет — на шаге игры эталон считает как раньше.
     *
     * Как и положения, скорости всех касаний правятся разом, с тем же делением массы. Клетка с
     * несколькими касаниями за проход отдаёт лишь часть лишнего, поэтому снятие лишнего повторяется до
     * сходимости (без повторов два лоскута на 64 подшагах расходились на 0.44 связи за тик вместо 0.10).
     */
    private fun restitution(h: Float) {
        if (cN == 0) return
        prepareGather()
        val qx = st.prevX; val qy = st.prevY
        // Доля скорости выталкивания, которой на шаге игры не было бы.
        val spare = 1.0f - SUBSTEPS.toFloat() / substeps
        var capped = false
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            cActive[c] = false; cGoal[c] = Float.POSITIVE_INFINITY
            if (st.invMass[i] == 0.0f && st.invMass[j] == 0.0f) continue
            var nx = st.px[i] - st.px[j]; var ny = st.py[i] - st.py[j]
            val d = sqrt(nx * nx + ny * ny)
            if (d > 1e-12f) { nx /= d; ny /= d } else { nx = cNx[c]; ny = cNy[c] }
            cNx[c] = nx; cNy[c] = ny
            val now = (st.vx[i] - st.vx[j]) * nx + (st.vy[i] - st.vy[j]) * ny
            // Скорость расхождения, положенная паре: доля отскока, а если пара и так расходилась — её прежняя.
            val due = if (cApproach[c] < 0.0f) -CONTACT_RESTITUTION * cApproach[c] else cApproach[c]
            var change = 0.0f
            if (now < due) {
                // Сколько скорости расхождения не хватает до доли отскока.
                if (cApproach[c] < 0.0f) change = due - now
            } else if (spare > 0.0f) {
                val old = cRest[c] - sqrt((qx[i] - qx[j]) * (qx[i] - qx[j]) + (qy[i] - qy[j]) * (qy[i] - qy[j]))
                if (old > 0.0f) {
                    cGoal[c] = maxOf(due, now - old / h * spare)
                    change = cGoal[c] - now; capped = true
                }
            }
            if (change == 0.0f) continue
            cRest[c] = change
            cActive[c] = true; countTouch(i); countTouch(j)
        }
        pushApart()
        if (capped) {
            val slack = REFERENCE_SPEED_SLACK * st.linkScale / DT
            for (sweep in 1 until REFERENCE_CONTACT_SWEEPS) {
                var active = 0
                for (c in 0 until cN) {
                    cActive[c] = false
                    if (cGoal[c] == Float.POSITIVE_INFINITY) continue
                    val i = cI[c]; val j = cJ[c]
                    val over = (st.vx[i] - st.vx[j]) * cNx[c] + (st.vy[i] - st.vy[j]) * cNy[c] - cGoal[c]
                    if (over <= slack) continue
                    cRest[c] = -over
                    cActive[c] = true; countTouch(i); countTouch(j); active++
                }
                if (active == 0) break
                pushApart()
            }
        }
        cN = 0
    }

    /** Отмеченным парам (cActive) — поправка скорости расхождения (cRest); масса тела делится между его касаниями. */
    private fun pushApart() {
        for (c in 0 until cN) {
            if (!cActive[c]) continue
            val i = cI[c]; val j = cJ[c]
            val wsum = splitMass(i, cNx[c], cNy[c]) + splitMass(j, cNx[c], cNy[c])
            if (wsum <= 0.0f) continue
            val p = cRest[c] / wsum
            gather(i, cNx[c] * p, cNy[c] * p); gather(j, -cNx[c] * p, -cNy[c] * p)
        }
        release(velocity = true)
    }

    /**
     * Контур не выворачивается: треугольник у ребра контура, чья площадь сменила знак (клетку вдавило
     * касанием сквозь противоположную сторону), возвращается к нулевой площади вдоль её градиента.
     */
    private fun orientBoundary(): Boolean {
        val px = st.px; val py = st.py; val w = st.invMass
        val sign = st.localBoundary.orientation
        var moved = false
        for (k in 0 until st.boundCount) {
            val c = st.boundOpp[k]
            if (c < 0) continue
            val a = st.boundA[k]; val b = st.boundB[k]
            val area = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            if (area * sign[k] >= 0.0f) continue
            val gax = py[b] - py[c]; val gay = px[c] - px[b]
            val gbx = py[c] - py[a]; val gby = px[a] - px[c]
            val gcx = py[a] - py[b]; val gcy = px[b] - px[a]
            val denom = w[a] * (gax * gax + gay * gay) + w[b] * (gbx * gbx + gby * gby) + w[c] * (gcx * gcx + gcy * gcy)
            if (denom < 1e-18f) continue
            // Площадь вдоль градиента — квадратичная функция шага: берётся ближайший корень, а не линейный шаг.
            val bend = (w[a] * w[b] + w[b] * w[c] + w[c] * w[a]) * area
            val lambda = -2.0f * area / (denom + sqrt((denom * denom - 4.0f * bend * area).coerceAtLeast(0.0f)))
            px[a] += w[a] * lambda * gax; py[a] += w[a] * lambda * gay
            px[b] += w[b] * lambda * gbx; py[b] += w[b] * lambda * gby
            px[c] += w[c] * lambda * gcx; py[c] += w[c] * lambda * gcy
            moved = true
        }
        return moved
    }

    // --- опора о дно ---

    /**
     * Хватка клетки о дно: доля скорости, которую клетка теряет. У игры она задана на её подшаг
     * (1 − grip за каждый из SUBSTEPS); эталон гасит за тик ту же долю, разложив её на свои подшаги.
     * «Дом» клетки — пружина к точке с податливостью XPBD.
     */
    private fun grip(h: Float) {
        val grips = st.cellGrip; val homes = st.cellHomeCompliance
        for (i in 0 until minOf(st.n, grips.size, homes.size)) {
            if (grips[i] <= 0f && homes[i] <= 0.0f) continue
            val w = st.invMass[i]
            if (w == 0.0f) continue
            var vx = st.vx[i]; var vy = st.vy[i]
            if (homes[i] > 0.0f) {
                val share = w / (w + homes[i] / (h * h))
                vx += (st.cellHomeX[i] - st.px[i]) * share / h; vy += (st.cellHomeY[i] - st.py[i]) * share / h
            }
            val keep = StrictMath.pow(((1.0f - grips[i]).coerceAtLeast(0.0f)).toDouble(), (SUBSTEPS.toFloat() / substeps).toDouble()).toFloat()
            vx *= keep; vy *= keep
            st.vx[i] = vx; st.vy[i] = vy
            st.px[i] = st.prevX[i] + vx * h; st.py[i] = st.prevY[i] + vy * h
        }
    }

    // --- среда: раз в тик, по скоростям ---

    /** Вязкость ткани: связь гасит долю VISCOSITY × тик встречной скорости своих концов вдоль себя. */
    private fun viscosity(dt: Float) {
        val k = minOf(0.5f, VISCOSITY * dt)
        val px = st.px; val py = st.py; val vx = st.vx; val vy = st.vy; val w = st.invMass
        for (c in 0 until st.conCount) {
            if (st.conDead[c]) continue
            val i = st.conA[c]; val j = st.conB[c]
            val wsum = w[i] + w[j]
            if (wsum == 0.0f) continue
            var nx = px[j] - px[i]; var ny = py[j] - py[i]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-12f) continue
            nx /= len; ny /= len
            val closing = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
            vx[i] += closing * nx * k * w[i] / wsum; vy[i] += closing * ny * k * w[i] / wsum
            vx[j] -= closing * nx * k * w[j] / wsum; vy[j] -= closing * ny * k * w[j] / wsum
        }
    }

    /**
     * Сопротивление среды по контуру. Ребро контура тормозит свою скорость поперёк себя относительно
     * «запаса среды» организма — воды, которую тело увлекает за собой: запас тянется к скорости центра
     * масс (FLOW_ENTRAIN), тело за это платит импульсом (FLOW_MASS), сам запас затухает (FLOW_DECAY).
     * Сопротивление видит среднюю скорость клетки за тик (пройденное за тик / тик), а не мгновенную:
     * внутренняя дрожь ткани не должна превращаться в тягу.
     */
    private fun normalDrag(dt: Float) {
        if (st.dbgDragOff) return
        val px = st.px; val py = st.py; val vx = st.vx; val vy = st.vy; val w = st.invMass
        val n = st.n
        for (i in 0 until n) {
            if (w[i] == 0.0f) { jitterX[i] = 0.0f; jitterY[i] = 0.0f; continue }
            val mx = (px[i] - startX[i]) / DT; val my = (py[i] - startY[i]) / DT
            jitterX[i] = vx[i] - mx; jitterY[i] = vy[i] - my
            vx[i] = mx; vy[i] = my
        }
        val orgs = st.organismCount
        if (orgX.size < orgs) { orgX = FloatArray(orgs); orgY = FloatArray(orgs) }
        java.util.Arrays.fill(orgX, 0, orgs, 0.0f); java.util.Arrays.fill(orgY, 0, orgs, 0.0f)
        for (i in 0 until n) {
            if (w[i] <= 0.0f) continue
            val o = st.organismOf[i]
            orgX[o] += vx[i] / w[i]; orgY[o] += vy[i] / w[i]
        }
        val entrain = minOf(FLOW_ENTRAIN * dt, 0.9f / (1.0f + FLOW_MASS))
        val decay = minOf(1.0f, FLOW_DECAY * dt)
        for (o in 0 until orgs) {
            val mass = st.organismMass[o]
            if (mass <= 0.0f) { orgX[o] = 0.0f; orgY[o] = 0.0f; continue }
            val gainX = (orgX[o] / mass - st.flowVX[o]) * entrain; val gainY = (orgY[o] / mass - st.flowVY[o]) * entrain
            st.flowVX[o] = (st.flowVX[o] + gainX) * (1.0f - decay); st.flowVY[o] = (st.flowVY[o] + gainY) * (1.0f - decay)
            orgX[o] = gainX; orgY[o] = gainY
        }
        for (i in 0 until n) {
            if (w[i] <= 0.0f) continue
            val o = st.organismOf[i]
            vx[i] -= FLOW_MASS * orgX[o]; vy[i] -= FLOW_MASS * orgY[o]
        }
        for (e in 0 until st.boundCount) {
            val i = st.boundA[e]; val j = st.boundB[e]
            val ex = px[j] - px[i]; val ey = py[j] - py[i]
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-12f) continue
            val nx = -ey / len; val ny = ex / len
            val o = st.organismOf[i]
            val mx = (vx[i] + vx[j]) * 0.5f - st.flowVX[o]; val my = (vy[i] + vy[j]) * 0.5f - st.flowVY[o]
            val across = mx * nx + my * ny
            val k = minOf(0.5f, (NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * abs(across)) * len * dt)
            vx[i] -= across * k * nx; vy[i] -= across * k * ny
            vx[j] -= across * k * nx; vy[j] -= across * k * ny
            if (st.tangentDrag > 0.0f) {
                val tx = ex / len; val ty = ey / len
                val along = mx * tx + my * ty
                val kt = minOf(0.5f, st.tangentDrag * NORMAL_DRAG * len * dt)
                vx[i] -= along * kt * tx; vy[i] -= along * kt * ty
                vx[j] -= along * kt * tx; vy[j] -= along * kt * ty
            }
        }
        for (i in 0 until n) { vx[i] += jitterX[i]; vy[i] += jitterY[i] }
    }

    /** Общее торможение о среду: каждая частица теряет долю MEDIUM_DRAG × тик скорости (× свой множитель). */
    private fun mediumDrag(dt: Float) {
        if (st.dbgIsoDragOff) return
        for (i in 0 until st.n) {
            val keep = (1.0f - MEDIUM_DRAG * dt * st.dragScale[i]).coerceAtLeast(0.0f)
            st.vx[i] *= keep; st.vy[i] *= keep
        }
    }

    // --- для проверок ---

    /** Пары касания, найденные перебором и сеткой, — для сверки двух способов поиска (ReferenceSolverTest). */
    internal fun contactPairs(grid: Boolean): LongArray {
        findContacts(forceGrid = grid, forceAll = !grid)
        val out = LongArray(cN) { cI[it].toLong() shl 32 or cJ[it].toLong() }
        cN = 0
        return out
    }
}
