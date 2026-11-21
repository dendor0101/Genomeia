package io.github.some_example_name.physics.lab.stands

import kotlin.math.abs
import kotlin.math.hypot

/*
 * СЕМЕЙСТВО «ГИДРОДИНАМИКА И КУСКИ ТЕЛ».
 *
 * Среда в решателе — не сетка, а запас на каждый остров связей (BodyState.flowVX/flowVY): тело
 * увлекает свою воду, тормозится о неё контуром и гребёт ею. Отсюда два вида ошибок, которые
 * общие правила не ловят и ради которых семейство заведено:
 *  - запас одного острова влияет на другой: тело едет или тормозит оттого, что рядом (или на
 *    другом конце мира) движется чужое. Так уже было, когда запас был один на весь мир;
 *  - тело разделилось на куски (разрыв, гибель клеток), и куски делят один запас или получают
 *    его неверно: стоящий кусок трогается сам, летящий — рывком меняет скорость, появляется
 *    дрожь.
 *
 * Поэтому здесь проверяется то, чего в других семействах нет: тело, которого не трогали,
 * стоит на месте с точностью до сотых связи; соседство не меняет путь тела; куски после
 * разреза ведут себя как отдельные тела. Дрожь ловит общее правило «нет дрожи».
 */

/** Насколько вправе сдвинуться тело, которого никто не трогал и которое ничего не касается, связей. */
private const val UNTOUCHED = 0.02

/** Проба «тело ушло от старта, связей» и ожидание, что нетронутое тело осталось на месте. */
private fun StandWorld.expectUntouched(b: StandBody, until: Int, name: String = "нетронутое тело ушло, связей") =
    expectStill(name, b, IntArray(b.count) { it }, 0, until, UNTOUCHED)

/** Путь тела от старта вдоль ([ux], [uy]) и поперёк, связей. */
private fun StandWorld.probePath(b: StandBody, ux: Double, uy: Double, along: String, across: String) {
    val start = centerOf(b)
    probe(along) { val c = centerOf(b); ((c[0] - start[0]) * ux + (c[1] - start[1]) * uy) / GAME_LINK }
    probe(across) { val c = centerOf(b); abs((c[0] - start[0]) * uy - (c[1] - start[1]) * ux) / GAME_LINK }
}

