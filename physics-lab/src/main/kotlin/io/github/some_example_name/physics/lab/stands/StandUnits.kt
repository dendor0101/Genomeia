package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.WorldScale
import kotlin.math.sqrt

/*
 * ЕДИНИЦЫ СТЕНДОВ — ЕДИНИЦЫ ИГРЫ.
 *
 * Сцены описываются так, как их видит игрок: клетка радиуса 0.5, связь 0.6, рельеф с шагом
 * 2/3. В мир физики всё уходит с тем же множителем, что у моста игры (PhysicsUnits.kt в
 * :core, PHYSICS_PER_GAME = 0.048), и с теми же постоянными мира (GAME_WORLD_SCALE).
 * :physics-lab не видит :core, поэтому числа повторены здесь: это четыре константы, и
 * расхождение с мостом поймает сверка стенда с игрой, а не внимательность.
 *
 * Скорости задаются в связях за тик: в этих же единицах стоит потолок скорости решателя
 * (MAX_SPEED_CELLS_PER_TICK), и «лёгкий толчок» или «таран» читаются без пересчёта.
 */

/** Единиц мира физики на единицу игры — как PhysicsUnits.PHYSICS_PER_GAME моста игры. */
const val PHYSICS_PER_GAME = 0.048

/** Радиус клетки игры по умолчанию. */
const val GAME_RADIUS = 0.5

/** Длина связи между соседними клетками в редакторе генома: 0.6 суммы радиусов. */
const val GAME_LINK = 0.6

/** Шаг рядов шестиугольной решётки редактора. */
val GAME_ROW: Double = GAME_LINK * sqrt(3.0) / 2.0

/**
 * РЕЛЬЕФ СТЕНДОВ — ПЛОТНАЯ МЕМБРАНА (16.11, по замечанию автора игры).
 *
 * Клетки карты — как в игре: шаг [TERRAIN_STEP] (1 / MAP_CELLS_PER_WORLD_CELL), радиус частицы
 * [TERRAIN_RADIUS]. Но частиц в клетке кромки не одна, а [TERRAIN_SUB] × [TERRAIN_SUB], с шагом
 * в треть единицы и малым случайным сдвигом. Неподвижная частица в мире физики — свободная
 * частица, и радиус касания у неё 0.6 радиуса, то есть 0.3: при шаге 0.33 круги касания соседей
 * перекрыты с запасом и покрывают клетку кромки целиком, без единой щели.
 *
 * ПОЧЕМУ НЕ «КАК В ИГРЕ». До 16.11 стенды повторяли раскладку игры буквально: одна частица на
 * клетку (шаг 0.67) и случайный сдвиг ±0.375. При радиусе касания 0.3 между соседями кромки
 * оставались щели до 0.8 — шире диаметра касания клетки тела (0.63), и «рельеф не держит клетки»
 * (823 красные сцены прогона 15.11) был свойством такой расстановки, а не решателя. Комментарий
 * в StaticTerrain игры считает щель по радиусу частицы 0.5, а не по радиусу касания 0.3 — это
 * вопрос к игре, он записан в docs/physics/stands.md.
 */
const val TERRAIN_STEP = 2.0 / 3.0
const val TERRAIN_RADIUS = 0.5

/** Сколько частиц рельефа на сторону клетки карты у кромки. */
const val TERRAIN_SUB = 2

/** Случайный сдвиг частицы рельефа от своего места, полная ширина: ±0.04 — неровность, которая не открывает щелей. */
const val TERRAIN_JITTER = 0.08

/** От центра клетки кромки до центров её крайних частиц: четверть шага карты. */
const val TERRAIN_FACE = TERRAIN_STEP / (2 * TERRAIN_SUB)

/**
 * На сколько круги касания частиц кромки выступают за границу своей клетки карты наружу:
 * радиус касания 0.3 минус четверть клетки. На столько полость в рельефе теснее рамки своих
 * свободных клеток.
 */
const val TERRAIN_PROTRUDE = TERRAIN_RADIUS * 0.6 - TERRAIN_STEP / 2 + TERRAIN_FACE

/** Группа касаний рельефа и стенки у рамки (StaticTerrain.TERRAIN_GROUP): между собой не сталкиваются. */
const val TERRAIN_GROUP = 1

/** Постоянные мира игры (PhysicsUnits.GAME_WORLD_SCALE): клетка радиуса 0.5 — масса 1. */
val STAND_WORLD_SCALE = WorldScale(
    meanLinkLength = (GAME_LINK * PHYSICS_PER_GAME).toFloat(),
    meanCellArea = (GAME_RADIUS * PHYSICS_PER_GAME) * (GAME_RADIUS * PHYSICS_PER_GAME),
)

/** Скорость игры (доля прежнего шага 7.5 за тик) → единицы мира физики в секунду, как у моста. */
const val GAME_SIM_STEP = 7.5
val PHYSICS_VELOCITY_PER_GAME: Double = GAME_SIM_STEP * PHYSICS_PER_GAME / DT

/** Длина связи в единицах мира физики — та же, что WorldScale.meanLinkLength в double. */
val PHYSICS_LINK: Double = STAND_WORLD_SCALE.meanLinkLength.toDouble()

/** Скорость «связей за тик» в единицах мира физики в секунду. */
fun linksPerTick(v: Double): Double = v * PHYSICS_LINK / DT

/** Скорость мира физики в связях за тик. */
fun toLinksPerTick(v: Double): Double = v * DT / PHYSICS_LINK

/** Потолок скорости решателя в связях за тик. */
const val SPEED_CAP: Double = MAX_SPEED_CELLS_PER_TICK
