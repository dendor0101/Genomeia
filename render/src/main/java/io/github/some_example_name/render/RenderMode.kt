package io.github.some_example_name.render

/** dumpCode — стабильный формат снимков; preferenceId — исторический индекс в настройках и командах. */
enum class RenderMode(val dumpCode: Int, val preferenceId: Int) {
    DEBUG(0, 1),
    NOSTALGIC(2, 2),
    TISSUE_MAP(4, 3);

    val drawsDebugOverlay get() = this == DEBUG

    companion object {
        fun fromDumpCode(code: Int) =
            if (code == 3) TISSUE_MAP else entries.firstOrNull { it.dumpCode == code } ?: DEBUG
        fun fromPreference(id: Int) = if (id == 4) TISSUE_MAP else entries.firstOrNull { it.preferenceId == id } ?: DEBUG
    }
}
