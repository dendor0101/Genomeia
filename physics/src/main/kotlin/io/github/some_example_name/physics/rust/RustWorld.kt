package io.github.some_example_name.physics.rust

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.WorldScale

/**
 * Мир физики на Rust — те же методы, что у [io.github.some_example_name.physics.PhysicsWorld] (подмножество, которое
 * уже перенесено; см. RUST_PORT.md). Состояние живёт в куче Rust; объект держит его адрес и обязан быть закрыт [close].
 *
 * Параметры решателя берутся из уже собранного [BodyState] Kotlin ([template]) — так обе реализации гарантированно
 * стартуют с одних разобранных чисел (RB_*, CT_*, подшаги).
 */
class RustWorld(template: BodyState, scale: WorldScale? = template.worldScale) : AutoCloseable {
    private var handle: Long

    init {
        check(RustPhysics.available) { "нет библиотеки физики Rust: ${RustPhysics.loadError}" }
        handle = RustPhysics.worldCreate(configLayout(template), scale != null,
            scale?.meanLinkLength ?: 0f, scale?.meanCellArea ?: 0f)
    }

    override fun close() {
        if (handle != 0L) { RustPhysics.worldDestroy(handle); handle = 0L }
    }

    private fun call(op: Int, i: Int = 0, j: Int = 0, a: Double = 0.0, b: Double = 0.0, c: Double = 0.0, d: Double = 0.0) =
        RustPhysics.worldCall(handle, op, i, j, a, b, c, d)

    fun load(definition: BodyDefinition) { send(0, definition, null, null) }

    fun addBody(definition: BodyDefinition, born: BooleanArray? = null, boneOn: BooleanArray? = null): Int =
        send(1, definition, born, boneOn)

    private fun send(mode: Int, d: BodyDefinition, born: BooleanArray?, boneOn: BooleanArray?): Int {
        fun flat(c: Array<IntArray>) = IntArray(c.sumOf { it.size }).also { out ->
            var at = 0; for (cl in c) { cl.copyInto(out, at); at += cl.size }
        }
        return RustPhysics.worldAddBody(handle, mode, d.x, d.y, d.radius, d.isBone, d.isMuscle,
            d.linkA, d.linkB, d.triA, d.triB, d.triC, d.triRestArea2,
            flat(d.boneClusters), IntArray(d.boneClusters.size) { d.boneClusters[it].size },
            flat(d.muscleClusters), IntArray(d.muscleClusters.size) { d.muscleClusters[it].size },
            d.scale, d.linkRetireStart, d.linkRetireCells, born, boneOn)
    }

    fun step(dt: Float = DT) = RustPhysics.worldStep(handle, dt)

    /**
     * Перенести изменяемые параметры решателя из [st] (стенды крутят их прямо в BodyState: cancelSpinOn, contactsOn,
     * флаг сна, параметры мышц, мышь, номер тика). Порядок — [configLayout].
     */
    fun syncParams(st: BodyState) = RustPhysics.worldSyncParams(handle, configLayout(st), st.mouseX, st.mouseY, st.playTick)
    fun stepSolverOnly() { call(31) }
    fun reset() { call(30) }
    fun digest(): ByteArray = RustPhysics.worldDigest(handle)

    /** Копия массива частиц в [out]: 0 — x, 1 — y, 2 — vx, 3 — vy. */
    fun read(which: Int, out: FloatArray = FloatArray(particleCount)): FloatArray {
        RustPhysics.worldRead(handle, which, out); return out
    }

