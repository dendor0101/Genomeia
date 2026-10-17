package io.github.some_example_name.physics.lab.inspection

import io.github.some_example_name.physics.DRAG_MATCH_WEIGHT
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.BoundaryContacts
import io.github.some_example_name.physics.countInverted
import io.github.some_example_name.physics.lab.scenes.applyLabDamage
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.applyDragVelocity
import io.github.some_example_name.physics.applyMediumDrag
import io.github.some_example_name.physics.applyNormalDrag
import io.github.some_example_name.physics.applyRestitution
import io.github.some_example_name.physics.applyViscosity
import io.github.some_example_name.physics.bendCompliance
import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.integrate
import io.github.some_example_name.physics.projectBone
import io.github.some_example_name.physics.solveAreas
import io.github.some_example_name.physics.solveConstraints
import io.github.some_example_name.physics.updateMuscles
import io.github.some_example_name.physics.updateVelocities
import io.github.some_example_name.physics.restoreTopology
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Стенд ЧЕСТНОСТИ для DemoSession.
 *
 * Вызывает типизированные стадии НАСТОЯЩЕГО решателя и мерит сохранение импульса, момента и энергии между ними.
 * Поэтому все цифры относятся к тому коду, который реально крутится в демо.
 */
internal object Probe {
    lateinit var demo: DemoSession
    lateinit var body: BodyDefinition

    var n = 0
    lateinit var px: DoubleArray
    lateinit var py: DoubleArray
    lateinit var vx: DoubleArray
    lateinit var vy: DoubleArray
    lateinit var prevX: DoubleArray
    lateinit var prevY: DoubleArray
    lateinit var invMass: DoubleArray
    lateinit var restInvMass: DoubleArray
    lateinit var matchWeight: DoubleArray
    lateinit var muscleActivation: DoubleArray
    lateinit var muscleTarget: DoubleArray
    lateinit var rigidBones: Array<IntArray>
    lateinit var organismOf: IntArray
    lateinit var boundA: IntArray
    lateinit var boundB: IntArray
    // Длины списков читаются ЖИВЫМИ, а не запоминаются: разрыв их меняет, а массивы
    // теперь держат ёмкость целого тела и по длине ничего не скажут. Раньше стенд мерил
    // «выброс» и «внутри» по разбивке на организмы с момента сброса — то есть по старой.
    val organismCount: Int get() = field("organismCount")
    val boundCount: Int get() = field("boundCount")
    val conCount: Int get() = field("conCount")
    lateinit var conA: IntArray
    lateinit var conRest: DoubleArray
    lateinit var triA: IntArray
    lateinit var triMuscle: IntArray
    lateinit var triB: IntArray
    lateinit var triC: IntArray
    val triCount: Int get() = field("triCount")
    lateinit var conB: IntArray

    private fun <T> field(name: String): T {

        @Suppress("UNCHECKED_CAST")
        return DemoInspector.read(demo, name) as T
    }

    fun setField(name: String, v: Any) {

        DemoInspector.write(demo, name, v)
    }

    /** Физические константы демо теперь Double. Для float-полей есть constFloat. */
    fun const(name: String): Double {

        return (DemoInspector.constant(name, if (::demo.isInitialized) demo else null) as Number).toDouble()
    }

    fun constFloat(name: String): Float {

        return (DemoInspector.constant(name, if (::demo.isInitialized) demo else null) as Number).toFloat()
    }

    /** Текущая податливость изгиба контура в демо: это состояние, а не константа. */
    fun bendCompliance(): Double =
        demo.state.bendCompliance()

    fun constBool(name: String): Boolean {

        return (DemoInspector.constant(name, if (::demo.isInitialized) demo else null) as Boolean)
    }

    fun constInt(name: String): Int {

        return (DemoInspector.constant(name, if (::demo.isInitialized) demo else null) as Number).toInt()
    }

    fun boot(path: String, copies: Int = 2, freeParticles: Int = 6,
             killFraction: Double = 0.0, killSeed: Long = 0L) {
        demo = DemoSession(path, copies, freeParticles, killFraction, killSeed)
        demo.buildFromFile()
        attachFields()
        setField("dragId", -1)
    }

