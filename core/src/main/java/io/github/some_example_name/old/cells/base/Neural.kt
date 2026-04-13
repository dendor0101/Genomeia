package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.core.DISimulationContainer.cellEntity
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin


val formulaType = arrayOf(
    "y = ax + b",
    "y = c * sin(ax + b)",
    "y = c * cos(ax + b)",
    "y = sigmoid(ax + b) + c",
    "y = b, x <= a; y = c, x > a",
    "y = b, x < a; y = c, x >= a",
    "y = t",
    "y = impulse(a), x>=1",
    "y = x in (a, b) else y = c",
    "y = x^(a)",
    "y = remember(x), 0, 1"
)

fun activation(cellIndex: Int, x: Float) = with(cellEntity) {
    when (getActivationFuncType(cellIndex)) {
        0 -> getA(cellIndex) * x + getB(cellIndex)
        1 -> getC(cellIndex) * sin(getA(cellIndex) * x + getB(cellIndex))
        2 -> getC(cellIndex) * cos(getA(cellIndex) * x + getB(cellIndex))
        3 -> 1f / (1f + exp(-(getA(cellIndex) * x + getB(cellIndex)))) + getC(cellIndex)
        4 -> if (x <= getA(cellIndex)) getB(cellIndex) else getC(cellIndex)
        5 -> if (x < getA(cellIndex)) getB(cellIndex) else getC(cellIndex)
        6 -> simulationData.timeSimulation
        7 -> {
            if (x >= 1f && simulationData.timeSimulation > getDTime(cellIndex)) {
                setDTime(cellIndex, simulationData.timeSimulation + getA(cellIndex))
            }

            if (simulationData.timeSimulation < getDTime(cellIndex)) {
                1f
            } else {
                setDTime(cellIndex, -1f)
                0f
            }
        }

        8 -> {
            if (x > getA(cellIndex) && x < getB(cellIndex)) {
                x
            } else getC(cellIndex)
        }

        9 -> {
            x.pow(getA(cellIndex))
        }

        10 -> {
            if (x > 0) {
                setRemember(cellIndex, 1.0f)
            } else if (x < 0) {
                setRemember(cellIndex, 0.0f)
            }
            getRemember(cellIndex)
        }

        else -> x
    }
}
