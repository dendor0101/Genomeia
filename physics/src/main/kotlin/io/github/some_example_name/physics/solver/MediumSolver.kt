package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * СНАЧАЛА ВСЕ ЧТЕНИЯ ЧАСТИЦЫ, ПОТОМ ВСЕ ЗАПИСИ — и это не стиль, а скорость.
 *
 * Процессор решает, зависит ли чтение от недавней записи, по младшим 12 битам адреса.
 * Массивы частиц, которые JVM положила с одинаковыми младшими битами (G1 кладёт
 * большие массивы в начало своего участка, ART на телефоне выравнивает их по
 * странице), дают совпадение на каждом i: чтение vx[i] после записи prevX[i] ждёт
 * эту запись (4K aliasing). Прежний порядок «записал prevX — прочитал vx — записал
 * px — прочитал vy» на сцене 100k стоил 47.7 мс за тик при такой раскладке против
 * 9.0 при раскладке подряд, и в каком случае окажется запуск, решала случайность.
 * Запись в конце итерации с чтением следующей частицы не совпадает: адрес другой на 8.
 * Результат побитово прежний: те же операции над теми же числами.
 * Тот же порядок держат updateVelocities и TickMomentum.afterExternal.
 */
fun BodyState.integrate(h: Float) {
    prepareAwakeSpans()
    val s = sleep
    // Только бодрствующие частицы: у спящей prevX = px и скорость ноль. См. BodySleep.
    for (k in 0 until s.spanCount) integrateRange(s.spanStart[k], s.spanEnd[k], h)
}

/**
 * Интегрирование частиц [from, to). Обход решателя зовёт его по блоку прямо перед
 * ограничениями блока (см. solveBlockedIteration): частицы блока остаются в кэше.
 * Спящая частица (invMass = 0) здесь — пустая работа: prevX и так равен px.
 */
internal fun BodyState.integrateRange(from: Int, to: Int, h: Float, hitBones: BoneWallHits = wallHits) {
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop (на телефоне проход 8.6 → 3.8 мс).
    // Рамка мира за проход не меняется — тоже в локальные.
    val px = px; val py = py; val vx = vx; val vy = vy; val prevX = prevX; val prevY = prevY
    val invMass = invMass; val inContact = inContact; val radius = radius
    val boundsOn = boundsOn
    val boundsMinX = boundsMinX; val boundsMaxX = boundsMaxX; val boundsMinY = boundsMinY; val boundsMaxY = boundsMaxY
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or prevX.size or prevY.size or invMass.size or
        inContact.size or radius.size)
    for (i in from until to) {
        val x = px[i]; val y = py[i]
        if (invMass[i] == 0.0f) { prevX[i] = x; prevY[i] = y; inContact[i] = false; continue }
        val nvy = vy[i] + GRAVITY * h
        var nx = x + vx[i] * h
        var ny = y + nvy * h
        var ground = false
        if (ny < GROUND_Y) {
            nx += (x - nx) * GROUND_FRICTION
            ny = GROUND_Y
            ground = true
        }
        if (boundsOn) {
            // Частицу мягкой ткани ограничиваем сразу. У кости отмечаем первое
            // пересечение, чтобы после решения формы сдвинуть все её вершины вместе.
            val r = radius[i]
            if (nx < boundsMinX + r || nx > boundsMaxX - r || ny < boundsMinY + r || ny > boundsMaxY - r) {
                val bone = if (bonesRigid) boneOf[i] else -1
                if (bone >= 0) hitBones.add(bone)
                else {
                    if (nx < boundsMinX + r) nx = boundsMinX + r else if (nx > boundsMaxX - r) nx = boundsMaxX - r
                    if (ny < boundsMinY + r) ny = boundsMinY + r else if (ny > boundsMaxY - r) ny = boundsMaxY - r
                    // Рамка — опора: отнятый ею импульс учёт за тик телу не возвращает (TickMomentum.block).
                    if (cancelSpinOn) tickMomentum.block(i)
                }
            }
        }
        prevX[i] = x; prevY[i] = y; vy[i] = nvy
        px[i] = nx; py[i] = ny; inContact[i] = ground
    }
}

