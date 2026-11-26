package io.github.some_example_name.physics

/**
 * ОСТРОВА — ПО ЗАМЕРУ ВРЕМЕНИ (четвёртая веха, 25.11.2026; docs/physics/islands.md).
 *
 * Острова выигрывают, когда мир — много тел помельче (тело в своём кэше, без барьеров между стадиями), и проигрывают,
 * когда в мире крупные тела: мир с ними и так держит воркеров занятыми по блокам, а острова идут отдельной фазой до
 * него, и их работа больше не заполняет простои воркеров на крупных телах (мир по умолчанию, замер 25.11: 4.9 →
 * 5.8–6.0 мс на ПК). Где граница, зависит от устройства (ядра, кэш), поэтому режим выбирается замером тиков.
 *
 * ВЫБОР НЕ МЕНЯЕТ БИТОВ: сумма мира с островами и без одна (IslandsTest, genomeBench, bugReplayCheck с островами),
 * поэтому время здесь решает только, как считать, а не что получится. Повтор `.bug` на другом устройстве выберет
 * иначе и придёт к тем же битам.
 *
 * ЦИКЛ. Первые [WARMUP] тиков — без островов и без пробы: JIT ещё компилирует, и время тика шумит сильнее разницы
 * режимов (S24 Ultra 26.11: проба на первых 18 тиках взяла острова на мире, где они медленнее на 9%). Затем проба —
 * четыре блока по [BLOCK] тиков: без островов, с ними, без, с ними; первый тик блока не считается (смена режима
 * перестраивает широкую фазу касаний мира), а чередование блоков делит дрейф прогрева и нагрева между режимами поровну.
 * Острова выбираются, если медиана их тиков быстрее медианы без них хотя бы на [WIN_PERCENT]% и острова были во всех
 * тиках пробы. Затем [HOLD] тиков в выбранном режиме; тот же ответ подряд — держится вдвое дольше, до [HOLD_MAX].
 * Цена разведки — [BLOCK] × 2 тиков в худшем режиме на удержание: при проигрыше островов на 20% это меньше 1% в
 * среднем, а в устоявшемся мире — десятые доли процента.
 *
 * Замеры 25–26.11 (genomeBench, 20 подшагов): ПК (8 воркеров) — мир тел до 1000 клеток с островами быстрее, мир по
 * умолчанию — без; Galaxy A21s (7) — мелкие 151–155 → 129–130 мс, по умолчанию 60 → 73–76 мс; S24 Ultra (5) — мелкие
 * 20.5–21.7 → 19.7 мс, по умолчанию 8.1–8.2 → 8.9–9.0 мс.
 */
internal class IslandChooser {
    private var phase = WARMUP_PHASE
    private var tick = 0
    private val off = LongArray(SAMPLES)
    private val on = LongArray(SAMPLES)
    private var offN = 0
    private var onN = 0
    private var onTicks = 0
    private var islandTicks = 0

    /** Выбор последней пробы: острова быстрее. */
    var chosen = false
        private set

    /** Тиков в выбранном режиме до следующей пробы. */
    private var hold = HOLD
    private var probed = false

    /** Острова в этом тике. */
    fun wantIslands(): Boolean = when (phase) {
        WARMUP_PHASE -> false
        PROBE_PHASE -> probeOn(tick)
        else -> chosen
    }

    /** Тик пробы [t] — в блоке с островами (блоки: без, с, без, с). */
    private fun probeOn(t: Int): Boolean = (t / BLOCK) % 2 == 1

    /** Тик прошёл за [ns] наносекунд, островов в нём [islands]. */
    fun record(ns: Long, islands: Int) {
        when (phase) {
            WARMUP_PHASE -> if (++tick >= WARMUP) startProbe()
            PROBE_PHASE -> {
                val withIslands = probeOn(tick)
                if (withIslands) { onTicks++; if (islands > 0) islandTicks++ }
                if (tick % BLOCK != 0) { if (withIslands) on[onN++] = ns else off[offN++] = ns }
                if (++tick == BLOCK * 4) decide()
            }
            else -> if (++tick >= hold) startProbe()
        }
    }

    private fun startProbe() {
        phase = PROBE_PHASE; tick = 0
        offN = 0; onN = 0; onTicks = 0; islandTicks = 0
    }

    private fun decide() {
        val next = islandTicks == onTicks && median(on, onN) * 100 < median(off, offN) * WIN_PERCENT
        // Тот же ответ, что в прошлый раз, — держать вдвое дольше (до HOLD_MAX): в устоявшемся мире разведка почти
        // ничего не стоит. Ответ сменился — снова часто.
        hold = if (probed && next == chosen) minOf(hold * 2, HOLD_MAX) else HOLD
        chosen = next; probed = true
        phase = HOLD_PHASE; tick = 0
    }

    private val sorted = LongArray(SAMPLES)

    private fun median(samples: LongArray, n: Int): Long {
        System.arraycopy(samples, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        return sorted[n / 2]
    }

    private companion object {
        const val WARMUP_PHASE = 0
        const val PROBE_PHASE = 1
        const val HOLD_PHASE = 2
        /** Тиков без островов и без пробы в начале: 2 секунды при 60 тиках в секунду. */
        const val WARMUP = 120
        /** Тиков в блоке пробы; блоков четыре, считаются все тики блока, кроме первого. */
        const val BLOCK = 5
        /** Замеров на режим за пробу: два блока без первого тика. */
        const val SAMPLES = 2 * (BLOCK - 1)
        /** Тиков в выбранном режиме до следующей пробы: 5 секунд при 60 тиках в секунду. */
        const val HOLD = 300
        /** Дольше не держать: мир меняется (тела растут, сходятся), а разведка за 40 секунд стоит доли процента. */
        const val HOLD_MAX = 2400
        /** Острова — только если быстрее хотя бы на 3%: шум замера не должен переключать режим туда-сюда. */
        const val WIN_PERCENT = 97
    }
}

/** Режим островов: ключ ISLANDS — 0 выкл., 1 всегда (проверки), auto — по замеру ([IslandChooser]). */
internal const val ISLANDS_OFF = 0
internal const val ISLANDS_ON = 1
internal const val ISLANDS_AUTO = 2

/**
 * Режим без ключа ISLANDS — по замеру (25.11): на ПК и телефонах он берёт острова там, где они быстрее (мир мелких
 * тел: ПК −25%, A21s −15%, S24 −7%), и не берёт там, где медленнее (мир по умолчанию), — docs/physics/islands.md.
 */
internal const val ISLANDS_DEFAULT = ISLANDS_AUTO
