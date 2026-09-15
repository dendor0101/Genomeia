package io.github.some_example_name.lwjgl3.demo

/**
 * СНЯТИЕ ОСТАТКА ИМПУЛЬСА И МОМЕНТА ПОЗИЦИОННОГО РЕШАТЕЛЯ.
 *
 * Общий для RealBodyDemo и SwimSolver: зеркало обязано совпадать побитово, и две копии
 * одной формулы разъехались бы при первой же правке.
 *
 * ПОЧЕМУ ЭТО ЗАКОННО. Все позиционные стадии — связи, площади, лоскуты, предел длины,
 * проекция кости и контакты — силы между клетками. Суммарный импульс и момент импульса
 * тех клеток, между которыми они действуют, обязаны сохраняться точно. Всё, что
 * набежало сверх этого, — численный остаток, и он вычитается жёстким сдвигом и жёстким
 * поворотом, форма при этом не меняется вовсе.
 *
 * ОТКУДА ОСТАТОК. Парная поправка момента не даёт относительно того положения, где её
 * приложили, но обход последовательный: следующая ложится на уже сдвинутые клетки.
 * Пока невязка случайна, перекрёстные члены гасят друг друга. При ПОСТОЯННОМ споре
 * ограничений они складываются в момент одного знака каждый подшаг.
 *
 * Первым это поймали на мышце: пока она сокращается, невязка у всех мышечных связей
 * смотрит в одну сторону. Оторванный кусок, контакты и среда выключены, 300 тиков
 * сокращения: момент 0.167 -> -2.29.
 *
 * Второй раз — на журнале игрока: кусок из 364 клеток крутился с постоянной скоростью
 * 0.09 рад/с. Связи и предел длины подкручивали его на +0.125 рад/с за 2 секунды, не
 * завися от скорости вращения, среда тормозила. Без среды он разгонялся 0.09 -> 0.70
 * рад/с за 20 секунд; с выключенными контактами спор пропадал и вращение сохранялось
 * идеально.
 *
 * ПОЧЕМУ ПО ГРУППАМ, А НЕ ПО ОРГАНИЗМАМ. Сначала остаток снимался с каждого организма,
 * а контакты считались внешними. Не вышло: тот же кусок спорил ещё и с застрявшими в
 * нём чужими осколками, и контакты с ними подкручивали его уже на +0.15 рад/с за 2 с —
 * точный счёт показал это, хотя пораздельная раскладка стадий давала там ноль.
 * Сохраняться обязан СУММАРНЫЙ момент тел, соединённых контактами, а обмен моментом
 * между ними законен. Поэтому группа — организмы, связанные контактами на этом
 * подшаге, и для группы все позиционные стадии внутренние.
 *
 * ОТНОСИТЕЛЬНО ЧЕГО МОМЕНТ. Скорость восстанавливается как (px - prevX) / h, и
 * точное приращение момента от поправок Δ за подшаг равно Σ m (prevX - c) × Δ / h,
 * где prevX — положение на НАЧАЛО подшага, до интегрирования. Относительно него и
 * считается; поворот снимается вокруг того же центра, и снятие гасит приращение ровно.
 *
 * Группа с закреплённой клеткой не трогается — у неё есть внешняя опора.
 */
internal class InternalMomentum(val n: Int) {
    private val snapX = DoubleArray(n)
    private val snapY = DoubleArray(n)
    /** Организм каждой клетки на начало подшага и первая клетка каждого такого организма. */
    private val startOrg = IntArray(n)
    private var startFirst = IntArray(0)
    /** Пары клеток всех контактов этого подшага, из КАЖДОГО прохода. См. noteContacts. */
    private var pairI = IntArray(1024)
    private var pairJ = IntArray(1024)
    private var pairN = 0

    private var parent = IntArray(0)
    private var gM = DoubleArray(0)
    private var gCx = DoubleArray(0)
    private var gCy = DoubleArray(0)
    private var gDx = DoubleArray(0)
    private var gDy = DoubleArray(0)
    private var gL = DoubleArray(0)
    private var gI = DoubleArray(0)
    private var gPinned = BooleanArray(0)

    /** Разбор: группа этой клетки — сколько момента снято, Σ m r × Δ. */
    var dbgCell = -1
    var dbgLint = 0.0

    /** Начало подшага, сразу после интегрирования. */
    fun mark(px: DoubleArray, py: DoubleArray, organismOf: IntArray, organismCount: Int) {
        System.arraycopy(px, 0, snapX, 0, n)
        System.arraycopy(py, 0, snapY, 0, n)
        System.arraycopy(organismOf, 0, startOrg, 0, n)
        if (startFirst.size < organismCount) startFirst = IntArray(organismCount)
        java.util.Arrays.fill(startFirst, 0, organismCount, -1)
        for (i in 0 until n) { val o = organismOf[i]; if (o in 0 until organismCount && startFirst[o] < 0) startFirst[o] = i }
        pairN = 0
    }