/** Порядок «сначала чтения» — см. integrate. Худшая раскладка 100k: 13.7 мс против 7.8. */
fun BodyState.updateVelocities(h: Float) {
    prepareAwakeSpans()
    val s = sleep
    val invH = 1.0f / h // как velocityBeforeRange: умножение вместо деления (находка 41)
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (invMass[i] == 0.0f) { vx[i] = 0.0f; vy[i] = 0.0f; continue }
        val nvx = (px[i] - prevX[i]) * invH
        val nvy = (py[i] - prevY[i]) * invH
        vx[i] = nvx; vy[i] = nvy
    }
}

/** Продольная вязкость: гасит скорость деформации, жёсткое движение не трогает. */
fun BodyState.applyViscosity(h: Float) {
    var k = VISCOSITY * h
    if (k > 0.5f) k = 0.5f
    // Со спящими — только блоки с бодрствующими частицами и шов; у спящих связей вес
    // ноль, и они всё равно пропускаются. Без сна — прежний порядок. См. BodySleep.
    val layout = constraintBlocks
    val w = activeWorkers
    if (w != null && organismCount > 1) { parallelPhysics(w).viscosity(k, layout); return }
    // Без воркеров — тот же порядок, что у них: по блокам, затем швы.
    if (layout == null) {
        viscousLinksRange(0, conCount, k)
        return
    }
    val links = layout.links
    for (block in 0 until layout.buckets) {
        if (block < layout.seam && layout.awake[block] == 0) continue
        viscousLinksOf(links.items, links.start[block], links.start[block] + links.count[block], k)
    }
}

/** Вязкость одной связи: для проверок поштучно. Циклы стадии идут через viscousLinksRange и viscousLinksOf. */
internal fun BodyState.viscousLink(c: Int, k: Float) = viscousLinkAt(c, k, conDead, conA, conB, invMass, px, py, vx, vy)

/** Вязкость связей [c0, c1) по номерам. Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop. */
internal fun BodyState.viscousLinksRange(c0: Int, c1: Int, k: Float) {
    val dead = conDead; val a = conA; val b = conB; val w = invMass; val px = px; val py = py; val vx = vx; val vy = vy
    lengthsBeforeLoop(dead.size or a.size or b.size or w.size or px.size or py.size or vx.size or vy.size)
    for (c in c0 until c1) viscousLinkAt(c, k, dead, a, b, w, px, py, vx, vy)
}

/** Вязкость связей списка [items] на местах [from, to); отрицательный номер — пустое место списка. */
internal fun BodyState.viscousLinksOf(items: IntArray, from: Int, to: Int, k: Float) {
    val dead = conDead; val a = conA; val b = conB; val w = invMass; val px = px; val py = py; val vx = vx; val vy = vy
    lengthsBeforeLoop(dead.size or a.size or b.size or w.size or px.size or py.size or vx.size or vy.size or items.size)
    for (slot in from until to) {
        val c = items[slot]
        if (c >= 0) viscousLinkAt(c, k, dead, a, b, w, px, py, vx, vy)
    }
}

