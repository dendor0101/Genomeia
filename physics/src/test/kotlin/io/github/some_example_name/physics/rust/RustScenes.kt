package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustWorld

/**
 * Сцены сверки Rust ↔ Kotlin: тела на треугольной решётке масштаба игры и пара миров
 * (Kotlin в одном потоке и Rust), шагающих одними входами. После каждого тика сравнивается
 * полный отпечаток состояния (RustDigest); первое расхождение — с номером тика и разделом.
 */
object RustScenes {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    const val LINK = 0.0288f
    const val R = 0.024f
    val SCALE = WorldScale(meanLinkLength = LINK, meanCellArea = R * R)

    /**
     * Тело на треугольной решётке [cols]×[rows] с началом в ([x0], [y0]). [muscles] — кластеры
     * мышц по клеткам (номера внутри тела), [bones] — кластеры костей. Геометрия считается в
     * double и один раз округляется во float: описание тела — вход, обоим мирам он один.
     */
    fun lattice(cols: Int, rows: Int, x0: Float = 1f, y0: Float = 1f,
                muscles: List<IntArray> = emptyList(), bones: List<IntArray> = emptyList(),
                link: Float = LINK, radius: Float = R): BodyDefinition {
        val n = cols * rows
        val l = link.toDouble()
        val h = l * kotlin.math.sqrt(3.0) / 2
        val x = FloatArray(n); val y = FloatArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            x[r * cols + c] = (x0 + c * l + (r % 2) * l / 2).toFloat()
            y[r * cols + c] = (y0 + r * h).toFloat()
        }
        fun id(r: Int, c: Int) = r * cols + c
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>(); val area = ArrayList<Float>()
        fun link(a: Int, b: Int) { la.add(a); lb.add(b) }
        fun tri(a: Int, b: Int, c: Int) {
            val a2 = (x[b] - x[a]).toDouble() * (y[c] - y[a]) - (y[b] - y[a]).toDouble() * (x[c] - x[a])
            if (a2 >= 0) { ta.add(a); tb.add(b); tc.add(c); area.add(a2.toFloat()) }
            else { ta.add(a); tb.add(c); tc.add(b); area.add((-a2).toFloat()) }
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (c + 1 < cols) link(id(r, c), id(r, c + 1))
            if (r + 1 < rows) {
                // Чётный ряд смещён влево: его клетка смотрит вверх на (c-1, c), нечётный — на (c, c+1).
                val up0 = if (r % 2 == 0) c - 1 else c
                val up1 = if (r % 2 == 0) c else c + 1
                if (up0 in 0 until cols) link(id(r, c), id(r + 1, up0))
                if (up1 in 0 until cols && up1 != up0) link(id(r, c), id(r + 1, up1))
                if (c + 1 < cols && up1 in 0 until cols) tri(id(r, c), id(r, c + 1), id(r + 1, up1))
                if (up0 in 0 until cols && up1 in 0 until cols) tri(id(r, c), id(r + 1, up1), id(r + 1, up0))
            }
        }
        val muscleFlag = BooleanArray(n).also { f -> muscles.forEach { cl -> cl.forEach { f[it] = true } } }
        val boneFlag = BooleanArray(n).also { f -> bones.forEach { cl -> cl.forEach { f[it] = true } } }
        return BodyDefinition(x, y, FloatArray(n) { radius }, boneFlag, muscleFlag,
            la.toIntArray(), lb.toIntArray(), ta.toIntArray(), tb.toIntArray(), tc.toIntArray(), area.toFloatArray(),
            bones.toTypedArray(), muscles.toTypedArray(), 1f)
    }

    /**
     * Мир Kotlin без островов: тогда сверяется и кэш широкой фазы (RustDigest.ignoredFor). Настройка игры — острова
     * по замеру — в [GAME].
     */
    val STRICT = PhysicsConfig(mapOf("ISLANDS" to "0"))
    val GAME = PhysicsConfig()

    /**
     * Мир с [workers] воркерами у Rust (RB_WORKERS) и параллельностью с любого размера (RB_PARALLEL_MIN=0), без
     * островов. Kotlin получает те же ключи; его воркеры — по [Pair.kotlinWorkers].
     */
    fun withWorkers(workers: Int, islands: Boolean = false) = PhysicsConfig(mapOf(
        "ISLANDS" to (if (islands) "auto" else "0"), "RB_WORKERS" to workers.toString(), "RB_PARALLEL_MIN" to "0"))

    /**
     * Пара миров: Kotlin и Rust с теми же параметрами. Kotlin по умолчанию в одном потоке ([kotlinWorkers] = false):
     * его биты от числа воркеров не зависят, и сверка Rust с воркерами против него проверяет обе стороны.
     */
    class Pair(config: PhysicsConfig = STRICT, contacts: Boolean = false, scale: WorldScale? = SCALE,
               kotlinWorkers: Boolean = false) : AutoCloseable {
        val k = PhysicsWorld(config, workers = if (kotlinWorkers && config.WORKERS > 1) PhysicsWorkers.shared(config.WORKERS) else null,
            scale = scale).apply {
            state.quiet = true
            state.contactsOn = contacts
        }
        val r = RustWorld(k.state, scale)
        var tick = 0
            private set

        /** Шаг обоих миров и сверка; [label] — что за сцена (в сообщении об ошибке). */
        fun step(label: String, dt: Float = DT) {
            r.syncParams(k.state)
            k.step(dt); r.step(dt); tick++
            check(label)
        }

        fun check(label: String) {
            val diff = RustDigest.firstDifference(RustDigest.of(k.state), r.digest(), RustDigest.ignoredFor(k.state))
            if (diff != null) throw AssertionError("[$label] тик $tick: $diff")
        }

        override fun close() = r.close()
    }
}
