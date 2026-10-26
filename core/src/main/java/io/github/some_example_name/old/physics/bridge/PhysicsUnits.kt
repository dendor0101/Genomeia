package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.WorldScale

/**
 * ЕДИНИЦЫ МИРА ФИЗИКИ. Материал библиотеки подобран на стенде, а не в единицах игры: часть
 * постоянных привязана к длине (NORMAL_DRAG — «коэффициент привязан к масштабу тела»,
 * MediumParameters), поэтому мост переводит длины, а не библиотека подстраивается.
 *
 * Масштаб — тот, в котором подбирались материал и все сцены стенда: эталонное тело из
 * редактора (detached-fragments, 947 клеток, средняя связь 0.593 единицы игры) стенд
 * вписывает с множителем 0.048, средняя связь — 0.0285; сцена 30k (showcase-30k) берёт
 * этот масштаб для всех тел. Клетки игры по умолчанию (радиус 0.5, связь 0.6) дают 0.0288.
 *
 * Отвергнуто: подбирать масштаб по телу, как одиночное тело стенда (вписать в 2.6 по
 * большей стороне). Тогда физика организма зависела бы от его размера: мелкое тело
 * растягивалось бы в крупное и вязло в среде иначе.
 */
const val PHYSICS_PER_GAME = 0.048
const val GAME_PER_PHYSICS = 1.0 / PHYSICS_PER_GAME

/**
 * Скорость игры — смещение за тик в долях прежнего MovementManager.SIM_STEP (x += vx · 7.5),
 * под которое подобраны жгутик и плавник; скорость библиотеки — единиц её мира в секунду
 * при шаге DT. Один тик игры = один шаг мира.
 */
private const val GAME_SIM_STEP = 7.5
const val PHYSICS_VELOCITY_PER_GAME = GAME_SIM_STEP * PHYSICS_PER_GAME / DT

/**
 * Постоянные мира игры (WorldScale): средняя связь и средний квадрат радиуса клетки не
 * выводятся из первых тел, а заданы по клетке по умолчанию (радиус 0.5, связь 0.6 =
 * CellPlacement.CENTER_MIN от суммы радиусов). Клетка радиуса 0.5 — масса 1.
 */
private const val GAME_LINK_LENGTH = 0.6
private const val GAME_CELL_RADIUS = 0.5
val GAME_WORLD_SCALE = WorldScale(
    meanLinkLength = (GAME_LINK_LENGTH * PHYSICS_PER_GAME).toFloat(),
    meanCellArea = (GAME_CELL_RADIUS * PHYSICS_PER_GAME) * (GAME_CELL_RADIUS * PHYSICS_PER_GAME)
)