internal fun hydroFamily(): List<Scenario> {
    val family = Family("12. Гидродинамика и куски тел")
    val square = Shapes.square(8)
    val disc = Shapes.disc(5)

    // --- накат: тело после толчка тормозится само и не сворачивает ---
    // symmetric — фигура зеркальна относительно линии хода: только от такой можно требовать, чтобы её не уводило в сторону.
    // Брусок с чётным числом рядов на шестиугольной решётке не зеркален (ряды сдвинуты через один), и среда законно
    // сносит его вбок: замер 15.11 — брусок 16×4 на скорости 2 уходит на 24 связи при пути в 257.
    class Glider(val shape: ShapeSpec, val angle: Double, val id: String, val name: String, val symmetric: Boolean = true)
    val gliders = listOf(
        Glider(square, 0.0, "square8", "квадрат 8"), Glider(disc, 0.0, "disc5", "круг радиуса 5"),
        Glider(Shapes.rect(16, 5), 0.0, "rod-along", "брусок 16×5 вдоль хода"), Glider(Shapes.rect(16, 5), Math.PI / 2, "rod-across", "брусок 16×5 поперёк хода", symmetric = false),
        Glider(Shapes.ring(6, 3), 0.0, "ring", "кольцо 6/3"), Glider(Shapes.square(8).boneAll(), 0.0, "brick", "костный кирпич"),
        Glider(Shapes.ribbon(14), Math.PI / 2, "ribbon-across", "лента 2×14 поперёк хода", symmetric = false), Glider(Shapes.rope(12), 0.0, "rope-along", "нить вдоль хода"),
        Glider(Shapes.rect(16, 4), 0.0, "rod-skew", "брусок 16×4 вдоль хода (не зеркален)", symmetric = false),
    )
    for (g in gliders) for (speed in listOf(0.6, 2.0)) {
        val slow = !g.shape.hasTissue
        val ticks = settle(speed, slow)
        family.add("hydro/glide/${g.id}/v$speed", "${g.name}: накат после толчка $speed",
            "Одно тело в пустом мире получает скорость $speed связи за тик вправо. Больше его никто не трогает.",
            "Тело плавно тормозится о среду и останавливается: скорость только падает, оно не дрожит; к концу стоит и спит. " +
                (if (g.symmetric) "Фигура зеркальна относительно линии хода, поэтому в сторону её не уводит: снос меньше связи. В вариациях со " +
                    "случайными длинами связей и радиусами она уже не зеркальна — там снос вправе быть до пятой части пути."
                else "Фигура не зеркальна относительно линии хода, и среда вправе снести её вбок — но не больше чем на пятую часть пути."), ticks, Limits.INTACT) {
            val b = body(g.shape, 0.0, 0.0, g.angle)
            kick(b, speed, 0.0)
            probePath(b, 1.0, 0.0, "путь вдоль хода, связей", "снос вбок, связей")
            probe("скорость тела") { speedOf(b) }
            // Зеркальность — свойство ровной решётки: случайные сдвиги клеток и разные массы её ломают (Variation.uneven).
            if (g.symmetric && !Variation.current.uneven) expectMaxAtMost("тело не увело в сторону", "снос вбок, связей", 1, ticks, 1.0)
            else {
                val start = centerOf(b)
                probe("снос вбок к пути") { val c = centerOf(b); val along = c[0] - start[0]; if (along < 3 * GAME_LINK) 0.0 else abs(c[1] - start[1]) / along }
                expectMaxAtMost("снос вбок не больше пятой части пути", "снос вбок к пути", 1, ticks, 0.2)
            }
            expectMaxAtMost("тело не разгоняется само", "скорость тела", 1, ticks, speed * 1.02)
            expectMaxAtLeast("тело проехало по инерции", "путь вдоль хода, связей", 1, ticks, speed * 5)
        }
    }
    for (g in listOf(gliders[0], gliders[1], gliders[2], gliders[5], gliders[8])) {
        family.add("hydro/spin/${g.id}", "${g.name}: вращение на месте затухает",
            "Одно тело получает вращение 0.05 радиана за тик вокруг своего центра, без поступательной скорости.",
            "Тело вращается всё медленнее и останавливается; его центр остаётся на месте (уходит меньше чем на двадцатую часть поперечника тела), ничего не рвётся; к концу стоит и спит.",
            settle(1.0), Limits.INTACT) {
            val b = body(g.shape, 0.0, 0.0)
            kick(b, 0.0, 0.0, spin = 0.05)
            // Именно центр, а не каждая клетка (expectStill): клетки вращающегося тела законно ходят по кругу.
            // 15.11 здесь стояло expectStill, и все пять сцен краснели во всех вариациях по вине стенда.
            val start = centerOf(b)
            mustTurn(b, 1.0, "тело повернулось хотя бы на радиан")
            probe("центр тела ушёл, связей") { val c = centerOf(b); Math.hypot(c[0] - start[0], c[1] - start[1]) / GAME_LINK }
            // Порог — двадцатая часть поперечника: тело не уплывает от одного вращения. Ровно на месте центр не стоит и
            // стоять не обязан — среда толкает несимметричное тело; замер 15.11: квадрат и круг уходят на 0.1–0.3 связи,
            // брусок 16×5 (ряды решётки сдвинуты через один) — на 0.5–0.9, тела со случайными длинами и радиусами — до 1.
            expectMaxAtMost("центр тела остался на месте", "центр тела ушёл, связей", 1, settle(1.0), 0.05 * 2 * g.shape.reach / GAME_LINK)
        }
    }

    // --- чужое движение на расстоянии: стоящее тело не трогается ---
    for ((id, gap, name) in listOf(Triple("near", 2.0, "в двух единицах"), Triple("mid", 8.0, "в восьми единицах"), Triple("far", 60.0, "в шестидесяти единицах"))) {
        for (speed in listOf(0.6, 4.0)) {
            family.add("hydro/pass/$id/v$speed", "тело пролетает мимо стоящего $name, скорость $speed",
                "Квадрат летит вправо со скоростью $speed связи за тик мимо стоящего круга; между их краями $gap единицы, тела не касаются.",
                "Стоящий круг не трогается с места: чужое движение через среду на него не действует. Он остаётся в сотых долях связи от старта и засыпает.",
                settle(speed), Limits.INTACT) {
                val flyer = body(square, -(square.reach + START_GAP + 6.0), 0.0)
                val stander = body(disc, 0.0, -(square.halfHeight + disc.reach + gap))
                kick(flyer, speed, 0.0)
                mustMove(flyer, 3.0, "летящее тело пролетело мимо стоящего")
                mustNotTouch(flyer, stander, "летящее тело задело стоящее")
                expectUntouched(stander, settle(speed))
                probe("зазор между телами, единиц") { clearance(flyer, stander) }
                expectMinAtLeast("тела не коснулись", "зазор между телами, единиц", 1, settle(speed), 0.3)
            }
        }
    }

    // --- соседство не меняет сопротивление: одно тело едет так же, как два рядом ---
    for (speed in listOf(0.6, 2.0)) {
        family.add("hydro/twins/v$speed", "два тела рядом едут так же, как одно вдали, скорость $speed",
            "Три одинаковых квадрата получают одну и ту же скорость $speed связи за тик вправо: два идут бок о бок с просветом в единицу, третий — в ста единицах от них.",
            "Все три проезжают одинаковый путь (различие меньше 5%): сосед не помогает ехать и не тормозит. Никто не дрожит; к концу все стоят и спят.",
            settle(speed), Limits.INTACT) {
            val step = 2 * square.halfHeight + 2 * square.contact.max() + 1.0
            val a = body(square, 0.0, 0.0); val b = body(square, 0.0, step); val alone = body(square, 0.0, -100.0)
            for (x in listOf(a, b, alone)) { kick(x, speed, 0.0); mustMove(x, 2.0, "тело проехало хотя бы две связи") }
            mustNotTouch(a, b, "тела, едущие рядом, коснулись")
            val sa = centerOf(a); val sl = centerOf(alone)
            probe("путь тела с соседом к пути одиночки") {
                val pa = centerOf(a)[0] - sa[0]; val pl = centerOf(alone)[0] - sl[0]
                if (pl < 0.3) 1.0 else pa / pl
            }
            expectMaxAtMost("сосед не помогает ехать", "путь тела с соседом к пути одиночки", 20, settle(speed), 1.05)
            expectMinAtLeast("сосед не тормозит", "путь тела с соседом к пути одиночки", 20, settle(speed), 0.95)
            view = doubleArrayOf(-square.reach - 2.0, -square.reach - 3.0, square.reach + 30.0, step + square.reach + 3.0)
        }
    }

    // --- пловцы: мышцы гребут, тело плывёт; сосед остаётся на месте ---
    class Swimmer(val id: String, val name: String, val shape: ShapeSpec, val drive: Drive, val least: Double)
    val swimmers = listOf(
        Swimmer("worm-wave", "червь 24×4, бегущая волна", Shapes.rect(24, 4).muscleSegments(6), WAVE, 0.3),
        Swimmer("worm-swap", "червь 24×4, верх и низ попеременно", Shapes.rect(24, 4).muscleTopBottom(), SWAP_FAST, 0.1),
        Swimmer("snake-wave", "змейка, бегущая волна", Shapes.snake(22, 3, 3).muscleSegments(8), WAVE, 0.3),
        Swimmer("fish", "рыба с хребтом, бока попеременно", fishShape(), SWAP_FAST, 0.1),
        Swimmer("tadpole", "головастик, хвост попеременно", tadpoleShape(), SWAP_FAST, 0.1),
        Swimmer("ribbon-wave", "лента 2×20, бегущая волна", Shapes.ribbon(20).muscleSegments(5), WAVE, 0.2),
    )
    for (s in swimmers) {
        val ticks = s.drive.active + settle(0.5)
        family.add("hydro/swim/${s.id}", "${s.name}: плывёт и останавливается",
            "Тело гребёт мышцами ${s.drive.active} тиков (${s.drive.about.replaceFirstChar { it.lowercase() }}), затем мышцы отпускают.",
            "Тело сдвигается с места своей греблей хотя бы на ${s.least} связи, не дрожит и не рвёт себя; после расслабления останавливается, возвращает форму и засыпает.",
            ticks, Limits.DRIVEN) {
            val b = body(s.shape, 0.0, 0.0)
            s.drive.script(this, b)
            expectTravelled("тело уплыло от старта, связей", b, IntArray(b.count) { it }, 0, ticks, s.least, "гребля сдвинула тело")
        }
        family.add("hydro/swim/${s.id}/neighbour", "${s.name}: рядом стоит чужое тело",
            "То же тело гребёт мышцами; в трёх единицах от него стоит круг, которого никто не трогает и не касается.",
            "Круг остаётся на месте (в сотых долях связи от старта): гребля соседа через среду на него не действует.",
            ticks, Limits.DRIVEN) {
            val b = body(s.shape, 0.0, 0.0)
            val stander = body(disc, 0.0, -(s.shape.halfHeight + disc.reach + 3.0 + 0.5 * s.shape.halfWidth))
            s.drive.script(this, b)
            mustDeform(b, 0.05, "мышцы пловца изменили его форму")
            mustNotTouch(b, stander, "пловец задел стоящее тело")
            expectUntouched(stander, ticks)
            probe("зазор между телами, единиц") { clearance(b, stander) }
            expectMinAtLeast("тела не коснулись", "зазор между телами, единиц", 1, ticks, 0.3)
        }
    }

    // --- куски: тело разделили, и каждый кусок живёт сам ---
    class Cut(val id: String, val name: String, val shape: ShapeSpec)
    val bodies = listOf(
        Cut("rect16x6", "брусок 16×6", Shapes.rect(16, 6)), Cut("square10", "квадрат 10", Shapes.square(10)), Cut("disc6", "круг радиуса 6", Shapes.disc(6)),
        Cut("ring", "кольцо 7/4", Shapes.ring(7, 4)), Cut("ribbon", "лента 2×20", Shapes.ribbon(20)), Cut("spine", "брусок 18×6 с костным хребтом", Shapes.rect(18, 6).boneSpine()),
    )
    val cutAt = 20
    for (c in bodies) {
        val shape = c.shape
        // Разрез по вертикали через середину: левая и правая половины.
        val left = sideCells(shape, -1.0, 0.0); val right = (0 until shape.count).filter { it !in left.toHashSet() }.toIntArray()
        val leftSet = left.toHashSet()
        fun StandWorld.slice(b: StandBody) = cut(b) { a, d -> (a in leftSet) != (d in leftSet) }
        // Полоса клеток посередине, которая гибнет: между половинами остаётся просвет.
        val strip = (0 until shape.count).filter { abs(shape.lx[it]) < 0.75 * GAME_LINK }.toIntArray()
        val stripSet = strip.toHashSet()
        val farLeft = (0 until shape.count).filter { it !in stripSet && shape.lx[it] < 0 }.toIntArray()
        val farRight = (0 until shape.count).filter { it !in stripSet && shape.lx[it] > 0 }.toIntArray()

        family.add("hydro/cut/${c.id}/rest", "${c.name}: разрезали пополам в покое",
            "Тело стоит; на тике $cutAt все связи между левой и правой половиной разрезаются, клетки остаются живы. Половины стоят вплотную.",
            "Обе половины остаются на месте (не дальше трети связи: вплотную стоящие куски вправе чуть раздвинуться до расстояния касания), не дрожат и засыпают.",
            cutAt + settle(0.1), Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { slice(b) }
            expectStill("левая половина ушла, связей", b, left, cutAt, cutAt + settle(0.1), 0.34)
            expectStill("правая половина ушла, связей", b, right, cutAt, cutAt + settle(0.1), 0.34)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMaxAtLeast("тело разделилось", "кусков тела", cutAt + 1, cutAt + 30, 2.0)
        }
        family.add("hydro/cut/${c.id}/gap", "${c.name}: погибла полоса клеток посередине",
            "Тело стоит; на тике $cutAt гибнет полоса клеток посередине, тело распадается на две половины с просветом между ними.",
            "Половины не касаются, и каждая остаётся ровно там, где была (в сотых долях связи), не дрожит и засыпает.",
            cutAt + 250, Limits.INTACT.copy(name = "без повреждений; гибель по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { killCells(b, strip) }
            expectStill("левая половина ушла, связей", b, farLeft, cutAt, cutAt + 250, UNTOUCHED)
            expectStill("правая половина ушла, связей", b, farRight, cutAt, cutAt + 250, UNTOUCHED)
        }
        family.add("hydro/cut/${c.id}/one-kicked", "${c.name}: одну половину толкнули, другая стоит",
            "Тело стоит; на тике $cutAt гибнет полоса клеток посередине. На тике ${cutAt + 100} левая половина получает скорость 0.6 связи за тик влево — прочь от правой.",
            "Левая половина уезжает и останавливается. Правая остаётся на месте (в сотых долях связи): запас среды у кусков раздельный, толчок соседа через среду не приходит.",
            cutAt + 100 + settle(0.6), Limits.INTACT.copy(name = "без повреждений; гибель по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { killCells(b, strip) }
            at(cutAt + 100) { kickCells(b, farLeft, -0.6, 0.0) }
            expectStill("правая половина ушла, связей", b, farRight, cutAt, cutAt + 100 + settle(0.6), UNTOUCHED)
            expectTravelled("левая половина уехала, связей", b, farLeft, cutAt + 100, cutAt + 100 + settle(0.6), 2.0, "левую половину толчок увёз")
        }
        family.add("hydro/cut/${c.id}/moving", "${c.name}: разрезали на ходу",
            "Тело летит вправо со скоростью 0.6 связи за тик; на тике $cutAt связи между левой и правой половиной разрезаются.",
            "Половины продолжают ехать в ту же сторону с прежней скоростью (различие путей меньше четверти), без рывка, дрожи и прироста энергии; затем останавливаются и засыпают.",
            cutAt + settle(0.6), Limits.INTACT.copy(name = "без повреждений; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            kick(b, 0.6, 0.0)
            at(cutAt) { slice(b) }
            val l0 = centerOfCells(b, left); val r0 = centerOfCells(b, right)
            probe("путь левой половины к пути правой") {
                val pl = centerOfCells(b, left)[0] - l0[0]; val pr = centerOfCells(b, right)[0] - r0[0]
                if (pr < 0.5) 1.0 else pl / pr
            }
            expectMaxAtMost("половины едут одинаково", "путь левой половины к пути правой", cutAt, cutAt + settle(0.6), 1.25)
            expectMinAtLeast("половины едут одинаково", "путь левой половины к пути правой", cutAt, cutAt + settle(0.6), 0.75)
        }
        family.add("hydro/cut/${c.id}/spinning", "${c.name}: разрезали при вращении",
            "Тело вращается на месте (0.04 радиана за тик); на тике $cutAt связи между левой и правой половиной разрезаются.",
            "Половины расходятся по касательной, как им велит инерция, и тормозятся о среду. Энергия не растёт, ничего не взрывается и не дрожит; к концу всё стоит и спит.",
            cutAt + settle(1.0), Limits.INTACT.copy(name = "без повреждений; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            kick(b, 0.0, 0.0, spin = 0.04)
            at(cutAt) { slice(b) }
        }
    }

    // Много кусков: тело рассекли решёткой разрезов.
    for ((n, name) in listOf(2 to "четыре", 3 to "девять", 5 to "двадцать пять")) {
        val shape = Shapes.square(15)
        // Номер куска клетки: квадрат со стороной в четырнадцать связей делится на n × n равных частей.
        fun cellOf(k: Int): Int {
            val w = 14 * GAME_LINK
            val cx = (((shape.lx[k] + w / 2) / (w + 1e-9)) * n).toInt().coerceIn(0, n - 1)
            val cy = (((shape.ly[k] + w / 2) / (w + 1e-9)) * n).toInt().coerceIn(0, n - 1)
            return cy * n + cx
        }
        val block = IntArray(shape.count) { cellOf(it) }
        val corner = (0 until shape.count).filter { block[it] == 0 }.toIntArray()
        val opposite = (0 until shape.count).filter { block[it] == n * n - 1 }.toIntArray()
        family.add("hydro/cut/grid$n/rest", "квадрат 15 рассекли на $name кусков в покое",
            "Квадрат стоит; на тике $cutAt он рассечён разрезами на $name кусков, клетки остаются живы.",
            "Куски остаются на месте (вправе чуть раздвинуться до расстояния касания), не дрожат и засыпают; ничего не разлетается.",
            cutAt + settle(0.1), Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { cut(b) { a, d -> block[a] != block[d] } }
            expectStill("угловой кусок ушёл, связей", b, corner, cutAt, cutAt + settle(0.1), 0.6)
            probe("кусков тела") { pieces(b).toDouble() }
            expectMaxAtLeast("тело разделилось на все куски", "кусков тела", cutAt + 1, cutAt + 30, (n * n).toDouble())
        }
        family.add("hydro/cut/grid$n/one-kicked", "квадрат 15 рассекли на $name кусков, угловой кусок толкнули прочь",
            "Квадрат рассечён на $name кусков на тике $cutAt; на тике ${cutAt + 100} угловой кусок получает скорость 0.6 связи за тик прочь от остальных.",
            "Толкнутый кусок уезжает. Кусок в противоположном углу остаётся на месте: чужой толчок через среду к нему не приходит.",
            cutAt + 100 + settle(0.6), Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { cut(b) { a, d -> block[a] != block[d] } }
            at(cutAt + 100) { kickCells(b, corner, -0.42, -0.42) }
            expectStill("дальний кусок ушёл после толчка, связей", b, opposite, cutAt + 100, cutAt + 100 + settle(0.6), 0.05)
            expectTravelled("толкнутый кусок уехал, связей", b, corner, cutAt + 100, cutAt + 100 + settle(0.6), 2.0, "толчок увёз угловой кусок")
        }
    }

    // Тело распалось на отдельные клетки.
    for (shape in listOf(Shapes.square(8), Shapes.disc(5))) {
        family.add("hydro/shred/${shape.id}", "${shape.name}: все связи разрезаны разом",
            "Тело стоит; на тике $cutAt разрезаны все его связи: остаётся россыпь отдельных клеток, стоящих вплотную.",
            "Клетки вправе чуть раздвинуться, но не разлетаются: скорость остаётся малой, никто не гибнет, россыпь успокаивается и засыпает.",
            cutAt + 900, Limits.INTACT.copy(name = "без повреждений; разрез по сценарию", energy = Energy.SETTLES)) {
            val b = body(shape, 0.0, 0.0)
            at(cutAt) { cut(b) { _, _ -> true } }
            probe("наибольшая скорость клетки") {
                val st = world.state
                var best = 0.0
                for (k in 0 until b.count) { val p = b.particle(k); best = maxOf(best, hypot(st.vx[p], st.vy[p])) }
                toLinksPerTick(best)
            }
            expectMaxAtMost("клетки не разлетаются", "наибольшая скорость клетки", cutAt, cutAt + 900, 0.5)
        }
    }

    // Пловца разрезали на ходу: обе половины гребут дальше.
    for (s in listOf(swimmers[0], swimmers[1])) {
        val shape = s.shape
        val left = sideCells(shape, -1.0, 0.0); val leftSet = left.toHashSet()
        val ticks = s.drive.active + settle(0.5)
        family.add("hydro/cut/${s.id}/swimming", "${s.name}: разрезали пополам, пока плывёт",
            "Тело гребёт мышцами; на тике 120 связи между его левой и правой половиной разрезаются, мышцы обеих половин работают дальше.",
            "Каждая половина гребёт сама по себе: без рывка, дрожи и взрыва в момент разреза, мышцы не рвут ткань. После расслабления всё останавливается и засыпает.",
            ticks, Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; разрез по сценарию")) {
            val b = body(shape, 0.0, 0.0)
            s.drive.script(this, b)
            at(120) { cut(b) { a, d -> (a in leftSet) != (d in leftSet) } }
        }
    }

    // Оторванный кусок остаётся рядом с телом, которое движется: его не должно таскать за телом.
    family.add("hydro/cut/dragged-away", "тело уезжает от своего отрезанного куска",
        "У бруска гибнет полоса клеток: остаются большая часть и малый кусок. На тике ${cutAt + 100} большую часть мышь плавно увозит на двенадцать единиц.",
        "Малый кусок остаётся лежать, где лежал (в сотых долях связи): тело, от которого он оторван, за собой его через среду не тащит.",
        cutAt + 100 + 260 + settle(0.5), Limits.DRIVEN.copy(name = "внешняя сила, без повреждений; гибель по сценарию")) {
        val shape = Shapes.rect(20, 6)
        val strip = (0 until shape.count).filter { shape.lx[it] > 3.0 * GAME_LINK && shape.lx[it] < 4.6 * GAME_LINK }.toIntArray()
        val small = (0 until shape.count).filter { shape.lx[it] >= 4.6 * GAME_LINK }.toIntArray()
        val b = body(shape, 0.0, 0.0)
        at(cutAt) { killCells(b, strip) }
        val cell = shape.extreme(-1.0, 0.0)
        val sx = shape.x[cell]; val sy = shape.y[cell]
        mousePath(b, cell, cutAt + 100, 3, (0..80).map { doubleArrayOf(sx - 12.0 * it / 80.0, sy) })
        expectStill("отрезанный кусок ушёл, связей", b, small, cutAt + 1, cutAt + 100 + 260 + settle(0.5), UNTOUCHED)
    }
    return family.list
}
