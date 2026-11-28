package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SOFT_COMPLIANCE

/**
 * ЛИПУЧКИ (Sticky) НА ФИЗИКЕ-БИБЛИОТЕКЕ: временные связи мира физики (PhysicsWorld.addJoint) и
 * кто их держит. Касание липучка находит в параллельной стадии и шлёт команду STICK; здесь,
 * однопоточно, связь заводится ([request]) и каждый тик ведётся ([sync]): сила — импульс
 * липучки, отпускание — импульс ноль, гибель липучки или партнёра; рвёт связь сама физика.
 *
 * Запись — связь, липучка с поколением и партнёр: частица игры с поколением её хозяина (клетка
 * или субстанция; у стенки −1 — она не гибнет). Записи идут в порядке появления и удаляются со
 * сдвигом: обход и результат от числа воркеров не зависят. Входят в сохранение и хэш состояния.
 * Записей — единицы–сотни (по [MAX_PER_CELL] на липучку): все проходы — по ним, не по миру.
 */
internal class StickyJoints(
    private val cellEntity: CellEntity,
    private val particleEntity: ParticleEntity,
    private val substancesEntity: SubstancesEntity,
    private val stickyType: Int,
) {
    private var joint = IntArray(16); private var cell = IntArray(16); private var cellGen = IntArray(16)
    private var partner = IntArray(16); private var partnerGen = IntArray(16)
    var count = 0
        private set

    fun clear() { count = 0 }

    /**
     * Липучка [c] поколения [gen] коснулась частицы игры [p]; [j] — её частица в мире физики
     * (−1 — нет). Прилипает, если липучка жива, импульс больше нуля, к этому партнёру ещё не
     * прилипла, связей меньше [MAX_PER_CELL] и в мире физики они действительно касаются (частицу
     * игры могли за этот проход убрать и отдать другой).
     */
    fun request(world: PhysicsWorld, c: Int, gen: Int, p: Int, j: Int) {
        if (j < 0 || !cellEntity.isAliveAndSameGen(c, gen) || cellEntity.cellType[c].toInt() != stickyType) return
        val impulse = cellEntity.neuronImpulseOutput[c]
        if (impulse <= 0f) return
        val i = particleEntity.physicsIndex[cellEntity.particleIndexes[c]]
        // Мёртвым в мире физики может быть и сама липучка, а не только партнёр: физика убивает
        // клетку в шаге, а игра узнаёт об этом командой DELETE_CELL, которая стоит в конце тика
        // (WorkOrder, WorldPhysics.reportDeaths), — позже, чем STICK из стадии клеток. Без этой
        // проверки addJoint падал на «связь — между двумя живыми частицами» (отчёт 26.11,
        // genomeia-20261126-172103: физика убила липучку в тот же тик, когда та коснулась партнёра;
        // повтор на сборке отчёта с этой проверкой проходит до конца, все 320 хэшей сошлись).
        if (i < 0 || i == j || world.state.cellDead[i] || world.state.cellDead[j]) return
        val pg = generationOf(p)
        var mine = 0
        for (k in 0 until count) {
            if (cell[k] != c || cellGen[k] != gen) continue
            if (partner[k] == p && partnerGen[k] == pg) return
            mine++
        }
        if (mine >= MAX_PER_CELL) return
        // Длина покоя — корнем из суммы квадратов, а не hypot: hypot на ПК — fdlibm в Java, на Android — системная
        // libm, и одна липкая связь с другой длиной покоя расходит повтор .bug между ПК и телефоном. Корень и
        // арифметика float побитово одни везде (и в Rust, и на GPU). Переполнения тут нет: расстояние — доли мира.
        val dx = world.x[i] - world.x[j]; val dy = world.y[i] - world.y[j]
        val rest = kotlin.math.sqrt(dx * dx + dy * dy)
        if (rest > TOUCH * (world.state.radius[i] + world.state.radius[j])) return
        val id = world.addJoint(i, j, rest, complianceOf(impulse))
        if (count == joint.size) grow()
        joint[count] = id; cell[count] = c; cellGen[count] = gen; partner[count] = p; partnerGen[count] = pg
        count++
    }

    /** Раз в тик, до шага мира: сила по импульсу, отпускание. */
    fun sync(world: PhysicsWorld) {
        var m = 0
        for (k in 0 until count) {
            val id = joint[k]; val c = cell[k]
            val impulse = cellEntity.neuronImpulseOutput[c]
            val keep = world.isJointLive(id) && cellEntity.isAliveAndSameGen(c, cellGen[k]) &&
                cellEntity.cellType[c].toInt() == stickyType && impulse > 0f && partnerAlive(k)
            if (!keep) { if (world.isJointLive(id)) world.removeJoint(id); continue }
            world.setJointCompliance(id, complianceOf(impulse))
            if (m != k) {
                joint[m] = id; cell[m] = c; cellGen[m] = cellGen[k]; partner[m] = partner[k]; partnerGen[m] = partnerGen[k]
            }
            m++
        }
        count = m
    }

    private fun generationOf(p: Int): Int {
        val holder = particleEntity.holderEntityIndex[p]
        return when {
            particleEntity.isCell[p] -> cellEntity.getGeneration(holder)
            particleEntity.isSub[p] -> substancesEntity.getGeneration(holder)
            else -> -1
        }
    }

    private fun partnerAlive(k: Int): Boolean {
        val pg = partnerGen[k]
        if (pg == -1) return true
        val p = partner[k]
        val holder = particleEntity.holderEntityIndex[p]
        return when {
            particleEntity.isCell[p] -> cellEntity.isAliveAndSameGen(holder, pg)
            particleEntity.isSub[p] -> substancesEntity.isAliveAndSameGen(holder, pg)
            else -> false
        }
    }

    private fun grow() {
        val cap = joint.size * 2
        joint = joint.copyOf(cap); cell = cell.copyOf(cap); cellGen = cellGen.copyOf(cap)
        partner = partner.copyOf(cap); partnerGen = partnerGen.copyOf(cap)
    }

    fun write(out: java.io.DataOutput) {
        out.writeInt(count)
        for (k in 0 until count) {
            out.writeInt(joint[k]); out.writeInt(cell[k]); out.writeInt(cellGen[k])
            out.writeInt(partner[k]); out.writeInt(partnerGen[k])
        }
    }

    fun read(input: java.io.DataInput) {
        count = 0
        repeat(input.readInt()) {
            if (count == joint.size) grow()
            joint[count] = input.readInt(); cell[count] = input.readInt(); cellGen[count] = input.readInt()
            partner[count] = input.readInt(); partnerGen[count] = input.readInt()
            count++
        }
    }

    fun hash(h: Long): Long {
        var x = Fnv.word(h, count)
        for (k in 0 until count) {
            x = Fnv.word(x, joint[k]); x = Fnv.word(x, cell[k]); x = Fnv.word(x, cellGen[k])
            x = Fnv.word(x, partner[k]); x = Fnv.word(x, partnerGen[k])
        }
        return x
    }

    companion object {
        /** Связей у одной липучки — сразу с несколькими соседями, как липла прежняя (ко всем касаниям). */
        const val MAX_PER_CELL = 3
        /** Касание при сцепке: расстояние не больше этой доли суммы радиусов мира физики. */
        const val TOUCH = 1.25
        /**
         * Податливость по импульсу: при 1 — вдвое мягче связи тела (2 · SOFT_COMPLIANCE, решение
         * 04.11: липучка вдвое слабее обычной связи), слабее — мягче, от 0.05 и ниже — в 40 раз
         * мягче связи (не бесконечно: деление на ноль и рывок при включении). Рвётся связь в мире
         * физики, как связь тела (Joints.kt), — при той же доле растяжения, то есть вдвое меньшей силой.
         */
        fun complianceOf(impulse: Float): Float = 2f * SOFT_COMPLIANCE / impulse.coerceIn(0.05f, 1f)
    }
}
