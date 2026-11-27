package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import io.github.some_example_name.physics.rust.RustWorld

/**
 * ТЕНЬ НА RUST — проверка переноса на любых стендах, проверках и в игре без их правки
 * (ветка alpha-0.2.4-rust-port, RUST_PORT.md).
 *
 * `-Dgenomeia.physics.rust=shadow`: каждый [PhysicsWorld] заводит рядом мир на Rust, повторяет
 * в нём все свои методы, а после каждого тика сверяет отпечатки состояния ([RustDigest]).
 * Первое расхождение останавливает прогон исключением с тиком и разделом — значит, либо
 * перенос разошёлся с Kotlin, либо вызывающий правит состояние в обход методов мира.
 * Параметры решателя, которые стенды крутят прямо в BodyState (cancelSpinOn, мышь, номер тика
 * и т. п.), перед шагом переносятся в тень ([RustWorld.syncParams]).
 *
 * `-Dgenomeia.physics.rust.every=N` — сверять раз в N тиков (полный отпечаток 100k частиц —
 * десятки миллисекунд).
 */
internal object RustShadow {
    /** Заводить ли тень новым мирам. var — чтобы тесты тени (RustShadowTest) включали её сами. */
    @Volatile var requested: Boolean = System.getProperty("genomeia.physics.rust") == "shadow"
    private val every: Long = System.getProperty("genomeia.physics.rust.every")?.toLongOrNull()?.coerceAtLeast(1) ?: 1

    /** Сколько тиков сверено во всех тенях процесса (печатается при выходе из JVM). */
    @Volatile var checkedTicks = 0L
        private set
    @Volatile private var worlds = 0

    /** Сверенных тиков, в которых острова были у Kotlin и у Rust (каждый выбирает их сам): видно, что тень их прошла. */
    private val kotlinIslandTicks = java.util.concurrent.atomic.AtomicLong()
    private val rustIslandTicks = java.util.concurrent.atomic.AtomicLong()

    fun create(owner: Any, state: BodyState, scale: WorldScale?): RustWorld {
        check(RustPhysics.available) { "теневой мир Rust запрошен, но библиотеки нет: ${RustPhysics.loadError}" }
        synchronized(this) {
            // Итог при выходе: зелёный прогон без этой строки значит, что тень не работала.
            if (worlds++ == 0) Runtime.getRuntime().addShutdownHook(Thread {
                val adoptions = if (adopted == 0L) "" else ", перенесено правок стенда $adopted (разделы $adoptedSections)"
                val detached = if (detachedBy.isEmpty()) "" else ", тень снята $detachedBy"
                System.err.println("тень Rust: миров $worlds, сверено тиков $checkedTicks (раз в $every), из них с " +
                    "островами у Kotlin ${kotlinIslandTicks.get()}, у Rust ${rustIslandTicks.get()}$adoptions$detached")
            })
        }
        val world = RustWorld(state, scale)
        // У PhysicsWorld нет close: игра бросает старый мир при рестарте (WorldPhysics.reset).
        // Память Rust освобождается, когда сборщик забирает владельца. java.lang.ref.Cleaner на Android есть только с
        // API 33: на телефоне постарше (A21s) двойник стенда живёт до конца процесса — стенду этого хватает.
        try { registerClose(owner, world) } catch (_: Throwable) { }
        return world
    }

    private fun registerClose(owner: Any, world: RustWorld) { cleaner.register(owner, world::close) }

    /**
     * Лениво: этот объект загружается при создании ЛЮБОГО PhysicsWorld (поле shadow), а Cleaner есть не везде (см.
     * create). StackWalker в checkOp — тоже только на ПК; на телефоне строгий режим не включают.
     */
    private val cleaner by lazy { java.lang.ref.Cleaner.create() }

    /** Сколько миров сняли тень и почему (операция, которой в Rust нет по решению, — эталонный решатель). */
    private val detachedBy = java.util.TreeMap<String, Int>()

    fun detach(reason: String) { synchronized(this) { detachedBy.merge(reason, 1, Int::plus) } }

    /** Сколько миров сняли тень за процесс (для тестов). */
    val detachments: Int get() = synchronized(this) { detachedBy.values.sum() }

    fun check(k: BodyState, r: RustWorld, tick: Long, what: String) {
        if (tick % every != 0L) return
        val diff = RustDigest.firstDifference(RustDigest.of(k), r.digest(), RustDigest.ignoredFor(k)) ?: run {
            checkedTicks++
            if (k.islandsLastTick > 0) kotlinIslandTicks.incrementAndGet()
            if (r.islandsLastTick > 0) rustIslandTicks.incrementAndGet()
            return
        }
        throw IllegalStateException("тень Rust разошлась с Kotlin: $what, тик мира $tick: $diff")
    }

