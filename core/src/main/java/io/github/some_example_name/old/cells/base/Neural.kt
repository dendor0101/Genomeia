package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DIContainer.cellEntity
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

fun activation(id: Int, x: Float) = with(cellEntity) {
    when (getActivationFuncType(id)) {
        0 -> getA(id) * x + getB(id)
        1 -> getC(id) * sin(getA(id) * x + getB(id))
        2 -> getC(id) * cos(getA(id) * x + getB(id))
        3 -> 1f / (1f + exp(-(getA(id) * x + getB(id)))) + getC(id)
        4 -> if (x <= getA(id)) getB(id) else getC(id)
        5 -> if (x < getA(id)) getB(id) else getC(id)
        6 -> simEntity.timeSimulation
        7 -> {
            if (x >= 1f && simEntity.timeSimulation > getDTime(id)) {
                setDTime(id, simEntity.timeSimulation + getA(id))
            }

            if (simEntity.timeSimulation < getDTime(id)) {
                1f
            } else {
                setDTime(id, -1f)
                0f
            }
        }

        8 -> {
            if (x > getA(id) && x < getB(id)) {
                x
            } else getC(id)
        }

        9 -> {
            x.pow(getA(id))
        }

        10 -> {
            if (x > 0) {
                setRemember(id, 1.0f)
            } else if (x < 0) {
                setRemember(id, 0.0f)
            }
            getRemember(id)
        }

        else -> x
    }
}
