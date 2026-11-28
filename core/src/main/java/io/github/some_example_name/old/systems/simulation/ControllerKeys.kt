package io.github.some_example_name.old.systems.simulation

import com.badlogic.gdx.Input

/**
 * Клавиши контроллера: номер клавиши в SimulationData.controllerKeys и её имя.
 *
 * Номер — бит в маске, которую журнал .bug пишет событием ControllerKeys, и индекс кнопки
 * экранной клавиатуры (SimulationScreen). Имя — то, чем клавиша записана в геноме у
 * клетки Controller (ControllerData.key): оно постоянное, номер в геном не попадает.
 */
object ControllerKeys {

    val NAMES = arrayOf(
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "W", "A", "S", "D",
        "SPACE",
        "UP", "LEFT", "DOWN", "RIGHT"
    )

    val COUNT = NAMES.size

    /**
     * Клавиши контроллера на клавиатуре — по индексу в controllerKeyTouched:
     * 0–9 цифры, 10–13 WASD, 14 пробел, 15–18 стрелки. Порядок — тот же, что у имён
     * в ControllerKeys.NAMES: по имени клетка Controller находит номер клавиши.
     */
    val KEY_CODES = intArrayOf(
        Input.Keys.NUM_0, Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3,
        Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7,
        Input.Keys.NUM_8, Input.Keys.NUM_9,
        Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.D,
        Input.Keys.SPACE,
        Input.Keys.UP, Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.RIGHT
    ).also { check(it.size == COUNT) { "клавиш контроллера ${it.size}, а имён ${COUNT}" } }

    /** Номер клавиши по имени, -1 — такой клавиши нет. */
    fun indexOf(name: String): Int = NAMES.indexOf(name)
}
