package io.github.some_example_name.attempts.game.physics


import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.attempts.game.gameabstraction.entity.*
import io.github.some_example_name.attempts.game.logutils.measureTime
import io.github.some_example_name.attempts.game.main.*
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.CELL_SIZE
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.MAX_REPULSION_RADIUS
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.REST_LENGTH
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.STIFFNESS
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.WORLD_SIZE
import io.github.some_example_name.attempts.game.sound.SoundManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PhysicsManager(
    private val worldGridManager: WorldGridManager,
    private val soundManager: SoundManager
) {


    var particles = mutableListOf<Particle>()
    private var copyParticles = mutableListOf<Particle>()
    private var particlesAdded = emptyList<Particle>()
    var links = mutableListOf<Link>()

    fun init() {
        copyParticles = particles.copy()
        val physicsThread = getPhysicsThread()
        physicsThread.start()
    }

    fun addParticleByClick(x: Float, y: Float) {
        if (x > 30 && y > 30 && x < WORLD_SIZE - 30 && y < WORLD_SIZE - 30) {
            synchronized(particlesAdded) {
                soundManager.playPik()
                particlesAdded = listOf(
                    Particle(
                        x,
                        y,
                        type = ParticleType.LEAF,
                    ).apply {
                        color = genome["plug"]?.color ?: Color.BLACK
                    }
                )
            }
        }
    }

    private fun getPhysicsThread() = Thread {
        val frameTime = 16_666_666L // 16.6666 мс в наносекундах
        while (!Thread.currentThread().isInterrupted) {
            val startTime = System.nanoTime()

            if (!isPaused) {
                "math".measureTime {
                    synchronized(particlesAdded) {
                        if (particlesAdded.isNotEmpty()) {
                            particlesAdded.forEachIndexed { index, particle ->
                                worldGridManager.grid[(particle.x / CELL_SIZE).toInt()][(particle.y / CELL_SIZE).toInt()].add(
                                    copyParticles.size + index
                                )
                            }
                            copyParticles.addAll(particlesAdded)
                            particlesAdded = emptyList()
                        }
                    }

                    "performMath".measureTime {
                        performMath()
                    }
                    "copyParticles".measureTime {
                        particles = synchronized(particles) {
                            copyParticles.map {
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
                        }
                    }
                }
            }

            // Расчет оставшегося времени до конца цикла
            val elapsedTime = System.nanoTime() - startTime
            val sleepTime = frameTime - elapsedTime

            if (sleepTime > 0) {
                Thread.sleep(sleepTime / 1_000_000, (sleepTime % 1_000_000).toInt())
            }
        }
    }.apply {
        priority = Thread.MAX_PRIORITY // Устанавливаем максимальный приоритет
        isDaemon = true // Поток завершится при завершении программы
    }

    private fun neighbourCells(
        gridXIndex: Int,
        gridYIndex: Int,
        i: Int,
        particle: Particle,
    ) {
        for (dx in -1..1) {
            for (dy in -1..1) {
                worldGridManager.grid[gridXIndex + dx][gridYIndex + dy].repelNeighbours(i, particle)
            }
        }
    }

    private fun neighbourCellsLink(
        gridXIndex: Int,
        gridYIndex: Int,
    ) {
        for (dx in -2..2) {
            for (dy in -2..2) {
                worldGridManager.grid[gridXIndex + dx][gridYIndex + dy].forEach {
//                    copyParticles[it].genomeId ==
                }
            }
        }
    }

    private fun MutableList<Int>.repelNeighbours(i: Int, particle: Particle) {
        this.forEach {
            if (it != i && !particle.updatedBy.contains(it)) {
                val other = copyParticles[it]
                if (particle.type != ParticleType.WALL || other.type != ParticleType.WALL || isWallMovable) {
                    particle.repel(other, i, particle.isOld && other.isOld, it)
                }
            }
        }
    }

    private fun performMath() {
        "link".measureTime {
            links.forEach {
                val c1 = copyParticles[it.id1]
                val c2 = copyParticles[it.id2]
                val dx = c2.x - c1.x
                val dy = c2.y - c1.y
                val dist = sqrt(dx * dx + dy * dy)
                val force = (dist - REST_LENGTH) * STIFFNESS
                val fx = force * dx / dist
                val fy = force * dy / dist

                c1.vx += fx
                c1.vy += fy
                c2.vx -= fx
                c2.vy -= fy
            }
        }

        "move".measureTime {
            copyParticles.forEachIndexed { index, particle ->
                val startX = (particle.x / CELL_SIZE).toInt()
                val startY = (particle.y / CELL_SIZE).toInt()
                particle.move(index)
                val movedX = (particle.x / CELL_SIZE).toInt()
                val movedY = (particle.y / CELL_SIZE).toInt()
                if (startX != movedX || startY != movedY) {
                    worldGridManager.grid[startX][startY].remove(index)
                    worldGridManager.grid[movedX][movedY].add(index)
                }
            }
        }

        "revel".measureTime {
            for (i in copyParticles.indices) {
                val gridXIndex: Int
                val gridYIndex: Int
                val particle: Particle = copyParticles[i]
                gridXIndex = (particle.x / CELL_SIZE).toInt()
                gridYIndex = (particle.y / CELL_SIZE).toInt()
                neighbourCells(gridXIndex, gridYIndex, i, particle)
            }
        }

        "clear updatedBy".measureTime {
            copyParticles.forEach { it.updatedBy.clear() }
//            copyParticles.addAll(particlesToAdd)
        }
    }


    private fun Particle.move(index: Int) {
        if (abs(vx) < 0.0001) {
            vx = 0f
        }
        if (abs(vy) < 0.0001) {
            vy = 0f
        }
        if (MAX_REPULSION_RADIUS > repulsionRadius) {
            repulsionRadius += if (type == ParticleType.WALL) 0.006f else 0.006f
        } else {
            repulsionRadius = 5f
            if (type != ParticleType.WALL && !isOld) {
                val newParticle = split(index)
                if (newParticle != null) {
                    particlesAdded += newParticle
                } else {
                    isOld = true
                }
            }
        }
        if (type == ParticleType.WALL && !isWallMovable) return
        x += vx
        y += vy
        vx *= friction
        vy *= friction


        //TODO сделать более адекватную систему и добавить логику под изменения грида
        when {
            x - 25 <= 0 && vx < 0 -> {
                x = WORLD_SIZE - 25
            }

            y - 25 <= 0 && vy < 0 -> {
                y = WORLD_SIZE - 25
            }

            x + 25 >= WORLD_SIZE && vx > 0 -> {
                x = 25f
            }

            y + 25 >= WORLD_SIZE && vy > 0 -> {
                y = 25f
            }
        }
    }

    private fun getOffset(angleCellDivision: Double, offset: Float): Pair<Float, Float> {
        val angleRadians = Math.toRadians(angleCellDivision.toDouble())
        val xOffset = offset * cos(angleRadians).toFloat()
        val yOffset = offset * sin(angleRadians).toFloat()
        return  Pair(xOffset, yOffset)
    }

    private fun Particle.split(index: Int): Particle? {
        val result = traverseGenome(genome[cell.genomeId], tree) ?: return null

        if (result.heirPair == null) {
            isOld = true
            return null
        }

        val copyTree = tree.map { it }

        val offset = 0.1f
        val childRadius = repulsionRadius * 0.707f


        val particleOffset1 = getOffset(result.angleCellDivision.toDouble(), offset)
        val particleOffset2 = getOffset(result.angleCellDivision.toDouble() + 180.0, offset)

        var isClearTree1 = false
        val leaf1 = when(val it = result.heirPair.first) {
            is Cycle -> {
                isClearTree1 = true
                genome[it.genomeId] ?: return null
            }
            is GenomeLeaf -> it
        }

        val particle = Particle(x + particleOffset1.first, y + particleOffset1.second, ParticleType.LEAF).apply {
            repulsionRadius = childRadius
            color = leaf1.color
            colorCore = leafColors.random()
            isOld = leaf1.heirPair == null
            genomeId = leaf1.id ?: 0
//            needToJoin = leaf1.joins.toMutableList()
            tree += if (isClearTree1) emptyList() else {
                copyTree + listOf(false)
            }
        }

        var isClearTree2 = false
        val leaf2 = when(val it = result.heirPair.second) {
            is Cycle -> {
                isClearTree2 = true
                genome[it.genomeId] ?: return null
            }
            is GenomeLeaf -> it
        }
        this.apply {
            x += particleOffset2.first
            y += particleOffset2.second
            vx = 0f
            vy = 0f
            updatedBy = mutableListOf()
            repulsionRadius = childRadius
            color = leaf2.color
            genomeId = leaf2.id ?: 0
            colorCore = leafColors.random()
            isOld = leaf2.heirPair == null
//            needToJoin = leaf2.joins.toMutableList()
            tree = if (isClearTree2) emptyList() else {
                copyTree + listOf(true)
            }

//            val newLinks = links.filterIndexed { index, link ->
//                !linksId.contains(index)
//            }
//
////            println("linksId $linksId")
////            println(links)
////            println("clear")
////            println(newLinks)
//            links.clear()
//            links.addAll(newLinks)
//            linksId.clear()
        }

        val gridXIndex = (this.x / CELL_SIZE).toInt()
        val gridYIndex = (this.y / CELL_SIZE).toInt()
//        this.linkedParticleId = neighbourCellsLink(
//            gridXIndex,
//            gridYIndex,
//            leaf2.joins
//        )

        soundManager.playPik()
        return particle
    }


    private fun Particle.repel(other: Particle, i: Int, isAllOld: Boolean, otherId: Int) {
        //8-9 mc
        other.updatedBy.add(i)
        //10-11 mc
        val radiusSquared = if (isAllOld) {
            100f
        } else {
            val radius = repulsionRadius + other.repulsionRadius
            radius * radius
        }

        val dx = x - other.x
        val dy = y - other.y

        val dx2 = dx * dx
        if (dx2 > radiusSquared) return
        val dy2 = dy * dy
        if (dy2 > radiusSquared) return

        val distanceSquared = dx2 + dy2

        //11-12 mc
        if (distanceSquared < radiusSquared) {

            // Квадратичная зависимость силы
            val force = if (isAllOld) {
                2f - 0.02f * distanceSquared
            } else {
                2f - 2f * distanceSquared / radiusSquared
            }
            val distance = 1.0f / invSqrt(distanceSquared)
            // Нормализация вектора расстояния
            val normX = dx / distance
            val normY = dy / distance
            val vectorX = normX * force
            val vectorY = normY * force
            vx += vectorX
            vy += vectorY
            other.vx -= vectorX
            other.vy -= vectorY
        }
    }

}
