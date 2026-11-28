package io.github.some_example_name.old.systems.genomics.genome

/**
 * Кластеры мотонейронов (cells/MotorNeuron.kt) во взрослом теле: что запекается в раскладку
 * (BakedLayout.motorClusterOfSlot и motorNeuronSlots).
 *
 * Кластер — связная по физическим связям раскладки группа клеток «мышца» и «мотонейрон».
 * Мотонейрон внутри группы её не рвёт, а соединяет: два куска мышц, которых он касается, —
 * один кластер. Группа без мотонейрона кластером не считается: её мышцам раздавать нечего.
 *
 * Считается один раз при запекании, поэтому сложность и аллокации здесь неважны. Разрыв
 * ткани в мире кластер не делит (решение 20.11): живые связи сюда не смотрят.
 *
 * Номера кластеров — по первому слоту кластера, по возрастанию: результат не зависит ни от
 * чего, кроме раскладки.
 */
class MotorClusters(
    /** По слотам: номер кластера у мышц и мотонейронов кластера, -1 у остальных. Пусто — мотонейронов нет. */
    val clusterOfSlot: List<Int>,
    /** Слоты мотонейронов по возрастанию. */
    val motorNeuronSlots: List<Int>
) {
    companion object {
        val NONE = MotorClusters(emptyList(), emptyList())

        /**
         * [linkA]/[linkB] — слоты концов физических связей, [isMuscle]/[isMotorNeuron] — тип
         * клетки во взрослом теле по слотам.
         */
        fun of(
            cellCount: Int,
            linkA: IntArray,
            linkB: IntArray,
            isMuscle: BooleanArray,
            isMotorNeuron: BooleanArray
        ): MotorClusters {
            if (isMotorNeuron.none { it }) return NONE
            val member = BooleanArray(cellCount) { isMuscle[it] || isMotorNeuron[it] }

            // Система непересекающихся множеств по связям между участниками: связей втрое
            // больше клеток, а обход в ширину потребовал бы ещё и списков смежности.
            val parent = IntArray(cellCount) { it }
            fun root(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var y = x
                while (parent[y] != r) { val next = parent[y]; parent[y] = r; y = next }
                return r
            }
            for (k in linkA.indices) {
                val a = linkA[k]; val b = linkB[k]
                if (a < 0 || b < 0 || !member[a] || !member[b]) continue
                val ra = root(a); val rb = root(b)
                // Корень — меньший слот: он же первый слот кластера.
                if (ra < rb) parent[rb] = ra else if (rb < ra) parent[ra] = rb
            }

            // Кластер — только у группы с мотонейроном.
            val hasMotor = BooleanArray(cellCount)
            for (s in 0 until cellCount) if (isMotorNeuron[s]) hasMotor[root(s)] = true

            val numberOfRoot = IntArray(cellCount) { -1 }
            var clusters = 0
            val clusterOfSlot = IntArray(cellCount) { -1 }
            for (s in 0 until cellCount) {
                if (!member[s]) continue
                val r = root(s)
                if (!hasMotor[r]) continue
                if (numberOfRoot[r] == -1) numberOfRoot[r] = clusters++
                clusterOfSlot[s] = numberOfRoot[r]
            }
            val motors = (0 until cellCount).filter { isMotorNeuron[it] }
            return MotorClusters(clusterOfSlot.toList(), motors)
        }
    }
}
