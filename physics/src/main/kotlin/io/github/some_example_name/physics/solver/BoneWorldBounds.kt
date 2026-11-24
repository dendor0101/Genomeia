package io.github.some_example_name.physics

/** Бит на кость, список непустых слов. До 12 байт на 64 кости у каждого
 * воркера; обход и очистка только по задетым, без аллокаций на подшаге. */
internal class BoneWallHits(val capacity: Int) {
    private val bits = LongArray((capacity + 63) ushr 6)
    private val used = IntArray(bits.size)
    var size = 0
        private set

    fun add(bone: Int) {
        val word = bone ushr 6
        if (bits[word] == 0L) used[size++] = word
        bits[word] = bits[word] or (1L shl (bone and 63))
    }

    inline fun drain(visit: (Int) -> Unit) {
        for (k in 0 until size) {
            val word = used[k]
            var mask = bits[word]
            while (mask != 0L) {
                visit((word shl 6) + java.lang.Long.numberOfTrailingZeros(mask))
                mask = mask and (mask - 1)
            }
            bits[word] = 0L
            used[k] = 0
        }
        size = 0
    }
}

/** Ближайший к нулю допустимый сдвиг по каждой оси возвращает всю кость
 * внутрь, сохраняя расстояния и угол. Кости не делят вершин: порядок
 * поступления от воркеров не влияет на результат. */
internal fun BodyState.resolveBoneBounds(finalPass: Boolean) {
    wallHits.drain { bone -> fitBoneToBounds(bone, finalPass) }
}

private fun BodyState.fitBoneToBounds(bone: Int, finalPass: Boolean) {
    val ids = rigidBones[bone]
    var lowX = Float.NEGATIVE_INFINITY
    var lowY = Float.NEGATIVE_INFINITY
    var highX = Float.POSITIVE_INFINITY
    var highY = Float.POSITIVE_INFINITY
    var mass = 0.0f
    var momentumX = 0.0f
    var momentumY = 0.0f
    for (i in ids) {
        if (!movableBoneMember(i, bone)) continue
        val r = radius[i]
        lowX = maxOf(lowX, boundsMinX + r - px[i])
        highX = minOf(highX, boundsMaxX - r - px[i])
        lowY = maxOf(lowY, boundsMinY + r - py[i])
        highY = minOf(highY, boundsMaxY - r - py[i])
        if (finalPass) {
            val m = 1.0f / restInvMass[i]
            mass += m
            momentumX += m * vx[i]
            momentumY += m * vy[i]
        }
    }
    val dx = wallShift(lowX, highX)
    val dy = wallShift(lowY, highY)
    if (dx == 0.0f && dy == 0.0f && lowX <= highX && lowY <= highY) return
    // На подшаге восстановление скоростей учитывает сдвиг. После последнего
    // подшага гасим поступательную скорость в стену, сохраняя вращение.
    val stopX = if (finalPass && mass > 0 && dx * momentumX < 0) momentumX / mass else 0.0f
    val stopY = if (finalPass && mass > 0 && dy * momentumY < 0) momentumY / mass else 0.0f
    // Рамка — опора и для кости: её группа в этом тике из учёта импульса выходит (TickMomentum.block).
    val tm = if (cancelSpinOn) tickMomentum else null
    for (i in ids) {
        if (!movableBoneMember(i, bone)) continue
        tm?.block(i)
        val r = radius[i]
        px[i] = wallCoordinate(px[i] + dx, boundsMinX, boundsMaxX, r)
        py[i] = wallCoordinate(py[i] + dy, boundsMinY, boundsMaxY, r)
        if (finalPass) {
            vx[i] -= stopX
            vy[i] -= stopY
            if (invMass[i] == 0.0f) { prevX[i] = px[i]; prevY[i] = py[i] }
        }
    }
}

private fun BodyState.movableBoneMember(i: Int, bone: Int): Boolean =
    boneOf[i] == bone && !cellUnborn[i] && !cellDead[i] && !isFixed(i)

// Если кость шире рамки, жёсткого переноса по этой оси не существует.
// Центрируем избыток поровну и ограничиваем только выступающие координаты.
private fun wallShift(low: Float, high: Float): Float =
    if (low > high) (low + high) * 0.5f else 0.0f.coerceIn(low, high)

private fun wallCoordinate(x: Float, min: Float, max: Float, r: Float): Float =
    if (min + r > max - r) (min + max) * 0.5f else x.coerceIn(min + r, max - r)
