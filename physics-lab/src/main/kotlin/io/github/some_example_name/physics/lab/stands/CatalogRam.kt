package io.github.some_example_name.physics.lab.stands

import kotlin.math.hypot

/*
 * СЕМЕЙСТВО «ТАРАНЫ». В семействах фигур одно тело летит в стоящее и не быстрее шести связей за
 * тик. Здесь — то, чего там нет:
 *  - встречный удар: оба тела летят друг в друга, вплоть до потолка скорости у каждого
 *    (сближение — шестнадцать связей за тик, вдвое больше любой прежней сцены);
 *  - удар в растянутую ткань: мышцы одной стороны тела сокращены, другая сторона отпущена и
 *    растянута, и снаряд прилетает именно в неё. Растянутая мембрана — самое слабое место
 *    контура: её круги касания раздвинуты, и чужой клетке проще пройти между ними;
 *  - тиски: тело зажимают два тарана с двух сторон сразу.
 *
 * Правило семейства одно: что бы ни порвалось, чужая клетка не оказывается внутри ткани и не
 * проходит сквозь неё. Сколько вправе порваться — по скорости сближения (Limits.hit).
 */

private const val PULL = 0.5f
private const val REST = 1f
private const val LOOSE = 1.5f

/** Встречный удар: скорость каждого тела, связей за тик. */
private class Meet(val id: String, val title: String, val each: Double) {
    val limits: Limits get() = Limits.hit(2 * each)
    val rough: Boolean get() = 2 * each >= 3.0
    val ticks: Int get() = if (rough) CRASH_TICKS else settle(2 * each)
    val expect: String get() = when {
        2 * each >= Limits.WRECK_SPEED -> "Удар на пределе: ткань вправе порваться и потерять клетки. Но тела не проходят друг сквозь друга, " +
            "клетки не оказываются в чужой ткани, скорость не превышает потолка, энергия не берётся из ничего, движение обломков затухает."
        rough -> "Сильный встречный удар: порваться может до ${Math.round(Limits.damageShare(2 * each) * 100)}% связей. Тела не проходят друг сквозь друга, " +
            "клетки не оказываются в чужой ткани, энергия не берётся из ничего, движение затухает."
        else -> "Тела сталкиваются и разлетаются. Местный разрыв допустим — до ${Math.round(Limits.damageShare(2 * each) * 100)}% связей; " +
            "проникновений нет, энергия не берётся из ничего; к концу всё успокоилось."
    }
}

private val MEETS = listOf(
    Meet("each1", "встречный удар, каждое 1 связь за тик", 1.0),
    Meet("each2", "встречный таран, каждое 2 связи за тик", 2.0),
    Meet("each4", "встречный таран, каждое 4 связи за тик", 4.0),
    Meet("max", "встречный таран на потолке скорости, каждое 8 связей за тик", SPEED_CAP),
)

/** Тело с мышцами, у которого одни группы сокращаются, остальные отпущены; снаряд прилетает со стороны ([fromX], [fromY]). */
private class Stretched(val id: String, val name: String, val shape: ShapeSpec, val contract: Set<Int>, val fromX: Double, val fromY: Double, val about: String)

/** Чем бьют: тело (летит торцом, если [endOn]) или свободные частицы ([balls] штук в ряд). */
private class Projectile(val id: String, val name: String, val shape: ShapeSpec?, val endOn: Boolean = false, val balls: Int = 0)