    /**
     * ПРАВКИ СТЕНДА В ОБХОД МЕТОДОВ. Стенды-инструменты (Probe, сцены столкновений, толчки)
     * пишут позиции, скорости, массы и мышцы прямо в массивы BodyState: тень таких записей не
     * видит. Поэтому перед каждым тиком (и записью снимка) миры сравниваются по разделам, и если
     * расходятся только плоские данные из [ADOPTABLE], они переносятся из Kotlin в Rust.
     * Расхождение в структуре (связи, топология, контур, касания) — не перенос, а остановка с
     * названием раздела. Сверка ПОСЛЕ тика остаётся строгой. (В ветке rust-physics переносились ещё
     * пометки conDead и triInverted — сцена «раскол» и окно стендов; в ветке rust-port их перенос
     * не заведён: понадобится — остановка назовёт раздел.)
     *
     * Цена честности: правка операцией мира между тиками, которую Rust сделал бы неверно, в тех
     * же плоских разделах тоже была бы «перенесена». Поэтому при переносах в итоге печатаются их
     * число и разделы, а `checkOps` сверяет строго после каждой операции и переносит только
     * перед ней — так операции проверяются без этой лазейки.
     * `-Dgenomeia.physics.rust.adopt=false` — без переноса (строго; дешевле на больших сценах).
     */
    private val ADOPTABLE = setOf("px", "py", "prevX", "prevY", "vx", "vy", "invMass", "restInvMass", "matchWeight",
        "muscles", "organisms")
    @Volatile var adoptOn: Boolean = System.getProperty("genomeia.physics.rust.adopt") != "false"
    @Volatile private var adopted = 0L
    /** Сколько раз тень переносила правки стенда (для тестов и итога). */
    val adoptions: Long get() = adopted
    private val adoptedSections = java.util.TreeSet<String>()

    fun prepare(k: BodyState, r: RustWorld, tick: Long) {
        if (!adoptOn || (k.n == 0 && r.particleCount == 0)) return
        val kd = RustDigest.of(k)
        val rd = r.digest()
        if (kd.contentEquals(rd)) return
        val ignore = RustDigest.ignoredFor(k)
        val ks = RustDigest.sections(kd)
        val rs = RustDigest.sections(rd)
        val differ = ks.keys.filter { it !in ignore && rs[it]?.contentEquals(ks.getValue(it)) != true }
        if (differ.isEmpty() && ks.keys == rs.keys) return
        if (ks.keys != rs.keys || differ.any { it !in ADOPTABLE }) throw IllegalStateException(
            "тень Rust: перед тиком ${tick + 1} мир Kotlin изменён в обход методов PhysicsWorld, и не только " +
                "в плоских данных (разделы $differ): ${RustDigest.firstDifference(kd, rd, ignore)}")
        r.adopt(k, differ)
        RustDigest.firstDifference(RustDigest.of(k), r.digest(), ignore)?.let {
            throw IllegalStateException("тень Rust: перед тиком ${tick + 1} мир Kotlin изменён в обход методов " +
                "PhysicsWorld, и перенос плоских данных (разделы $differ) его не выровнял — правка задела " +
                "структуру: $it")
        }
        synchronized(this) { adopted++; adoptedSections += differ }
    }

    /**
     * `-Dgenomeia.physics.rust.checkOps=true` — сверка после КАЖДОЙ операции мира, а не только
     * после тика: находит операцию, на которой миры разошлись. Медленно (отпечаток на вызов).
     */
    val checkOps: Boolean = System.getProperty("genomeia.physics.rust.checkOps") == "true"

    fun checkOp(k: BodyState, r: RustWorld, tick: Long) {
        // Пустой мир Kotlin (рамка до первого тела) отпечатка не имеет: массивы ещё не заведены.
        if (k.n == 0 && r.particleCount == 0) return
        val diff = RustDigest.firstDifference(RustDigest.of(k), r.digest(), RustDigest.ignoredFor(k)) ?: return
        // Метод мира, из которого позвали (mirrored встроен в него).
        val caller = StackWalker.getInstance().walk { s -> s.skip(1).findFirst().map { it.methodName }.orElse("?") }
        throw IllegalStateException("тень Rust разошлась с Kotlin после $caller, тик мира $tick: $diff")
    }

    /** DataInput, который запоминает прочитанные байты: снимок нужен и тени. */
    class RecordingInput(private val inner: java.io.DataInput) : java.io.DataInput {
        val bytes = java.io.ByteArrayOutputStream()
        private val out = java.io.DataOutputStream(bytes)
        override fun readFully(b: ByteArray) { inner.readFully(b); out.write(b) }
        override fun readFully(b: ByteArray, off: Int, len: Int) { inner.readFully(b, off, len); out.write(b, off, len) }
        override fun skipBytes(n: Int): Int = throw UnsupportedOperationException("skipBytes")
        override fun readBoolean(): Boolean = inner.readBoolean().also { out.writeBoolean(it) }
        override fun readByte(): Byte = inner.readByte().also { out.writeByte(it.toInt()) }
        override fun readUnsignedByte(): Int = inner.readUnsignedByte().also { out.writeByte(it) }
        override fun readShort(): Short = inner.readShort().also { out.writeShort(it.toInt()) }
        override fun readUnsignedShort(): Int = inner.readUnsignedShort().also { out.writeShort(it) }
        override fun readChar(): Char = inner.readChar().also { out.writeChar(it.code) }
        override fun readInt(): Int = inner.readInt().also { out.writeInt(it) }
        override fun readLong(): Long = inner.readLong().also { out.writeLong(it) }
        override fun readFloat(): Float = inner.readFloat().also { out.writeInt(java.lang.Float.floatToRawIntBits(it)) }
        override fun readDouble(): Double = inner.readDouble().also { out.writeLong(java.lang.Double.doubleToRawLongBits(it)) }
        override fun readLine(): String = throw UnsupportedOperationException("readLine")
        override fun readUTF(): String = inner.readUTF().also { out.writeUTF(it) }
    }
}
