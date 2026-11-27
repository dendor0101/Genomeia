package io.github.some_example_name.physics.rust

/**
 * ЯДРО ФИЗИКИ НА RUST (ветка alpha-0.2.4-rust-port, RUST_PORT.md).
 *
 * Библиотека `genomeia_physics_jni` — дословный перенос этого модуля на Rust; Kotlin-версия остаётся эталоном, и тень
 * сверяет их побитово. Путь к библиотеке задаёт системное свойство `genomeia.physics.native` (его ставит Gradle тестам
 * и стендам); без него — System.loadLibrary по java.library.path. Где библиотеки нет (веб, iOS, машина без Rust),
 * [available] = false, и всё идёт по Kotlin.
 *
 * Все нативные методы — статические: объект Kotlin здесь только пространство имён. Мир на Rust удобнее держать через
 * [RustWorld]. Числа физики — float, как в Kotlin; double — только у [worldCall] (float в double и обратно без потерь).
 */
object RustPhysics {
    /** Версия двоичного договора; та же константа ABI_VERSION в physics-native/core/src/lib.rs. */
    const val ABI_VERSION = 5

    /** Почему библиотека не загрузилась (null — загрузилась или ещё не пробовали). */
    @Volatile var loadError: Throwable? = null
        private set

    /** Загружена ли библиотека и совпадает ли её договор с этим кодом. Пробует один раз. */
    val available: Boolean by lazy {
        try {
            val path = System.getProperty("genomeia.physics.native")
            if (path != null) System.load(path) else System.loadLibrary("genomeia_physics_jni")
            val abi = abiVersion()
            check(abi == ABI_VERSION) { "библиотека физики Rust версии договора $abi, код ждёт $ABI_VERSION" }
            true
        } catch (e: Throwable) {
            loadError = e
            false
        }
    }

    @JvmStatic external fun abiVersion(): Int

    /** Проверка переноса StrictMath: [op] 0 — sin, 1 — cos, 2 — acos по каждому элементу [input] в [out]. */
    @JvmStatic external fun strictBatch(op: Int, input: DoubleArray, out: DoubleArray)

    // --- мир (через RustWorld) ---

    @JvmStatic external fun worldCreate(config: DoubleArray, hasScale: Boolean, meanLink: Float, meanArea: Float): Long
    @JvmStatic external fun worldDestroy(handle: Long)

    @JvmStatic external fun worldAddBody(
        handle: Long, mode: Int,
        x: FloatArray, y: FloatArray, radius: FloatArray, isBone: BooleanArray, isMuscle: BooleanArray,
        linkA: IntArray, linkB: IntArray, triA: IntArray, triB: IntArray, triC: IntArray, triRestArea2: FloatArray,
        boneFlat: IntArray, boneSizes: IntArray, muscleFlat: IntArray, muscleSizes: IntArray, scale: Float,
        retireStart: IntArray, retireCells: IntArray,
        born: BooleanArray?, boneOn: BooleanArray?,
    ): Int

    /** Снимок мира (PhysicsWorld.write) с отпечатком материала [material]; байты те же, что пишет Kotlin. */
    @JvmStatic external fun worldSnapshotWrite(handle: Long, material: String): ByteArray

    /** Прочитать снимок в новый мир (PhysicsWorld.read). */
    @JvmStatic external fun worldSnapshotRead(handle: Long, data: ByteArray, material: String)

    /** Свободная частица (PhysicsWorld.addFree); номер частицы. */
    @JvmStatic external fun worldAddFree(handle: Long, x: Float, y: Float, radius: Float, vx: Float, vy: Float,
                                         mass: Float, drag: Float): Int

    /** Изменяемые параметры решателя (раскладка RustWorld.configLayout), мышь и номер тика. */
    @JvmStatic external fun worldSyncParams(handle: Long, params: DoubleArray, mouseX: Float, mouseY: Float, playTick: Int)

    /** Один тик — единственный вызов JNI на тик в установившемся режиме. */
    @JvmStatic external fun worldStep(handle: Long, dt: Float)

    @JvmStatic external fun worldDigest(handle: Long): ByteArray

    /** Копия массива частиц: 0 — x, 1 — y, 2 — vx, 3 — vy. */
    @JvmStatic external fun worldRead(handle: Long, which: Int, out: FloatArray)

    /** Тень: перенести массив [which], поправленный стендом прямо в BodyState (RustWorld.adopt). */
    @JvmStatic external fun worldAdopt(handle: Long, which: Int, values: FloatArray)

    /** Правка или вопрос между тиками по коду операции (см. [RustWorld]). */
    @JvmStatic external fun worldCall(handle: Long, op: Int, i: Int, j: Int, a: Double, b: Double, c: Double, d: Double): Double
}
