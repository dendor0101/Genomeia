package io.github.some_example_name.old.commands

import kotlinx.serialization.Serializable

/** Клетка на показанном кадре. Поколение и эпоха защищают от повторной выдачи слота. */
@Serializable
data class ScreenCell(val index: Int, val generation: Int, val epoch: Int)

/**
 * Касание мира в симуляции. Применяется потоком симуляции в фазе команд игрока, поэтому
 * и в журнал сессии попадает с номером тика, в котором применилась (канал SIM). В [detail]
 * координаты округлены: доли клетки в журнале не нужны.
 */
@Serializable
sealed interface PlayerCommand : GameCommand {

    @Serializable
    data class TouchDown(val x: Float, val y: Float, val isLeftButton: Boolean, val target: ScreenCell? = null) : PlayerCommand {
        override val name get() = "TouchDown"
        override val detail get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"}"
    }

    @Serializable
    data class Drag(val x: Float, val y: Float, val dx: Float, val dy: Float) : PlayerCommand {
        override val name get() = "Drag"
        override val detail get() = "${x.toInt()},${y.toInt()}"
    }

    @Serializable
    data class Tap(
        val x: Float,
        val y: Float,
        val isLeftButton: Boolean = true,
        val genomeIndex: Int? = null,
        // null у старых записей и программных посадок. index == -1 — промах по кадру.
        val target: ScreenCell? = null
    ) : PlayerCommand {
        override val name get() = "Tap"
        override val detail
            get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"} genome=${genomeIndex ?: "current"}"
    }

    /** Инструмент «Убить клетку»: клетка под касанием умирает, пустое место — ничего. */
    @Serializable
    data class KillCell(val x: Float, val y: Float, val target: ScreenCell? = null) : PlayerCommand {
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

    /**
     * Отладка физики — галочки отладочного режима: [noTailThrust] — хвосты не толкают, [noDeepKill]
     * — глубокое проникновение частиц друг в друга никого не убивает. Командой мира, как стресс:
     * меняет мир, поэтому лежит в журнале и повторяется в тот же тик.
     */
    @Serializable
    data class SetPhysicsDebug(val noTailThrust: Boolean, val noDeepKill: Boolean) : PlayerCommand {
        override val name get() = "SetPhysicsDebug"
        override val detail get() = "noTail=$noTailThrust noDeepKill=$noDeepKill"
    }

    /**
     * Настройка мира (окно «Настройки мира», WorldRules): правило [key] в [value]. Значение уже
     * итоговое — проценты ползунка переведены в него при создании команды. [cellType] — ключ типа
     * клетки у правил клетки (CellRule), пусто — правило всего мира (WorldRule). Командой мира, как
     * отладка физики: меняет мир, поэтому применяется в тике и лежит в журнале с его номером.
     */
    @Serializable
    data class SetWorldRule(val key: String, val value: Float, val cellType: String = "") : PlayerCommand {
        override val name get() = "SetWorldRule"
        override val detail get() = if (cellType.isEmpty()) "$key=$value" else "$cellType.$key=$value"
    }

    /** «Сбросить» в окне настроек мира: все правила — к стандарту нового мира. */
    @Serializable
    object ResetWorldRules : PlayerCommand {
        override val name get() = "ResetWorldRules"
    }
}
