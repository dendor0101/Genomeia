package io.github.some_example_name.physics

import kotlin.math.sqrt

/*
 * ГАШЕНИЕ СКОРОСТЕЙ СОСЕДНИХ ЧАСТИЦ — ОТДЕЛЬНОЙ ФАЗОЙ ПЕРЕД ИНТЕГРИРОВАНИЕМ БЛОКА (закон с 20.11.2026; значения, замер и
 * цена — config/DampingParameters.kt; reference.md, находки 26–28). Автор: «гасить скорость квадратично их скорости,
 * если столкновение слабое то скорость почти не будет гаситься, если столкновение резкое и быстрое то будет гаситься
 * очень сильно … вся эта энергия уходит в тепло»; «Гашение отдельной фазой (по идее тогда лучше в обе стороны хотя я не
 * знаю)» — демпфер, как был в Эйлере.
 *
 * Правило. В начале подшага, до интегрирования блока, по его связям: скорость пары вдоль связи u (связей за тик).
 * Сближается — снимается доля f = min(1, u / QD_LINK) этой скорости, расходится — f = min(1, u / QD_OPEN):
 * снятое растёт как квадрат скорости, медленное почти не трогается. Треугольники — так же, по скорости изменения
 * площади вдоль её градиента: сжатие — QD_AREA, рост — QD_OPEN. Снимается парой равных и противоположных толчков по
 * обратным массам: импульс и момент импульса те же, уходит энергия относительного движения («в тепло»); доля не больше
 * единицы — энергии не прибавляется. Скорости правятся сразу, и следующие связи прохода видят погашенные («паровозик»).
 *
 * ПОЧЕМУ ФАЗОЙ, А НЕ В ЯДРЕ (находка 27). Гашение внутри ядра связи двигает положения посреди обхода: толчок сдвигает
 * частицу, у которой часть связей в этом подшаге уже решена, и пережимает их — разрывов сжатием не меньше или в полтора
 * раза больше, чем без гашения. Здесь, как у прототипа (находка 26), меняются только скорости, и сдвиг достаётся всем
 * частицам блока сразу — в предсказании, до решения связей. По блокам на воркерах, спящие блоки пропускаются, данные
 * блока тут же нужны интегрированию и обходу (уже в кэше). Связи и треугольники швов — коротким проходом до всех блоков.
 * Гашения касаний нет (автор: «в гашении контакта особо нет смысла», находка 26). Эталонный решатель без гашения
 * (TODO.md). Путь со снятием сноса (CANCEL_INTERNAL_DRIFT, сейчас выключен) интегрирует до обхода — там фазы нет.
 *
 * ЭТО ОТЧАСТИ КОСТЫЛЬ (слова автора 20.11): лечит следствие — ткань не держит быстрый удар и быстрое сжатие при шести
 * подшагах — и стоит +33% тика в один поток. Оптимизировать обязательно: TODO.md, «Клин и гашение — отчасти костыли».
 *
 * Ключи настроек мира QD_LINK, QD_AREA, QD_OPEN — U в связях за тик, для замеров «было — стало»; 0 — без гашения.
 */

@JvmField var QD_LINK = DAMP_CLOSE_LINK
@JvmField var QD_AREA = DAMP_CLOSE_AREA
@JvmField var QD_OPEN = DAMP_OPEN

/**
 * Ключи физики для игры: её мир собирается с пустыми настройками, поэтому — запасной путь через свойство процесса, для
 * сравнений на глаз (`gradlew --no-daemon :lwjgl3:run "-Dgenomeia.physics=QD_LINK=0,QD_AREA=0,QD_OPEN=0"` — без гашения;
 * лаунчер прокидывает в игру все genomeia.*, как genomeia.substeps). В отчёт .bug эти ключи не попадают: отчёт, снятый с
 * ними, без них не повторится.
 */
private val GAME_KEYS: Map<String, String> = System.getProperty("genomeia.physics").orEmpty().split(',')
    .mapNotNull { val kv = it.split('='); if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null }.toMap()
private var gameKeysAnnounced = false

private fun key(c: PhysicsConfig, k: String): String? = c[k] ?: GAME_KEYS[k]

