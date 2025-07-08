package io.github.some_example_name.attempts.genome_editor

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import io.github.some_example_name.attempts.game.gameabstraction.entity.Gen
import io.github.some_example_name.attempts.game.gameabstraction.entity.GenomeLeaf
import io.github.some_example_name.attempts.game.gameabstraction.entity.writeGenome
import io.github.some_example_name.attempts.game.physics.genomeEditorColor
import io.github.some_example_name.attempts.game.physics.leafColors
import kotlin.math.pow
import io.github.some_example_name.attempts.utils.Pair


private lateinit var root: TreeNode // Инициализация отложена до метода create()

class GenomeEditor : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private val camera2 = Vector2(0f, 0f)
    private lateinit var camera: OrthographicCamera

    private var dragStart: TreeNode? = null
    private var dragEnd: TreeNode? = null
    private var currentMousePos = Vector2()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var draggingRight = false
    private var zoom = 1f

    override fun create() {
        shapeRenderer = ShapeRenderer()
        // Инициализация корневого узла после инициализации LibGDX
        root = TreeNode(Vector2(Gdx.graphics.width / 2f, Gdx.graphics.height - 50f), id = idCounter)
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        Gdx.input.inputProcessor = object : InputAdapter() {

            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.ESCAPE) {
                    val leafs = root.toGenomeLeaf()
                    writeGenome(leafs, "C:\\game\\gen123.bin")
                    Gdx.app.exit() // Закрыть приложение
                }
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                    if (!dragging) {
                        lastX = screenX.toFloat()
                        lastY = screenY.toFloat()
                        dragging = true
                    } else {
                        val deltaX = (lastX - screenX) * camera.zoom
                        val deltaY = (screenY - lastY) * camera.zoom
                        camera.translate(deltaX, deltaY)
                        lastX = screenX.toFloat()
                        lastY = screenY.toFloat()
                    }
                }
                return true
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                dragging = false
                return true
            }

            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val zoomFactor = 1f + amountY * 0.1f
                val newZoom = (camera.zoom * zoomFactor).coerceIn(0.005f, 10f)

                val mouseX = Gdx.input.x.toFloat()
                val mouseY = Gdx.input.y.toFloat()
                val worldCoordinatesBeforeZoom = camera.unproject(Vector3(mouseX, mouseY, 0f))

                camera.zoom = newZoom
                camera.update()

                val worldCoordinatesAfterZoom = camera.unproject(Vector3(mouseX, mouseY, 0f))
                val zoomAdjustment = worldCoordinatesBeforeZoom.sub(worldCoordinatesAfterZoom)
                camera.translate(zoomAdjustment.x, zoomAdjustment.y)

                return true
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                // Получаем экранные координаты касания
                val touchX = screenX.toFloat()
                val touchY = screenY.toFloat()

                // Создаем Vector3 для хранения координат
                val touchPos = Vector3(touchX, touchY, 0f)

                // Преобразуем экранные координаты в мировые координаты с учетом камеры
                camera.unproject(touchPos)

                if (button == Input.Buttons.LEFT) {
                    // Обрабатываем клик с учетом мировых координат (используем только x и y)
                    root.handleClick(Vector2(touchPos.x, touchPos.y))
                }
                return true
            }
        }

    }

    override fun render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        camera.update()
        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        root.render(shapeRenderer)
        if (draggingRight) {
            drawArc(shapeRenderer, dragStart!!.position, currentMousePos)
        }
        shapeRenderer.end()
        handleInput()
    }

    private fun handleInput() {
//        val touchPos = Vector2((Gdx.input.x * zoom) + camera2.x, (Gdx.graphics.height - Gdx.input.y) * zoom + camera2.y)

        val touchX = Gdx.input.x.toFloat()
        val touchY = Gdx.input.y.toFloat()

        // Создаем Vector3 для хранения координат
        val touchPos = Vector3(touchX, touchY, 0f)

        // Преобразуем экранные координаты в мировые координаты с учетом камеры
        camera.unproject(touchPos)
        val touchPosV2 = Vector2(touchPos.x, touchPos.y)

        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            if (!draggingRight) {
                dragStart = root.findNodeAt(touchPosV2)
                if (dragStart != null) draggingRight = true
            }
            currentMousePos.set(touchPosV2)
        } else if (draggingRight) {
            dragEnd = root.findNodeAt(touchPosV2)
            if (dragEnd != null && dragStart != dragEnd) {
                dragStart!!.addArc(dragEnd!!)
            }
            draggingRight = false
        }

//        if (Gdx.input.justTouched() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
//            root.handleClick(touchPos)
//        }
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


    override fun dispose() {
        shapeRenderer.dispose()
    }
}

private fun bezierPoint(p0: Vector2, p1: Vector2, p2: Vector2, t: Float): Vector2 {
    val invT = 1 - t
    return Vector2(
        invT * invT * p0.x + 2 * invT * t * p1.x + t * t * p2.x,
        invT * invT * p0.y + 2 * invT * t * p1.y + t * t * p2.y
    )
}

private var treeMaxLevel = 0
private var idCounter = 0

class TreeNode(
    position: Vector2,
    private val level: Int = 0,
    val id: Int
) {
    private val radius = 10f
    val position: Vector2 = Vector2(position)
    private var left: TreeNode? = null
    private var right: TreeNode? = null
    private val color = genomeEditorColor.random()
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

    fun handleClick(touchPos: Vector2): Boolean {
        if (position.dst(touchPos) < radius) {
            if (left == null && right == null) {
                left = TreeNode(
                    Vector2(
                        position.x - HORIZONTAL_OFFSET * 2.0.pow(treeMaxLevel - (level)).toFloat(),
                        position.y - 50f
                    ), level = level + 1, idCounter + 1
                )
                right = TreeNode(
                    Vector2(
                        position.x + HORIZONTAL_OFFSET * 2.0.pow(treeMaxLevel - (level)).toFloat(),
                        position.y - 50f
                    ), level = level + 1, idCounter + 2
                )
                idCounter += 2
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

    fun toGenomeLeaf(): GenomeLeaf {
        val heirPair = if (left != null || right != null) Pair(left?.toGenomeLeaf() as Gen, right?.toGenomeLeaf() as Gen) else null
        return GenomeLeaf(
            color = this.color,
            id = this.id,
            heirPair = heirPair,
            joins = arcs.mapNotNull { it.id }
        )
    }

    companion object {
        const val HORIZONTAL_OFFSET = 6f
    }
}