/** Ядро вязкости связи — единственная запись арифметики; `inline` с массивами в параметрах (см. lengthsBeforeLoop). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun viscousLinkAt(c: Int, k: Float, conDead: BooleanArray, conA: IntArray, conB: IntArray, invMass: FloatArray,
                                  px: FloatArray, py: FloatArray, vx: FloatArray, vy: FloatArray) {
    if (conDead[c]) return
    val i = conA[c]; val j = conB[c]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    val dx = px[j] - px[i]
    val dy = py[j] - py[i]
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-24f) return
    // БЕЗ КОРНЯ (24.11.2026, находка 41): поправка (Δv·n)·n с единичным n = d/|d| равна (Δv·d)·d / |d|², и длина
    // не нужна вовсе. Было: корень и четыре деления на связь (n и k·w/w), стало одно деление. Закон тот же, биты другие.
    val q = ((vx[j] - vx[i]) * dx + (vy[j] - vy[i]) * dy) * k / (w * len2)
    val qi = q * wi; val qj = q * wj
    vx[i] += dx * qi; vy[i] += dy * qi
    vx[j] -= dx * qj; vy[j] -= dy * qj
}

fun BodyState.applyNormalDrag(h: Float) {
    if (dbgDragOff) return
    var kd = FLOW_DECAY * h
    if (kd > 1.0f) kd = 1.0f

    // --- ЗАПАС СРЕДЫ. Догоняет скорость ЦЕНТРА МАСС, см. flowVX. ---
    //
    // Считается ДО цикла по рёбрам и ровно один раз: это одна степень свободы на
    // организм, а не на ребро. Вращение сюда не попадает — у чистого вращения
    // скорость центра масс равна нулю.
    if (FLOW_ENTRAIN > 0.0f && FLOW_MASS > 0.0f) {
        // Скорость центра масс КАЖДОГО организма по отдельности.
        //
        // Раньше здесь было одно среднее по всем частицам мира, и это была прямая
        // ошибка: два тела делили один запас среды, поэтому движение одного толкало
        // другое на любом расстоянии, а тяга у обоих падала — запас следовал за
        // средней скоростью пары, а не за собственной скоростью тела.
        // ВЗВЕШЕННО ПО МАССЕ, и это не педантизм.
        //
        // Здесь стояла простая сумма скоростей, делённая на число клеток. Это
        // среднее по частицам, а не скорость центра масс: та есть сумма m*v на
        // сумму m. Пока все клетки весили одинаково, разница пряталась в общем
        // множителе, и код был верен ПО СОВПАДЕНИЮ.
        //
        // На теле с разными радиусами совпадение кончается, и ломается ровно тот
        // инвариант, который обещан в комментарии выше. У чистого вращения
        // сумма m*v равна нулю, а сумма одних только v равна omega, векторно
        // умноженному на N и на разность геометрического центра и центра масс, —
        // то есть НЕ ноль. Вращение начинает заряжать запас, среда толкает тело,
        // толчок снова крутит. Движение из ничего.
        for (o in 0 until organismCount) { comAccX[o] = 0.0f; comAccY[o] = 0.0f }
        // Спящие: скорость ноль, масса ноль, запас среды ноль — проходы по ним пусты.
        prepareAwakeSpans()
        val s = sleep
        // С воркерами (и с одним) — суммы кусками, как у учёта импульса: порядок сложения от их числа не зависит.
        val cw = activeWorkers
        if (cw != null) parallelPhysics(cw).comSums()
        else if (constraintBlocks != null) comSumsSerial()
        else for (q in 0 until s.spanCount) for (i in s.spanStart[q] until s.spanEnd[q]) {
            if (invMass[i] <= 0.0f) continue
            val o = organismOf[i]
            val m = 1.0f / invMass[i]
            comAccX[o] += m * vx[i]; comAccY[o] += m * vy[i]
        }

        // Это демпфер между телом и запасом: за шаг относительная скорость падает
        // на kf * (1 + FLOW_MASS). Выше единицы — переброс через ноль, то есть
        // раскачка, поэтому коэффициент здесь и ограничивается. С этим ограничением
        // модель устойчива при ЛЮБОЙ массе среды, и подбирать её можно свободно.
        var kf = FLOW_ENTRAIN * h
        val limit = 0.9f / (1.0f + FLOW_MASS)
        if (kf > limit) kf = limit

        for (o in 0 until organismCount) {
            val mo = organismMass[o]
            if (mo <= 0.0f) continue
            val comVX = comAccX[o] / mo
            val comVY = comAccY[o] / mo
            val dfx = (comVX - flowVX[o]) * kf
            val dfy = (comVY - flowVY[o]) * kf
            flowVX[o] += dfx
            flowVY[o] += dfy
            comAccX[o] = dfx      // переиспользуем под плату, чтобы не заводить массив
            comAccY[o] = dfy
            // Рассеяние запаса в объём жидкости: только сток, никогда источник.
            // Без него запас был бы вечным аккумулятором импульса и тело не
            // остановилось бы никогда.
            flowVX[o] -= flowVX[o] * kd
            flowVY[o] -= flowVY[o] * kd
        }

        // Разгон среды телу СТОИТ: импульс FLOW_MASS * dFlow на каждую частицу.
        // Именно эта строка делает обмен честным — тело не может получить от среды
        // больше, чем вложило.
        val pw = activeWorkers
        if (pw != null && pw.count > 1) parallelPhysics(pw).flowPayment()
        else for (q in 0 until s.spanCount) flowPaymentRange(s.spanStart[q], s.spanEnd[q])
    }

    // Рёбра контура — по организмам, порядок внутри организма прежний: см. ParallelPhysics.normalDrag.
    val w = activeWorkers
    if (w != null) { parallelPhysics(w).normalDrag(h); return }
    val layout = constraintBlocks
    if (layout != null) { edgesByBlocksSerial(layout, 1, h); return }
    normalDragEdgesRange(0, boundCount, h)
}

/** Плата частиц [from, to) за разгон запаса среды. Кусок параллельного прохода. */
internal fun BodyState.flowPaymentRange(from: Int, to: Int) {
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val vx = vx; val vy = vy; val organismOf = organismOf; val comAccX = comAccX; val comAccY = comAccY
    lengthsBeforeLoop(vx.size or vy.size or organismOf.size or comAccX.size or comAccY.size)
    for (i in from until to) {
        val o = organismOf[i]
        vx[i] -= FLOW_MASS * comAccX[o]
        vy[i] -= FLOW_MASS * comAccY[o]
    }
}

