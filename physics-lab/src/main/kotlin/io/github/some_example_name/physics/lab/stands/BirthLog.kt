package io.github.some_example_name.physics.lab.stands

import kotlin.math.sqrt

/**
 * РОЖДЕНИЯ ТИКА — для правила «клетки рождаются на своём месте» (RuleMonitor, Rule.BIRTH).
 *
 * Повод — автор 20.11: «некоторые клетки спавнятся очень далеко от организма, а потом между ними создается треугольник
 * очень вытянутый, который очень медленно сжимается к нормальному состоянию, в общем сделай проверку что нет спавна
 * клеток далеко от места где они должны быть». Монитор видит мир только после шага, когда решатель уже сдвинул
 * новорождённую, поэтому рождение меряется здесь, в миг рождения: StandWorld.birth зовёт [note] сразу после того, как
 * клетка ожила, до шага мира. Обе меры — в длинах связи новорождённой (самая длинная её связь в описании; сначала
 * мерилось самой короткой, но у Crayfish есть связи 0.14 и 0.22 при медиане 0.74, и клетка с такой связью, рождённая
 * на обычном расстоянии от соседа, выходила «в трёх связях от тела»):
 *  - зазор: от новорождённой до ближайшей рождённой живой клетки её тела. Делением клетка рождается почти в родителе,
 *    по позе — в связи от соседей; далеко от тела — значит, поза, по которой она встала, не та;
 *  - лишняя длина: насколько связи, с которыми она родилась, и стороны треугольников, оживших вместе с ней (сторона
 *    между двумя прежними клетками тоже: «вытянутый треугольник»), длиннее своих длин в описании.
 * Лишняя длина, а не отношение длин: у тела генома связи разной длины, и короткая связь, честно рождённая делением в
 * родителе, бывает в 3–4 раза длиннее своей (прогон 20.11: так меряли сначала, и правило красило честный рост). А
 * лишняя длина у честного деления ограничена геометрией: клетка рождается в родителе, и её связь к соседу длиннее
 * положенной не больше чем на расстояние до родителя (неравенство треугольника); у двух связанных клеток одной стадии,
 * рождённых каждая в своём родителе, — не больше чем на два таких расстояния. Медленно стягивается именно лишняя
 * длина: новорождённая связь тянет не сильнее BIRTH_MAX_PULL своей цели.
 *
 * Цена: на рождение — проход по клеткам своего тела (зазор) и по своим связям и треугольникам; в тиках без рождений —
 * ничего. Описание тела раскладывается по клеткам один раз на тело.
 */
class BirthLog {
    /** Сколько клеток родилось за последний шаг. */
    var count = 0; private set
    /** Частица мира новорождённой, зазор и лишняя длина (в её длинах связи) — по рождению. */
    var particle = IntArray(16); private set
    var gap = DoubleArray(16); private set
    var excess = DoubleArray(16); private set
    /** Пара частиц с худшей лишней длиной (связь новорождённой или сторона ожившего треугольника); −1 — пары нет. */
    var worstA = IntArray(16); private set
    var worstB = IntArray(16); private set

    /** Связи и треугольники описания у каждой клетки описания (номер — частица минус first тела). */
    private class Adjacency(val links: Array<IntArray>, val triangles: Array<IntArray>)
    private val adjacency = HashMap<StandBody, Adjacency>()

    /** Начало шага: прошлые рождения монитор уже прочёл. */
    fun clear() { count = 0 }

    /** Клетка [k] фигуры тела [b] только что ожила (мир ещё не шагнул): запомнить, как она родилась. */
    fun note(w: StandWorld, b: StandBody, k: Int) {
        val st = w.world.state
        val def = b.def
        val first = b.first
        val p = b.particle(k)
        val d = p - first
        val adj = adjacency.getOrPut(b) { build(b) }
        fun alive(i: Int) = !st.cellUnborn[first + i] && !st.cellDead[first + i]
        fun design(i: Int, j: Int): Double {
            val dx = (def.x[i] - def.x[j]).toDouble(); val dy = (def.y[i] - def.y[j]).toDouble()
            return sqrt(dx * dx + dy * dy)
        }
        /** Насколько пара сейчас дальше, чем в описании, — в единицах мира. */
        fun over(i: Int, j: Int): Double {
            val dx = st.px[first + i] - st.px[first + j]; val dy = st.py[first + i] - st.py[first + j]
            return sqrt(dx * dx + dy * dy) - design(i, j)
        }
        var own = 0.0
        var worst = 0.0; var wa = -1; var wb = -1
        fun pair(i: Int, j: Int) { val v = over(i, j); if (v > worst) { worst = v; wa = first + i; wb = first + j } }
        for (e in adj.links[d]) {
            val o = if (def.linkA[e] == d) def.linkB[e] else def.linkA[e]
            val rest = design(d, o)
            // Мера — по постоянным связям: временная связь роста (связь поперёк граней) — диагональ, длиннее обычной.
            if (rest > own && !def.isTempLink(e)) own = rest
            if (alive(o)) pair(d, o)
        }
        for (t in adj.triangles[d]) {
            val a = def.triA[t]; val c = def.triB[t]; val e = def.triC[t]
            if (!alive(a) || !alive(c) || !alive(e)) continue
            pair(a, c); pair(c, e); pair(e, a)
        }
        if (own <= 1e-12) own = GAME_LINK * PHYSICS_PER_GAME
        var near2 = Double.MAX_VALUE
        for (q in 0 until b.count) {
            if (q == d || !alive(q)) continue
            val dx = st.px[first + q] - st.px[p]; val dy = st.py[first + q] - st.py[p]
            val r2 = dx * dx + dy * dy
            if (r2 < near2) near2 = r2.toDouble()
        }
        if (count == particle.size) {
            val size = count * 2
            particle = particle.copyOf(size); gap = gap.copyOf(size); excess = excess.copyOf(size)
            worstA = worstA.copyOf(size); worstB = worstB.copyOf(size)
        }
        particle[count] = p
        gap[count] = if (near2 == Double.MAX_VALUE) 0.0 else sqrt(near2) / own
        excess[count] = worst / own
        worstA[count] = wa; worstB[count] = wb
        count++
    }

    private fun build(b: StandBody): Adjacency {
        val def = b.def
        val n = b.count
        val linkCount = IntArray(n); val triCount = IntArray(n)
        for (e in def.linkA.indices) { linkCount[def.linkA[e]]++; linkCount[def.linkB[e]]++ }
        for (t in def.triA.indices) { triCount[def.triA[t]]++; triCount[def.triB[t]]++; triCount[def.triC[t]]++ }
        val links = Array(n) { IntArray(linkCount[it]) }
        val tris = Array(n) { IntArray(triCount[it]) }
        linkCount.fill(0); triCount.fill(0)
        for (e in def.linkA.indices) {
            val a = def.linkA[e]; val c = def.linkB[e]
            links[a][linkCount[a]++] = e; links[c][linkCount[c]++] = e
        }
        for (t in def.triA.indices) for (v in intArrayOf(def.triA[t], def.triB[t], def.triC[t])) tris[v][triCount[v]++] = t
        return Adjacency(links, tris)
    }
}
