package io.github.some_example_name.old.journal

import io.github.some_example_name.old.commands.GameCommand
import kotlinx.serialization.Serializable

/** Отдельная шкала тиков маленького мира гайда; не эпоха основной симуляции. */
sealed interface CellLabRecord : GameCommand

@Serializable
data class CellLabInput(val action: String, val key: String = "", val value: Float = 0f,
                        val x: Float = 0f, val y: Float = 0f) : CellLabRecord {
    override val name get() = "CellLabInput"
    override val detail get() = "$action $key $value $x $y"
}

/** Число фиксированных тиков, выбранное часами интерфейса. При повторе часы не читаются. */
@Serializable
data class CellLabAdvanced(val ticks: Int) : SimEvent, CellLabRecord {
    override val name get() = "CellLabAdvanced"
}

@Serializable
data class CellLabChecked(val hash: Long, val version: Int = StateHash.VERSION) : SimEvent, CellLabRecord {
    override val name get() = "CellLabChecked"
}