/** Сопротивление среды одному ребру контура: для проверок поштучно. Циклы стадии идут через normalDragEdgesOf. */
internal fun BodyState.normalDragEdge(e: Int, h: Float) =
    normalDragEdgeAt(e, h, boundA, boundB, px, py, vx, vy, organismOf, flowVX, flowVY, tangentDrag)

/** Сопротивление среды рёбрам списка [items] на местах [from, to). Массивы — в локальные: см. lengthsBeforeLoop. */
internal fun BodyState.normalDragEdgesOf(items: IntArray, from: Int, to: Int, h: Float) {
    val a = boundA; val b = boundB; val px = px; val py = py; val vx = vx; val vy = vy
    val org = organismOf; val flowVX = flowVX; val flowVY = flowVY; val tangent = tangentDrag
    lengthsBeforeLoop(a.size or b.size or px.size or py.size or vx.size or vy.size or org.size or flowVX.size or
        flowVY.size or items.size)
    for (x in from until to) normalDragEdgeAt(items[x], h, a, b, px, py, vx, vy, org, flowVX, flowVY, tangent)
}

/** Сопротивление среды рёбрам [from, to) по номерам. */
internal fun BodyState.normalDragEdgesRange(from: Int, to: Int, h: Float) {
    val a = boundA; val b = boundB; val px = px; val py = py; val vx = vx; val vy = vy
    val org = organismOf; val flowVX = flowVX; val flowVY = flowVY; val tangent = tangentDrag
    lengthsBeforeLoop(a.size or b.size or px.size or py.size or vx.size or vy.size or org.size or flowVX.size or flowVY.size)
    for (e in from until to) normalDragEdgeAt(e, h, a, b, px, py, vx, vy, org, flowVX, flowVY, tangent)
}

/** Ядро сопротивления ребра — единственная запись арифметики; `inline` с массивами в параметрах (см. lengthsBeforeLoop). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun normalDragEdgeAt(e: Int, h: Float, boundA: IntArray, boundB: IntArray, px: FloatArray, py: FloatArray,
                                     vx: FloatArray, vy: FloatArray, organismOf: IntArray, flowVX: FloatArray,
                                     flowVY: FloatArray, tangentDrag: Float) {
    val i = boundA[e]; val j = boundB[e]
    val ex = px[j] - px[i]
    val ey = py[j] - py[i]
    val len = sqrt(ex * ex + ey * ey)
    if (len < 1e-12f) return

    // Одно деление вместо двух-четырёх (находка 41): закон тот же, биты другие.
    val invLen = 1.0f / len
    val nx = -ey * invLen
    val ny = ex * invLen

    // Скорость середины ребра ОТНОСИТЕЛЬНО увлечённой среды: гребёт ребро целиком,
    // а не каждый конец отдельно. Вычитание общего вектора и даёт длинный накат —
    // когда среда догнала корпус, сопротивление его движению обнуляется, а
    // локальные взмахи плавника по-прежнему гребут в полную силу.
    // Запас берётся СВОЕГО организма: у каждого тела он свой, см. flowVX.
    val o = organismOf[i]
    val vmx = (vx[i] + vx[j]) * 0.5f - flowVX[o]
    val vmy = (vy[i] + vy[j]) * 0.5f - flowVY[o]
    val vn = vmx * nx + vmy * ny

    // Линейная плюс квадратичная. Квадратичная сила равна NORMAL_DRAG_Q * vn*|vn|,
    // поэтому в коэффициенте (он делится на vn) остаётся |vn|.
    var k = (NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * abs(vn)) * len * h
    if (k > 0.5f) k = 0.5f      // выше — переброс скорости через ноль, то есть раскачка

    val dv = -vn * k
    vx[i] += dv * nx; vy[i] += dv * ny
    vx[j] += dv * nx; vy[j] += dv * ny

    // Трение ВДОЛЬ ребра. См. TANGENT_DRAG.
    if (tangentDrag > 0.0f) {
        val tx = ex * invLen; val ty = ey * invLen
        val vt = vmx * tx + vmy * ty
        var kt = tangentDrag * NORMAL_DRAG * len * h
        if (kt > 0.5f) kt = 0.5f
        val dvt = -vt * kt
        vx[i] += dvt * tx; vy[i] += dvt * ty
        vx[j] += dvt * tx; vy[j] += dvt * ty
    }
}

/**
 * Гашение отскока от пола: у контактной вершины убирается скорость ВВЕРХ.
 *
 * Это численный эффект, а не физика: контакт зажал позицию, решатель вернул её
 * обратно, а updateVelocities превратил разность позиций в настоящую скорость.
 * При GRAVITY = 0 стадия холостая — тело до пола не доходит.
 */
