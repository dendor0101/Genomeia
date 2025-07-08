package io.github.some_example_name.attempts.game.main

import io.github.some_example_name.attempts.game.physics.Particle

fun MutableList<Particle>.copy(): MutableList<Particle> = this.map {
    Particle(it.x, it.y, it.type).apply {
        vx = it.vx
        vy = it.vy
        repulsionRadius = it.repulsionRadius
        updatedBy = it.updatedBy
        isOld = it.isOld
        color = it.color
        colorCore = it.colorCore
        tree = it.tree
    }
}.toMutableList()
