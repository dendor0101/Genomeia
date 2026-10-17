package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

fun BodyState.muscleOfPair(i: Int, j: Int): Int {
    val a = muscleOf[i]; val b = muscleOf[j]
    if (a == b) return a
    if (muscleTouch == 0) return -1
    // ШОВ МЕЖДУ СОСЕДНИМИ МЫШЦАМИ ТОЖЕ СОКРАЩАЕТСЯ. Пока между кластерами лежала
    // обычная ткань, шва не было; после роста они сходятся вплотную, и связь на стыке
    // осталась бы единственной, кто держит длину, пока обе стороны от неё уходят. На
    // body-export таких связей 92, и все они рвались растяжением на нулевом же тике.
    if (a >= 0 && b >= 0) return if (a < b) a else b
    if (muscleTouch != 2) return -1
    return if (a < 0) b else a
}

fun BodyState.muscleOfTri(i0: Int, i1: Int, i2: Int): Int {
    // ПЛОЩАДЬ У КОСТИ НЕ СОКРАЩАЕТСЯ. Кость жёсткая, её сторона треугольника длину не
    // меняет, а площадь просят уменьшить в s^2 раз — выполнить это можно только
    // уронив вершину на кость. Связи при этом тянут её же на s, и стадии дерутся:
    // на body-export это рвало 52 связи на нулевом тике, худшая связь сжималась до
    // 0.111 длины покоя. Тянет кость СВЯЗЬ, ей для этого площадь не нужна.
    if (boneOf[i0] >= 0 || boneOf[i1] >= 0 || boneOf[i2] >= 0) return -1
    val a = muscleOf[i0]; val b = muscleOf[i1]; val c = muscleOf[i2]
    if (a >= 0 && a == b && a == c) return a
    if (muscleTouch == 0) return -1
    // Все три вершины мышечные, но кластеры разные — см. шов у muscleOfPair.
    if (muscleTouch != 2 && (a < 0 || b < 0 || c < 0)) return -1
    var best = -1
    var votes = 0
    if (a >= 0) { val v = 1 + (if (b == a) 1 else 0) + (if (c == a) 1 else 0)
        if (v > votes || (v == votes && a < best)) { best = a; votes = v } }
    if (b >= 0) { val v = 1 + (if (a == b) 1 else 0) + (if (c == b) 1 else 0)
        if (v > votes || (v == votes && b < best)) { best = b; votes = v } }
    if (c >= 0) { val v = 1 + (if (a == c) 1 else 0) + (if (b == c) 1 else 0)
        if (v > votes || (v == votes && c < best)) { best = c; votes = v } }
    return best
}

/**
 * КОЛЬЦО ВОКРУГ МЫШЦЫ УХОДИТ В МЫШЦУ. Режим 1, см. MUSCLE_TOUCH.
 *
 * Треугольник с хотя бы одной мышечной вершиной отдаёт кластеру все три свои клетки.
 * Спорную клетку — ту, что граничит сразу с двумя мышцами, — забирает кластер с
 * меньшим номером: правило должно быть одинаковым при любом порядке треугольников.
 * Смотрим на ИСХОДНЫЙ список клеток, а не на растущий, иначе мышца расползлась бы по
 * телу на столько колец, сколько треугольников попадётся по дороге.
 */
fun BodyState.growMuscles() {
    if (muscleTouch != 1) return
    val core = muscleOf
    val grown = core.copyOf()
    for (t in 0 until body.triCount) {
        val i0 = body.triA[t]; val i1 = body.triB[t]; val i2 = body.triC[t]
        val a = core[i0]; val b = core[i1]; val c = core[i2]
        var m = -1
        if (a >= 0) m = a
        if (b >= 0 && (m < 0 || b < m)) m = b
        if (c >= 0 && (m < 0 || c < m)) m = c
        if (m < 0) continue
        if (a < 0 && (grown[i0] < 0 || m < grown[i0])) grown[i0] = m
        if (b < 0 && (grown[i1] < 0 || m < grown[i1])) grown[i1] = m
        if (c < 0 && (grown[i2] < 0 || m < grown[i2])) grown[i2] = m
    }
    muscleOf = grown
}

fun BodyState.muscleScale(m: Int) = if (m < 0) 1.0 else muscleScaleOf[m]

fun BodyState.updateMuscleScale(f: Double) {
    if (muscleScaleOf.size != muscleActivation.size) muscleScaleOf = DoubleArray(muscleActivation.size)
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = DoubleArray(muscleActivation.size)
    for (m in muscleActivation.indices) {
        val a = muscleActivationPrev[m] + (muscleActivation[m] - muscleActivationPrev[m]) * f
        muscleScaleOf[m] = 1.0 - a * (1.0 - muscleContraction)
    }
}

fun BodyState.updateMuscles(dt: Double) {
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = DoubleArray(muscleActivation.size)
    System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
    for (m in muscleActivation.indices) {
        val target = muscleTarget[m]
        // Сокращение и распрямление идут с РАЗНОЙ скоростью — см. константы.
        val rate = if (target > muscleActivation[m]) muscleRateContract else muscleRateRelax
        var k = rate * dt
        if (k > 1.0) k = 1.0
        muscleActivation[m] += (target - muscleActivation[m]) * k
    }
}