fun BodyState.applyRestitution() {
    prepareAwakeSpans()
    val s = sleep
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (inContact[i] && vy[i] > 0.0f) vy[i] *= GROUND_RESTITUTION
    }
}

fun BodyState.applyMediumDrag(h: Float) {
    if (dbgIsoDragOff) return
    var keep = 1.0f - MEDIUM_DRAG * h
    if (keep < 0.0f) keep = 0.0f
    prepareAwakeSpans()
    val s = sleep
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        val d = dragScale[i]
        val kp = if (d == 1.0f) keep else mediumKeep(h, d)
        vx[i] *= kp; vy[i] *= kp
    }
}

/**
 * ТЯГА МЫШИ — СИЛА, А НЕ ТЕЛЕПОРТ. Зовётся ПОСЛЕ updateVelocities.
 *
 * Раньше тяга правила ПОЗИЦИЮ прямо среди позиционных стадий, и из этого росли
 * сразу две беды, обе видимые руками.
 *
 * ПЕРВАЯ: сквозь тело можно было протащить что угодно. Контакт расталкивает
 * позиционно, но на следующем подшаге тяга снова ставила клетку туда же, куда
 * тянет курсор. Позиционная тяга ничем не ограничена и просто перетягивала
 * контакт, сколько бы тот ни отталкивал. Ткань при этом даже не напрягалась —
 * поэтому и синие треугольники не появлялись: рвать было нечего, клетка ехала
 * насквозь, а не продавливала.
 *
 * ВТОРАЯ: тяга за кость выходила тем сильнее, чем кость крупнее. projectBone
 * идёт ПОСЛЕ тяги и подтягивает ВЕСЬ кластер к сдвинутой клетке, так что одна
 * позиционная поправка двигала всю костную массу целиком.
 *
 * Обе беды снимает одно и то же: тяга задаёт СКОРОСТЬ, а положение получается
 * из неё через integrate на следующем подшаге. Тогда
 *   - контакт решает позиционно и ПОСЛЕДНИМ, значит выигрывает всегда;
 *   - кость сама делит тягу на свой размер: shape matching разносит смещение
 *     одной клетки по всему кластеру, и кость из N клеток трогается примерно
 *     в N раз медленнее. Делить вручную не нужно, это выходит из сохранения
 *     импульса.
 *
 * Ограничение по УСКОРЕНИЮ, а не по силе: тянуть надо одинаково отзывчиво и
 * лёгкую клетку, и тяжёлую, иначе мелкие клетки улетали бы от курсора. Деление
 * тяги по массе всё равно происходит, но уровнем ниже — в кости и в связях.
 */
fun BodyState.applyDragVelocity(h: Float) {
    val i = dragId
    if (i >= 0) pullToward(i, mouseX, mouseY, h)
}

