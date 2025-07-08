package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.attempts.game.physics.pinkColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager

class Sticky: Cell(), Neural {

    override var colorCore = pinkColors[3]
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override fun specificToThisType() {

    }

    override fun repulse(other: Cell) {
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
//            links.add(Link(this, other, true).apply { restLength = distance})TODO
        }
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {

//            if (cm.tickRestriction[id] == 14) {
////                println("tickRestriction")
//                cm.tickRestriction[id] = 0
//                if (cm.neuronImpulseImport[id] >= 1f) {
////                    println("delete1")
//                    if (cm.linksAmount[id] == 0) return
//                    for (i in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
//                        //TODO Sticky косячный, нужно доправить
//                        val linkId = cm.links[i]
////                        cm.showInfo()
////                        println("lolollol $i")
//                        if (linkId == -1) {
//                            println("kek ${linkId} ${cm.links[i]} ${i} ${cm.linksAmount[id]}")
//                            for (p in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
//                                print("${cm.links[p]} ${p}; ")
//                            }
//                            println()
//                            continue
//                        }
//                        if (cm.isStickyLink[linkId]) {
////                            println("delete Sticky")
////                            println("deleteLink ${cm.links1[linkId]} ${cm.links2[linkId]} $linkId")
//                            cm.deleteLink(cm.links1[linkId], cm.links2[linkId], linkId)
//                        }
//                    }
//                }
//            } else {
//                cm.tickRestriction[id] += 1
//            }
        }
    }
}
