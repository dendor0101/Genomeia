package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyState
import kotlin.math.abs

/** Независимый полный обход нужен только проверке. Иначе неверные organismOf
 * одновременно скрывают проникновение «своего» осколка и связывают его запас среды
 * с далёким телом. Позиционные хэши сами по себе эту ошибку не обнаруживают. */
internal class ConnectivityAudit(private val s: BodyState) {
    private val seen = BooleanArray(s.n)
    private val first = IntArray(s.n)
    private val queue = IntArray(s.n)

    fun verify(tick: Int) {
        seen.fill(false); first.fill(-1)
        for (seed in 0 until s.n) {
            if (seen[seed]) continue
            val org = s.organismOf[seed]
            check(org in 0 until s.organismCount) { "Invalid component tick=$tick cell=$seed org=$org" }
            check(first[org] < 0) {
                "Disconnected fragments share component tick=$tick cells=${first[org]},$seed org=$org"
            }
            first[org] = seed
            var head = 0; var tail = 1; var alive = 0; var mass = 0.0
            queue[0] = seed; seen[seed] = true
            while (head < tail) {
                val v = queue[head++]
                check(s.organismOf[v] == org) { "Connected cells have different components tick=$tick cells=$seed,$v" }
                if (!s.cellDead[v]) {
                    alive++
                    if (s.restInvMass[v] > 0.0) mass += 1.0 / s.restInvMass[v]
                }
                for (k in s.baked.vertStart[v] until s.baked.vertStart[v + 1]) {
                    if (s.baked.edgeDead[s.baked.vertEdge[k]]) continue
                    val other = s.baked.vertOther[k]
                    if (!seen[other]) { seen[other] = true; queue[tail++] = other }
                }
            }
            check(s.organismSize[org] == alive) { "Component size tick=$tick org=$org expected=$alive actual=${s.organismSize[org]}" }
            check(abs(s.organismMass[org] - mass) <= 1e-9 * maxOf(1.0, mass)) {
                "Component mass tick=$tick org=$org expected=$mass actual=${s.organismMass[org]}"
            }
        }
    }
}
