package io.github.some_example_name.physics.lab.inspection

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.scanTrapped
import io.github.some_example_name.physics.lab.checks.TrappedCrash
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.rebuildTimed
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Обход на проникновение с немедленной остановкой. Только для охоты, см. trapCrash.
 *
 * Пересборка перед обходом обязательна: порванное в последнем проходе предела длины
 * ещё не пересобрано, и сетка треугольников содержала бы мёртвые — тогда клетка
 * «внутри» треугольника, которого больше нет.
 */

internal fun DemoSession.trapCrashScan() { with(state) {
    if (contacts == null) return
    if (tearsPending) rebuildTimed()
    trapCrashCell = -1
    scanTrapped()
    val i = trapCrashCell
    if (i < 0) return
    // ЖУРНАЛ СНАЧАЛА. Падение в окне уносит буфер, а журнал — единственное, что потом
    // воспроизводится побитово: без него от вылета нет никакой пользы.
    try { plog?.flush() } catch (_: Throwable) {}
    val ct = contacts
    val t = trapCrashTri
    val ml = body.meanLinkLength.toDouble()
    // Глубина — наименьшее расстояние до сторон треугольника, в связях: «на волосок
    // внутри» и «прошила насквозь» это разные болезни, и различать их надо числом.
    var depth = 0.0
    var verts = intArrayOf(-1, -1, -1)
    if (t >= 0) {
        val a = triA[t]; val b = triB[t]; val c = triC[t]
        verts = intArrayOf(a, b, c)
        var best = Double.MAX_VALUE
        for (k in 0 until 3) {
            val p0 = verts[k]; val p1 = verts[(k + 1) % 3]
            var ex = px[p1] - px[p0]; var ey = py[p1] - py[p0]
            val el = sqrt(ex * ex + ey * ey)
            if (el < 1e-12) continue
            ex /= el; ey /= el
            val d = abs((px[i] - px[p0]) * ey - (py[i] - py[p0]) * ex)
            if (d < best) best = d
        }
        if (best < Double.MAX_VALUE) depth = best / ml
    }
    // Ближайшая клетка чужого тела с контактом: если она далеко, клетка попала не
    // сквозь мембрану, а мембрана уехала от неё.
    var nearD = Double.MAX_VALUE; var nearJ = -1
    val oi = organismOf[i]
    for (j in 0 until n) {
        if (j == i || cellDead[j] || organismOf[j] == oi) continue
        if (ct != null && ct.contactRadiusOf(j) <= 0.0) continue
        val dx = px[j] - px[i]; val dy = py[j] - py[i]
        val d = sqrt(dx * dx + dy * dy)
        if (d < nearD) { nearD = d; nearJ = j }
    }
    throw TrappedCrash(
        cell = i, tri = t, fold = trapCrashFold,
        orgCell = oi, orgTri = if (verts[0] >= 0) organismOf[verts[0]] else -1,
        x = px[i], y = py[i],
        speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i]) * DT / ml,
        radius = (ct?.contactRadiusOf(i) ?: 0.0) / ml,
        bone = boneOf[i] >= 0,
        free = isFree[i],
        depth = depth,
        nearForeign = nearJ,
        nearDist = if (nearD < Double.MAX_VALUE) nearD / ml else -1.0,
        nearRadius = if (nearJ >= 0) (ct?.contactRadiusOf(nearJ) ?: 0.0) / ml else -1.0,
        triVerts = verts,
    )
} }
