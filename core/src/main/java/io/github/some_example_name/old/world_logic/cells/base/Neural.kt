package io.github.some_example_name.old.world_logic.cells.base

import io.github.some_example_name.old.good_one.time
import io.github.some_example_name.old.world_logic.CellManager
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

interface Neural {
//    var activationFuncType: Int
//    var a: Float
//    var b: Float
//    var c: Float
//    var dTime: Float
//
//    fun activation(x: Float) = when (activationFuncType) {
//        0 -> a * x + b
//        1 -> c * sin(a * x + b)
//        2 -> c * cos(a * x + b)
//        3 -> 1f / (1f + exp(-(a * x + b))) + c
//        4 -> if (x <= a) b else c
//        5 -> if (x < a) b else c
//        6 -> time
//        7 -> {
//            if (x >= 1f && time > dTime) {
//                dTime = time + a
//            }
//
//            if (time < dTime) {
//                1f
//            } else {
//                dTime = -1f
//                0f
//            }
//        }
//        8 -> {
//            if (x > a && x < b) {
//                x
//            } else c
//        }
//        else -> 0f
//    }
}

fun activation(cm: CellManager, id: Int, x: Float) = when (cm.activationFuncType[id]) {
    0 -> cm.a[id] * x + cm.b[id]
    1 -> cm.c[id] * sin(cm.a[id] * x + cm.b[id])
    2 -> cm.c[id] * cos(cm.a[id] * x + cm.b[id])
    3 -> 1f / (1f + exp(-(cm.a[id] * x + cm.b[id]))) + cm.c[id]
    4 -> if (x <= cm.a[id]) cm.b[id] else cm.c[id]
    5 -> if (x < cm.a[id]) cm.b[id] else cm.c[id]
    6 -> time
    7 -> {
        if (x >= 1f && time > cm.dTime[id]) {
            cm.dTime[id] = time + cm.a[id]
        }

        if (time < cm.dTime[id]) {
            1f
        } else {
            cm.dTime[id] = -1f
            0f
        }
    }
    8 -> {
        if (x > cm.a[id] && x < cm.b[id]) {
            x
        } else cm.c[id]
    }
    9 -> {
        x.pow(cm.a[id])
    }
    10 -> {
        if (x > 0) {
            cm.remember[id] = 1.0f
        } else if (x < 0) {
            cm.remember[id] = 0.0f
        }
        cm.remember[id]
    }
    else -> x
}
