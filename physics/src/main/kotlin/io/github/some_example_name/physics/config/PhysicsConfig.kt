package io.github.some_example_name.physics

/** Неизменяемый снимок параметров запуска. Среду процесса читает только лаунчер.
 * Строковые ключи сохраняют совместимость лабораторных RB_* и CT_* переключателей;
 * разбор чисел происходит при создании, в циклах решателя карты не читаются.
 */
class PhysicsConfig(parameters: Map<String, String> = emptyMap()) {
    private val values = parameters.toSortedMap()
    operator fun get(name: String): String? = values[name]
    /**
     * Параметры МАТЕРИАЛА: то, что пишется в заголовок записи и сверяется при повторе.
     * Число воркеров и порог параллельности сюда не входят: результат от них не зависит
     * побитово, и повтор старой записи на другом числе воркеров — законная проверка.
     */
    fun snapshot(): Map<String, String> = values.filterKeys { it !in EXECUTION_KEYS }

    private companion object {
        // Острова (ISLANDS, ISLANDS_ROLLBACK_ALL) — тоже исполнение: сумма мира с ними и без одна (IslandRun.kt).
        val EXECUTION_KEYS = setOf("RB_WORKERS", "RB_PARALLEL_MIN", "ISLANDS", "ISLANDS_ROLLBACK_ALL")
    }
    val contacts = ContactConfig(values)
    val CONTACT_MAX_STEP = this["RB_MAXSTEP"]?.toFloatOrNull()
        ?: (2.0f * MAX_SPEED_CELLS_PER_TICK / SUBSTEPS)
    val LINK_MAX_STEP = this["RB_LINKSTEP"]?.toFloatOrNull() ?: 0.25f
    val MAXLEN_EARLY = this["RB_MAXLEN_EARLY"]?.let { it != "0" } ?: true
    val DRAG_ACCEL = this["RB_DRAG_ACCEL"]?.toFloatOrNull() ?: 128.0f
    val DRAG_SPEED_LIMIT = this["RB_DRAG_SPEED"]?.toFloatOrNull() ?: 1.0f
    val KILL_ON_DEEP_OVERLAP = this["RB_KILLDEEP"] != "0"
    /** Воркеров физики. На результат не влияет (побитово), только на скорость. */
    val WORKERS = this["RB_WORKERS"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    /** Меньше частиц — всё в одном потоке. Проверки ставят 0, чтобы гонять параллельный путь на малых записях. */
    val PARALLEL_MIN = this["RB_PARALLEL_MIN"]?.toIntOrNull() ?: PARALLEL_MIN_PARTICLES
}
