package io.github.some_example_name.physics

/** Неизменяемый снимок параметров запуска. Среду процесса читает только лаунчер.
 * Строковые ключи сохраняют совместимость лабораторных RB_* и CT_* переключателей;
 * разбор чисел происходит при создании, в циклах решателя карты не читаются.
 */
class PhysicsConfig(parameters: Map<String, String> = emptyMap()) {
    private val values = parameters.toSortedMap()
    operator fun get(name: String): String? = values[name]
    fun snapshot(): Map<String, String> = values.toMap()
    val contacts = ContactConfig(values)
    val CONTACT_MAX_STEP = this["RB_MAXSTEP"]?.toDoubleOrNull()
        ?: (2.0 * MAX_SPEED_CELLS_PER_TICK / SUBSTEPS)
    val LINK_MAX_STEP = this["RB_LINKSTEP"]?.toDoubleOrNull() ?: 0.25
    val MAXLEN_EARLY = this["RB_MAXLEN_EARLY"]?.let { it != "0" } ?: true
    val DRAG_ACCEL = this["RB_DRAG_ACCEL"]?.toDoubleOrNull() ?: 128.0
    val DRAG_SPEED_LIMIT = this["RB_DRAG_SPEED"]?.toDoubleOrNull() ?: 1.0
    val KILL_ON_DEEP_OVERLAP = this["RB_KILLDEEP"] != "0"
}
