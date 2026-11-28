package io.github.some_example_name.physics.lab.stands

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * БИБЛИОТЕКА ФИГУР. Размеры — в связях (0.6 единицы игры): «квадрат 8» — восемь клеток по
 * стороне. Каждая фигура — предикат «точка внутри» на решётке редактора, поэтому любую из них
 * можно собрать и в редакторе генома.
 *
 * Три слоя: простые фигуры, фигуры с вогнутостями и рваным контуром, и раскладки костей и
 * мышц поверх любой фигуры. Сценарии берут фигуры отсюда, а не рисуют свои: так одна и та же
 * «звезда» проходит покой, удары, рельеф, мышцы и рост.
 */
object Shapes {
    private const val L = GAME_LINK
    private const val E = 1e-6

    // --- простые ---

    fun square(n: Int) = Lattice.shape("square$n", "квадрат $n", n * L) { x, y ->
        abs(x) <= (n - 1) * L / 2 + E && abs(y) <= (n - 1) * L / 2 + E
    }

    fun disc(r: Int) = Lattice.shape("disc$r", "круг радиуса $r", (r + 1) * L) { x, y -> x * x + y * y <= r * L * r * L + E }

    fun rect(cols: Int, rows: Int) = Lattice.grid("rect${cols}x$rows", "прямоугольник $cols×$rows", cols, rows)

    /** Цепочка клеток без единого треугольника: её держат только связи. */
    fun rope(n: Int) = Lattice.grid("rope$n", "нить из $n клеток", n, 1)

    /** Самая тонкая ткань с треугольниками: два ряда. */
    fun ribbon(n: Int) = Lattice.grid("ribbon$n", "лента 2×$n", n, 2)

    /** Равносторонний треугольник со стороной [side] связей; сторона кратна трём — основание ложится на ряд решётки. */
    fun triangle(side: Int): ShapeSpec {
        val h = side * L * sqrt(3.0) / 2
        return Lattice.shape("tri$side", "треугольник со стороной $side", side * L) { x, y ->
            y >= -h / 3 - E && y <= 2 * h / 3 - sqrt(3.0) * abs(x) + E
        }
    }

    fun ellipse(a: Int, b: Int) = Lattice.shape("ellipse${a}x$b", "эллипс $a×$b", (max(a, b) + 1) * L) { x, y ->
        (x / (a * L)).pow(2) + (y / (b * L)).pow(2) <= 1.0 + E
    }

    fun ring(outer: Int, inner: Int) = Lattice.shape("ring${outer}_$inner", "кольцо $outer/$inner", (outer + 1) * L) { x, y ->
        val d2 = x * x + y * y
        d2 <= outer * L * outer * L + E && d2 >= inner * L * inner * L - E
    }

    // --- с вогнутостями ---

    /** Буква Г: квадрат [n] без правого верхнего угла, полки толщиной [t]. */
    fun lShape(n: Int, t: Int): ShapeSpec {
        val a = (n - 1) * L / 2
        return Lattice.shape("L${n}_$t", "уголок $n с полкой $t", n * L) { x, y ->
            abs(x) <= a + E && abs(y) <= a + E && (x <= -a + (t - 1) * L + E || y <= -a + (t - 1) * L + E)
        }
    }

    /** Подкова: прямоугольник [w] × [h] связей с вырезом сверху, стенки толщиной [t]. */
    fun uShape(w: Int, h: Int, t: Int): ShapeSpec {
        val a = (w - 1) * L / 2; val b = (h - 1) * L / 2
        return Lattice.shape("U${w}x${h}_$t", "подкова $w×$h со стенкой $t", max(w, h) * L) { x, y ->
            abs(x) <= a + E && abs(y) <= b + E && !(abs(x) < a - (t - 1) * L - E && y > -b + (t - 1) * L + E)
        }
    }

    /** Кольцо с разрезом справа на [gap] радиан: буква С. */
    fun cShape(outer: Int, inner: Int, gap: Double = 1.1) = Lattice.shape("C${outer}_$inner", "разомкнутое кольцо $outer/$inner", (outer + 1) * L) { x, y ->
        val d2 = x * x + y * y
        d2 <= outer * L * outer * L + E && d2 >= inner * L * inner * L - E && abs(atan2(y, x)) > gap / 2
    }