internal fun BodyState.pullToward(i: Int, targetX: Float, targetY: Float, h: Float) {
    if (invMass[i] == 0.0f) return
    val dx = targetX - px[i]
    val dy = targetY - py[i]
    val d = sqrt(dx * dx + dy * dy)
    if (d < 1e-12f) return

    // РУКА ДАЁТ ПОСТОЯННУЮ СИЛУ, а не постоянное ускорение. Это и есть причина
    // несоразмерности тяги за кость.
    //
    // Раньше кость получала ТО ЖЕ УСКОРЕНИЕ, что одиночная клетка. Ускорение то
    // же, а масса в N раз больше — значит импульс в N раз больше, и чем крупнее
    // кластер, тем сильнее рывок. Именно это и наблюдалось: за самую большую
    // кость тело улетало несоизмеримо резче, чем за мягкую ткань.
    //
    // Теперь ограничена СИЛА. Одиночная клетка средней массы получает прежнее
    // ускорение, а кость из N клеток — в N раз меньшее, потому что тяжёлое и
    // разгоняется медленнее. Импульс за подшаг при насыщении выходит одинаковым
    // в обоих случаях, force * h, то есть тяга стала честной по импульсу.
    //
    // Массы нормированы так, что средняя клетка весит единицу (см. restInvMass),
    // поэтому эталонная масса здесь и есть единица, и отдельной константы для
    // неё не нужно.
    val b = if (bonesRigid) boneOf[i] else -1

    var mTotal = 0.0f
    var cx = 0.0f; var cy = 0.0f
    val ids = if (b >= 0) rigidBones[b] else null
    if (ids != null) {
        for (k in ids) {
            if (boneOf[k] != b || invMass[k] <= 0.0f) continue
            val m = 1.0f / invMass[k]
            mTotal += m; cx += m * px[k]; cy += m * py[k]
        }
        if (mTotal <= 0.0f) return
        cx /= mTotal; cy /= mTotal
    }

    val force = DRAG_ACCEL * linkScale / (DT * DT)
    // Потолок тяги СВОЙ и может быть выше общего: см. clampSpeed.
    val vLimit = DRAG_SPEED_LIMIT * MAX_SPEED_CELLS_PER_TICK * linkScale / DT

    // ЦЕЛЬ ОБЯЗАНА БЫТЬ ДОСТИЖИМОЙ, иначе сервотяга превращается в ракету.
    //
    // Здесь стояло «доехать до курсора за один тик, но не быстрее
    // MAX_DRAG_SPEED», а это 4.0 мировых в секунду, то есть около 140 связей в
    // секунду. Схваченная клетка столько не развивает никогда: её держит тело,
    // и замер на записанном перетаскивании дал пик 1.37. Значит ошибка сервотяги
    // НИКОГДА не уменьшалась, полная сила давила без остановки, и тело полторы
    // секунды монотонно разгонялось: скорость центра масс 2.1 -> 8.0 связей/с,
    // вращение 0.28 -> 1.13 рад/с. Демпфирующее слагаемое в формуле есть, но
    // работает только у самой цели, куда дело не доходило.
    //
    // Вдобавок потолка было ДВА и они противоречили друг другу: цель
    // ограничивалась значением 4.0, а результат — vLimit, то есть 1.71. Цель
    // была заведомо недостижима по построению.
    //
    // Теперь потолок один. Как только клетка набирает vLimit, ошибка обращается
    // в ноль, сила снимается, и вместо бесконечного разгона получается
    // предельная скорость — то есть рука, а не ракета.
    var vWant = d / DRAG_TAU
    if (vWant > vLimit) vWant = vLimit

    val wantX = dx / d * vWant - vx[i]
    val wantY = dy / d * vWant - vy[i]

    if (ids == null) {
        var jx = wantX / invMass[i]
        var jy = wantY / invMass[i]
        val jm = sqrt(jx * jx + jy * jy)
        val jMax = force * h
        if (jm > jMax) { val s = jMax / jm; jx *= s; jy *= s }
        var nvx = vx[i] + jx * invMass[i]
        var nvy = vy[i] + jy * invMass[i]
        val v = sqrt(nvx * nvx + nvy * nvy)
        if (v > vLimit) { val sc = vLimit / v; nvx *= sc; nvy *= sc }
        vx[i] = nvx; vy[i] = nvy
        return
    }

    // КОСТЬ ТЯНЕТСЯ ЦЕЛИКОМ, а не через одну свою клетку.
    //
    // Дать скорость одной клетке жёсткой кости нельзя: projectBone тут же
    // вернёт её на жёсткую позу, updateVelocities сделает из возврата скорость
    // ПРОТИВОПОЛОЖНУЮ тяге, а на следующем подшаге тяга надавит снова. Кость
    // начинает перекачиваться сама с собой на частоте подшагов — отсюда и
    // хаотичная скорость, и разрывы ткани там, где рука её не касалась.
    //
    // Правильно — приложить импульс В ТОЧКУ ЗАХВАТА ко всему твёрдому телу и
    // разнести его как положено: поступательная часть J/M одинакова для всех,
    // вращательная (r x J)/I добавляет скорость по радиусу. Тогда поза кости
    // не нарушается вовсе, проекции нечего исправлять, и качаться нечему.
    //
    // Величина импульса подобрана так, чтобы кость получала ТО ЖЕ линейное
    // ускорение, что получила бы одиночная клетка. Значит тяжёлую кость тянуть
    // так же отзывчиво, как лёгкую, а вот тело вокруг неё сопротивляется по
    // своей массе — что и требуется.
    var inertia = 0.0f
    for (k in ids) {
        if (boneOf[k] != b || invMass[k] <= 0.0f) continue
        val rx = px[k] - cx; val ry = py[k] - cy
        val r2 = rx * rx + ry * ry
        inertia += r2 / invMass[k]
    }

    // ЭФФЕКТИВНАЯ МАССА В ТОЧКЕ ЗАХВАТА — вот где был баг с хвостом.
    //
    // Прежний код считал импульс как J = m * dv, будто точка захвата отзывается
    // на силу ускорением J/M. Для твёрдого тела это неверно: сила в точке на
    // расстоянии r от центра ещё и КРУТИТ, и та же точка отзывается сильнее —
    // J/M плюс вклад вращения (r x J)/I по радиусу.
    //
    // Насколько сильнее — считается точно. Для стержня, схваченного за кончик,
    // множитель 1 + M*r^2/I равен четырём. То есть сервотяга вчетверо
    // ПЕРЕДАВЛИВАЛА, точка проскакивала цель, на следующем подшаге тяга давила
    // в обратную сторону — и раскачка шла по вращательной степени свободы,
    // которую servo по одной точке вообще не видит: у твёрдого тела три
    // степени свободы, а скорость точки задаёт только две.
    //
    // Отсюда и наблюдаемое руками: схватить хвост, потянуть, отпустить,
    // потянуть снова — и организм начинает бешено вращаться. И отсюда же дрейф
    // кирпича от одного лишь удержания кости: 0.208 связи в секунду при полной
    // физике против 3.1e-14 без среды.
    //
    // Правильно — решить систему на импульс честно. Скорость точки меняется как
    //     dv = J/M + ((r x J)/I) x r,
    // что в двумерии есть линейная система dv = A*J с симметричной матрицей
    //     A = [[1/M + ry^2/I,  -rx*ry/I ],
    //          [ -rx*ry/I,     1/M + rx^2/I]].
    // Она положительно определена, обращается в две строки, и найденный J даёт
    // точке РОВНО заданное изменение скорости. Перелёту взяться неоткуда.
    val rgx = px[i] - cx; val rgy = py[i] - cy
    val invI = if (inertia > mTotal * (SPIN_MIN_ARM * linkScale) * (SPIN_MIN_ARM * linkScale)) 1.0f / inertia else 0.0f   // см. SPIN_MIN_ARM
    val invM = 1.0f / mTotal
    val a11 = invM + rgy * rgy * invI
    val a12 = -rgx * rgy * invI
    val a22 = invM + rgx * rgx * invI
    val det = a11 * a22 - a12 * a12
    if (det < 1e-24f) return
    var jx = (a22 * wantX - a12 * wantY) / det
    var jy = (a11 * wantY - a12 * wantX) / det

    // Сила руки ограничена одинаково для любой кости — см. выше про постоянную
    // силу. Ограничивается ИМПУЛЬС, а не ускорение, иначе крупная кость снова
    // получала бы больше импульса, чем мелкая.
    val jm = sqrt(jx * jx + jy * jy)
    val jMax = force * h
    if (jm > jMax) { val sJ = jMax / jm; jx *= sJ; jy *= sJ }

    val dOmega = (rgx * jy - rgy * jx) * invI

    // Прикидываем итог и, если самая быстрая клетка кости выходит за потолок,
    // ослабляем ВЕСЬ импульс — и поступательную часть, и вращательную. Резать
    // скорости по одной здесь нельзя: это сломало бы и жёсткость позы, и
    // сохранение импульса, ради которого весь этот кусок и написан.
    val dvx = jx / mTotal
    val dvy = jy / mTotal
    var worst2 = 0.0f
    for (k in ids) {
        if (boneOf[k] != b || invMass[k] <= 0.0f) continue
        val rx = px[k] - cx; val ry = py[k] - cy
        val nvx = vx[k] + dvx - dOmega * ry
        val nvy = vy[k] + dvy + dOmega * rx
        val v2 = nvx * nvx + nvy * nvy
        if (v2 > worst2) worst2 = v2
    }
    val worst = sqrt(worst2)
    var sc = 1.0f
    if (worst > vLimit && worst > 1e-18f) sc = vLimit / worst

    for (k in ids) {
        if (boneOf[k] != b || invMass[k] <= 0.0f) continue
        val rx = px[k] - cx; val ry = py[k] - cy
        vx[k] += sc * (dvx - dOmega * ry)
        vy[k] += sc * (dvy + dOmega * rx)
    }
}

