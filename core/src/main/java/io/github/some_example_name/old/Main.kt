/*
package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.physics.box2d.*
import io.github.some_example_name.old.game_entity.CellManager

class Main : ApplicationAdapter(), InputProcessor, ContactListener {
    private lateinit var camera: OrthographicCamera
    private lateinit var debugRenderer: Box2DDebugRenderer
    private lateinit var world: World
    private lateinit var shapeRenderer: ShapeRenderer

    private lateinit var gameController: GameController

    override fun create() {
        // Настройка камеры
        camera = OrthographicCamera(20f, 15f)
        camera.position[10f, 7.5f] = 0f
        camera.update()
        shapeRenderer = ShapeRenderer()
        world = World(Vector2(0f, 0f), true)

        //Sorta DI
        val physicsCircleCellController = PhysicsCircleCellController(world)
        gameController = GameController(
            world = world,
            cellManager = CellManager(physicsCircleCellController),
            physicsCircleCellController = physicsCircleCellController,
            cellDiffutils = CellDiffutils(),
            rendererHelper = RendererHelper(shapeRenderer)
        )

        gameController.start()
        // Для отладки физики
        debugRenderer = Box2DDebugRenderer()

        // Обработка ввода
        Gdx.input.inputProcessor = this

        world.setContactListener(this)
    }

    override fun render() {
        // Очистка экрана
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Обновление мира
        world.step(1 / 60f, 6, 2)


        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        val deltaTime = Gdx.graphics.deltaTime
        gameController.tick(deltaTime)

        shapeRenderer.end()
        // Отрисовка объектов
        debugRenderer.render(world, camera.combined)
    }

    override fun dispose() {
        world.dispose()
        debugRenderer.dispose()
        shapeRenderer.dispose()
    }

    // Обработка клика мыши
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        // Преобразуем координаты экрана в координаты мира
        val worldCoords = camera.unproject(Vector3(screenX.toFloat(), screenY.toFloat(), 0f))

        gameController.mouseClick(worldCoords.x, worldCoords.y)
        return true
    }

    // Обработка нажатия клавиш
    override fun keyDown(keycode: Int): Boolean {
        // Если нажат пробел (код клавиши пробела = 62)
        if (keycode == 62) {
            gameController.clearAll()
            return true
        }
        return false
    }

    // Обработка столкновений
    override fun beginContact(contact: Contact) {
//        val fixtureA = contact.fixtureA
//        val fixtureB = contact.fixtureB
//
//        if (isBallAndCenterObject(fixtureA, fixtureB)) {
//            val ballBody = if (fixtureA.body === centerObject) fixtureB.body else fixtureA.body
//            destroyBall(ballBody)
//        }
        gameController.collided(contact)
    }

// Проверка, что столкнулись шарик и центральный объект
//    private fun isBallAndCenterObject(fixtureA: Fixture, fixtureB: Fixture): Boolean {
//        // Проверяем, что один из объектов — центральный, а другой — шарик
//        val isA_Center = fixtureA.body === centerObject
//        val isB_Center = fixtureB.body === centerObject
//
//        // Проверяем, что один из объектов — шарик
//        val isA_Ball = "ball" == fixtureA.body.userData
//        val isB_Ball = "ball" == fixtureB.body.userData
//
//        // Возвращаем true, если один объект — центральный, а другой — шарик
//        return (isA_Center && isB_Ball) || (isB_Center && isA_Ball)
//    }

    override fun endContact(contact: Contact) {
    }

    override fun preSolve(contact: Contact, manifold: Manifold) {
    }

    override fun postSolve(contact: Contact, contactImpulse: ContactImpulse) {
    }

    override fun keyUp(keycode: Int) = false

    override fun keyTyped(character: Char) = false

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int) = false

    override fun touchCancelled(i: Int, i1: Int, i2: Int, i3: Int) = false

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int) = false

    override fun mouseMoved(screenX: Int, screenY: Int) = false

    override fun scrolled(amountX: Float, amountY: Float) = false
}
*/
