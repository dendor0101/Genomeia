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

    @Test fun singleParticleOrganismsShareBlocksUpToBlockSize() {
        // 0 0 | 1 2 3 | 4 5 | 6 6 | 7: одиночные подряд — общий блок до размера 3.
        val blocks=ConstraintBlocks(10,intArrayOf(0,0,1,2,3,4,5,6,6,7),3,20,5,0)
        assertArrayEquals(intArrayOf(0,0,1,1,1,2,2,3,3,4),blocks.particleBlock)
        assertEquals(5,blocks.blockCount)
    }

    /** Сетка width×width: [perm] — новый номер каждой клетки, null — номера по рядам. */
    private fun gridBody(width: Int, shiftX: Float, perm: IntArray?): BodyDefinition {
        val n = width * width
        fun id(k: Int) = perm?.get(k) ?: k
        val x = FloatArray(n); val y = FloatArray(n)
        for (k in 0 until n) { x[id(k)] = (k % width) + shiftX; y[id(k)] = (k / width).toFloat() }
        val triangles = ArrayList<IntArray>(); val edges = linkedSetOf<Pair<Int, Int>>()
        fun tri(a: Int, b: Int, c: Int) {
            triangles.add(intArrayOf(id(a), id(b), id(c)))
            for ((i, j) in arrayOf(id(a) to id(b), id(b) to id(c), id(c) to id(a))) edges.add(minOf(i, j) to maxOf(i, j))
        }
        for (r in 0 until width - 1) for (c in 0 until width - 1) {
            val a = r * width + c; tri(a, a + 1, a + width); tri(a + 1, a + width + 1, a + width)
        }
        return BodyDefinition(x, y, FloatArray(n) { 0.5f }, BooleanArray(n), BooleanArray(n),
            edges.map { it.first }.toIntArray(), edges.map { it.second }.toIntArray(),
            triangles.map { it[0] }.toIntArray(), triangles.map { it[1] }.toIntArray(), triangles.map { it[2] }.toIntArray(),
            FloatArray(triangles.size) { 1f }, emptyArray(), emptyArray(), 1f)
    }

    @Test fun deadCellsInsideBodyDoNotCutBlocks() {
        // Мёртвая клетка — отдельный организм посреди тела: разбиение по организмам резало блок на
        // каждой такой (замер 09.11: 1401 блок вместо ~100, и весь мир в одном потоке).
        val state = BodyState().also { it.quiet = true; it.initialize(gridBody(60, 0f, null)); it.resetState() }
        val blocksBefore = state.constraintBlocks!!.blockCount
        for (cell in 61 until state.n step 97) {
            for (s in state.baked.vertStart[cell] until state.baked.vertStart[cell + 1]) state.killEdgeNow(state.baked.vertEdge[s])
        }
        state.refreshSurface(); state.rebuildAfterTear()
        assertTrue("клетки должны оторваться", state.organismCount > 10)
        state.constraintBlocks = null
        state.initializeConstraintBlocks()
        assertEquals(blocksBefore, state.constraintBlocks!!.blockCount)
        assertEquals(0, state.constraintBlocks!!.serialBlocks)
    }

    @Test fun oneBadlyNumberedBodyStaysSerialAndKeepsResultBitForBit() {
        val width = 64; val n = width * width
        val shuffled = (0 until n).toMutableList().also { it.shuffle(Random(4242)) }.toIntArray()
        val bodies = gridBody(width, 0f, null).concat(gridBody(width, 2f * width, shuffled))
        fun run(workers: Int): Pair<FloatArray, BodyState> {
            val state = BodyState().also {
                it.quiet = true; it.initialize(bodies); it.resetState()
                it.workers = if (workers > 1) PhysicsWorkers.shared(workers) else null
            }
            for (i in 0 until state.n) { state.vx[i] = 0.3f * (i % 7 - 3); state.vy[i] = 0.2f * (i % 5 - 2) }
            val simulation = BodySimulation(state)
            repeat(12) { simulation.step() }
            return (state.px + state.py + state.vx + state.vy) to state
        }
        val (one, serial) = run(1)
        val layout = serial.constraintBlocks!!
        assertTrue("перемешанное тело считается в одном потоке", layout.serialBlocks > 0)
        assertTrue("первое тело — по воркерам", layout.serialBlocks < layout.blockCount)
        assertFalse(layout.serialBlock[0])
        val (four, _) = run(4)
        assertArrayEquals(one, four, 0.0f)
    }

    @Test fun organismInterleavedWithAnotherGetsOneSeamSetOfItsOwn() {
        // Находка 42 (25.11.2026). Частицы тела (связного куска запечённого графа) вперемешку с чужими — геном из
        // нескольких несвязанных кусков. Набор швов выдавался на прогон подряд идущих блоков: у тела 1 двухблочный
        // прогон получал свой набор, одноблочные — общий первый, то есть набор тела 0. Наборы швов идут по воркерам
        // параллельно — одни частицы правили два потока, суммы мира гуляли от прогона к прогону.
        // Блоки при размере 3: [0 0 0][0 0][1 1 1][1 1 1][2 2][1 1 1][2 2][1 1 1].
        val bodyOf = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 1, 1, 1, 2, 2, 1, 1, 1)
        val blocks = ConstraintBlocks(bodyOf.size, bodyOf, 3, 64, 16, 0)
        assertEquals(8, blocks.blockCount)
        val setOf = IntArray(3)
        for (o in 0..2) {
            val sets = (0 until blocks.blockCount).filter { bodyOf[blocks.firstParticle[it]] == o }.map { blocks.seamBucket[it] }.toSet()
            assertEquals("тело $o — один набор швов", 1, sets.size)
            setOf[o] = sets.first()
            // Любая связь тела через границу его блоков — в этом наборе.
            val own = bodyOf.indices.filter { bodyOf[it] == o }
            for (a in own) for (b in own) if (blocks.particleBlock[a] != blocks.particleBlock[b]) assertEquals(setOf[o], blocks.blockOf(a, b))
        }
        assertEquals("у каждого многоблочного тела свой набор", 3, setOf.toSet().size)
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