internal fun configureClosingDamping(c: PhysicsConfig) {
    if (GAME_KEYS.isNotEmpty() && !gameKeysAnnounced) {
        gameKeysAnnounced = true
        println("Ключи физики из genomeia.physics (не закон мира, только для сравнения): $GAME_KEYS")
    }
    QD_LINK = key(c, "QD_LINK")?.toDoubleOrNull() ?: DAMP_CLOSE_LINK
    QD_AREA = key(c, "QD_AREA")?.toDoubleOrNull() ?: DAMP_CLOSE_AREA
    QD_OPEN = key(c, "QD_OPEN")?.toDoubleOrNull() ?: DAMP_OPEN
    MX_OPEN = key(c, "MX_OPEN")?.toIntOrNull() ?: 0 // потолок разжатия мышцы (MuscleExperiment.kt): ключ и для игры
}

/** Есть ли фаза гашения: хоть один ключ. */
internal val closingPhaseOn: Boolean get() = QD_LINK > 0.0 || QD_AREA > 0.0 || QD_OPEN > 0.0

@Suppress("NOTHING_TO_INLINE")
private inline fun closingShare(x: Double): Double = if (x >= 1.0) 1.0 else x

/**
 * Фаза гашения одного списка блока или шва [bucket] — до интегрирования его частиц: px — положения начала подшага,
 * vx/vy — скорость, которой они сейчас будут предсказаны. Пишет только скорости частиц своего списка.
 */
internal fun BodyState.dampBucketVelocities(bucket: Int, layout: ConstraintBlocks) {
    val toLt = DT / linkScale
    val kClose = if (QD_LINK > 0.0) toLt / QD_LINK else 0.0
    val kShrink = if (QD_AREA > 0.0) toLt / QD_AREA else 0.0
    val kOpen = if (QD_OPEN > 0.0) toLt / QD_OPEN else 0.0
    val px = px; val py = py; val vx = vx; val vy = vy; val invMass = invMass
    if (kClose > 0.0 || kOpen > 0.0) {
        val a = conA; val b = conB; val dead = conDead
        val list = layout.links
        val from = list.start[bucket]; val to = from + list.count[bucket]
        for (slot in from until to) {
            val c = list.items[slot]
            if (c < 0 || dead[c]) continue
            val i = a[c]; val j = b[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var nx = px[i] - px[j]; var ny = py[i] - py[j]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-12) continue
            nx /= len; ny /= len
            // dv > 0 — пара расходится.
            val dv = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
            val f = if (dv < 0.0) closingShare(-dv * kClose) else closingShare(dv * kOpen)
            if (f == 0.0) continue
            val d = -dv * f
            val si = d * wi / w; val sj = d * wj / w
            vx[i] += nx * si; vy[i] += ny * si
            vx[j] -= nx * sj; vy[j] -= ny * sj
        }
    }
    if ((kShrink > 0.0 || kOpen > 0.0) && areasOn) {
        val ta = triA; val tb = triB; val tc = triC
        val alive = baked.triAlive; val tid = triId
        val list = layout.areas
        val from = list.start[bucket]; val to = from + list.count[bucket]
        for (slot in from until to) {
            val t = list.items[slot]
            if (t < 0 || !alive[tid[t]]) continue
            val i0 = ta[t]; val i1 = tb[t]; val i2 = tc[t]
            val x0 = px[i0]; val y0 = py[i0]; val x1 = px[i1]; val y1 = py[i1]; val x2 = px[i2]; val y2 = py[i2]
            // Градиенты удвоенной площади — как в solveBlockAreas.
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val rate = g0x * vx[i0] + g0y * vy[i0] + g1x * vx[i1] + g1y * vy[i1] + g2x * vx[i2] + g2y * vy[i2]
            if (rate == 0.0) continue
            val w0 = invMass[i0]; val w1 = invMass[i1]; val w2 = invMass[i2]
            val denom = w0 * (g0x * g0x + g0y * g0y) + w1 * (g1x * g1x + g1y * g1y) + w2 * (g2x * g2x + g2y * g2y)
            val gg = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-18 || gg < 1e-24) continue
            // Скорость сжатия или роста — вдоль градиента площади, в связях за тик (как в прототипе).
            val u = (if (rate < 0.0) -rate else rate) / sqrt(gg)
            val f = closingShare(u * (if (rate < 0.0) kShrink else kOpen))
            if (f == 0.0) continue
            val mu = -rate * f / denom
            vx[i0] += mu * w0 * g0x; vy[i0] += mu * w0 * g0y
            vx[i1] += mu * w1 * g1x; vy[i1] += mu * w1 * g1y
            vx[i2] += mu * w2 * g2x; vy[i2] += mu * w2 * g2y
        }
    }
}
