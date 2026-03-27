package io.github.some_example_name.old.good_one

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import kotlin.math.floor
import kotlin.random.Random

// Simplified 2D Sparse Paged Grid inspired by SPGrid
// Uses hash map to simulate sparse blocks (pages), each block is a fixed-size array for cells
// For simplicity, no actual mmap, but lazy allocation of blocks
// Block size: 4x4 cells, cell size user-defined
// Uses Morton order for block indexing (bit interleaving for 2D)

class SparsePagedGrid(private val cellSize: Float) {
    private val blocks = mutableMapOf<Long, Block>() // Key: Morton-coded block index

    data class Particle(val id: Int, val position: Vector2, val radius: Float)

    data class Block(val cells: Array<MutableList<Particle>>) // 4x4 = 16 cells
    // No init block needed; initialize with Array(16) { mutableListOf() } when creating

    // Compute block coordinates from world pos
    private fun getBlockCoords(x: Float, y: Float): Pair<Int, Int> {
        val cellX = floor(x / cellSize).toInt()
        val cellY = floor(y / cellSize).toInt()
        val blockX = cellX shr 2 // Divide by 4 (block size)
        val blockY = cellY shr 2
        return Pair(blockX, blockY)
    }

    // Morton code for block (interleave bits of blockX and blockY)
    private fun mortonCode(blockX: Int, blockY: Int): Long {
        var x = blockX.toLong()
        var y = blockY.toLong()
        x = (x or (x shl 32)) and 0x00000000FFFFFFFFL // For 32-bit
        y = (y or (y shl 32)) and 0x00000000FFFFFFFFL
        x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
        y = (y or (y shl 16)) and 0x0000FFFF0000FFFFL
        x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
        y = (y or (y shl 8)) and 0x00FF00FF00FF00FFL
        x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
        y = (y or (y shl 4)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x shl 2)) and 0x3333333333333333L
        y = (y or (y shl 2)) and 0x3333333333333333L
        x = (x or (x shl 1)) and 0x5555555555555555L
        y = (y or (y shl 1)) and 0x5555555555555555L
        return x or (y shl 1)
    }

    // Get or create block
    private fun getBlock(blockX: Int, blockY: Int): Block {
        val key = mortonCode(blockX, blockY)
        return blocks.getOrPut(key) { Block(Array(16) { mutableListOf() }) }
    }

    // Get local cell index in block (0-15)
    private fun getLocalCellIndex(cellX: Int, cellY: Int): Int {
        val localX = cellX and 3 // mod 4
        val localY = cellY and 3
        return localY * 4 + localX // Row-major
    }

    // Insert particle into grid
    fun insert(particle: Particle) {
        val cellX = floor(particle.position.x / cellSize).toInt()
        val cellY = floor(particle.position.y / cellSize).toInt()
        val (blockX, blockY) = getBlockCoords(particle.position.x, particle.position.y)
        val block = getBlock(blockX, blockY)
        val localIdx = getLocalCellIndex(cellX, cellY)
        block.cells[localIdx].add(particle)
    }

    // Clear all particles (but keep blocks allocated for sparsity)
    fun clearParticles() {
        for (block in blocks.values) {
            for (cell in block.cells) {
                cell.clear()
            }
        }
    }

    // Find potential collisions: get particles in same and neighboring cells
    fun findPotentialCollisions(particle: Particle): List<Particle> {
        val candidates = mutableListOf<Particle>()
        val cellX = floor(particle.position.x / cellSize).toInt()
        val cellY = floor(particle.position.y / cellSize).toInt()

        // Check 3x3 cells around (including own)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nCellX = cellX + dx
                val nCellY = cellY + dy
                val (blockX, blockY) = Pair(nCellX shr 2, nCellY shr 2)
                val key = mortonCode(blockX, blockY)
                val block = blocks[key] ?: continue // Sparse: skip if no block
                val localIdx = getLocalCellIndex(nCellX, nCellY)
                candidates.addAll(block.cells[localIdx])
            }
        }
        return candidates.filter { it.id != particle.id } // Exclude self
    }

    // Actual collision check
    fun checkCollision(p1: Particle, p2: Particle): Boolean {
        return p1.position.dst(p2.position) < p1.radius + p2.radius
    }
}

// Example LibGDX app with particles and collision detection using the grid
class ParticleSimulation : ApplicationAdapter() {
    private lateinit var batch: SpriteBatch
    private val particles = mutableListOf<SparsePagedGrid.Particle>()
    private val grid = SparsePagedGrid(cellSize = 10f) // Cell size 10 units

    override fun create() {
        batch = SpriteBatch()
        // Create some particles
        for (i in 0 until 100) {
            particles.add(SparsePagedGrid.Particle(i, Vector2(Random.nextFloat() * 800f, Random.nextFloat() * 600f), 5f))
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Update grid: clear and re-insert (for moving particles; in real, update incrementally)
        grid.clearParticles()
        particles.forEach { grid.insert(it) }

        // Collision detection
        particles.forEach { p ->
            val candidates = grid.findPotentialCollisions(p)
            candidates.forEach { other ->
                if (grid.checkCollision(p, other)) {
                    // Handle collision, e.g., bounce or color change
                    println("Collision between ${p.id} and ${other.id}")
                }
            }
        }
        throw Exception()

        // Render (simple circles, but LibGDX has no built-in circle draw; use ShapeRenderer for real)
        batch.begin()
        // Rendering code here... (add a ShapeRenderer for circles in a real app)
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
    }
}
