package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.input.GestureDetector.GestureListener
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Stage

class CameraControl(
    val camera: OrthographicCamera,
    val orientation: Float = 0f,
    positionX: Float = 64f,
    positionY: Float = 64f,
    val zoom: Float = 0.01f,
    val onTouchDown: (x: Float, y: Float, isLeft: Boolean) -> Unit,
    val onTap: (x: Float, y: Float, isLeft: Boolean) -> Unit,
    val onFling: () -> Unit,
    val onPan: (x: Float, y: Float, dx: Float, dy: Float) -> Unit,
    /**
     * Новый масштаб камеры и её положение после колеса или щипка. Жест только считает,
     * куда надо встать, а применяет [setZoom] обработчик команды — так масштаб попадает
     * в журнал, как и всё остальное. По умолчанию применяется сразу.
     */
    val onZoom: ((zoom: Float, x: Float, y: Float) -> Unit)? = null,
    /**
     * Средняя кнопка мыши — отметка клетки (SimulationIntent.MarkCell). Только отметка: ни
     * касания, ни захвата, ни посадки. null — средняя кнопка как правая, как раньше.
     */
    val onMiddleClick: ((x: Float, y: Float) -> Unit)? = null,
    private val inputCamera: OrthographicCamera = camera
): GestureListener {

    private var initialZoom = 0f
    private var currentPinchCenter: Vector2? = null

    init {
        camera.position.set(positionX, positionY, 0f)
        camera.zoom = this.zoom
        camera.rotate(orientation)
        camera.update()
    }

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val screenPos = Vector3(screenX, screenY, 0f)
        val worldPos = inputCamera.unproject(screenPos)
        return Pair(worldPos.x, worldPos.y)
    }

    fun getInput() = screenToWorld(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())

    fun getInputMultiplexer(stage: Stage): InputMultiplexer {
        val multiplexer = InputMultiplexer()
        multiplexer.addProcessor(stage)
        multiplexer.addProcessor(
            GestureDetector(
                10f,
                0.4f,
                1.1f,
                Float.MAX_VALUE,
                this
            ))
        multiplexer.addProcessor(object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val zoomFactor = if (amountY > 0) 1.05f else 0.95f
                zoomAround(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), camera.zoom * zoomFactor)
                return true
            }
        })
        return multiplexer
    }

    override fun touchDown(
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean {
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        val middle = onMiddleClick
        if (middle != null && button == Input.Buttons.MIDDLE) {
            middle.invoke(touchedCellX, touchedCellY)
            return false
        }
        onTouchDown.invoke(touchedCellX, touchedCellY, button == Input.Buttons.LEFT)
        return false
    }

    override fun tap(
        x: Float,
        y: Float,
        count: Int,
        button: Int
    ): Boolean {
        if (onMiddleClick != null && button == Input.Buttons.MIDDLE) return true
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        onTap.invoke(touchedCellX, touchedCellY, button == Input.Buttons.LEFT)
        return true
    }

    override fun longPress(x: Float, y: Float): Boolean  = false

    override fun fling(
        velocityX: Float,
        velocityY: Float,
        button: Int
    ): Boolean {
        onFling.invoke()
        return false
    }

    override fun pan(
        x: Float,
        y: Float,
        deltaX: Float,
        deltaY: Float
    ): Boolean {
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        val dx = -deltaX * camera.zoom
        val dy = deltaY * camera.zoom
        val angle = -orientation * MathUtils.degreesToRadians
        val cos = MathUtils.cos(angle)
        val sin = MathUtils.sin(angle)

        val worldDx = dx * cos - dy * sin
        val worldDy = dx * sin + dy * cos

        onPan.invoke(touchedCellX, touchedCellY, worldDx, worldDy)
        return true
    }

    override fun panStop(
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean = false

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        val center = currentPinchCenter ?: return false
        zoomAround(center.x, center.y, initialZoom * (initialDistance / distance))
        return true
    }

    /**
     * Масштаб вокруг точки экрана: точка под пальцем (курсором) остаётся на месте.
     *
     * Положение считается пробным применением масштаба к камере, после чего камера
     * возвращается как была: применяет результат обработчик команды ([onZoom]).
     */
    private fun zoomAround(screenX: Float, screenY: Float, targetZoom: Float) {
        val screenPos = Vector3(screenX, screenY, 0f)
        val worldBefore = camera.unproject(screenPos.cpy())

        val oldZoom = camera.zoom
        camera.zoom = MathUtils.clamp(targetZoom, 0.001f, 1000f)
        camera.update()
        val worldAfter = camera.unproject(screenPos.cpy())
        val newZoom = camera.zoom
        val newX = camera.position.x + worldBefore.x - worldAfter.x
        val newY = camera.position.y + worldBefore.y - worldAfter.y

        camera.zoom = oldZoom
        camera.update()

        val handler = onZoom
        if (handler != null) handler(newZoom, newX, newY) else setZoom(newZoom, newX, newY)
    }

    /** Применить масштаб и положение камеры — из обработчика команды. */
    fun setZoom(zoom: Float, x: Float, y: Float) {
        camera.zoom = zoom
        camera.position.x = x
        camera.position.y = y
        camera.update()
    }

    override fun pinch(
        initialPointer1: Vector2?,
        initialPointer2: Vector2?,
        pointer1: Vector2?,
        pointer2: Vector2?
    ): Boolean {
        if (initialPointer1 != null && initialPointer2 != null && currentPinchCenter == null) {
            initialZoom = camera.zoom
        }
        if (pointer1 == null || pointer2 == null) {
            currentPinchCenter = null
            return false
        }
        currentPinchCenter = pointer1.cpy().add(pointer2).scl(0.5f)
        return false
    }

    override fun pinchStop() {
        currentPinchCenter = null
        initialZoom = 0f
    }
}
