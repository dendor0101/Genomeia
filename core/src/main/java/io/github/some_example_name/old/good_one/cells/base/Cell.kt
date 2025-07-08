package io.github.some_example_name.old.good_one.cells.base

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.attempts.game.physics.genomeEditorColor
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.*
import io.github.some_example_name.old.good_one.cells.Pumper
import io.github.some_example_name.old.good_one.cells.Sticky
import io.github.some_example_name.old.good_one.substances.Substance
import io.github.some_example_name.old.good_one.utils.addCell
import kotlin.math.sqrt

open class Cell {
    var id: String = ""//cellCounter.toString()
    var x: Float = 0f
    var y: Float = 0f
    var vx = 0f
    var vy = 0f
    private var vxOld = 0f
    private var vyOld = 0f
    var ax = 0f
    var ay = 0f
    open var colorCore = genomeEditorColor.random()
    open val maxEnergy = 5f
    open var energy = 0f
    var energyNecessaryToDivide = 2f
    var energyNecessaryToMutate = 2f
    open val cellStrength = 2f
    open val linkStrength = 0.025f
    var neuronImpulseImport = 0f
    var frictionLevel = 0.93f
    private var isAliveWithoutEnergy = 200
    open val elasticity = 3.7f
    open val isLooseEnergy = true
    var isDividedInThisStage = true
    var isMutateInThisStage = true
    var cellMode = 0f

    val physicalLinks = mutableListOf<Link>()
    val neuronLinks = mutableListOf<Link>()
    val queuePhysicalLinks = hashMapOf<String, Float>()
    val queueNeuronLinks = hashSetOf<String>()

    open fun specificToThisType() {

    }

    //TODO Не очень нравится как это сделано
    fun divide(changes: PlayGround.ChangeUnits) {
        /*
        if (genomeStage == 0) return
        if (!isDividedInThisStage && energy >= energyNecessaryToDivide) {
            isDividedInThisStage = true
            val action = genomeStageInstruction[genomeStage - 1].cellActions[id]?.divide ?: return
            val cell = addCell(action.cellType ?: return)
            cell.id = action.id ?: return
            cell.x = x + MathUtils.cos(action.angle ?: return)
            cell.y = y + MathUtils.sin(action.angle ?: return)
            action.color?.let { cell.colorCore = it }
            if (cell is Neural) {
                action.funActivation?.let { cell.activationFuncType = it }
                action.a?.let { cell.a = it }
                action.b?.let { cell.b = it }
                action.c?.let { cell.c = it }
            }
            if (cell is Directed) {
                action.angleDirected?.let { cell.angle = it }
            }

            action.physicalLink.forEach { (linkId, linkLength) ->
                if (linkLength != null) {
                    val secondCell =
                        cells.firstOrNull { it.id == linkId } // Это норм потому что потом поиск будет через grid
                    if (secondCell != null) {
                        changes.addLinks.add(Link(secondCell, cell, true).apply { restLength = linkLength })
                    } else {
                        cell.queuePhysicalLinks[linkId] = linkLength
                    }
                }
            }

            action.neuronLink.forEach { (linkId, isAdd) ->
                if (isAdd) {
                    val secondCell =
                        cells.firstOrNull { it.id == linkId } // Это норм потому что потом поиск будет через grid
                    if (secondCell != null) {
                        changes.addNeronLinks.add(Link(secondCell, cell, false))
                    } else {
                        cell.queueNeuronLinks.add(linkId)
                    }
                }
            }
            changes.addCells.add(cell)

            energy -= energyNecessaryToDivide
            return
        }
        */
    }

    //TODO Не очень нравится как это сделано
    fun mutate(changes: PlayGround.ChangeUnits) {
        /*
        if (genomeStage == 0) return
        if (!isMutateInThisStage && energy >= energyNecessaryToMutate) {
            isMutateInThisStage = true
            val action = genomeStageInstruction[genomeStage - 1].cellActions[id]?.mutate ?: return

            action.color?.let { this.colorCore = it }
            if (this is Neural) {
                action.funActivation?.let { this.activationFuncType = it }
                action.a?.let { this.a = it }
                action.b?.let { this.b = it }
                action.c?.let { this.c = it }
            }
            if (this is Directed) {
                action.angleDirected?.let { this.angle = it }
            }

            action.physicalLink.forEach { (linkId, linkLength) ->
                val secondCell = cells.firstOrNull { it.id == linkId }
                if (linkLength != null) {
                    if (secondCell != null) {
                        changes.addLinks.add(Link(secondCell, this, true).apply { restLength = linkLength })
                    } else {
                        queuePhysicalLinks[linkId] = linkLength
                    }
                } else {
                    if (secondCell != null) {
                        physicalLinks.firstOrNull { it.c1.id == secondCell.id && it.c2.id == this.id || it.c1.id == this.id && it.c2.id == secondCell.id }
                            ?.let {
                                changes.deleteLinks.add(it)
                            }
                    }
                }
            }

            action.neuronLink.forEach { (linkId, isAdd) ->
                val secondCell = cells.firstOrNull { it.id == linkId }
                if (isAdd) {
                    if (secondCell != null) {
                        changes.addNeronLinks.add(Link(secondCell, this, false))
                    } else {
                        queueNeuronLinks.add(linkId)
                    }
                } else {
                    if (secondCell != null) {
                        neuronLinks.firstOrNull { it.c1.id == secondCell.id && it.c2.id == this.id || it.c1.id == this.id && it.c2.id == secondCell.id }
                            ?.let {
                                it.c1.neuronImpulseImport = 0f
                                it.c2.neuronImpulseImport = 0f
                                changes.deleteNeronLinks.add(it)
                            }
                    }
                }
            }

            action.cellType?.let {
                val cell = addCell(action.cellType)
                cell.id = this.id
                cell.x = this.x
                cell.y = this.y
                cell.colorCore = action.color ?: this.colorCore
                if (cell is Neural) {
                    val originalCell = this as? Neural
                    cell.activationFuncType = action.funActivation ?: originalCell?.activationFuncType ?: 0
                    cell.a = action.a ?: originalCell?.a ?: 1f
                    cell.b = action.b ?: originalCell?.b ?: 0f
                    cell.c = action.c ?: originalCell?.c ?: 0f
                }
                if (cell is Directed) {
                    val originalCell = this as? Directed
                    cell.angle = action.angleDirected ?: originalCell?.angle ?: 0f
                }

                this.queuePhysicalLinks.forEach { (k, v) ->
                    cell.queuePhysicalLinks[k] = v
                }
                this.queueNeuronLinks.forEach { v ->
                    cell.queueNeuronLinks.add(v)
                }

                changes.deleteCells.add(this)
                changes.addCells.add(cell)

                //лажа
                this.physicalLinks.forEach {
                    if (it.c1 == this) it.c1 = cell
                    if (it.c2 == this) it.c2 = cell
                }
                this.neuronLinks.forEach {
                    if (it.c1 == this) it.c1 = cell
                    if (it.c2 == this) it.c2 = cell
                }

                cell.physicalLinks.addAll(this.physicalLinks)
                cell.neuronLinks.addAll(this.neuronLinks)

                changes
            }

            energy -= energyNecessaryToDivide
        }
        */
    }

