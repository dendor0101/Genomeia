package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

internal fun BodyState.solveTick(observer: StepObserver?) {
    // Пересборка после разрывов стоит В САМОМ КОНВЕЙЕРЕ, а не в обёртке над ним.
    // Сначала она была в stepOnce — и не срабатывала в проверках вовсе, потому
    // что стенд зовёт simulate() напрямую: связи помечались порванными сотнями,
    // а убито было ноль. Та же ошибка уже подводила с пересборкой стадий.
    //
    // Сейчас поверхность обновляется локально сразу после повреждения, иначе
    // контакт пропустит вновь открывшийся контур. Полное уплотнение рабочих
    // списков остаётся в конце тика: рвётся обычно пучок связей за один удар.
    java.util.Arrays.fill(contactPressure, 0.0)
    java.util.Arrays.fill(contactPressureN, 0)
    java.util.Arrays.fill(crushedCell, false)
    if (tearsPending) refreshSurface()
    val h = DT / SUBSTEPS
    // Совместный опыт минимальной частоты: контакты 32, момент и среда 1 раз за тик.
    observer?.beginTick()
    if (cancelSpinOn) tickMomentum.begin(px, py, vx, vy, invMass, organismOf)
    for (step in 0 until SUBSTEPS) {
        // Длина покоя мышцы доезжает до своего значения за тик, а не прыгает в первом
        // же подшаге — см. muscleScaleOf.
        updateMuscleScale((step + 1).toDouble() / SUBSTEPS)
        // КОНТАКТ ПРИКЛАДЫВАЕТСЯ К СКОРОСТИ ДО ИНТЕГРИРОВАНИЯ, как гравитация.
        // Почему именно так и что было раньше — см. toVelocity в solveContacts.
        if (contacts != null && contactsOn && CONTACT_BEFORE_INTEGRATE) {
            contacts!!.prepare(px, py, prevX, prevY, vx, vy, invMass)
            contacts!!.updateBones(px, py, invMass,
                if (bonesRigid) boneOf else null, rigidBones)
            contacts!!.solveContacts(px, py, vx, vy, invMass, h, 1.0, toVelocity = true)
        }
        integrate(h)

        // Скобка вокруг ВНУТРЕННИХ стадий: центр масс до и после. Внешние силы
        // (среда, пол, вязкость) идут ниже по скоростям и в скобку не попадают.
        var beforeX = 0.0
        var beforeY = 0.0
        // Только когда тело не держат: при активной тяге посылка «центр масс не
        // должен двигаться» неверна, и поправка даёт растяжение (см. константу).
        val cancel = CANCEL_INTERNAL_DRIFT && dragId < 0
        if (cancel) {
            for (i in 0 until n) { beforeX += px[i]; beforeY += py[i] }
            dragShiftX = 0.0; dragShiftY = 0.0
        }

        // Обход связей меняет направление КАЖДЫЙ подшаг — см. sweepBackwards.
        // Ставится здесь, а не внутри solveConstraints, чтобы фаза шла ровно по
        // подшагам и не зависела от того, сколько раз стадию позвали снаружи.

        // См. SWEEP_FLIP: раз в подшаг или каждый проход.
        if (sweepFlip == 1) sweepBackwards = !sweepBackwards
        // Внутренние ограничения, затем единственный контактный проход подшага.
        // Импульс учитывается TickMomentum на протяжении тика; коррекция в конце.
        for (iter in 0 until SOLVER_ITERS) {
            if (sweepFlip == 2) sweepBackwards = !sweepBackwards
            solveBlockedIteration(h)

        }
        // ПРЕДЕЛ ДЛИНЫ ПОВТОРНО, УЖЕ ПОСЛЕ КОСТИ.
        //
        // Первый вызов стоит после solveConstraints, но проекция кости идёт ПОЗЖЕ
        // и способна снова растянуть связь сверх предела — она ставит вершины на
        // жёсткую позу, ни на что не оглядываясь. А от этого предела зависит
        // непроницаемость границы: два соседних граничных круга перекрываются,
        // только пока расстояние между ними не больше суммы радиусов. Растянутая
        // костью связь оставляет в мембране щель, и сквозь неё можно воткнуть
        // чужое тело — что и наблюдалось при резком рывке за кость.
        tick0(); dbgMark(); solveLinkMaxLength(); dbgMeasure(4, h); tick1(2)

        // Снять импульс и момент, внесённые ВНУТРЕННИМИ стадиями. Контакты стоят в том
        // же цикле, но их смещения копятся отдельно и не снимаются: контакт внешний
        // для организма и передаёт импульс между телами законно. См. InternalMomentum.
        tick0(); dbgMark();  dbgMeasure(12, h); tick1(8)

        dbgMark(); if (cancel) cancelInternalDrift(beforeX, beforeY); dbgMeasure(12, h)

        // Закрываем перемещения внутренних ограничений контактом до восстановления скоростей.
        refreshSurface()
        val finalContacts = contacts
        if (finalContacts != null && contactsOn) {
            tick0()
            finalContacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = false)
            tick1(6); tick0()
            if (cancelSpinOn) tickMomentum.noteContacts(finalContacts)
            finalContacts.updateBones(px, py, invMass, if (bonesRigid) boneOf else null, rigidBones)
            tick1(13); tick0()
            finalContacts.solveContacts(px, py, vx, vy, invMass, h)
            tick1(7)
            contactsStale = false
            if (finalContacts.killN > 0) { applyKills(finalContacts); contactsStale = true }
            if (finalContacts.pierceN > 0) applyPierce(finalContacts)
            refreshSurface()
            tick0()
            solveBoundaryOrientation(boundCount, boundA, boundB, boundOpp,
                localBoundary.orientation, px, py, invMass)
            tick1(14)
        }

        tick0()
        updateVelocities(h)
        // Отскок: позиционная часть удар гасит целиком, см. solveRestitution.
        dbgMarkV()
        if (cancelSpinOn) tickMomentum.beforeExternal(vx, vy)
        val cr = contacts
        if (cr != null && contactsOn && !contactsStale) cr.solveRestitution(px, py, vx, vy, invMass)
        dbgMeasureV(7); dbgMarkV()
        applyDragVelocity(h)
        if (step == SUBSTEPS - 1) applyViscosity(h * SUBSTEPS)
        dbgMeasureV(8); dbgMarkV()
        if (step == SUBSTEPS - 1) applyNormalDrag(h * SUBSTEPS)
        dbgMeasureV(10); dbgMarkV()
        applyRestitution()
        if (step == SUBSTEPS - 1) applyMediumDrag(h * SUBSTEPS)
        clampSpeed()
        dbgMeasureV(11); tick1(9)
        if (cancelSpinOn) {
            tickMomentum.afterExternal(px, py, vx, vy, invMass, inContact)
            if (step == SUBSTEPS - 1) {
                tickMomentum.cancel(px, py, vx, vy, invMass)
                // Коррекция тоже меняет скорости: потолок должен оставаться последним.
                clampSpeed()
            }
        }
        observer?.afterSubstep(step)
        dbgSubstepHook?.invoke(step)
    }
    // Пластика — РАЗ В ТИК, после всех подшагов: течение медленное, внутри тика
    // ему делать нечего, а цена умножилась бы на число подшагов.
    refreshSurface()
    applyPlasticity(DT)
    burstByPressure()
    removePersistentInversions()
    // Один проход упаковки за тик. Контактная топология уже актуальна;
    // упаковка нужна плотным рабочим массивам и чтению состояния стенда.
    if (tearsPending) { tick0(); rebuildTimed(); tick1(11) }
    if (watchCell >= 0) watchReport()

}

/** Единственная последовательность стадий, общая для окна и проверок.
 * Сначала load/reset, затем step. Ввод (цели мышц, захват) выставляет адаптер до step.
 * Отдельные стадии доступны стендам для проверки импульса; обычный запуск их не вызывает.
 */
class BodySimulation(val state: BodyState) {
    var observer: StepObserver? = null
    fun load(definition: BodyDefinition) {
        // Контактные буферы имеют размер загруженного тела. Для другого тела нужен
        // новый экземпляр; reset восстанавливает это же тело после разрушения.
        check(state.n == 0) { "BodySimulation.load вызывается один раз; для новой топологии создайте новый решатель" }
        state.initialize(definition)
    }
    fun reset() = state.resetState()
    fun step() = state.solveTick(observer)
}

/** Необязательный наблюдатель для истории рисунка; без окна отсутствует. */
interface StepObserver {
    fun beginTick()
    fun afterSubstep(step: Int)
}