    fun cross(arm: Int, thick: Int): ShapeSpec {
        val a = arm * L; val t = (thick - 1) * L / 2
        return Lattice.shape("cross${arm}_$thick", "крест с плечом $arm и толщиной $thick", (arm + 1) * L) { x, y ->
            (abs(x) <= a + E && abs(y) <= t + E) || (abs(y) <= a + E && abs(x) <= t + E)
        }
    }

    /** Звезда: [points] лучей до [outer], впадины до [inner]. */
    fun star(points: Int, outer: Int, inner: Int) = Lattice.shape("star${points}_$outer", "звезда на $points лучей", (outer + 1) * L) { x, y ->
        val d = sqrt(x * x + y * y)
        val wave = (0.5 + 0.5 * cos(points * atan2(y, x))).pow(2.0)
        d <= (inner + (outer - inner) * wave) * L + E
    }

    /** Гребёнка: основание и [teeth] зубцов вверх шириной [toothW], высотой [toothH], через промежуток той же ширины. */
    fun comb(teeth: Int, toothW: Int, toothH: Int, baseH: Int): ShapeSpec {
        val period = 2 * toothW * L
        val width = (2 * teeth - 1) * toothW * L
        val base = baseH * GAME_ROW
        val top = (baseH + toothH) * GAME_ROW
        return Lattice.shape("comb${teeth}_$toothW", "гребёнка на $teeth зубцов", width + top) { x, y ->
            val u = x + width / 2
            val v = y + top / 2
            if (u < -E || u > width + E || v < -E || v > top + E) false
            else v <= base + E || (u % period) <= toothW * L + E
        }
    }

    /** Гантель: два круга радиуса [r] на перемычке длиной [bridge] и шириной [thick]. */
    fun dumbbell(r: Int, bridge: Int, thick: Int): ShapeSpec {
        val c = (bridge / 2.0 + r) * L
        return Lattice.shape("dumbbell${r}_$bridge", "гантель: круги $r на перемычке $bridge", c + (r + 1) * L) { x, y ->
            val left = (x + c) * (x + c) + y * y <= r * L * r * L + E
            val right = (x - c) * (x - c) + y * y <= r * L * r * L + E
            left || right || (abs(x) <= c && abs(y) <= (thick - 1) * L / 2 + E)
        }
    }

    /** Полумесяц: круг [r] без сдвинутого вправо круга поменьше. */
    fun crescent(r: Int) = Lattice.shape("crescent$r", "полумесяц радиуса $r", (r + 1) * L) { x, y ->
        val big = x * x + y * y <= r * L * r * L + E
        val cut = (x - 0.45 * r * L).pow(2) + y * y <= (0.78 * r * L).pow(2)
        big && !cut
    }

    /** Рамка: квадрат [n] с квадратной дырой, стенка толщиной [t]. */
    fun frame(n: Int, t: Int): ShapeSpec {
        val a = (n - 1) * L / 2
        return Lattice.shape("frame${n}_$t", "рамка $n со стенкой $t", n * L) { x, y ->
            abs(x) <= a + E && abs(y) <= a + E && (abs(x) >= a - (t - 1) * L - E || abs(y) >= a - (t - 1) * L - E)
        }
    }

    /** Шестерёнка: круг [r] с [teeth] зубцами высотой в две связи. */
    fun gear(r: Int, teeth: Int) = Lattice.shape("gear${r}_$teeth", "шестерёнка радиуса $r на $teeth зубцов", (r + 3) * L) { x, y ->
        val d = sqrt(x * x + y * y)
        val tooth = cos(teeth * atan2(y, x)) > 0.2
        d <= (if (tooth) r + 2 else r) * L + E
    }