    fun update(changes: PlayGround.ChangeUnits) {
        if (energy > 0) isAliveWithoutEnergy = 200
        if (isAliveWithoutEnergy < 0) {
            if (substances.size < 140) {
                substances.add(Substance().also {
                    it.x = x
                    it.y = y
                })
            }
            changes.deleteCells.add(this)
            changes.deleteLinks.addAll(this.physicalLinks)
            changes.deleteNeronLinks.addAll(this.neuronLinks)
            return
        }
        vxOld = vx
        vyOld = vy
        vx *= frictionLevel
        vy *= frictionLevel
        x += vx
        y += vy
        if (x < 20f) {
            x = 20f; vx = -vx
        }
        if (x > 940f) {
            x = 940f; vx = -vx
        }
        if (y < 20f) {
            y = 20f; vy = -vy
        }
        if (y > 940f) {
            y = 940f; vy = -vy
        }

        if (energy <= 0f) isAliveWithoutEnergy -= 1
        if (isLooseEnergy) energy -= 0.001f
    }

    fun updateAcceleration(changes: PlayGround.ChangeUnits) {
        ax = ((vx - vxOld) / 144f) * 2f
        ay = ((vy - vyOld) / 144f) * 2f
        val isDead = false//ax * ax + ay * ay > 0.02f
        if (isDead) {
            if (substances.size < 140) {
                substances.add(Substance().also {
                    it.x = x
                    it.y = y
                })
            }
            changes.deleteCells.add(this)
            changes.deleteLinks.addAll(this.physicalLinks)
            changes.deleteNeronLinks.addAll(this.neuronLinks)
        }
    }

    open fun repulse(other: Cell) {
        //TODO убивать, если ядра клеток оказались слишком близко
        val dx = x - other.x
        val dy = y - other.y
        val dx2 = dx * dx
        val radiusSquared = 1600
        if (dx2 > radiusSquared) return
        val dy2 = dy * dy
        if (dy2 > radiusSquared) return
        val distanceSquared = dx2 + dy2
        if (distanceSquared < radiusSquared) {
            val distance = 1.0f / invSqrt(distanceSquared)
            if (distance.isNaN()) throw Exception("TODO потом убрать")
            if (other is Sticky) {//TODO не очень такое нравится, как-то надо намудрить с ООП чтобы тут было лучше
//                links.add(Link(other, this, true).apply { restLength = distance })TODO
            } else {
                // Квадратичная зависимость силы
                val force = cellStrength - cellStrength * distanceSquared / radiusSquared
                // Нормализация вектора расстояния
                val normX = dx / distance
                val normY = dy / distance
                if (normX.isNaN() || normY.isNaN()) throw Exception("TODO потом убрать")
                val vectorX = normX * force
                val vectorY = normY * force
                vx += vectorX
                vy += vectorY
                other.vx -= vectorX
                other.vy -= vectorY
                if (this is Pumper) {//TODO не очень такое нравится
                    if (this.energy < this.maxEnergy) {
                        this.energy += 0.1f
                        other.energy -= 0.1f
                    }
                } else if (other is Pumper) {
                    if (other.energy < other.maxEnergy) {
                        this.energy -= 0.1f
                        other.energy += 0.1f
                    }
                }
            }
        }
    }

    fun distanceTo(px: Float, py: Float): Float {
        val dx = px - x
        val dy = py - y
        val sqrt = dx * dx + dy * dy
        if (sqrt <= 0) return 0f
        val result = sqrt(sqrt)
        if (result.isNaN()) throw Exception("TODO потом убрать")
        return result
    }

    fun moveTo(px: Float, py: Float) {
        vx += (px - x) * 0.01f
        vy += (py - y) * 0.01f
    }

    fun draw(renderer: ShapeRenderer) {
        renderer.color = colorCore
        renderer.circle(x, y, 20f)
    }
}
