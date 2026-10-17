package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BoundaryContacts
import io.github.some_example_name.physics.lab.inspection.DemoInspector
import io.github.some_example_name.physics.lab.io.BodyFile
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.BakedTopology
import io.github.some_example_name.physics.LocalBoundaryConstraints
import io.github.some_example_name.physics.LocalComponents
import io.github.some_example_name.physics.LocalSurface
import io.github.some_example_name.physics.VertexPairs
import io.github.some_example_name.physics.killEdgeNow
import io.github.some_example_name.physics.refreshSurface
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TopologyRegressionTest {
    @Test fun sharedEdgeBecomesBoundaryBeforePacking() {
        val file = Files.createTempFile("shared-edge-", ".txt").toFile()
        try {
            file.writeText("""
                P 0 0 0 0.5 0 0 0
                P 1 1 0 0.5 0 0 0
                P 2 1 1 0.5 0 0 0
                P 3 0 1 0.5 0 0 0
                L 0 1
                L 1 2
                L 2 0
                L 2 3
                L 3 0
            """.trimIndent())
            val demo = DemoSession(file.path, copies = 1, freeParticles = 0)
            demo.replayBoot()
            fun field(name: String): Any = DemoInspector.read(demo, name)!!
            fun cut(edge: Int) {
                demo.state.killEdgeNow(edge)
                demo.state.refreshSurface()
            }
            val topology = field("baked") as BakedTopology
            val a = field("conA") as IntArray; val b = field("conB") as IntArray
            val boundary = field("conBoundary") as BooleanArray
            val dead = field("conDead") as BooleanArray
            val shared = (0 until field("conCount") as Int).filter {
                (a[it] == 0 && b[it] == 2) || (a[it] == 2 && b[it] == 0)
            }
            assertEquals(1, shared.size)
            assertTrue(shared.all { !boundary[it] })
            cut(topology.edgeOf(0, 1))
            assertTrue(shared.all { boundary[it] })
            cut(topology.edgeOf(0, 2))
            assertTrue(shared.all { dead[it] })
        } finally { file.delete() }
    }

    private fun body(): BodyDefinition {
        val file = Files.createTempFile("local-topology-", ".txt").toFile()
        try {
            file.writeText("""
                P 0 0 0 0.5 0 0 0
                P 1 1 0 0.5 0 0 0
                P 2 0.5 0.8660254 0.5 0 0 0
                P 3 -0.5 0.8660254 0.5 0 0 0
                P 4 -1 0 0.5 0 0 0
                P 5 -0.5 -0.8660254 0.5 0 0 0
                P 6 0.5 -0.8660254 0.5 0 0 0
                L 0 1
                L 0 2
                L 0 3
                L 0 4
                L 0 5
                L 0 6
                L 1 2
                L 2 3
                L 3 4
                L 4 5
                L 5 6
                L 6 1
            """.trimIndent())
            return BodyFile.load(file.path)
        } finally { file.delete() }
    }

    @Test fun breakingEdgeImmediatelyExposesItsTrianglesAndOnlyTheirVertices() {
        val body = body(); val topology = BakedTopology(body)
        assertEquals(6, body.triCount)
        val surface = LocalSurface(body, topology, IntArray(7) { -1 }, BooleanArray(7),
            1.0, 1.05, 1e-5, BooleanArray(7), BooleanArray(7))
        val constraints = LocalBoundaryConstraints(body, topology, IntArray(7) { -1 })
        for (v in 0..6) { surface.update(v); constraints.updateVertex(v) }
        assertFalse(surface.active[0])
        assertEquals(6, constraints.count)
        assertTrue(topology.kill(topology.edgeOf(0, 1)))
        assertEquals(4, topology.dirtyCount)
        assertEquals(setOf(0, 1, 2, 6), topology.dirtyVertices.take(topology.dirtyCount).toSet())
        for (k in 0 until topology.dirtyCount) {
            val v = topology.dirtyVertices[k]; surface.update(v); constraints.updateVertex(v)
        }
        assertTrue(surface.active[0])
        assertTrue(surface.radius[0] > 0.0)
        assertEquals(8, constraints.count)
        assertEquals(4, topology.triAlive.count { it })
        assertEquals(2, topology.bareEdges)
        topology.kill(topology.edgeOf(1, 2))
        assertEquals(1, topology.bareEdges)
        topology.restore()
        assertEquals(0, topology.dirtyCount)
        assertEquals(0, topology.bareEdges)
        assertEquals(6, topology.triAlive.count { it })
    }

    @Test fun localComponentsMatchIndependentFloodFillAfterEveryCut() {
        val body = body(); val topology = BakedTopology(body)
        val of = IntArray(7); val sizes = IntArray(7); sizes[0] = 7
        val masses = DoubleArray(7); masses[0] = 7.0
        val flow = DoubleArray(7); flow[0] = 0.3
        val parts = LocalComponents(topology, of, sizes, masses, DoubleArray(7) { 1.0 },
            BooleanArray(7), flow, DoubleArray(7), 1)
        for (edge in 0 until topology.edgeCount) {
            topology.kill(edge); parts.split(edge)
            val expected = IntArray(7) { -1 }; var count = 0
            val queue = IntArray(7)
            for (seed in 0..6) if (expected[seed] < 0) {
                var head = 0; var tail = 1; queue[0] = seed; expected[seed] = count
                while (head < tail) {
                    val v = queue[head++]
                    for (s in topology.vertStart[v] until topology.vertStart[v + 1]) {
                        if (topology.edgeDead[topology.vertEdge[s]]) continue
                        val u = topology.vertOther[s]
                        if (expected[u] < 0) { expected[u] = count; queue[tail++] = u }
                    }
                }
                count++
            }
            assertEquals(count, parts.count)
            for (i in 0..6) for (j in 0..6) assertEquals(expected[i] == expected[j], of[i] == of[j])
            for (o in 0 until parts.count) {
                assertEquals(of.count { it == o }, sizes[o])
                assertEquals(sizes[o].toDouble(), masses[o], 0.0)
                assertEquals(0.3, flow[o], 0.0)
            }
        }
    }

    @Test fun replacingVertexPairsDoesNotLoseAnotherVerticesConstraints() {
        val body = body(); val pairs = VertexPairs(7)
        pairs.add(0, 1, 2, body); pairs.add(1, 2, 3, body)
        pairs.add(0, 3, 4, body); pairs.add(2, 4, 5, body)
        pairs.add(0, 5, 6, body)
        pairs.clear(0)
        assertEquals(setOf(2 to 3, 4 to 5), (0 until pairs.count).map { pairs.a[it] to pairs.b[it] }.toSet())
        pairs.clear(1); pairs.clear(2)
        assertEquals(0, pairs.count)
    }

    @Test fun resetAfterLocalTearRestoresContactExclusions() {
        val body = body(); val topology = BakedTopology(body)
        val bones = IntArray(7) { -1 }; val dead = BooleanArray(7)
        val surface = LocalSurface(body, topology, bones, dead, 1.0, 1.05, 1e-5,
            BooleanArray(7), BooleanArray(7))
        val boundary = LocalBoundaryConstraints(body, topology, bones)
        for (v in 0..6) { surface.update(v); boundary.updateVertex(v) }
        val a = boundary.a.copyOf(); val b = boundary.b.copyOf(); val opp = boundary.opposite.copyOf()
        val count = boundary.count
        val radius = DoubleArray(7) { body.radius[it].toDouble() }
        val ever = BoundaryContacts.linkedPairs(body.linkA, body.linkB, body.linkCount)
        val rest = BoundaryContacts.bakeRestPairs(7, BoundaryContacts.maxContactRadius(7,
            body.linkA, body.linkB, body.linkCount, radius, 1.0, body.x, body.y), body.x, body.y, ever)
        val contacts = BoundaryContacts.build(7, body.linkA, body.linkB, body.linkCount,
            a, b, count, opp, radius, 1.0, 0.4, 0.1, 0.0, body.x, body.y,
            body.meanLinkLength.toDouble(), 0.2, surface.probe, ever, dead, rest)
        contacts.attachSurface(surface)
        val original = contacts.contactDistanceOf(1, 2)
        topology.kill(topology.edgeOf(1, 2))
        for (k in 0 until topology.dirtyCount) surface.update(topology.dirtyVertices[k])
        contacts.updateSurface(surface, topology.dirtyVertices, topology.dirtyCount)
        assertFalse(contacts.isBonded(1, 2))
        assertTrue(contacts.contactDistanceOf(1, 2) < original)
        topology.restore()
        for (v in 0..6) surface.update(v)
        contacts.reconfigure(body.linkA, body.linkB, body.linkCount, a, b, count, opp,
            body.x, body.y, surface.probe, dead, rest, ever)
        assertTrue(contacts.isBonded(1, 2))
        assertEquals(original, contacts.contactDistanceOf(1, 2), 0.0)
    }
}
