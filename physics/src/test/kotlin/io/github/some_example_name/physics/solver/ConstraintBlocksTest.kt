package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ConstraintBlocksTest {
    @Test fun damageAndPackingKeepEveryLiveConstraintInExactlyOneBlock() {
        val width=34; val n=width*width
        val triangles=ArrayList<IntArray>(); val edges=linkedSetOf<Pair<Int,Int>>()
        fun tri(a:Int,b:Int,c:Int) {
            triangles.add(intArrayOf(a,b,c))
            for ((i,j) in arrayOf(a to b,b to c,c to a)) edges.add(minOf(i,j) to maxOf(i,j))
        }
        for(y in 0 until width-1) for(x in 0 until width-1) {
            val a=y*width+x;tri(a,a+1,a+width);tri(a+1,a+width+1,a+width)
        }
        val body=BodyDefinition(FloatArray(n){(it%width).toFloat()},FloatArray(n){(it/width).toFloat()},
            FloatArray(n){0.5f},BooleanArray(n),BooleanArray(n),edges.map{it.first}.toIntArray(),
            edges.map{it.second}.toIntArray(),triangles.map{it[0]}.toIntArray(),triangles.map{it[1]}.toIntArray(),
            triangles.map{it[2]}.toIntArray(),FloatArray(triangles.size){1f},emptyArray(),emptyArray(),1f)
        val state=BodyState().also{it.quiet=true;it.initialize(body);it.resetState()}
        fun checkLists() {
            val layout=state.constraintBlocks!!
            fun check(index:BlockIndex,expected:List<Int>,blockOf:(Int)->Int) {
                val found=ArrayList<Int>()
                for(b in index.head.indices) {
                    var c=index.head[b]
                    while(c>=0) {
                        assertTrue(found.size<=expected.size); assertEquals(b,blockOf(c));found.add(c);c=index.next[c]
                    }
                }
                assertEquals(expected,found.sorted())
            }
            fun checkDense(index:DenseBlockIndex,expected:List<Int>,blockOf:(Int)->Int) {
                val found=ArrayList<Int>()
                for (b in index.start.indices) assertEquals(expected.count { blockOf(it) == b }, index.liveCount[b])
                for(b in index.start.indices) for(slot in index.start[b] until index.start[b]+index.count[b]) {
                    val c=index.items[slot]
                    if(c>=0) {assertEquals(b,blockOf(c));found.add(c)}
                }
                assertEquals(expected,found.sorted())
            }
            checkDense(layout.links,(0 until state.conCount).filter{!state.conDead[it]}){layout.blockOf(state.conA[it],state.conB[it])}
            check(layout.limits,(0 until state.conCount).filter{!state.conDead[it]&&state.conBoundary[it]}){layout.blockOf(state.conA[it],state.conB[it])}
            checkDense(layout.areas,(0 until state.triCount).filter{state.baked.triAlive[state.triId[it]]}){layout.blockOf(state.triA[it],state.triB[it],state.triC[it])}
            check(layout.bends,(0 until state.bendCount).toList()){layout.blockOf(state.bendA[it],state.bendB[it])}
            check(layout.flaps,(0 until state.flapCount).toList()){layout.blockOf(state.flapA[it],state.flapB[it])}
        }
        val random=Random(719)
        checkLists()
        repeat(100) {
            if(it%3==0) state.killTriangleNow(random.nextInt(state.baked.triAlive.size))
            else state.killEdgeNow(random.nextInt(state.baked.edgeCount))
            state.refreshSurface();checkLists()
            if(it%4==0) {state.rebuildAfterTear();checkLists()}
        }
        state.rebuildAfterTear();checkLists()
        state.resetState();checkLists()
    }

    @Test fun indexPreservesOrderThroughGrowthRemovalAndCompaction() {
        val index = BlockIndex(1, 7)
        val expected = Array(7) { mutableListOf<Int>() }
        val random = Random(809)
        repeat(4000) {
            val id = random.nextInt(128)
            val block = random.nextInt(7)
            when (random.nextInt(3)) {
                0 -> {
                    if (expected.none { id in it }) expected[block].add(id)
                    index.add(id, block)
                }
                1 -> if (id < index.owner.size) {
                    expected.forEach { it.remove(id) }; index.remove(id)
                }
                2 -> if (id < index.owner.size) {
                    val to = random.nextInt(index.owner.size)
                    if (id != to) {
                        expected.forEach { it.remove(to) }
                        expected.forEach { list -> val at = list.indexOf(id); if (at >= 0) list[at] = to }
                    }
                    index.move(id, to)
                }
            }
            for (b in expected.indices) {
                val forward = mutableListOf<Int>()
                var c = index.head[b]
                while (c >= 0) { assertTrue(forward.size < 128); forward.add(c); assertEquals(b,index.owner[c]); c=index.next[c] }
                assertEquals(expected[b],forward)
                val backward=mutableListOf<Int>(); c=index.tail[b]
                while(c>=0) { assertTrue(backward.size<128);backward.add(c);c=index.prev[c] }
                assertEquals(expected[b].reversed(),backward)
            }
        }
        index.clear()
        assertTrue(index.owner.all { it == -1 })
        assertTrue(index.head.all { it == -1 })
    }

    @Test fun crossingConstraintsAreSeamsAndFreshContourJoinsCurrentPass() {
        val blocks=ConstraintBlocks(9,intArrayOf(0,0,0,0,0,1,1,1,1),3,20,5,0)
        assertArrayEquals(intArrayOf(0,0,0,1,1,2,2,2,3),blocks.particleBlock)
        assertEquals(0,blocks.blockOf(0,2))
        assertEquals(blocks.seam,blocks.blockOf(2,3))
        assertEquals(blocks.seam,blocks.blockOf(0,1,3))
        blocks.addLimit(0,0,1); blocks.addLimit(1,1,2)
        blocks.beginLimits(0)
        blocks.limits.remove(0)
        blocks.addLimit(2,0,2)
        assertEquals(3,blocks.limitCount())
        assertEquals(1,blocks.limitAt(1))
        assertEquals(2,blocks.limitAt(2))
        blocks.endLimits()
        blocks.beginLimits(0)
        assertEquals(2,blocks.limitCount())
        assertEquals(1,blocks.limitAt(0))
        blocks.endLimits()
    }
}