internal fun ramFamily(): List<Scenario> {
    val family = Family("11. Тараны")

    // --- встречный удар одинаковых тел ---
    class Runner(val shape: ShapeSpec, val angle: Double = 0.0)
    val runners = listOf(
        Runner(Shapes.square(10)), Runner(Shapes.disc(6)), Runner(Shapes.rect(16, 4)), Runner(Shapes.star(5, 8, 3)), Runner(Shapes.ring(6, 3)),
        Runner(Shapes.ribbon(14), Math.PI / 2), Runner(Shapes.blob(2, 6)), Runner(Shapes.uShape(12, 9, 3)), Runner(Shapes.triangle(9)), Runner(Shapes.rope(12), Math.PI / 2),
        Runner(Shapes.square(10).boneCore()), Runner(Shapes.square(8).boneAll()), Runner(Shapes.rect(18, 6).bonePlates(3)), Runner(Shapes.disc(6).boneShell()),
    )
    for (r in runners) for (meet in MEETS) {
        val shape = r.shape
        val turned = if (r.angle != 0.0) " (плашмя)" else ""
        family.add("ram/meet/${shape.id}/${meet.id}", "${shape.name}$turned: ${meet.title}",
            "Два одинаковых тела летят друг в друга, каждое со скоростью ${meet.each} связи за тик: сближение — ${2 * meet.each} связи за тик.",
            meet.expect + (if (shape.boneCount > 0) " Кости не мнутся." else ""), meet.ticks, meet.limits) {
            val (a, b) = duelBoth(shape, shape, meet.each, angleA = r.angle, angleB = r.angle)
            expectMet(a, b, 60, 2 * meet.each)
        }
    }
    // Встречный удар со сдвигом: тела задевают друг друга половиной высоты.
    for (r in listOf(runners[0], runners[1], runners[3], runners[10])) for (meet in listOf(MEETS[1], MEETS[3])) {
        val shape = r.shape
        family.add("ram/meet/${shape.id}/${meet.id}-glance", "${shape.name}: ${meet.title}, вскользь",
            "Два одинаковых тела летят навстречу, каждое со скоростью ${meet.each} связи за тик; левое сдвинуто на полвысоты.",
            meet.expect, meet.ticks, meet.limits) {
            duelBoth(shape, shape, meet.each, offset = shape.halfHeight)
        }
    }
    // Разные тела навстречу: малое в большое, кость в мягкое, нить в круг.
    val pairs = listOf(
        Shapes.disc(3) to Shapes.square(12), Shapes.square(8).boneAll() to Shapes.square(10), Shapes.rope(12) to Shapes.disc(6),
        Shapes.rect(16, 4) to Shapes.ribbon(14), Shapes.disc(4) to Shapes.disc(12), Shapes.square(6).boneAll() to Shapes.ring(7, 4),
    )
    for ((a, b) in pairs) for (meet in MEETS) {
        family.add("ram/meet/${a.id}-vs-${b.id}/${meet.id}", "${a.name} и ${b.name}: ${meet.title}",
            "Тело «${a.name}» и тело «${b.name}» летят друг в друга, каждое со скоростью ${meet.each} связи за тик.",
            meet.expect, meet.ticks, meet.limits) {
            val (first, second) = duelBoth(a, b, meet.each)
            expectMet(first, second, 60, 2 * meet.each)
        }
    }

    // --- одно тело на потолке скорости в стоящее ---
    for (r in runners) {
        val shape = r.shape
        family.add("ram/max/${shape.id}", "${shape.name}: таран на потолке скорости в стоящее тело",
            "Левое тело летит в стоящее такое же со скоростью ${SPEED_CAP.toInt()} связей за тик — на самом потолке скорости решателя.",
            "Ткань вправе порваться и потерять клетки. Но тела не проходят друг сквозь друга, клетки не оказываются в чужой ткани, " +
                "скорость ни у кого не превышает потолка, энергия не берётся из ничего, движение обломков затухает.", CRASH_TICKS, Limits.CRASH) {
            val (a, b) = duel(shape, shape, SPEED_CAP, angleA = r.angle, angleB = r.angle)
            expectMet(a, b, 40, SPEED_CAP)
        }
    }

    // --- удар в ткань, растянутую собственной мышцей ---
    val targets = listOf(
        Stretched("worm20", "червь 20×6", Shapes.rect(20, 6).muscleTopBottom(), setOf(0), 0.0, -1.0, "верхняя половина сокращена, нижняя отпущена и растянута дугой"),
        Stretched("worm30", "червь 30×8", Shapes.rect(30, 8).muscleTopBottom(), setOf(0), 0.0, -1.0, "верхняя половина сокращена, нижняя отпущена и растянута дугой"),
        Stretched("disc7", "круг радиуса 7", Shapes.disc(7).muscleLeftRight(), setOf(0), 1.0, 0.0, "левая половина сокращена, правая отпущена и растянута"),
        Stretched("ring8", "кольцо 8/5", Shapes.ring(8, 5).muscleLeftRight(), setOf(0), 1.0, 0.0, "левая половина сокращена, правая отпущена и растянута"),
        Stretched("ellipse", "эллипс 10×5", Shapes.ellipse(10, 5).muscleTopBottom(), setOf(0), 0.0, -1.0, "верхняя половина сокращена, нижняя отпущена и растянута"),
        Stretched("square12", "квадрат 12", Shapes.square(12).muscleQuadrants(), setOf(0, 1), 0.0, -1.0, "две верхние четверти сокращены, две нижние отпущены и растянуты"),
        Stretched("blob", "клякса", Shapes.blob(2, 6).muscleTopBottom(), setOf(0), 0.0, -1.0, "верхняя половина сокращена, нижняя отпущена и растянута"),
        Stretched("spine", "брусок 24×7 с костным хребтом", Shapes.rect(24, 7).boneSpine().muscleTopBottom(), setOf(0), 0.0, -1.0,
            "мышцы над костным хребтом сокращены, под ним — отпущены и растянуты"),
        Stretched("loose", "червь 20×6, все мышцы отпущены", Shapes.rect(20, 6).muscleAll(), emptySet(), 0.0, -1.0, "всё тело расслаблено до предела податливости"),
        Stretched("tight", "червь 20×6, все мышцы сжаты", Shapes.rect(20, 6).muscleAll(), setOf(0), 0.0, -1.0, "всё тело сжато мышцами до предела"),
    )
    val shots = listOf(
        Projectile("disc3", "мягкий круг радиуса 3", Shapes.disc(3)),
        Projectile("brick", "костный кирпич", Shapes.square(5).boneAll()),
        Projectile("rod", "брусок 10×3 торцом", Shapes.rect(10, 3), endOn = true),
        Projectile("bullet", "свободная частица", null, balls = 1),
        Projectile("volley", "пять свободных частиц в ряд", null, balls = 5),
    )
    val kickAt = 45
    val holdUntil = kickAt + 200
    for (t in targets) for (shot in shots) for (hit in listOf(BUMP, HIT, RAM, CRASH, MAX_HIT)) {
        // Частицы — только на сильном ударе и на потолке скорости: от слабой частицы тело даже не вздрогнет.
        if (shot.balls > 0 && hit !== HIT && hit !== MAX_HIT) continue
        val ticks = holdUntil + if (hit.rough) CRASH_TICKS else settle(0.5)
        val base = if (hit.limits.tornShare > 0.0) hit.limits else Limits.INTACT
        val limits = base.copy(name = base.name + "; мышцы держат", energy = Energy.DRIVEN)
        family.add("ram/stretched/${t.id}/${shot.id}/${hit.id}", "${t.name}, растянут мышцей; бьёт ${shot.name}: ${hit.title}",
            "У тела ${t.about}. На тике $kickAt ${shot.name} летит в растянутую сторону со скоростью ${hit.speed} связи за тик. Мышцы держат до тика $holdUntil, затем отпускают.",
            "Растянутая мембрана держит удар: чужая клетка не оказывается внутри ткани и не проходит сквозь неё. " +
                (if (hit.limits.tornShare >= 1.0) "Ткань вправе порваться, но ничего не взрывается; движение затухает."
                else if (hit.limits.tornShare > 0.0) "Порваться может до ${Math.round(hit.limits.tornShare * 100)}% связей; мышцы сами свою ткань не рвут."
                else "Ничего не рвётся и не гибнет; после расслабления тело возвращает форму и засыпает.") +
                (if (t.shape.boneCount > 0) " Кости не мнутся." else ""), ticks, limits) {
            val target = body(t.shape, 0.0, 0.0)
            val groups = t.shape.muscleGroups
            at(5) { for (g in 0 until groups) muscle(target, g, if (g in t.contract) PULL else LOOSE) }
            at(holdUntil) { muscles(target, REST) }
            // Снаряд ждёт со стороны растянутой ткани: за краем тела, запасом на выгиб и тремя связями зазора.
            val edge = t.shape.extreme(t.fromX, t.fromY)
            val out = t.shape.x[edge] * t.fromX + t.shape.y[edge] * t.fromY
            val bulge = 0.5 * maxOf(t.shape.halfWidth, t.shape.halfHeight)
            val shape = shot.shape
            if (shape != null) {
                val d = out + bulge + shape.reach + START_GAP
                val p = body(shape, t.fromX * d, t.fromY * d, if (shot.endOn) Math.atan2(-t.fromY, -t.fromX) else 0.0)
                at(kickAt) {
                    val c = centerOf(target); val q = centerOf(p)
                    val len = hypot(c[0] - q[0], c[1] - q[1])
                    kick(p, hit.speed * (c[0] - q[0]) / len, hit.speed * (c[1] - q[1]) / len)
                }
                expectMet(p, target, holdUntil, hit.speed)
            } else {
                val d = out + bulge + START_GAP + 1.0
                at(kickAt) {
                    val c = centerOf(target)
                    for (k in 0 until shot.balls) {
                        // Ряд частиц поперёк направления полёта, с шагом чуть больше расстояния касания.
                        val side = (k - (shot.balls - 1) / 2.0) * 0.66
                        val x = t.fromX * d - t.fromY * side; val y = t.fromY * d + t.fromX * side
                        val len = hypot(c[0] - t.fromX * d, c[1] - t.fromY * d)
                        ball(x, y, hit.speed * (c[0] - t.fromX * d) / len, hit.speed * (c[1] - t.fromY * d) / len)
                    }
                }
            }
        }
    }

    // --- тиски: два тарана с двух сторон сразу ---
    val squeezed = listOf(Shapes.square(10), Shapes.disc(6), Shapes.ring(6, 3), Shapes.square(10).boneCore(), Shapes.rect(6, 12), Shapes.star(5, 8, 3))
    val jaws = listOf(Shapes.square(8), Shapes.square(6).boneAll())
    for (shape in squeezed) for (jaw in jaws) for (hit in listOf(BUMP, HIT, RAM, MAX_HIT)) {
        val ticks = if (hit.rough) CRASH_TICKS else settle(hit.speed)
        family.add("ram/vise/${shape.id}/${jaw.id}/${hit.id}", "${shape.name} в тисках: два тела «${jaw.name}» с двух сторон, ${hit.title}",
            "Тело стоит посередине; слева и справа в него одновременно летят два тела «${jaw.name}» со скоростью ${hit.speed} связи за тик каждое.",
            "Тело сдавливается с двух сторон сразу и не может уйти от удара. Клетки не оказываются в чужой ткани и не проходят сквозь неё. " + hit.expect, ticks, hit.limits) {
            val middle = body(shape, 0.0, 0.0)
            val apart = separation(jaw, shape, CONTACT_DISTANCE) + START_GAP
            val left = body(jaw, -apart, 0.0)
            val right = body(jaw, apart, 0.0)
            kick(left, hit.speed, 0.0)
            kick(right, -hit.speed, 0.0)
            expectMet(left, middle, 60, hit.speed, "зазор слева, связей")
            expectMet(right, middle, 60, hit.speed, "зазор справа, связей")
        }
    }

    // --- поезд: три тела одно за другим в стоящее ---
    for (hit in listOf(HIT, RAM, MAX_HIT)) for (shape in listOf(Shapes.square(8), Shapes.disc(5), Shapes.square(8).boneAll())) {
        family.add("ram/train/${shape.id}/${hit.id}", "${shape.name}: три тела подряд в стоящее, ${hit.title}",
            "Три одинаковых тела летят гуськом со скоростью ${hit.speed} связи за тик в четвёртое, стоящее.",
            "Удары приходят один за другим, каждый следующий — в уже смятую ткань. Клетки не оказываются в чужой ткани. " + hit.expect,
            if (hit.rough) CRASH_TICKS else settle(hit.speed) + 100, hit.limits) {
            val step = separation(shape, shape, CONTACT_DISTANCE) + START_GAP
            val standing = body(shape, 0.0, 0.0)
            val wagons = (1..3).map { body(shape, -it * step, 0.0) }
            for (w in wagons) kick(w, hit.speed, 0.0)
            expectMet(wagons[0], standing, 60, hit.speed)
        }
    }
    return family.list
}
