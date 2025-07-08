package io.github.some_example_name.attempts.genome_editor

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import io.github.some_example_name.attempts.game.physics.leafColors
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.sin
/*
private lateinit var root: TreeNode

class BinaryTreeApp : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private val camera = Vector2(0f, 0f)
    private var zoom = 1f

    private var isDragging = false
    private var dragStart: TreeNode? = null
    private var dragEnd: TreeNode? = null
    private var currentMousePos = Vector2()

    override fun create() {
        shapeRenderer = ShapeRenderer()
        root = TreeNode(Vector2(Gdx.graphics.width / 2f, Gdx.graphics.height - 50f))
    }

    override fun render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix.setToOrtho2D(camera.x, camera.y, Gdx.graphics.width * zoom, Gdx.graphics.height * zoom)

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        root.render(shapeRenderer)

        if (isDragging) {
            drawArc(shapeRenderer, dragStart!!.position, currentMousePos)
        }

        shapeRenderer.end()

        handleInput()
    }

    private fun handleInput() {
        val touchPos = Vector2((Gdx.input.x * zoom) + camera.x, (Gdx.graphics.height - Gdx.input.y) * zoom + camera.y)

        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            if (!isDragging) {
                dragStart = root.findNodeAt(touchPos)
                if (dragStart != null) isDragging = true
            }
            currentMousePos.set(touchPos)
        } else if (isDragging) {
            dragEnd = root.findNodeAt(touchPos)
            if (dragEnd != null && dragStart != dragEnd) {
                dragStart!!.addArc(dragEnd!!)
            }
            isDragging = false
        }

        if (Gdx.input.justTouched() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            root.handleClick(touchPos)
        }
    }

    private fun drawArc(shapeRenderer: ShapeRenderer, start: Vector2, end: Vector2) {
        shapeRenderer.color = Color.YELLOW
        val mid = start.cpy().lerp(end, 0.5f).add(0f, 50f)
        val segments = 30

        var prev = start.cpy()
        for (i in 1..segments) {
            val t = i / segments.toFloat()
            val next = bezierPoint(start, mid, end, t)
            shapeRenderer.rectLine(prev, next, 2f)
            prev = next
        }
    }

    private fun bezierPoint(p0: Vector2, p1: Vector2, p2: Vector2, t: Float): Vector2 {
        val invT = 1 - t
        return Vector2(
            invT * invT * p0.x + 2 * invT * t * p1.x + t * t * p2.x,
            invT * invT * p0.y + 2 * invT * t * p1.y + t * t * p2.y
        )
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }
}

private var treeMaxLevel = 0

private class TreeNode(
    position: Vector2,
    private val level: Int = 0
) {
    private val radius = 10f
    val position: Vector2 = Vector2(position)
    private var left: TreeNode? = null
    private var right: TreeNode? = null
    private val color = leafColors.random()
    private val arcs = mutableListOf<TreeNode>()

    fun render(shapeRenderer: ShapeRenderer) {
        shapeRenderer.color = Color.WHITE
        left?.let {
            shapeRenderer.line(position, it.position)
            it.render(shapeRenderer)
        }

        shapeRenderer.color = Color.WHITE
        right?.let {
            shapeRenderer.line(position, it.position)
            it.render(shapeRenderer)
        }

        shapeRenderer.color = color
        shapeRenderer.circle(position.x, position.y, radius)

        shapeRenderer.color = Color.YELLOW
        arcs.forEach { drawArc(shapeRenderer, position, it.position) }
    }

    private fun drawArc(shapeRenderer: ShapeRenderer, start: Vector2, end: Vector2) {
        val mid = start.cpy().lerp(end, 0.5f).add(0f, 50f)
        val segments = 30

        var prev = start.cpy()
        for (i in 1..segments) {
            val t = i / segments.toFloat()
            val next = bezierPoint(start, mid, end, t)
            shapeRenderer.rectLine(prev, next, 2f)
            prev = next
        }
    }

    private fun bezierPoint(p0: Vector2, p1: Vector2, p2: Vector2, t: Float): Vector2 {
        val invT = 1 - t
        return Vector2(
            invT * invT * p0.x + 2 * invT * t * p1.x + t * t * p2.x,
            invT * invT * p0.y + 2 * invT * t * p1.y + t * t * p2.y
        )
    }

    fun handleClick(touchPos: Vector2): Boolean {
        if (position.dst(touchPos) < radius) {
            if (left == null && right == null) {
                left = TreeNode(Vector2(position.x - HORIZONTAL_OFFSET * 2.0.pow(treeMaxLevel - (level)).toFloat(), position.y - 100f), level = level + 1)
                right = TreeNode(Vector2(position.x + HORIZONTAL_OFFSET * 2.0.pow(treeMaxLevel - (level)).toFloat(), position.y - 100f), level = level + 1)
                if (treeMaxLevel < level + 1) {
                    treeMaxLevel = level + 1
                    relocateNodePosition(root, treeMaxLevel)
                }
            } else {
                left = null
                right = null
            }
            return true
        }

        return left?.handleClick(touchPos) == true || right?.handleClick(touchPos) == true
    }

    fun findNodeAt(touchPos: Vector2): TreeNode? {
        if (position.dst(touchPos) < radius) return this
        return left?.findNodeAt(touchPos) ?: right?.findNodeAt(touchPos)
    }

    fun addArc(target: TreeNode) {
        if (!arcs.contains(target)) arcs.add(target)
    }

    fun relocateNodePosition(root: TreeNode, level: Int) {
        if (root.left != null && root.right != null) {
            root.left!!.position.x = root.position.x - HORIZONTAL_OFFSET * 2.0.pow(level).toFloat()
            root.right!!.position.x = root.position.x + HORIZONTAL_OFFSET * 2.0.pow(level).toFloat()
            relocateNodePosition(root.left!!, level - 1)
            relocateNodePosition(root.right!!, level - 1)
        }
    }

    companion object {
        const val HORIZONTAL_OFFSET = 10f
    }
}
*/