    /**
     * Смотреть в уже собранное демо, ничего в нём не трогая. Нужно разбору журнала
     * игрока: там демо собирает и сбрасывает себя само, ровно как окно.
     */
    fun attach(d: DemoSession) {
        demo = d
        attachFields()
    }

    /** Любое поле демо по имени — для разборов, которым мало готовых измерителей. */
    fun <T> get(name: String): T = field(name)

    private fun attachFields() {
        body = field("body")
        n = field("n")
        px = field("px"); py = field("py")
        vx = field("vx"); vy = field("vy")
        prevX = field("prevX"); prevY = field("prevY")
        invMass = field("invMass"); matchWeight = field("matchWeight")
        restInvMass = field("restInvMass")
        muscleActivation = field("muscleActivation")
        muscleTarget = field("muscleTarget")
        rigidBones = field("rigidBones")
        organismOf = field("organismOf")
        boundA = field("boundA"); boundB = field("boundB")
        conA = field("conA"); conB = field("conB")
        conRest = field("conRest")
        triA = field("triA"); triB = field("triB"); triC = field("triC")
        triMuscle = field("triMuscle")
    }

    /** Перечитать массивы, которые пересборка топологии могла заменить. */
    private fun refresh() {
        boundA = field("boundA"); boundB = field("boundB")
        conA = field("conA"); conB = field("conB")
        conRest = field("conRest")
        triA = field("triA"); triB = field("triB"); triC = field("triC")
        triMuscle = field("triMuscle")
        organismOf = field("organismOf")
    }

    /** Позиции и скорости в начальное состояние (аналог reset без камеры). */
    fun resetState() {
        // Топология тоже возвращается к целой: без этого одно порванное тело
        // утекало бы во все последующие проверки, а идут они подряд по одному
        // экземпляру демо.
        demo.state.restoreTopology()
        refresh()
        for (i in 0 until n) {
            px[i] = body.x[i].toDouble(); py[i] = body.y[i].toDouble()
            vx[i] = 0.0; vy[i] = 0.0
            // Массу берём ТУ ЖЕ, что считает демо, а не зашитую единицу. Раньше здесь
            // стояло 1.0, и когда демо начало считать её из радиуса, разошлось на
            // один ulp — этого хватило, чтобы за 200 кадров траектории разъехались.
            invMass[i] = restInvMass[i]; matchWeight[i] = 1.0
            prevX[i] = px[i]; prevY[i] = py[i]
        }
        muscleActivation.fill(0.0)
        muscleTarget.fill(0.0)
        resetFlow()
    }

    // --- стадии решателя ---
    fun integrate(h: Double) { demo.state.integrate(h) }
    fun solveConstraints(h: Double) { demo.state.solveConstraints(h) }
    fun solveAreas(h: Double) { demo.state.solveAreas(h) }
    fun projectBones() { for (b in rigidBones.indices) demo.state.projectBone(b) }
    fun updateVelocities(h: Double) { demo.state.updateVelocities(h) }
    fun applyDragVelocity(h: Double) { demo.state.applyDragVelocity(h) }
    fun applyViscosity(h: Double) { demo.state.applyViscosity(h) }
    fun applyNormalDrag(h: Double) { demo.state.applyNormalDrag(h) }
    fun applyRestitution() { demo.state.applyRestitution() }
    fun applyMediumDrag(h: Double) { demo.state.applyMediumDrag(h) }
    fun updateMuscles(dt: Double) { demo.state.updateMuscles(dt) }

    /**
     * Переворачивает направление обхода связей — то же, что делает simulate() каждый
     * подшаг. Без этого стенд мерил бы АЛГОРИТМ, КОТОРОГО В ДЕМО НЕТ: чередование
     * живёт в simulate(), а сюда стадии зовутся напрямую.
     */
    fun flipSweep() {

        DemoInspector.write(demo, "sweepBackwards", !(DemoInspector.read(demo, "sweepBackwards") as Boolean))
    }

    /** Настоящий simulate() демо, без пересборки стадий. */
    private fun simulate() { demo.simulate() }

