package io.github.some_example_name.physics

/*
 * СВОБОДНЫЕ ЧАСТИЦЫ (субстанции). Этап A3 плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * Свободная частица — клетка без связей, свой организм: сталкивается, тонет в среде,
 * засыпает, как одиночная клетка тела. Появляются они часто (в игре — на каждую смерть
 * клетки), поэтому дописывать тело на каждую нельзя (~45 мс на 100k). Мир держит пул —
 * тело из одиночных НЕРОЖДЁННЫХ клеток (PhysicsWorld.addFree), — и частица рождается в
 * готовом слоте тем же путём, что клетка растущего тела (activateCell): цена — по соседям,
 * то есть почти ничего, без перестройки блоков, плана и сетки. Удалённая частица снова
 * становится нерождённой, слот ждёт следующую.
 *
 * Радиус и масса — свои у каждой частицы: радиус пишется в состояние мира (поверхность и
 * контакты читают его оттуда, см. LocalSurface.cellRadius), масса — как у клетки того же
 * радиуса, если не задана.
 */

/**
 * Родить свободную частицу в слоте [i] пула: точка ([x], [y]), скорость ([vx], [vy]), радиус
 * [r], масса [mass] (NaN — как у клетки радиуса [r]: r² на среднюю площадь клетки мира).
 */
internal fun BodyState.activateFree(i: Int, x: Double, y: Double, vx0: Double, vy0: Double, r: Double, mass: Double,
                                    drag: Double = 1.0) {
    require(baked.vertStart[i] == baked.vertStart[i + 1]) { "слот $i — не одиночная клетка" }
    require(r > 0.0) { "радиус свободной частицы должен быть больше нуля" }
    radius[i] = r
    val m = if (!mass.isNaN()) mass else {
        val meanArea = worldScale!!.meanCellArea
        if (meanArea > 0.0) (r * r) / meanArea else 1.0
    }
    restInvMass[i] = if (m > 1e-12) 1.0 / m else 1.0
    dragScale[i] = drag
    activateCell(i, x, y, vx0, vy0)
}

/**
 * Убрать свободную частицу [i]: слот снова нерождённый — мёртвый, без массы и контакта.
 * Частицу могла убить и физика (глубокий контакт, давление): тогда из организма она уже
 * вычтена и посчитана в погибших (killIncidentEdges), а нерождённые там не считаются.
 * История частицы (касание пола, раздавленность, ловушка, давление) обнуляется, чтобы
 * следующая частица в этом слоте начинала с чистого листа. [refresh] = false — контур и
 * контакты обновит вызывающий одним refreshSurface на всю пачку.
 */
internal fun BodyState.deactivateFree(i: Int, refresh: Boolean = true) {
    require(!cellUnborn[i]) { "свободной частицы $i нет" }
    require(baked.vertStart[i] == baked.vertStart[i + 1]) { "слот $i — не одиночная клетка" }
    if (dragId == i) { matchWeight[i] = 1.0; dragId = -1 }
    if (cellDead[i]) deadCount-- else localComponents.removeCell(i)
    cellDead[i] = true; cellUnborn[i] = true
    invMass[i] = 0.0; matchWeight[i] = 0.0
    vx[i] = 0.0; vy[i] = 0.0; prevX[i] = px[i]; prevY[i] = py[i]
    inContact[i] = false; dragScale[i] = 1.0
    if (crushedCell.size > i) crushedCell[i] = false
    if (trapTicks.size > i) trapTicks[i] = 0
    if (trapOrg.size > i) trapOrg[i] = -1
    if (foldTicks.size > i) foldTicks[i] = 0
    if (contactPressure.size > i) contactPressure[i] = 0.0
    if (contactPressureN.size > i) contactPressureN[i] = 0
    baked.touch(i)
    if (refresh) refreshSurface()
    structureVersion++
}