fun BodyState.clampSpeed() {
    if (dbgNoClamp) return
    val maxV = MAX_SPEED_CELLS_PER_TICK * linkScale / DT
    val maxV2 = maxV * maxV
    // ОСВОБОЖДАТЬ СХВАЧЕННУЮ КЛЕТКУ ОТ ОБЩЕГО ПОТОЛКА — ОТВЕРГНУТО.
    //
    // Так удавалось рвать ткань мышью, но это и был костыль: одна клетка жила по
    // другим правилам, чем весь мир, а потолок скорости держит устойчивость стадий не
    // от скуки. Решение пользователя — вернуть общий потолок и опустить вместо этого
    // ПОРОГ РАЗРЫВА, см. LINK_TEAR_STRAIN.
    prepareAwakeSpans()
    val s = sleep
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        val v2 = vx[i] * vx[i] + vy[i] * vy[i]
        if (v2 > peakSpeed2) peakSpeed2 = v2
        if (v2 <= maxV2) continue
        val s = maxV / sqrt(v2)
        vx[i] *= s; vy[i] *= s
        speedCapHits++
    }
}

fun BodyState.comX(): Float { var s = 0.0f; for (i in 0 until n) s += px[i]; return s / n }

fun BodyState.comY(): Float { var s = 0.0f; for (i in 0 until n) s += py[i]; return s / n }