    /**
     * Один кадр.
     *
     * При hydro = true зовётся НАСТОЯЩИЙ simulate() демо, а не пересобранный из стадий.
     * Раньше здесь был ручной список, и он ровно так и подвёл: в simulate() добавились
     * контакты, предел длины связи и потолок скорости, а список о них не знал — стенд
     * стал мерить конвейер, которого в демо нет. Пересобирать порядок стадий вручную
     * можно только там, где нужно вклиниться МЕЖДУ ними (замер утечки по стадиям), и
     * нигде больше.
     *
     * hydro = false — намеренно урезанный диагностический режим «внешних сил нет».
     * Он и обязан отличаться от демо, поэтому собирается вручную.
     */
    /**
     * Кадр с ЯВНО ЗАДАННЫМИ целями мышц — для воспроизведения записи.
     *
     * Обычный frame либо молчит, либо жмёт мышцу номер ноль. Записанное же
     * перетаскивание могло идти под работающим гребком, и без этого воспроизведение
     * показывало совсем другую картину, чем видел человек.
     */
    fun frameWithMuscles(dt: Double, targets: DoubleArray) {
        muscleTarget.fill(0.0)
        for (m in targets.indices) if (m < muscleTarget.size) muscleTarget[m] = targets[m]
        updateMuscles(dt)
        simulate()
    }

    /**
     * Тик с АВТОМАТИЧЕСКИМ ГРЕБКОМ — то же, что клавиша G в демо.
     *
     * Отдельно от frame(contract = true): та включает одну мышцу, а гребок включает
     * все и с тем же периодом и скважностью, что в демо. Именно на нём от тела
     * отваливаются куски, а разговор идёт про них.
     */
    /** Одна мышца держится сокращённой — как при наведении мышью в окне. */
    fun frameHold(dt: Double, muscle: Int) {
        muscleTarget.fill(0.0)
        if (muscle in muscleTarget.indices) muscleTarget[muscle] = 1.0
        updateMuscles(dt)
        simulate()
    }

    fun frameGait(dt: Double, frame: Int) {
        // Период и доля берутся У ДЕМО, а не из констант: их можно подменить на время
        // замера, см. RB_PERIOD в DemoSession.
        val period = demo.state.gaitPeriod
        val duty = (period * demo.state.gaitDuty).toInt().coerceAtLeast(1)
        muscleTarget.fill(0.0)
        if (frame % period < duty) muscleTarget.fill(1.0)
        updateMuscles(dt)
        simulate()
    }

    fun frame(dt: Double, sub: Int, contract: Boolean, hydro: Boolean = true) {
        muscleTarget.fill(0.0)
        if (contract && muscleTarget.isNotEmpty()) muscleTarget[0] = 1.0
        updateMuscles(dt)
        if (hydro) { simulate(); return }
        val h = dt / sub
        for (s in 0 until sub) {
            integrate(h)
            flipSweep()
            solveConstraints(h)
            solveAreas(h)
            projectBones()
            updateVelocities(h)
            applyDragVelocity(h)
            applyViscosity(h)
            applyRestitution()
            applyMediumDrag(h)
        }
    }

    // --- измерители ---
    /**
     * Счётчики АВАРИЙНЫХ механизмов. Оба чинят состояние, правя частицы ПО ОДНОЙ,
     * то есть НЕ сохраняют импульс: обрезка CCD тянет частицу назад по её
     * собственному toi, потолок скорости масштабирует её скорость отдельно от
     * соседей. Пока они молчат — это страховка; как только начали срабатывать
     * регулярно, они превращаются в источник блуждания. Поэтому их видно в
     * проверке, а не только в HUD.
     */
    fun speedCapHits(): Int {

        return (DemoInspector.read(demo, "speedCapHits") as Number).toInt()
    }

    /** Объект самоконтакта демо — для полного перебора проникновений. */
    /**
     * НАСТОЯЩИЙ тракт перетаскивания мышью: dragId плюс цель курсора.
     *
     * Держать частицу нулевой обратной массой для проверки непроницаемости НЕЛЬЗЯ:
     * бесконечную массу не остановит ничто, она обязана оказаться внутри чужого тела,
     * и тест мерил бы собственную жестокость, а не физику. Настоящее перетаскивание
     * податливое и ограничено MAX_DRAG_SPEED — именно оно и есть сценарий игрока.
     */
    fun dragTo(id: Int, x: Double, y: Double) {
        // Демо при захвате задирает вес клетки в shape matching, и без этого
        // воспроизведение шло с СОВЕРШЕННО другой физикой кости: две записанные
        // сессии выглядели исправными, хотя руками тело шло вразнос.
        val prev = field<Int>("dragId")
        if (prev >= 0 && prev != id) matchWeight[prev] = 1.0
        matchWeight[id] = const("DRAG_MATCH_WEIGHT")
        setField("dragId", id)
        setField("mouseX", x)
        setField("mouseY", y)
    }

