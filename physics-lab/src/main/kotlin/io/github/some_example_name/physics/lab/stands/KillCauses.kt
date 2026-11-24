package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import java.util.Locale
import kotlin.math.sqrt

/**
 * ОБ КОГО И КАК ГИБНУТ КЛЕТКИ по правилу «центр в чужом радиусе». Решатель контактов сообщает о
 * каждой заявке на гибель пары (BoundaryContacts.dbgKillHook); здесь погибшая клетка раскладывается
 * по двум вопросам, от ответа на которые зависит, что чинить:
 *
 *  - об кого: о неподвижную частицу (рельеф, стенка), о клетку своего тела (ткань смялась в
 *    себя), о клетку чужого тела, о свободную частицу;
 *  - как пара туда попала: влетела за один подшаг (до него не касалась — удар пропустил шаг),
 *    вдавлена из касания (касание было и не удержало) или оказалась глубоко уже к началу подшага
 *    (её вдавило то, что идёт после решения касаний, или прошлый подшаг не дожал).
 *
 * Счёт — погибших клеток: в паре гибнут обе, кроме костных, а клетка, попавшая за тик в несколько
 * заявок, считается по первой. Сумма по всем причинам равна числу погибших «центр в чужом радиусе».
 * Заявки, по которым не гибнет никто (обе клетки костные), считаются отдельно ([boneBone]): такая
 * пара подаёт заявку каждый подшаг, пока кости стоят друг в друге.
 *
 * Заведено 17.11.2026: без этого разбора удар генома о стену читался как «стена убивает», а
 * клетки гибли друг о друга внутри своего тела — и так по всему каталогу (три четверти гибели).
 *
 * Прогон с воркерами и прогон эталона не слушаются: у первого заявки приходят из нескольких
 * потоков, у второго гибель считает его собственный код.
 */
class KillCauses(private val w: StandWorld) {
    /** Счёт погибших клеток: [kind * WAYS.size + way]; порядок тот же, что у [COLUMNS]. */
    val count = IntArray(KINDS.size * WAYS.size)
    /** Заявки «кость о кость»: не гибнет никто. */
    var boneBone = 0
        private set
    /** Сколько первых заявок запомнить строками — для подробного режима. */
    var keepLines = 0
    val lines = ArrayList<String>()
    private val st = w.world.state
    private val hook = { i: Int, j: Int, d: Float, atStart: Boolean -> note(i, j, d.toDouble(), atStart) }
    /** Тик, в котором клетка уже сочтена погибшей: вторая заявка на неё в том же тике не в счёт. */
    private var countedAt = IntArray(0)

    /** Звать перед каждым шагом мира: решатель контактов появляется и пересоздаётся по ходу сцены. */
    fun attach() { st.contacts?.dbgKillHook = hook }

    val total: Int get() = count.sum()
    fun of(kind: Int, way: Int): Int = count[kind * WAYS.size + way]

    private fun bodyOf(p: Int): Int {
        for (b in w.bodies) if (p >= b.first && p < b.first + b.count) return b.index
        return -1
    }

    /** Клетка гибнет по заявке (не костная) и в этом тике ещё не сочтена. */
    private fun dies(p: Int, tick: Int): Boolean {
        if (st.bonesRigid && st.boneOf[p] >= 0) return false
        if (p >= countedAt.size) countedAt = countedAt.copyOf(maxOf(p + 1, countedAt.size * 2, 64))
        if (countedAt[p] == tick) return false
        countedAt[p] = tick
        return true
    }

    private fun note(i: Int, j: Int, d: Double, atStart: Boolean) {
        val con = st.contacts ?: return
        val tick = w.tick + 1
        if (st.bonesRigid && st.boneOf[i] >= 0 && st.boneOf[j] >= 0) { boneBone++; return }
        val dead = (if (dies(i, tick)) 1 else 0) + (if (dies(j, tick)) 1 else 0)
        if (dead == 0) return
        val rr = con.contactRadiusOf(i) + con.contactRadiusOf(j)
        val bi = bodyOf(i); val bj = bodyOf(j)
        val kind = if (st.invMass[i] <= 0.0 || st.invMass[j] <= 0.0) FIXED else if (bi < 0 || bj < 0) BALL else if (bi == bj) SELF else FOREIGN
        // Сближение пары за подшаг вдоль линии центров — по скоростям на входе в подшаг.
        val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-12f)
        val closing = -((st.vx[i] - st.vx[j]) * dx + (st.vy[i] - st.vy[j]) * dy) / len * (DT / SUBSTEPS)
        val way = if (atStart) DEEP else if (d + closing >= rr) FLEW else PRESSED
        count[kind * WAYS.size + way] += dead
        if (lines.size < keepLines) lines.add(String.format(Locale.ROOT,
            "    тик %d: #%d и #%d — %s, %s; расстояние %.2f от касания, сближение за подшаг %.2f",
            tick, i, j, KINDS[kind], WAYS[way], d / rr, closing / rr))
    }

    companion object {
        const val FIXED = 0; const val SELF = 1; const val FOREIGN = 2; const val BALL = 3
        const val FLEW = 0; const val PRESSED = 1; const val DEEP = 2
        val KINDS = arrayOf("о неподвижную частицу", "о клетку своего тела", "о клетку чужого тела", "о свободную частицу")
        val WAYS = arrayOf("влетела за подшаг", "вдавлена из касания", "глубоко к началу подшага")
        /** Столбцы файла итогов, в порядке [count]. */
        val COLUMNS = arrayOf("killFixedFlew", "killFixedPressed", "killFixedDeep", "killSelfFlew", "killSelfPressed", "killSelfDeep",
            "killForeignFlew", "killForeignPressed", "killForeignDeep", "killBallFlew", "killBallPressed", "killBallDeep")
    }
}
