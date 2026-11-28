package io.github.some_example_name.old.features.guide

import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoTutorial
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import kotlinx.serialization.Serializable

/** Порядок — формат журнала (SelectTab пишет вкладку номером): новые только в конец. */
enum class GuideTab {
    TUTORIAL, CELLS, FORMULAS
}

/** Что игрок хочет сделать в гайде. */
@Serializable
sealed interface GuideIntent : GameCommand {

    @Serializable
    data class SelectTab(val tab: GuideTab) : GuideIntent {
        override val name get() = "SelectTab"
        override val detail get() = tab.name
    }

    /** Начать обучение: редактор генома с шагами обучения. */
    @Serializable
    data object StartTutorial : GuideIntent {
        override val name get() = "StartTutorial"
    }

    @Serializable
    data class OpenCellLab(val key: String) : GuideIntent {
        override val name get() = "OpenCellLab"
        override val detail get() = key
    }

    @Serializable
    data object CloseCellLab : GuideIntent { override val name get() = "CloseCellLab" }

    // --- Стенд формул: один нейрон, который игрок настраивает ползунками ---

    /** Формула нейрона — ключом (Formula.key), как в геноме. */
    @Serializable
    data class SelectFormula(val formulaKey: String) : GuideIntent {
        override val name get() = "SelectFormula"
        override val detail get() = formulaKey
    }

    /** Коэффициент формулы: [index] 0 — a, 1 — b, 2 — c. */
    @Serializable
    data class SetCoefficient(val index: Int, val value: Float) : GuideIntent {
        override val name get() = "SetCoefficient"
        override val detail get() = "${"abc"[index]}=$value"
    }

    /** Значение входного сигнала номер [index]. */
    @Serializable
    data class SetInput(val index: Int, val value: Float) : GuideIntent {
        override val name get() = "SetInput"
        override val detail get() = "$index=$value"
    }

    @Serializable
    data class SetInputCount(val count: Int) : GuideIntent {
        override val name get() = "SetInputCount"
        override val detail get() = count.toString()
    }

    /** Как собирать входы: сложением или умножением (как «Сложение / Умножение» в редакторе). */
    @Serializable
    data class SetCombine(val isSum: Boolean) : GuideIntent {
        override val name get() = "SetCombine"
        override val detail get() = if (isSum) "sum" else "product"
    }

    /**
     * Импульсы на входе 1: раз в [period] тиков вход 1 подаёт своё значение на один тик,
     * остальное время молчит; 0 — ровный вход. Так на стенде видно, как trace превращает
     * частоту спайков в ровный сигнал.
     */
    @Serializable
    data class SetPulse(val period: Int) : GuideIntent {
        override val name get() = "SetPulse"
        override val detail get() = period.toString()
    }
}

class GuideViewModel {
    internal val cellLab = io.github.some_example_name.old.features.guide.celllab.CellLabSession()

    /** Вкладка живёт здесь, а не в экране: рекомпозиция пересобирает вёрстку целиком. */
    var currentTab: GuideTab = GuideTab.TUTORIAL
        private set

    /** Настройки нейрона на стенде формул. */
    val lab = NeuronLabSettings()

    private val navigation = DIGameGlobalContainer.navigationCommandsManager

    fun handle(intent: GuideIntent) = SessionJournal.execute(CommandSource.GUIDE, intent) { apply(it) }

    private fun apply(intent: GuideIntent) {
        when (intent) {
            is GuideIntent.SelectTab -> {
                cellLab.close()
                currentTab = intent.tab
            }
            is GuideIntent.OpenCellLab -> cellLab.open(intent.key)
            GuideIntent.CloseCellLab -> cellLab.close()
            GuideIntent.StartTutorial -> navigation.performCommand(GoTutorial)
            is GuideIntent.SelectFormula -> lab.formulaKey = intent.formulaKey
            is GuideIntent.SetCoefficient -> when (intent.index) {
                0 -> lab.a = intent.value
                1 -> lab.b = intent.value
                else -> lab.c = intent.value
            }
            is GuideIntent.SetInput -> lab.inputs[intent.index] = intent.value
            is GuideIntent.SetInputCount ->
                lab.inputCount = intent.count.coerceIn(1, NeuronLabSettings.MAX_INPUTS)
            is GuideIntent.SetCombine -> lab.isSum = intent.isSum
            is GuideIntent.SetPulse -> lab.pulsePeriod = intent.period.coerceIn(0, NeuronLabSettings.MAX_PULSE_PERIOD)
        }
    }
}
