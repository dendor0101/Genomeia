package io.github.some_example_name.old.commands

import kotlinx.serialization.Serializable

/**
 * Касание мира в симуляции. Применяется потоком симуляции в фазе команд игрока, поэтому
 * и в журнал сессии попадает с номером тика, в котором применилась (канал SIM). В [detail]
 * координаты округлены: доли клетки в журнале не нужны.
 */
@Serializable
sealed interface PlayerCommand : GameCommand {

    @Serializable
    data class TouchDown(val x: Float, val y: Float, val isLeftButton: Boolean) : PlayerCommand {
        override val name get() = "TouchDown"
        override val detail get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"}"
    }

    @Serializable
    data class Drag(val x: Float, val y: Float, val dx: Float, val dy: Float) : PlayerCommand {
        override val name get() = "Drag"
        override val detail get() = "${x.toInt()},${y.toInt()}"
    }

    @Serializable
    class Tap(
        val x: Float,
        val y: Float,
        val isLeftButton: Boolean = true,
        val genomeIndex: Int? = null
    ) : PlayerCommand {
        override val name get() = "Tap"
        override val detail
            get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"} genome=${genomeIndex ?: "current"}"
    }

    /** Инструмент «Убить клетку»: клетка под касанием умирает, пустое место — ничего. */
    @Serializable
    data class KillCell(val x: Float, val y: Float) : PlayerCommand {
        override val name get() = "KillCell"
        override val detail get() = "${x.toInt()},${y.toInt()}"
    }

    @Serializable
    object StopDrag : PlayerCommand {
        override val name get() = "StopDrag"
    }

    /**
     * Стресс жизненного цикла для проверок (bugReplayCheck): раз в [killEvery] тиков
     * умирает случайная клетка, раз в [flingEvery] — случайная клетка улетает, рвя связи,
     * раз в [spawnEvery] — в случайном месте сажается организм случайного запечённого
     * генома. 0 — выключено. Из интерфейса игрока не посылается.
     *
     * Командой, а не флагом запуска: стресс меняет мир, поэтому он обязан попасть в журнал
     * и повториться при повторе бага — в тех же тиках, при любом числе воркеров.
     */
    @Serializable
    data class SetStress(val killEvery: Int, val flingEvery: Int, val spawnEvery: Int) : PlayerCommand {
        override val name get() = "SetStress"
        override val detail get() = "kill=$killEvery fling=$flingEvery spawn=$spawnEvery"
    }
}