    /** Песочные часы: прямоугольник [w] × [h] с перехватом шириной [neck] посередине. */
    fun hourglass(w: Int, h: Int, neck: Int): ShapeSpec {
        val a = (w - 1) * L / 2; val b = (h - 1) * L / 2
        return Lattice.shape("hourglass${w}x$h", "песочные часы $w×$h с перехватом $neck", max(w, h) * L) { x, y ->
            val half = (neck - 1) * L / 2 + (a - (neck - 1) * L / 2) * abs(y) / b
            abs(y) <= b + E && abs(x) <= half + E
        }
    }

    /**
     * Змейка: полоса толщиной [thick] вдоль синусоиды длиной [len] и размахом [amp]. Толщина
     * меряется поперёк волны, а не по вертикали: иначе на крутом склоне полоса сужается до
     * одной общей клетки, и вместо змейки выходит цепочка кусков на шарнирах (15.11).
     */
    fun snake(len: Int, amp: Int, thick: Int) = Lattice.shape("snake${len}_$amp", "змейка длиной $len", (len / 2 + amp + thick + 1) * L) { x, y ->
        val k = 2 * PI / (len * L / 1.5)
        val mid = amp * L * sin(k * x)
        val slope = amp * L * k * cos(k * x)
        abs(x) <= len * L / 2 + E && abs(y - mid) <= thick * L / 2 * sqrt(1 + slope * slope) + E
    }

    /** Клякса: круг [r] с контуром из случайных гармоник — плавные выступы и впадины. */
    fun blob(seed: Long, r: Int): ShapeSpec {
        val rnd = Random(seed)
        val amp = DoubleArray(5) { 0.07 + 0.14 * rnd.nextDouble() }
        val phase = DoubleArray(5) { 2 * PI * rnd.nextDouble() }
        return Lattice.shape("blob${seed}_$r", "клякса №$seed радиуса $r", (r * 1.9 + 1) * L) { x, y ->
            val th = atan2(y, x)
            var k = 1.0
            for (h in amp.indices) k += amp[h] * cos((h + 2) * th + phase[h])
            sqrt(x * x + y * y) <= r * L * k + E
        }
    }

    /** Коралл: цепочка из [parts] случайных кругов — рваный контур с глубокими впадинами. */
    fun coral(seed: Long, parts: Int): ShapeSpec {
        val rnd = Random(seed)
        val cx = DoubleArray(parts); val cy = DoubleArray(parts); val cr = DoubleArray(parts)
        var x0 = 0.0; var y0 = 0.0
        for (k in 0 until parts) {
            cr[k] = (1.6 + 1.6 * rnd.nextDouble()) * L
            cx[k] = x0; cy[k] = y0
            val a = 2 * PI * rnd.nextDouble()
            val step = cr[k] * 1.25
            x0 += step * cos(a); y0 += step * sin(a)
        }
        val mx = (cx.min() + cx.max()) / 2; val my = (cy.min() + cy.max()) / 2
        val reach = (0 until parts).maxOf { sqrt((cx[it] - mx).pow(2) + (cy[it] - my).pow(2)) + cr[it] } + L
        return Lattice.shape("coral${seed}_$parts", "коралл №$seed из $parts кругов", reach) { x, y ->
            (0 until parts).any { (x + mx - cx[it]).pow(2) + (y + my - cy[it]).pow(2) <= cr[it] * cr[it] + E }
        }
    }
}

// --- раскладки костей: поверх любой фигуры ---

/** Костное ядро в середине, мягкая ткань вокруг. */
fun ShapeSpec.boneCore(share: Double = 0.5) = withBones("core", "костное ядро") { x, y, _ ->
    val r = share * min(halfWidth, halfHeight)
    x * x + y * y <= r * r
}

/** Костный хребет вдоль длинной стороны: три ряда клеток по оси. */
fun ShapeSpec.boneSpine() = withBones("spine", "костный хребет") { x, y, _ ->
    if (halfWidth >= halfHeight) abs(y) <= GAME_ROW * 1.1 && abs(x) <= halfWidth * 0.85
    else abs(x) <= GAME_LINK * 1.1 && abs(y) <= halfHeight * 0.85
}

