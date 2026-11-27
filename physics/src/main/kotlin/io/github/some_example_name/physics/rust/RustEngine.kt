package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import io.github.some_example_name.physics.rust.RustWorld

/**
 * ФИЗИКА НА RUST В ИГРЕ (RUST_PORT.md, «Rust в игре»): мир на Rust шагает вместо Kotlin, а [PhysicsWorld] остаётся
 * фасадом — те же методы, те же номера тел и частиц, те же биты (Rust = Kotlin побитово, поэтому `.bug` и снимки годятся
 * для обоих движков).
 *
 * Включает его лаунчер Android ([preferRust] = true до создания мира физики; Rust нужен только телефонам — автор 26.11)
 * или `-Dgenomeia.physics.engine=rust` (проверки на ПК: `bugReplayCheck`, тесты). Нет библиотеки или её договор другой —
 * мир молча идёт на Kotlin с теми же битами ([RustPhysics.loadError] скажет почему).
 */
object RustEngine {
    @Volatile var preferRust: Boolean = System.getProperty("genomeia.physics.engine") == "rust"

    /**
     * Пул воркеров Rust для мира, который будет создан: проверок до сна у ждущего воркера (0 — как SpinPhysicsWorkers) и
     * приоритет его потоков (nice, 0 — не трогать). Игра ставит их от своего пула (PlatformTuning): на телефоне спин
     * короткий — иначе ядро греется и сбрасывает частоту, — а приоритет тот же, что у потока симуляции (он — воркер 0).
     * Исполнение: биты от них не зависят.
     */
    @Volatile var workerSpinLimit = 0
    @Volatile var workerNice = 0
    /** Потолок сна ждущего воркера Rust, мкс (0 — 2 мс): на телефоне как у пула игры, чтобы не просыпаться зря. */
    @Volatile var workerParkMaxMicros = 0

    /** Брать ли Rust новому миру: просили, и библиотека загрузилась. */
    val active: Boolean
        get() {
            if (!preferRust) return false
            val ok = RustPhysics.available
            if (!reported) {
                // Один раз за процесс: проверке и игроку нужно видеть, на чём шла физика, а отступ на Kotlin — тихий.
                reported = true
                if (ok) System.err.println("физика: движок Rust")
                else System.err.println("физика: Rust запрошен, но библиотека не загрузилась — Kotlin (${RustPhysics.loadError})")
            }
            return ok
        }

    @Volatile private var reported = false

    /**
     * Освободить мир Rust, когда сборщик заберёт его фасад [owner]: подстраховка к явному PhysicsWorld.close. Cleaner есть
     * на Android с API 33; раньше — только явный close (игра зовёт его при новой сессии, WorldPhysics.reset).
     */
    fun registerClose(owner: Any, world: RustWorld) {
        try { cleaner.register(owner, world::close) } catch (_: Throwable) { }
    }

    private val cleaner by lazy { java.lang.ref.Cleaner.create() }
}

/**
 * ЗЕРКАЛО МИРА RUST ДЛЯ ИГРЫ. С движком Rust состояние Kotlin ([BodyState]) не считается, но мост игры держит ссылки на
 * его массивы и читает их каждый тик (`WorldPhysics`: позиции и скорости — через [PhysicsWorld.x]…, гибель, рождение,
 * кости, множители и мягкость клеток, касания, хватка, радиус — прямо из `state`). Зеркало — эти массивы, заполненные из
 * мира Rust: целиком после шага и после редких правок (тела, пул, уплотнение, снимок), по одной частице — после правок
 * клетки. Остальное состояние Kotlin при движке Rust пусто; вопросы мира (события шага, контур, сон, топология, тела)
 * идут в мир Rust.
 *
 * Мост пишет в зеркало прямо одно — цели мышц клеток и два признака множителей (WorldPhysics.syncOrganismFast); перед
 * шагом они уходят в Rust ([pushInputs]).
 *
 * ЦЕНА: после шага — 13 копий по n чисел (на 20 тыс. частиц около 0.9 МБ), перед шагом — одна; правка клетки — один
 * вызов JNI. Ссылки на массивы меняются, только когда меняется число частиц (мост берёт их заново после шага и после
 * подсадки — WorldPhysics.publish).
 */
internal class RustMirror(private val st: BodyState, private val rust: RustWorld) {
    private var allocated = -1

    /** «Организм частицы спит» (PhysicsWorld.isAsleep): игра спрашивает из потока рисования — только зеркало. */
    var asleep = BooleanArray(0)
        private set

    private val cellF = FloatArray(9)
    private val cellI = IntArray(6)

    /** Всё зеркало из мира Rust: после шага и после правок, которые меняют число частиц или многие частицы. */
    fun pullAll() {
        val n = rust.particleCount
        if (allocated != n) allocate(n)
        st.n = n
        rust.read(0, st.px); rust.read(1, st.py); rust.read(2, st.vx); rust.read(3, st.vy)
        rust.read(4, st.cellScale); rust.read(5, st.cellScaleTarget); rust.read(6, st.cellSoftness)
        rust.read(7, st.cellGrip); rust.read(8, st.radius)
        rust.readBools(0, st.cellDead); rust.readBools(1, st.cellUnborn); rust.readBools(2, st.cellNoContact)
        rust.readBools(3, asleep)
        rust.readInts(0, st.boneOf)
        st.cellScalesOn = rust.cellScalesOn; st.cellScalesMoving = rust.cellScalesMoving
    }

    /** Одна частица [i] после правки клетки между тиками (рождение, гибель, толчок, множитель, мягкость…). */
    fun pullCell(i: Int) {
        rust.readCell(i, cellF, cellI)
        st.px[i] = cellF[0]; st.py[i] = cellF[1]; st.vx[i] = cellF[2]; st.vy[i] = cellF[3]
        st.cellScale[i] = cellF[4]; st.cellScaleTarget[i] = cellF[5]; st.cellSoftness[i] = cellF[6]
        st.cellGrip[i] = cellF[7]; st.radius[i] = cellF[8]
        st.cellDead[i] = cellI[0] != 0; st.cellUnborn[i] = cellI[1] != 0; st.cellNoContact[i] = cellI[2] != 0
        st.boneOf[i] = cellI[3]
        st.cellScalesOn = cellI[4] != 0; st.cellScalesMoving = cellI[5] != 0
    }

    /** Перед шагом: то, что мост пишет прямо в зеркало, — в мир Rust. */
    fun pushInputs() {
        if (st.n == 0) return
        rust.adoptCellScaleTargets(st.cellScaleTarget)
        rust.setCellScaleFlags(st.cellScalesOn, st.cellScalesMoving)
    }

    private fun allocate(n: Int) {
        st.px = FloatArray(n); st.py = FloatArray(n); st.vx = FloatArray(n); st.vy = FloatArray(n)
        st.cellScale = FloatArray(n); st.cellScaleTarget = FloatArray(n); st.cellSoftness = FloatArray(n)
        st.cellGrip = FloatArray(n); st.radius = FloatArray(n)
        st.cellDead = BooleanArray(n); st.cellUnborn = BooleanArray(n); st.cellNoContact = BooleanArray(n)
        st.boneOf = IntArray(n)
        asleep = BooleanArray(n)
        allocated = n
    }
}