    /**
     * Запомнить пары контактов после прохода. Зовётся после КАЖДОГО solveContacts: после
     * разрыва посреди подшага список пересобирается, и пара, толкавшаяся в ранней
     * итерации и разошедшаяся, в последний список уже не попадёт, а импульс передала.
     * Пары хранятся клетками: номера организмов пересборка меняет.
     */
    fun noteContacts(contacts: BoundaryContacts) {
        val k = contacts.contactCount
        if (pairN + k > pairI.size) {
            val cap = maxOf(pairI.size * 2, pairN + k)
            pairI = pairI.copyOf(cap); pairJ = pairJ.copyOf(cap)
        }
        for (c in 0 until k) { pairI[pairN] = contacts.contactI(c); pairJ[pairN] = contacts.contactJ(c); pairN++ }
    }

    private fun find(o: Int): Int {
        var r = o
        while (parent[r] != r) r = parent[r]
        var x = o
        while (parent[x] != r) { val nx = parent[x]; parent[x] = r; x = nx }
        return r
    }

    /** Конец позиционного цикла, до updateVelocities. */
    fun cancel(organismOf: IntArray, organismCount: Int,
               px: DoubleArray, py: DoubleArray, prevX: DoubleArray, prevY: DoubleArray,
               invMass: DoubleArray) {
        val c = organismCount
        if (c <= 0) return
        if (parent.size < c) {
            parent = IntArray(c)
            gM = DoubleArray(c); gCx = DoubleArray(c); gCy = DoubleArray(c)
            gDx = DoubleArray(c); gDy = DoubleArray(c)
            gL = DoubleArray(c); gI = DoubleArray(c); gPinned = BooleanArray(c)
        }
        for (o in 0 until c) parent[o] = o
        // Разорванное ПОСРЕДИ подшага — всё ещё одна группа: до разрыва связь законно
        // передавала импульс между половинками, и снимать его с каждой нельзя.
        for (i in 0 until n) {
            val s = startOrg[i]
            if (s < 0 || s >= startFirst.size) continue
            val f = startFirst[s]
            if (f < 0 || f == i) continue
            val a = organismOf[i]; val b = organismOf[f]
            if (a == b || a !in 0 until c || b !in 0 until c) continue
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (k in 0 until pairN) {
            val a = organismOf[pairI[k]]; val b = organismOf[pairJ[k]]
            if (a == b || a !in 0 until c || b !in 0 until c) continue
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        java.util.Arrays.fill(gM, 0, c, 0.0)
        java.util.Arrays.fill(gCx, 0, c, 0.0); java.util.Arrays.fill(gCy, 0, c, 0.0)
        java.util.Arrays.fill(gDx, 0, c, 0.0); java.util.Arrays.fill(gDy, 0, c, 0.0)
        java.util.Arrays.fill(gL, 0, c, 0.0); java.util.Arrays.fill(gI, 0, c, 0.0)
        java.util.Arrays.fill(gPinned, 0, c, false)
        for (i in 0 until n) {
            val o = organismOf[i]
            if (o !in 0 until c) continue
            val g = find(o)
            if (invMass[i] <= 0.0) { gPinned[g] = true; continue }
            val m = 1.0 / invMass[i]
            gM[g] += m; gCx[g] += m * prevX[i]; gCy[g] += m * prevY[i]
        }
        for (g in 0 until c) if (gM[g] > 0.0) { gCx[g] /= gM[g]; gCy[g] /= gM[g] }
        for (i in 0 until n) {
            val o = organismOf[i]
            if (o !in 0 until c || invMass[i] <= 0.0) continue
            val g = find(o)
            if (gPinned[g]) continue
            val m = 1.0 / invMass[i]
            val dx = px[i] - snapX[i]; val dy = py[i] - snapY[i]
            val rx = prevX[i] - gCx[g]; val ry = prevY[i] - gCy[g]
            gDx[g] += m * dx; gDy[g] += m * dy
            gL[g] += m * (rx * dy - ry * dx)
            gI[g] += m * (rx * rx + ry * ry)
        }
        if (dbgCell in 0 until n && organismOf[dbgCell] in 0 until c) dbgLint += gL[find(organismOf[dbgCell])]
        for (i in 0 until n) {
            val o = organismOf[i]
            if (o !in 0 until c || invMass[i] <= 0.0) continue
            val g = find(o)
            if (gPinned[g] || gM[g] <= 0.0) continue
            val tx = gDx[g] / gM[g]; val ty = gDy[g] / gM[g]
            val w = if (gI[g] > 1e-18) gL[g] / gI[g] else 0.0
            val rx = prevX[i] - gCx[g]; val ry = prevY[i] - gCy[g]
            px[i] -= tx - w * ry
            py[i] -= ty + w * rx
        }
    }
}