/**
 * Конец шага: все частицы — внутрь рамки мира (с отступом в радиус). Интегрирование держит
 * стенку, но связи и контакты подшага выталкивают частицу за неё (у клиента-игры сильная мышца
 * выносила клетку за край сетки, и еда погибшей клетки ложилась вне сетки — падение игры
 * 27.10). Проход по всем частицам раз в тик, только при включённой рамке; мёртвые тоже —
 * клиент читает их позицию (место гибели).
 */
internal fun BodyState.clampToBounds() {
    for (i in 0 until n) {
        val r = radius[i]
        val x = px[i]; val y = py[i]
        // СКОРОСТЬ В СТЕНУ ГАСИТСЯ (неупругий удар, 03.11). Раньше возвращалась только позиция, а
        // скорость «в стену» оставалась: тело, прижатое к стенке, стояло, а скорость его клеток к
        // стене копилась, питала увлечённую среду (запас), и среда сопротивлением рёбер снова
        // толкала тело в стену. Отчёт 03.11 02:51 (Burdock leaf): после отпускания мыши скорость к
        // стене росла сама 200 тиков (0.06 → 0.083 клетки/тик при неподвижном центре), затем тело
        // выстреливало прочь — 400–500 погибших клеток. «Стена засасывает, потом откидывает».
        if (x >= boundsMinX + r && x <= boundsMaxX - r && y >= boundsMinY + r && y <= boundsMaxY - r) continue
        val bone = if (bonesRigid) boneOf[i] else -1
        if (bone >= 0 && !cellDead[i] && !cellUnborn[i] && !isFixed(i)) { wallHits.add(bone); continue }
        if (x < boundsMinX + r) { px[i] = boundsMinX + r; if (vx[i] < 0.0f) vx[i] = 0.0f }
        else if (x > boundsMaxX - r) { px[i] = boundsMaxX - r; if (vx[i] > 0.0f) vx[i] = 0.0f }
        if (y < boundsMinY + r) { py[i] = boundsMinY + r; if (vy[i] < 0.0f) vy[i] = 0.0f }
        else if (y > boundsMaxY - r) { py[i] = boundsMaxY - r; if (vy[i] > 0.0f) vy[i] = 0.0f }
    }
    if (wallHits.size != 0) resolveBoneBounds(finalPass = true)
}
