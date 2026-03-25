package io.github.some_example_name.old.core.utils

import io.github.some_example_name.old.systems.physics.GridManager


fun GridManager.collectParticles(gridX: Int, gridY: Int, radius: Int = 3): IntArray {
    val list = ArrayList<Int>()
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            val arr = getParticles(gridX + dx, gridY + dy)
            for (v in arr) list.add(v)
        }
    }
    return list.toIntArray()
}
