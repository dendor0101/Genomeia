package io.github.some_example_name.old.features.guide

import com.badlogic.gdx.graphics.Color
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.Formula
import io.github.some_example_name.old.cells.base.FormulaGroup
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.disableParentFlickScrollWhileDragging
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.hint
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.features.guide.NeuronLabSettings.Companion.MAX_INPUTS
import io.github.some_example_name.old.features.guide.NeuronLabSettings.Companion.MAX_PULSE_PERIOD
import io.github.some_example_name.old.features.guide.NeuronLabSettings.Companion.MAX_VALUE
import io.github.some_example_name.old.features.guide.NeuronLabSettings.Companion.MIN_VALUE
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Вкладка «Формулы» гайда: стенд одного нейрона. Входные сигналы и коэффициенты a, b, c —
 * ползунками, формула — списком, как в диалоге клетки; график и значения x, y меняются
 * сразу при движении ползунка.
 *
 * Каждое движение ползунка — команда GuideIntent (её применяет GuideViewModel), а вёрстка
 * только перерисовывает подписи и график: пересобирать вкладку целиком нельзя — прокрутка
 * уехала бы наверх посреди движения ползунка.
 */
class FormulaLabView(
    private val viewModel: GuideViewModel,
    private val lab: NeuronLab
) {
    private val settings get() = viewModel.lab

    /** Формулы, которые можно выбрать в редакторе, — в том же порядке. */
    private val formulas: List<Formula> = CellPalette.FULL.formulaChoices(current = null)

    private val curveX = FloatArray(CURVE_POINTS) { MIN_VALUE + it * (MAX_VALUE - MIN_VALUE) / (CURVE_POINTS - 1) }
    private val curveY = FloatArray(CURVE_POINTS)

    private lateinit var graph: FormulaGraph
    private lateinit var inputs: VisTable
    private lateinit var xLabel: VisLabel
    private lateinit var yLabel: VisLabel
    private lateinit var graphNote: VisLabel
    private lateinit var description: VisLabel

    /** Кривую y(x) пересчитать: поменялись формула или коэффициенты. */
    private var curveChanged = true

    /**
     * На широком экране — две колонки: слева ползунки, справа график, чтобы результат был
     * виден, пока тянешь ползунок. На узком — график сверху, ползунки под ним.
     */
    fun compose(table: VisTable, width: Float) = with(table) {
        val inner = width - 32.dp()
        defaults().left().width(inner)

        visLabel(bundle.get("guide.formulas.text")).setWrap(true)
        row()

        if (width >= TWO_COLUMNS_FROM.dp()) {
            val column = (inner - 24.dp()) / 2
            visTable({ padTop(16.dp()) }) {
                // spaceRight(0): VisTable сам ставит 6 px между колонками, и строка выходила
                // на них шире страницы — таблица центрировала лишнее, левая колонка уезжала под
                // край прокрутки и обрезала первые буквы. Зазор между колонками — padRight.
                visTable({ top().width(column).padRight(24.dp()).spaceRight(0f) }) {
                    defaults().left().width(column)
                    controls()
                }
                visTable({ top().width(column) }) {
                    defaults().left().width(column)
                    graphColumn()
                }
            }
            row()
        } else {
            visTable({ padTop(16.dp()) }) {
                defaults().left().width(inner)
                graphColumn()
                row()
                controls()
            }
            row()
        }

        // --- как собрать спайковую сеть: формулы lif и trace вместе с Synapse
        visLabel(bundle.get("guide.formulas.snn.title"), font = game.extraLargeFont) { padTop(24.dp()) }
        row()
        visLabel(bundle.get("guide.formulas.snn.text")).setWrap(true)
        row()

        // --- справочник: все формулы по разделам
        visLabel(bundle.get("guide.formulas.all"), font = game.extraLargeFont) { padTop(24.dp()) }
        row()
        for (group in FormulaGroup.entries) {
            val inGroup = formulas.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            val name = group.name.lowercase()
            visLabel(bundle.get("guide.formulas.group.$name"), font = game.largeFont) { padTop(16.dp()) }
            row()
            visLabel(bundle.get("guide.formulas.group.$name.text"), textColor = MUTED).setWrap(true)
            row()
            for (formula in inGroup) {
                visLabel(formula.label, textColor = OUTPUT) { padTop(8.dp()) }
                row()
                visLabel(formula.description).setWrap(true)
                row()
            }
        }

        onFormulaChanged()
        refreshInputs()
    }

    /** Входы и плашка нейрона. */
    private fun VisTable.controls() {
        // --- входные сигналы
        visLabel(bundle.get("guide.formulas.inputs"), font = game.extraLargeFont)
        row()
        inputs = visTable { }
        composeInputs()
        row()

        visTable({ padTop(8.dp()) }) {
            visTextButton("-", onClick = { setInputCount(settings.inputCount - 1) })
                .hint(bundle.get("guide.formulas.removeInput"))
            visTextButton("+", onClick = { setInputCount(settings.inputCount + 1) })
                .hint(bundle.get("guide.formulas.addInput"))
        }
        row()

        pulseControl()

        // Отдельной строкой: рядом с «-» и «+» в половине экрана не помещается.
        visTable({ padTop(8.dp()) }) {

            lateinit var sum: VisTextButton
            lateinit var product: VisTextButton
            var syncing = false
            fun combine(isSum: Boolean) {
                if (syncing) return
                syncing = true
                sum.isChecked = isSum
                product.isChecked = !isSum
                syncing = false
                if (settings.isSum == isSum) return
                viewModel.handle(GuideIntent.SetCombine(isSum))
                refreshInputs()
            }
            sum = visToggleButton(bundle.get("button.addition"), checked = settings.isSum,
                onCheckedChange = { combine(if (it) true else settings.isSum) })
            product = visToggleButton(bundle.get("button.multiplication"), checked = !settings.isSum,
                onCheckedChange = { combine(if (it) false else settings.isSum) })
            sum.hint(bundle.get("editor.hint.combineInputs"))
            product.hint(bundle.get("editor.hint.combineInputs"))
        }
        row()

        xLabel = visLabel("", textColor = INPUT) { padTop(8.dp()) }
        xLabel.setWrap(true)
        row()

        // --- нейрон: та же плашка, что в диалоге клетки, только с ползунками
        visTable({ padTop(16.dp()) }, backgroundColor = CARD) {
            pad(12.dp())
            defaults().left()

            visLabel(bundle.get("guide.formulas.neuron"), font = game.extraLargeFont) { colspan(3) }
            row()

            // minWidth(0): желаемая ширина списка — по самой длинной формуле, и с ней карточка
            // выходила шире колонки, а содержимое съезжало за её левый край.
            visSelectBox(
                items = formulas.map { it.label }.toTypedArray(),
                selectedIndex = formulas.indexOfFirst { it.key == settings.formulaKey }.coerceAtLeast(0),
                onChange = { _, i ->
                    viewModel.handle(GuideIntent.SelectFormula(formulas[i].key))
                    onFormulaChanged()
                }
            ) { colspan(3).padBottom(8.dp()).minWidth(0f).fillX() }.hint(bundle.get("editor.hint.formula"))
            row()

            coefficient(0, "a", settings.a, "editor.hint.coefA")
            coefficient(1, "b", settings.b, "editor.hint.coefB")
            coefficient(2, "c", settings.c, "editor.hint.coefC")
        }
        row()
    }

    /** График, значения и описание выбранной формулы. */
    private fun VisTable.graphColumn() {
        // --- график
        graph = FormulaGraph(game.mediumFont)
        add(graph).height(300.dp())
        row()

        graphNote = visLabel("", textColor = MUTED, font = game.mediumFont)
        graphNote.setWrap(true)
        row()

        yLabel = visLabel("", textColor = OUTPUT, font = game.extraLargeFont) { padTop(4.dp()) }
        row()

        description = visLabel("") { padTop(8.dp()) }
        description.setWrap(true)
        row()
    }

    /**
     * Кадр экрана: формулы с памятью и импульсы на входе живут во времени, остальным хватает
     * пересчёта по изменению.
     */
    fun update(delta: Float) {
        lab.configure(settings)
        val x = settings.x()
        val formula = lab.formula(settings)
        if (overTime(formula)) {
            lab.advance(delta, settings)
            graph.showHistory(
                lab.historyX, lab.historyY, lab.historySize, lab.historyX.size, NeuronLab.TICKS_PER_SECOND,
                memory = if (formula.showsMemory) lab.historyMemory else null
            )
            yLabel.setText("y = ${format(lab.lastY)}")
        } else if (curveChanged) {
            curveChanged = false
            lab.curve(curveX, curveY)
            val y = lab.output(x)
            graph.showCurve(curveX, curveY, x, y)
            yLabel.setText("y = ${format(y)}")
        }
    }

    private fun VisTable.coefficient(index: Int, name: String, value: Float, hintKey: String) {
        visLabel("$name:", font = game.extraLargeFont) { padRight(8.dp()) }.hint(bundle.get(hintKey))
        lateinit var valueLabel: VisLabel
        visSlider(MIN_VALUE, MAX_VALUE, STEP, value = value, onValueChange = {
            val v = round(it)
            viewModel.handle(GuideIntent.SetCoefficient(index, v))
            valueLabel.setText(format(v))
            curveChanged = true
        }) { growX().minWidth(200.dp()) }.apply { disableParentFlickScrollWhileDragging() }.hint(bundle.get(hintKey))
        valueLabel = visLabel(format(value)) { width(90.dp()).padLeft(8.dp()) }
        row()
    }

    /** Строки входов: их столько, сколько входов, поэтому пересобираются при смене числа. */
    private fun composeInputs() {
        inputs.clearChildren()
        inputs.defaults().left()
        for (i in 0 until settings.inputCount) {
            inputs.visLabel(bundle.format("guide.formulas.input", i + 1)) { padRight(8.dp()) }
            lateinit var valueLabel: VisLabel
            inputs.visSlider(MIN_VALUE, MAX_VALUE, STEP, value = settings.inputs[i], onValueChange = {
                val v = round(it)
                viewModel.handle(GuideIntent.SetInput(i, v))
                valueLabel.setText(format(v))
                refreshInputs()
            }) { growX().minWidth(200.dp()) }.disableParentFlickScrollWhileDragging()
            valueLabel = inputs.visLabel(format(settings.inputs[i])) { width(90.dp()).padLeft(8.dp()) }
            inputs.row()
        }
        inputs.invalidateHierarchy()
    }

    private fun setInputCount(count: Int) {
        val clamped = count.coerceIn(1, MAX_INPUTS)
        if (clamped == settings.inputCount) return
        viewModel.handle(GuideIntent.SetInputCount(clamped))
        composeInputs()
        refreshInputs()
    }

    /** Вход поменялся: подпись x = … и отметка на кривой. */
    private fun refreshInputs() {
        val parts = (0 until settings.inputCount).joinToString(if (settings.isSum) " + " else " * ") {
            val v = format(settings.inputs[it]).let { v -> if (settings.inputs[it] < 0) "($v)" else v }
            // Импульсный вход — то значение, что он подаёт в тик импульса.
            if (it == 0 && settings.pulsePeriod > 0) bundle.format("guide.formulas.pulseValue", v) else v
        }
        // При импульсах x меняется каждый тик — его показывает голубая линия графика.
        xLabel.setText(if (settings.pulsePeriod > 0) "x = $parts" else "x = $parts = ${format(settings.x())}")
        curveChanged = true
    }

    private fun onFormulaChanged() {
        val formula = lab.formula(settings)
        description.setText(formula.description)
        refreshGraphNote()
        curveChanged = true
    }

    private fun refreshGraphNote() {
        val formula = lab.formula(settings)
        val note = bundle.get(if (overTime(formula)) "guide.formulas.overTime" else "guide.formulas.curve")
        graphNote.setText(if (formula.showsMemory) note + " " + bundle.get("guide.formulas.memoryLine") else note)
    }

    /** Ползунок импульсов на входе 1: 0 — ровный вход. */
    private fun VisTable.pulseControl() {
        fun text(period: Int) =
            if (period == 0) bundle.get("guide.formulas.pulse.off") else bundle.format("guide.formulas.pulse", period)
        val label = visLabel(text(settings.pulsePeriod)) { padTop(12.dp()) }.hint(bundle.get("guide.formulas.pulse.hint"))
        label.setWrap(true)
        row()
        visSlider(0f, MAX_PULSE_PERIOD.toFloat(), 1f, value = settings.pulsePeriod.toFloat(), onValueChange = {
            val period = it.roundToInt()
            if (period != settings.pulsePeriod) {
                viewModel.handle(GuideIntent.SetPulse(period))
                label.setText(text(period))
                refreshInputs()
                refreshGraphNote()
            }
        }) { growX().minWidth(200.dp()) }.apply { disableParentFlickScrollWhileDragging() }
            .hint(bundle.get("guide.formulas.pulse.hint"))
        row()
    }

    /** Рисовать сигнал во времени, а не кривую y(x). */
    private fun overTime(formula: Formula) = formula.dependsOnTime || settings.pulsePeriod > 0

    private fun round(v: Float) = (v / STEP).roundToInt() * STEP

    private fun format(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

    private companion object {
        const val CURVE_POINTS = 401
        /** С какой ширины вкладки (dp) график встаёт справа от ползунков. */
        const val TWO_COLUMNS_FROM = 760
        const val STEP = 0.1f

        val CARD = Color(0.12f, 0.15f, 0.18f, 1f)
        val MUTED = Color(0.65f, 0.62f, 0.56f, 1f)
        val INPUT = Color(0.45f, 0.75f, 0.95f, 1f)
        val OUTPUT = Color(0.95f, 0.80f, 0.35f, 1f)
    }
}