    fun dragRelease() {
        val prev = field<Int>("dragId")
        if (prev >= 0) matchWeight[prev] = 1.0
        setField("dragId", -1)
    }

    /** Свободная ли это клетка — без единой связи. */
    /** Сколько связей отмечено как «должна была порваться». */
    fun setContacts(on: Boolean) { setField("contactsOn", on) }
    fun setBonesRigid(on: Boolean) { setField("bonesRigid", on) }
    fun setTearing(on: Boolean) { setField("tearingOn", on) }

    fun liveContacts(): Int {
        return contactsObj()?.lastContacts ?: 0
    }

    fun boneIds(b: Int): IntArray = rigidBones[b]

    /**
     * Угловая скорость ОДНОГО организма вокруг ЕГО центра масс.
     *
     * Общесценовые angMom и inertia для этого не годятся: когда тянут одно тело, а
     * второе стоит, вокруг общего центра масс возникает большой момент, к вращению
     * тела отношения не имеющий. На двух копиях одного тела это давало разные
     * числа для одинаковых костей — по этому расхождению ошибка и нашлась.
     */
    fun angVelOf(o: Int): Double {
        var m = 0.0; var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until n) {
            if (organismOf[i] != o || invMass[i] <= 0.0) continue
            val w = 1.0 / invMass[i]
            m += w; cx += w * px[i]; cy += w * py[i]; sx += w * vx[i]; sy += w * vy[i]
        }
        if (m <= 0.0) return 0.0
        cx /= m; cy /= m; sx /= m; sy /= m
        var l = 0.0; var j = 0.0
        for (i in 0 until n) {
            if (organismOf[i] != o || invMass[i] <= 0.0) continue
            val w = 1.0 / invMass[i]
            val rx = px[i] - cx; val ry = py[i] - cy
            l += w * (rx * (vy[i] - sy) - ry * (vx[i] - sx))
            j += w * (rx * rx + ry * ry)
        }
        return if (j > 0.0) l / j else 0.0
    }

    /** Число тел СЕЙЧАС, а не на момент запуска: разрыв его меняет. */
    fun organismCountNow(): Int {
        organismOf = field("organismOf")
        return organismCount
    }

    /** Перечитать топологию после разрыва: связи и треугольники заменяются целиком. */
    /** Замер по стадиям: включить/выключить. */
    fun setStageProbe(on: Boolean) { setField("dbgStages", on) }

    /** Выключить анизотропное сопротивление среды: без него тяги нет вовсе. */
    fun setDragOff(on: Boolean) { setField("dbgDragOff", on) }

    /** Выключить потолок скорости: он масштабирует каждую частицу отдельно. */
    fun setClampOff(on: Boolean) { setField("dbgNoClamp", on) }

    /** Ограничить замер по стадиям подмножеством клеток. */
    fun setStageMask(mask: BooleanArray?) {

        DemoInspector.write(demo, "dbgOnly", mask)
    }

    /** Наибольшая скорость, внесённая стадией, и число сдвинутых ею клеток. */
    fun stageMove(): DoubleArray = field("dbgStageMove")
    fun stageHits(): IntArray = field("dbgStageHits")
    fun stageL(): DoubleArray = field("dbgStageL")
    fun stagePx(): DoubleArray = field("dbgStagePx")
    fun stagePy(): DoubleArray = field("dbgStagePy")

    /** Стенд решётки: убить долю связей тем же путём, что разрыв в игре. */
    fun applyLabDamage() { demo.applyLabDamage() }

    fun refreshTopology() {
        conA = field("conA"); conB = field("conB")
        conRest = field("conRest")
        triA = field("triA"); triB = field("triB"); triC = field("triC")
        triMuscle = field("triMuscle")
        boundA = field("boundA"); boundB = field("boundB")
        organismOf = field("organismOf")
    }

    fun organismSize(o: Int): Int {

        return (DemoInspector.read(demo, "organismSize") as IntArray)[o]
    }

    /** Сколько связей РЕАЛЬНО убито разрывом (не просто помечено). */
    fun killedLinks(): Int {

        return (DemoInspector.read(demo, "tornTotal") as Number).toInt()
    }

    fun softLinks(): Int {

        return (DemoInspector.read(demo, "conCount") as Number).toInt()
    }

    fun tornCount(): Int {

        val a = DemoInspector.read(demo, "linkTorn") as BooleanArray? ?: return 0
        return a.count { it }
    }

    fun resetCounters() {
        DemoInspector.write(demo, "speedCapHits", 0)
        DemoInspector.write(demo, "peakSpeed2", 0.0)

        (DemoInspector.read(demo, "linkTorn") as BooleanArray?)?.fill(false)
    }

    fun isFree(i: Int): Boolean {

        @Suppress("UNCHECKED_CAST")
        return (DemoInspector.read(demo, "isFree") as BooleanArray)[i]
    }

    fun contactsObj(): BoundaryContacts? {

        return DemoInspector.read(demo, "contacts") as BoundaryContacts?
    }

    fun toiClamps(): Int {

        return demo.state.contacts?.lastToiClamps ?: 0
    }

    fun peakSpeedCellsPerTick(): Double {

        val v2 = (DemoInspector.read(demo, "peakSpeed2") as Number).toDouble()
        return Math.sqrt(v2) * const("DT") / body.meanLinkLength
    }

    /**
     * ВСЕ СВОДНЫЕ ВЕЛИЧИНЫ ВЗВЕШЕНЫ ПО МАССЕ, и это не педантизм.
     *
     * Раньше здесь везде стояли простые суммы: центр масс считался как центр
     * ТЯЖЕСТИ ТОЧЕК, импульс — как сумма скоростей. Пока у всех клеток был один
     * радиус, массы были равны, и разница пряталась в общем множителе — проверки
     * проходили с остатком 1e-12 и выглядели надёжными.
     *
     * Стоило появиться телу с двумя радиусами (0.5 и 0.2, то есть массы отличаются
     * в шесть с лишним раз), и всё посыпалось: XPBD сохраняет сумму m*dx, а сумму
     * одних только dx не сохраняет и не должен. Проверка «стадия не двигает центр
     * масс» стала ловить ПРАВИЛЬНУЮ физику как ошибку и выдавала остаток 1.389
     * вместо 1e-12.
     *
     * Урок общий: тест, верный лишь при равных массах, молча становится ложным,
     * как только тела перестают быть однородными. В движке клетки разнотипные с
     * самого начала, так что взвешивать надо было сразу.
     *
     * Масса берётся как 1/invMass. Закреплённые клетки (invMass = 0, бесконечная
     * масса) в суммы не входят вовсе: иначе один пин утянул бы центр масс на себя
     * и обессмыслил любой замер.
     */
    private inline fun massWeighted(acc: (Int, Double) -> Unit): Double {
        var total = 0.0
        for (i in 0 until n) {
            val w = invMass[i]
            if (w <= 0.0) continue
            val m = 1.0 / w
            total += m
            acc(i, m)
        }
        return total
    }

    /** Суммарная масса подвижных клеток. */
    fun totalMass(): Double = massWeighted { _, _ -> }

    /**
     * Скорость ЦЕНТРА МАСС в связях за секунду.
     *
     * Нормировать |P| НА ЧИСЛО ЧАСТИЦ было неверно: импульс это сумма m*v, и деление
     * на количество оставляет в числе единицу массы, которая зависит от радиусов
     * клеток в конкретном теле. Порог 1e-5, подобранный на теле из одинаковых клеток
     * радиуса 0.5, на теле со смешанными радиусами означает уже совсем другую
     * скорость. Деление на суммарную массу даёт скорость, а деление на длину связи
     * делает её независимой ещё и от масштаба тела.
     */
    fun comSpeed(): Double {
        val m = totalMass()
        if (m <= 0.0) return 0.0
        val vx0 = pX() / m; val vy0 = pY() / m
        return sqrt(vx0 * vx0 + vy0 * vy0) / body.meanLinkLength
    }

    fun comX(): Double { var s = 0.0; val m = massWeighted { i, mi -> s += mi * px[i] }; return s / m }
    fun comY(): Double { var s = 0.0; val m = massWeighted { i, mi -> s += mi * py[i] }; return s / m }
    fun pX(): Double { var s = 0.0; massWeighted { i, mi -> s += mi * vx[i] }; return s }
    fun pY(): Double { var s = 0.0; massWeighted { i, mi -> s += mi * vy[i] }; return s }

    /** Момент импульса относительно центра масс, за вычетом поступательного движения. */
    fun angMom(): Double {
        val cx = comX(); val cy = comY()
        var sx = 0.0; var sy = 0.0
        val mt = massWeighted { i, mi -> sx += mi * vx[i]; sy += mi * vy[i] }
        val vxm = sx / mt; val vym = sy / mt
        var l = 0.0
        massWeighted { i, mi ->
            val rx = px[i] - cx; val ry = py[i] - cy
            l += mi * (rx * (vy[i] - vym) - ry * (vx[i] - vxm))
        }
        return l
    }

    fun kinetic(): Double {
        var e = 0.0
        massWeighted { i, mi -> e += 0.5 * mi * (vx[i] * vx[i] + vy[i] * vy[i]) }
        return e
    }

    fun inertia(): Double {
        val cx = comX(); val cy = comY()
        var j = 0.0
        massWeighted { i, mi ->
            val rx = px[i] - cx; val ry = py[i] - cy
            j += mi * (rx * rx + ry * ry)
        }
        return j
    }

    fun snapshotPos(): DoubleArray {
        val out = DoubleArray(2 * n)
        for (i in 0 until n) { out[2 * i] = px[i]; out[2 * i + 1] = py[i] }
        return out
    }

    /** Линейный и угловой позиционный импульс стадии: сумма dx и сумма r x dx. */
    fun positionalImpulse(before: DoubleArray): DoubleArray {
        var cx = 0.0; var cy = 0.0
        val mt = massWeighted { i, mi -> cx += mi * before[2 * i]; cy += mi * before[2 * i + 1] }
        cx /= mt; cy /= mt
        var sx = 0.0; var sy = 0.0; var l = 0.0
        massWeighted { i, mi ->
            val dx = px[i] - before[2 * i]
            val dy = py[i] - before[2 * i + 1]
            sx += mi * dx; sy += mi * dy
            val rx = before[2 * i] - cx; val ry = before[2 * i + 1] - cy
            l += mi * (rx * dy - ry * dx)
        }
        return doubleArrayOf(sx, sy, l)
    }

    fun countInverted(): Int {
        var c = 0
        for (t in 0 until body.triCount) {
            val i0 = body.triA[t]; val i1 = body.triB[t]; val i2 = body.triC[t]
            val a2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0])
            if (a2 < 0f) c++
        }
        return c
    }

    fun maxSpeed(): Double {
        var mx = 0.0
        for (i in 0 until n) {
            val s = sqrt(vx[i].toDouble() * vx[i] + vy[i].toDouble() * vy[i])
            if (s > mx) mx = s
        }
        return mx
    }

    /** Скорость увлечённой среды. Теперь ОДИН вектор на организм, а не массив по рёбрам. */
    /** Наибольший запас среди организмов: теперь их несколько. */
    fun flowPeak(): Double {
        val fx = demo.state.flowVX as DoubleArray
        val fy = demo.state.flowVY as DoubleArray
        var mx = 0.0
        for (o in fx.indices) mx = maxOf(mx, sqrt(fx[o] * fx[o] + fy[o] * fy[o]))
        return mx
    }

    /** Запас среды одного организма. */
    fun flowOf(o: Int): Double {
        val fx = demo.state.flowVX as DoubleArray
        val fy = demo.state.flowVY as DoubleArray
        if (o < 0 || o >= fx.size) return 0.0
        return sqrt(fx[o] * fx[o] + fy[o] * fy[o])
    }


    /** Запас среды теперь МАССИВ — по одному на организм, а не один на мир. */
    fun resetFlow() {
        (demo.state.flowVX as DoubleArray).fill(0.0)
        (demo.state.flowVY as DoubleArray).fill(0.0)
    }
}
