package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

internal fun BodyState.solveTick(observer: StepObserver?) {
    // Пересборка после разрывов стоит В САМОМ КОНВЕЙЕРЕ, а не в обёртке над ним.
    // Сначала она была в stepOnce — и не срабатывала в проверках вовсе, потому
    // что стенд зовёт simulate() напрямую: связи помечались порванными сотнями,
    // а убито было ноль. Та же ошибка уже подводила с пересборкой стадий.
    //
    // Ровно один раз за тик: внутри подшагов контур и контакты пересобирались бы
    // по шестнадцать раз, а рвётся обычно пучок связей за один удар.
    java.util.Arrays.fill(contactPressure, 0.0)
    java.util.Arrays.fill(contactPressureN, 0)
    java.util.Arrays.fill(crushedCell, false)
    if (tearsPending) refreshSurface()
    val h = DT / SUBSTEPS
    observer?.beginTick()
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
        // Снимок ДО внутренних стадий: всё, что они внесут сверх формы, снимется
        // перед контактами. См. cancelInternalSpin.
        if (cancelSpinOn) markInternal()
        // ЦИКЛ ПО ВСЕМ ОГРАНИЧЕНИЯМ СРАЗУ, ВКЛЮЧАЯ КОНТАКТ.
        //
        // Смысл в накоплении множителя. Сила при повторении просто умножается на
        // число заходов, и общий цикл с ней делал только хуже (0.096, 0.25, 0.92,
        // 6.25 при одном, двух, четырёх и восьми). Ограничение с накопленным
        // множителем ведёт себя наоборот: повторения его СХОДЯТ, и в равновесии
        // приращение обращается в ноль само. А в скорость идёт именно приращение.
        //
        // Поэтому здесь связи и контакт стоят в одном цикле и делят между собой
        // одну и ту же невязку, вместо того чтобы переписывать работу друг друга.
        for (iter in 0 until SOLVER_ITERS) {
            if (sweepFlip == 2) sweepBackwards = !sweepBackwards
            tick0(); dbgMark(); solveConstraints(h); dbgMeasure(0, h); tick1(0)
            tick0(); dbgMark(); solveFlaps(h); dbgMeasure(9, h); tick1(1)
            // Ранний предел оставляем: его пропуск уже проверяли, покоящееся тело
            // начинало ползти. Это другой момент решателя, чем предел после площадей.
            if (MAXLEN_EARLY) { tick0(); dbgMark(); solveLinkMaxLength(); dbgMeasure(4, h); tick1(2); refreshSurface() }
            // ОПЫТ: выключить стадию площадей знаком среды RB_AREAS=0. Нужен проверке
            // гипотезы игрока — не она ли проталкивает клетки сквозь чужую ткань в
            // обход контактов. Возвращать в код насовсем не планируется.
            if (areasOn) { tick0(); dbgMark(); solveAreas(h); dbgMeasure(1, h); tick1(3) }
            tick0(); dbgMark(); solveBend(h); dbgMeasure(2, h); tick1(4)
            tick0(); dbgMark()
            if (bonesRigid) for (b in rigidBones.indices) projectBone(b)
            dbgMeasure(3, h); tick1(5)
            tick0(); dbgMark(); solveLinkMaxLength(); dbgMeasure(4, h); tick1(2)
            // Производная поверхность обновляется сразу и локально. В конце тика
            // остаётся только упаковка рабочих списков; см. tearQueue.
            refreshSurface()
            val ci = contacts
            if (ci != null && contactsOn) {
                // Геометрия изменилась после связей и костей: новые пары нужны
                // на каждом проходе; множители старых сохраняются внутри подшага.
                tick0(); dbgMark()
                ci.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = iter > 0)
                dbgMeasure(6, h); tick1(6)
                if (cancelSpinOn) internalMom?.noteContacts(ci)
                contactsStale = false
                tick0()
                ci.updateBones(px, py, invMass,
                    if (bonesRigid) boneOf else null, rigidBones)
                tick1(13)
                tick0(); dbgMark(); ci.solveContacts(px, py, vx, vy, invMass, h); dbgMeasure(5, h); tick1(7)
                if (ci.killN > 0) { applyKills(ci); contactsStale = true }
                // ПРОВЕРЯТЬ ПРОБОЙ ПОСЛЕ КАЖДОЙ ИТЕРАЦИИ — ПРОВЕРЕНО И ОТВЕРГНУТО.
                //
                // Догадка игрока была верной: на нулевой итерации видно только
                // баллистику, а внутрь клетку заводит решатель, и его смещение не
                // проверялось. Проверка после каждой итерации действительно нашла на
                // 67% больше пересечений (130 пробоев против 78). Но проникновение
                // стало ХУЖЕ: осталось внутри 637 против 461, входов 1204 против 978.
                //
                // Потому что РАЗРЫВ НЕ ОСТАНАВЛИВАЕТ КЛЕТКУ, А ВПУСКАЕТ ЕЁ: каждый
                // пробой открывает дыру, и следующие заходят через неё, уже ничего не
                // пересекая. Чем больше честных пробоев, тем больше дыр. Значит
                // непроницаемость контура разрывом не достигается в принципе — нужен
                // упор, который клетку ОСТАНАВЛИВАЕТ, а разрыв остаётся ответом на то,
                // что упор не удержал.
                //
                // ПЕРЕМЕР 06.10: тот замер делался, когда отломанные клетки были
                // призраками (радиус 0) и смерти от проникновения не было. Обе
                // причины убраны, поэтому развилка открыта заново — RB_PIERCE_ITER=1.
                if (pierceEachIter) ci.scanPierce(px, py, prevX, prevY)
                if (ci.pierceN > 0) applyPierce(ci)
            }
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
        tick0(); dbgMark(); if (cancelSpinOn) cancelInternalSpin(); dbgMeasure(12, h); tick1(8)

        dbgMark(); if (cancel) cancelInternalDrift(beforeX, beforeY); dbgMeasure(12, h)

        // Предел длины и снятие момента тоже двигают клетки. Закрываем их
        // перемещение контактом до восстановления скоростей.
        refreshSurface()
        val finalContacts = contacts
        if (finalContacts != null && contactsOn) {
            tick0()
            finalContacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = true)
            tick1(6); tick0()
            finalContacts.updateBones(px, py, invMass, if (bonesRigid) boneOf else null, rigidBones)
            tick1(13); tick0()
            finalContacts.solveContacts(px, py, vx, vy, invMass, h)
            tick1(7)
            contactsStale = false
            if (finalContacts.killN > 0) { applyKills(finalContacts); contactsStale = true }
            if (finalContacts.pierceN > 0) applyPierce(finalContacts)
        }

        tick0()
        updateVelocities(h)
        // Отскок: позиционная часть удар гасит целиком, см. solveRestitution.
        dbgMarkV()
        val cr = contacts
        if (cr != null && contactsOn && !contactsStale) cr.solveRestitution(px, py, vx, vy, invMass)
        dbgMeasureV(7); dbgMarkV()
        applyDragVelocity(h)
        applyViscosity(h)
        dbgMeasureV(8); dbgMarkV()
        applyNormalDrag(h)
        dbgMeasureV(10); dbgMarkV()
        applyRestitution()
        applyMediumDrag(h)
        clampSpeed()
        dbgMeasureV(11); tick1(9)
        observer?.afterSubstep(step)
        dbgSubstepHook?.invoke(step)
    }
    // Пластика — РАЗ В ТИК, после всех подшагов: течение медленное, внутри тика
    // ему делать нечего, а стоить оно будет в шестнадцать раз дороже.
    refreshSurface()
    applyPlasticity(DT)
    burstByPressure()
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