/** Костная оболочка: край фигуры — кость, внутри мягко. */
fun ShapeSpec.boneShell() = withBones("shell", "костная оболочка") { _, _, k -> degree(k) < 6 }

/** Левая половина — кость, правая — мягкая ткань. */
fun ShapeSpec.boneLeft() = withBones("half", "левая половина — кость") { x, _, _ -> x < -1e-6 }

/** Две костные пластины по краям и мягкий сустав шириной [joint] связей между ними. */
fun ShapeSpec.bonePlates(joint: Int = 3) = withBones("plates", "две костные пластины с суставом") { x, _, _ ->
    abs(x) > joint * GAME_LINK / 2
}

/** Целиком кость: «кирпич». */
fun ShapeSpec.boneAll() = withBones("brick", "целиком кость") { _, _, _ -> true }

/** Несколько случайных костных кусков по [size] клеток: блуждание по связям. */
fun ShapeSpec.boneScattered(seed: Long, clusters: Int, size: Int): ShapeSpec {
    val rnd = Random(seed)
    val mask = BooleanArray(count)
    repeat(clusters) {
        var cur = rnd.nextInt(count)
        var grown = 0; var guard = 0
        while (grown < size && guard++ < size * 20) {
            if (!mask[cur]) { mask[cur] = true; grown++ }
            val d = degree(cur)
            if (d == 0) break
            cur = adj[adjStart[cur] + rnd.nextInt(d)]
        }
    }
    return withBones("bits$seed", "костные куски №$seed") { _, _, k -> mask[k] }
}

// --- раскладки мышц ---

/** Вся ткань — одна мышца. */
fun ShapeSpec.muscleAll() = withMuscles("mAll", "целиком мышца") { _, _, _ -> 0 }

/** Верхняя половина — группа 0, нижняя — группа 1: сгибают тело поперёк длины. */
fun ShapeSpec.muscleTopBottom() = withMuscles("mTB", "мышцы сверху и снизу") { _, y, _ -> if (y >= 0) 0 else 1 }

/** Левая половина — группа 0, правая — группа 1. */
fun ShapeSpec.muscleLeftRight() = withMuscles("mLR", "мышцы слева и справа") { x, _, _ -> if (x < 0) 0 else 1 }

/** [n] сегментов вдоль длины, группа — номер сегмента: бегущая волна. */
fun ShapeSpec.muscleSegments(n: Int) = withMuscles("mSeg$n", "$n мышечных сегментов") { x, _, _ ->
    (((x + halfWidth) / (2 * halfWidth + 1e-9)) * n).toInt().coerceIn(0, n - 1)
}

/** Мышечная полоса по оси на долю [share] высоты, остальное — обычная ткань. */
fun ShapeSpec.muscleBand(share: Double = 0.4) = withMuscles("mBand", "мышечная полоса по оси") { _, y, _ ->
    if (abs(y) <= share * halfHeight) 0 else -1
}

/** Мышечный ободок: край фигуры и следующий за ним слой. */
fun ShapeSpec.muscleRim(): ShapeSpec {
    val edge = BooleanArray(count) { degree(it) < 6 }
    val near = BooleanArray(count) { k -> edge[k] || (adjStart[k] until adjStart[k + 1]).any { edge[adj[it]] } }
    return withMuscles("mRim", "мышечный ободок") { _, _, k -> if (near[k]) 0 else -1 }
}

/** Четыре четверти — четыре группы. */
fun ShapeSpec.muscleQuadrants() = withMuscles("mQuad", "мышцы по четвертям") { x, y, _ ->
    (if (x >= 0) 0 else 1) + (if (y >= 0) 0 else 2)
}

/** [arms] секторов дальше [inner] связей от центра — по группе на сектор (лучи звезды, плечи креста). */
fun ShapeSpec.muscleArms(arms: Int, inner: Double) = withMuscles("mArms$arms", "мышцы в $arms лучах") { x, y, _ ->
    if (sqrt(x * x + y * y) < inner * GAME_LINK) -1
    else (((atan2(y, x) + PI + PI / arms) / (2 * PI)) * arms).toInt().mod(arms)
}