    // --- входы тика (коды операций — функция call в physics-native/jni/src/lib.rs) ---
    fun setMuscleTarget(m: Int, value: Float) { call(1, m, a = value.toDouble()) }
    fun setAllMuscleTargets(value: Float) { call(2, a = value.toDouble()) }
    fun setCellScale(cell: Int, scale: Float) { call(3, cell, a = scale.toDouble()) }
    fun setSoftness(cell: Int, softness: Float) { call(4, cell, a = softness.toDouble()) }
    fun setContactless(cell: Int, on: Boolean) { call(5, cell, a = if (on) 1.0 else 0.0) }
    fun setGrip(cell: Int, grip: Float) { call(6, cell, a = grip.toDouble()) }
    fun setHome(cell: Int, x: Float, y: Float, compliance: Float) {
        call(7, cell, a = x.toDouble(), b = y.toDouble(), c = compliance.toDouble())
    }
    fun setContactGroup(cell: Int, group: Int) { call(8, cell, group) }
    fun addJoint(a: Int, b: Int, rest: Float, compliance: Float, wakeStrain: Float = 0.0f, wake: Boolean = true): Int =
        call(9, a, b, rest.toDouble(), compliance.toDouble(), wakeStrain.toDouble(), if (wake) 1.0 else 0.0).toInt()
    fun removeJoint(id: Int) { call(10, id) }
    fun setJointCompliance(id: Int, compliance: Float) { call(11, id, a = compliance.toDouble()) }
    fun setMass(cell: Int, mass: Float) { call(12, cell, a = mass.toDouble()) }
    fun setDrag(cell: Int, scale: Float) { call(13, cell, a = scale.toDouble()) }
    fun setDeepKillOn(value: Boolean) { call(14, a = if (value) 1.0 else 0.0) }
    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        call(15, a = minX.toDouble(), b = minY.toDouble(), c = maxX.toDouble(), d = maxY.toDouble())
    }
    fun clearBounds() { call(16) }
    fun kill(cell: Int) { call(17, cell) }
    fun grab(cell: Int) { call(18, cell) }
    fun dragTo(x: Float, y: Float) { call(19, a = x.toDouble(), b = y.toDouble()) }
    fun release() { call(20) }
    fun setVelocity(i: Int, vx: Float, vy: Float) { call(21, i, a = vx.toDouble(), b = vy.toDouble()) }
    fun place(i: Int, x: Float, y: Float) { call(22, i, a = x.toDouble(), b = y.toDouble()) }
    fun wake(i: Int) { call(23, i) }
    fun sleepNow(i: Int) { call(24, i) }
    fun setSleepOn(value: Boolean) { call(25, a = if (value) 1.0 else 0.0) }
    fun setBone(cell: Int): Boolean = call(26, cell) != 0.0
    fun activate(cell: Int, x: Float, y: Float, vx: Float = 0.0f, vy: Float = 0.0f) {
        call(27, cell, a = x.toDouble(), b = y.toDouble(), c = vx.toDouble(), d = vy.toDouble())
    }
    fun activate(cell: Int) { call(28, cell) }
    fun activateFromParent(cell: Int, parent: Int) { call(29, cell, parent) }
    fun restoreTopology() { call(38) }
    /** То, чем PhysicsWorld.cellScaleMismatches меняет мир: полный пересчёт множителей клеток. */
    fun recomputeCellScalesLikeCheck() { call(39) }
    fun setCellFiber(cell: Int, x: Float, y: Float) { call(40, cell, a = x.toDouble(), b = y.toDouble()) }
    /** Среда мира: коэффициент трения передаётся сырыми битами float во втором целом. */
    fun setEnvironment(gravityY: Float, mediumScale: Float, edgeDragScale: Float, softness: Float, frictionScale: Float) {
        call(41, 0, java.lang.Float.floatToRawIntBits(frictionScale), gravityY.toDouble(), mediumScale.toDouble(),
            edgeDragScale.toDouble(), softness.toDouble())
    }
    fun pullToward(i: Int, x: Float, y: Float) { call(43, i, a = x.toDouble(), b = y.toDouble()) }
    fun wakeAll() { call(44) }

    /** Снимок мира (уплотняет мир, как PhysicsWorld.write); [material] — PhysicsWorld.materialKey. */
    fun write(material: String): ByteArray = RustPhysics.worldSnapshotWrite(handle, material)

    /** Прочитать снимок [data] в этот (пустой) мир. */
    fun read(data: ByteArray, material: String) { RustPhysics.worldSnapshotRead(handle, data, material) }

    // --- тела и свободные частицы ---
    fun removeBody(body: Int) { call(33, body) }
    fun compact() { call(34) }
    fun addFree(x: Float, y: Float, radius: Float, vx: Float = 0.0f, vy: Float = 0.0f, mass: Float = Float.NaN,
                drag: Float = 1.0f): Int = RustPhysics.worldAddFree(handle, x, y, radius, vx, vy, mass, drag)
    fun addFixed(x: Float, y: Float, radius: Float): Int = addFree(x, y, radius, mass = Float.POSITIVE_INFINITY)
    fun removeFree(id: Int) { call(36, id) }
    fun freeParticle(id: Int): Int = call(131, id).toInt()
    fun isFreeInUse(id: Int): Boolean = call(132, id) != 0.0
    fun isFreeAlive(id: Int): Boolean = call(133, id) != 0.0
    val compactions: Int get() = call(134).toInt()
    val freeCount: Int get() = call(135).toInt()
    fun bodyMuscleFirst(body: Int): Int = call(104, body).toInt()
    fun bodyMuscleCount(body: Int): Int = call(105, body).toInt()

    /**
     * Тень (RustShadow.prepare): перенести из [st] плоские данные разделов отпечатка [sections], которые стенд поправил
     * прямо в BodyState. Коды массивов — worldAdopt в physics-native/jni/src/lib.rs.
     */
    fun adopt(st: BodyState, sections: Collection<String>) {
        fun put(which: Int, a: FloatArray, len: Int) = RustPhysics.worldAdopt(handle, which, a.copyOf(len))
        val n = st.n
        for (s in sections) when (s) {
            "px" -> put(0, st.px, n)
            "py" -> put(1, st.py, n)
            "prevX" -> put(2, st.prevX, n)
            "prevY" -> put(3, st.prevY, n)
            "vx" -> put(4, st.vx, n)
            "vy" -> put(5, st.vy, n)
            "invMass" -> put(6, st.invMass, n)
            "restInvMass" -> put(14, st.restInvMass, n)
            "matchWeight" -> put(7, st.matchWeight, n)
            "muscles" -> {
                put(8, st.muscleActivation, st.muscleActivation.size)
                put(9, st.muscleActivationPrev, st.muscleActivationPrev.size)
                put(10, st.muscleTarget, st.muscleTarget.size)
                put(11, st.muscleScaleOf, st.muscleScaleOf.size)
            }
            "organisms" -> { put(12, st.flowVX, st.organismCount); put(13, st.flowVY, st.organismCount) }
            else -> error("раздел «$s» тень не переносит")
        }
    }

    // --- выходы ---
    val particleCount: Int get() = call(100).toInt()
    val organismCount: Int get() = call(101).toInt()
    fun bodyFirst(body: Int): Int = call(102, body).toInt()
    fun bodyCount(body: Int): Int = call(103, body).toInt()
    fun isBodyAlive(body: Int): Boolean = call(106, body) != 0.0
    val edgeDeathCount: Int get() = call(107).toInt()
    fun edgeDeathA(k: Int): Int = call(108, k).toInt()
    fun edgeDeathB(k: Int): Int = call(109, k).toInt()
    val cellDeathCount: Int get() = call(110).toInt()
    fun cellDeath(k: Int): Int = call(111, k).toInt()
    val boundaryEdgeCount: Int get() = call(112).toInt()
    fun boundaryEdgeA(k: Int): Int = call(113, k).toInt()
    fun boundaryEdgeB(k: Int): Int = call(114, k).toInt()
    fun isSurfaceCell(i: Int): Boolean = call(115, i) != 0.0
    val surfaceChangeCount: Int get() = call(116).toInt()
    fun surfaceChange(k: Int): Int = call(117, k).toInt()
    fun clearSurfaceChanges() { call(118) }
    fun isAsleep(i: Int): Boolean = call(119, i) != 0.0
    fun isBone(cell: Int): Boolean = call(120, cell) != 0.0
    fun isUnborn(cell: Int): Boolean = call(121, cell) != 0.0
    val meanLinkLength: Float get() = call(123).toFloat()
    fun isDead(cell: Int): Boolean = call(124, cell) != 0.0
    val bodyIds: Int get() = call(130).toInt()

    /** Островов в прошлом тике без откатившихся и откатов за всё время (как `islandsLastTick`/`islandAborts` Kotlin). */
    val islandsLastTick: Int get() = call(136).toInt()
    val islandAborts: Long get() = call(137).toLong()

    companion object {
        /**
         * Разобранные параметры решателя в порядке `Config::from_slice` (physics-native/core/src/config.rs). Длина и
         * порядок — часть договора ABI. Ключи, которых перенос не умеет (разбор пробоя CT_WATCH, полный перебор
         * CT_BPCHECK, отладочные ловушки), Rust отвергает: молча идти другим путём нельзя.
         */
        fun configLayout(st: BodyState): DoubleArray {
            val c = st.config
            val ct = c.contacts
            val env = st.environment
            check(ct["CT_WATCH"] == null) { "CT_WATCH (разбор пробоя по клетке) в переносе на Rust не поддержан" }
            check(!ct.BP_CHECK) { "CT_BPCHECK (полный перебор пар) в переносе на Rust не поддержан" }
            fun b(v: Boolean) = if (v) 1.0 else 0.0
            fun f(v: Float) = v.toDouble()
            return doubleArrayOf(
                // Воркеров — как у мира Kotlin на деле (пул, переданный миру), иначе по ключу RB_WORKERS.
                SUBSTEPS.toDouble(), (st.workers?.count ?: c.WORKERS).toDouble(), c.PARALLEL_MIN.toDouble(),
                f(st.CONTACT_MAX_STEP), f(st.LINK_MAX_STEP), b(st.MAXLEN_EARLY), f(st.DRAG_ACCEL), f(st.DRAG_SPEED_LIMIT),
                b(st.KILL_ON_DEEP_OVERLAP),
                b(st.probeOn), b(st.sleep.on), b(st.pierceEachIter),
                f(st.muscleContraction), f(st.muscleRateContract), f(st.muscleRateRelax), st.gaitPeriod.toDouble(),
                f(st.gaitDuty), st.muscleTouch.toDouble(), b(st.dbgSealOn), st.sweepFlip.toDouble(), f(st.tendonTear),
                f(st.linkTearStrain), f(st.tangentDrag), b(st.areasOn), f(st.pressureBurst), f(st.membraneTearStrain),
                b(st.crushBurstOn), st.trapKillTicks.toDouble(), b(st.membranePassTear), b(st.trapKillFolds),
                st.trapCrash.toDouble(), st.watchCell.toDouble(),
                f(ct.CONTACT_OMEGA_H), f(ct.BP_MARGIN), b(ct.CT_SWEEP_FLIP), b(ct.CCD_ALWAYS), f(ct.CCD_GATE),
                b(ct.PIERCE_TEAR), f(ct.PIERCE_DEPTH), b(ct.REST_FULL), b(ct.BP_CHECK), b(ct.XPBD_WARM_START),
                b(ct.abLegacy), f(ct.CONTACT_COMPLIANCE), f(ct.CONTACT_OMEGA_PAIR), f(ct.CONTACT_SEAL),
                b(st.cancelSpinOn), b(st.tearingOn), b(st.bonesRigid), b(st.contactsOn), b(st.deepKillOn),
                f(st.fiberThicken),
                // Отладочные ключи, меняющие физику, и среда мира: стенды пишут их прямо в состояние.
                b(st.dbgDragOff), b(st.dbgIsoDragOff), b(st.dbgNoClamp),
                f(env.gravityY), f(env.mediumDrag), f(env.normalDrag), f(env.normalDragQuadratic),
                f(env.softCompliance), f(env.areaCompliance), f(env.contactFriction),
                // Острова (ISLANDS, ISLANDS_ROLLBACK_ALL): исполнение, биты от них не зависят; Rust выбирает сам.
                st.islandsMode.toDouble(), b(st.islandsRollbackAll),
                // Порядок по цветам (COLOR_ORDER, закон с 27.11, ColorOrder.kt / solver/color_order.rs): меняет биты.
                b(st.colorOrderOn),
            ).also { check(it.size == 65) { "раскладка параметров Rust: ${it.size} чисел вместо 65" } }
        }
    }
}
