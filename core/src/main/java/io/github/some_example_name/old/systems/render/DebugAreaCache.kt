package io.github.some_example_name.old.systems.render

import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.render.pack.DebugAreaIndices
import it.unimi.dsi.fastutil.ints.IntArrayList

/** Поток симуляции. Положение, радиус и цвет не меняют индексную сетку. */
internal class DebugAreaCache {
    private var physicsOfVertex = IntArray(0)
    private var vertexOfPhysics = IntArray(0)
    private var mappingChanged = true
    private var previousCount = -1
    private var previousFaces: BooleanArray? = null
    private var previousRevision = -1L
    private val indices = IntArrayList()
    private val source = Any()
    private var version = 0L

    fun reserveCells(count: Int) {
        if (physicsOfVertex.size < count) physicsOfVertex = physicsOfVertex.copyOf(maxOf(count, physicsOfVertex.size * 2))
    }

    fun cell(vertex: Int, physics: Int) {
        if (physicsOfVertex[vertex] != physics) {
            physicsOfVertex[vertex] = physics
            mappingChanged = true
        }
    }

    fun publish(world: PhysicsWorld?, count: Int, target: DebugAreaIndices): DebugAreaIndices? {
        if (world == null || world.particleCount == 0 || count == 0) return null
        // Треугольники — у мира Kotlin его же массивы, у физики на Rust — копия из Rust, когда они сменились.
        val mesh = world.debugTriangles()
        val faces = mesh.triAlive
        val revision = mesh.triangleRevision
        if (mappingChanged || count != previousCount || faces !== previousFaces || revision != previousRevision) {
            rebuild(world.state, mesh, count)
            mappingChanged = false
            previousCount = count
            previousFaces = faces
            previousRevision = revision
            version++
        }
        if (indices.isEmpty()) return null
        target.update(source, version, indices.elements(), indices.size)
        return target
    }

    private fun rebuild(state: BodyState, mesh: PhysicsWorld.DebugTopology, count: Int) {
        if (vertexOfPhysics.size < state.n) vertexOfPhysics = IntArray(state.n)
        for (v in 0 until count) {
            val p = physicsOfVertex[v]
            if (p >= 0) vertexOfPhysics[p] = v + 1
        }
        indices.clear()
        // У террейна могут быть десятки тысяч граней. Идём только от клеток снимка.
        // Угол 0 встречается ровно раз у каждой грани, поэтому устранять дубликаты не нужно.
        for (a in 0 until count) {
            val p = physicsOfVertex[a]
            if (p < 0) continue
            for (k in mesh.vertexTriStart[p] until mesh.vertexTriStart[p + 1]) {
                val corner = mesh.vertexTriangles[k]
                if (corner % 3 != 0) continue
                val t = corner / 3
                if (!mesh.triAlive[t]) continue
                val b = vertexOfPhysics[mesh.triB[t]] - 1
                val c = vertexOfPhysics[mesh.triC[t]] - 1
                if (b < 0 || c < 0) continue
                indices.add(a)
                indices.add(b)
                indices.add(c)
            }
        }
        // Очистка только занятых мест: умершая клетка не оставляет вершину следующему снимку.
        for (v in 0 until count) {
            val p = physicsOfVertex[v]
            if (p >= 0) vertexOfPhysics[p] = 0
        }
    }
}
