package io.github.some_example_name.old.systems.rules

import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.physics.bridge.WorldPhysics
import io.github.some_example_name.old.systems.genomics.CellSystem
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * НАСТРОЙКИ МИРА — СОСТОЯНИЕ МИРА (см. WorldRule). Меняет их только поток симуляции, в фазе
 * команд игрока (PlayerCommand.SetWorldRule, ResetWorldRules), то есть однопоточно и до
 * параллельных стадий: клетки на воркерах читают их без гонок. Поэтому они в журнале с номером
 * тика, в хэше мира ([hash], часть SimulationData) и в сохранении ([encode]).
 *
 * ГДЕ ЖИВУТ ЗНАЧЕНИЯ. Клетки читают свои числа из [settings] — СВОЕЙ копии настроек у мира
 * ([ownSettings]): общую (DIGameGlobalContainer.substrateSettings) читает и редактор генома, и
 * правка мира не должна менять его предпросмотр. Среду физики и мягкость по типу клетки
 * читает WorldPhysics прямо отсюда раз в тик. Перенос по связям — CellSystem.linkEnergyTransfer.
 *
 * СТАНДАРТ — то, с чем начинается новый мир: числа общих настроек игры (файл
 * GlobalSubstrateSettings, он же уходит во вложения .bug) и материал физики, подобранный на
 * стендах. «Сбросить» возвращает к нему. Перезапуск мира настройки НЕ сбрасывает: это правила
 * мира, а перезапуск — тот же мир с начала.
 *
 * Цена: на тик — ничего, кроме чтений; на команду — запись поля, а у «максимальной энергии»
 * типа ещё проход по клеткам (их массив CellEntity.maxEnergy — копия на клетку, его читает
 * перенос по связям). Проход — по номерам клеток один раз на действие игрока, не на тик.
 */
class WorldRules(
    /** Своя копия настроек мира — та, что передана сущностям мира ([ownSettings]). */
    val settings: SubstrateSettings,
    /** Общие настройки игры — стандарт нового мира. */
    private val base: SubstrateSettings,
    /** Ключи типов клеток по номеру типа (CellTypeList.keys()). */
    val cellKeys: List<String>,
    private val cellEntity: CellEntity,
    private val cellSystem: CellSystem
) {
    private val ruleCount = WorldRule.entries.size
    private val typeCount = cellKeys.size

    val standard = FloatArray(ruleCount)
    val values = FloatArray(ruleCount)

    /** По правилу клетки и номеру типа: [CellRule.ordinal] * число типов + тип. */
    val cellStandard = FloatArray(CellRule.entries.size * typeCount)
    val cellValues = FloatArray(CellRule.entries.size * typeCount)

    /**
     * Мягкость ткани по номеру типа — её читает сверка WorldPhysics каждой клетке каждый тик,
     * поэтому отдельным плотным массивом (типов ~30: одна-две кэш-линии).
     */
    val cellSoftness = FloatArray(typeCount)

    init {
        check(settings.cellsSettings.size == typeCount) { "настроек клеток ${settings.cellsSettings.size}, а типов $typeCount" }
        reset()
    }

    // --- чтение для WorldPhysics (раз в тик) ----------------------------------------------

    val gravity: Float get() = values[WorldRule.GRAVITY.ordinal]
    val viscosity: Float get() = values[WorldRule.VISCOSITY.ordinal]
    val waterDrag: Float get() = values[WorldRule.WATER_DRAG.ordinal]
    val friction: Float get() = values[WorldRule.FRICTION.ordinal]
    val softness: Float get() = values[WorldRule.SOFTNESS.ordinal]

    operator fun get(rule: WorldRule): Float = values[rule.ordinal]
    operator fun get(rule: CellRule, type: Int): Float = cellValues[rule.ordinal * typeCount + type]
    fun standardOf(rule: CellRule, type: Int): Float = cellStandard[rule.ordinal * typeCount + type]

    // --- изменение (поток симуляции, фаза команд) -------------------------------------------

    /** Всё к стандарту: новый мир и кнопка «Сбросить». */
    fun reset() {
        val b = base.data
        standard[WorldRule.GRAVITY.ordinal] = 0f
        standard[WorldRule.VISCOSITY.ordinal] = 1f
        standard[WorldRule.WATER_DRAG.ordinal] = 1f
        // Среда физики — множителями к материалу (PhysicsBridge.setEnvironment): стандарт 1.
        standard[WorldRule.FRICTION.ordinal] = 1f
        standard[WorldRule.LIGHT.ordinal] = b.amountOfSolarEnergy
        standard[WorldRule.FOOD_ENERGY.ordinal] = b.amountOfFoodEnergy
        standard[WorldRule.SOFTNESS.ordinal] = 1f
        standard[WorldRule.TAIL_FORCE.ordinal] = b.tailMaxSpeedCoefficient
        standard[WorldRule.LINK_TRANSFER.ordinal] = b.rateOfEnergyTransferInLinks
        standard[WorldRule.PUMP_RATE.ordinal] = b.rateOfEnergyTransferForPumper
        standard[WorldRule.PRODUCER_PAUSE.ordinal] = b.producerRestoreTimeTickCoefficient
        for (type in 0 until typeCount) {
            val cell = base.cellsSettings[type]
            cellStandard[CellRule.MAX_ENERGY.ordinal * typeCount + type] = cell.maxEnergy
            cellStandard[CellRule.ACTION_COST.ordinal * typeCount + type] = cell.energyActionCost
            cellStandard[CellRule.SOFTNESS.ordinal * typeCount + type] = WorldPhysics.standardSoftness(cellKeys[type])
        }
        for (rule in WorldRule.entries) put(rule, standard[rule.ordinal])
        for (rule in CellRule.entries) for (type in 0 until typeCount) put(rule, type, cellStandard[rule.ordinal * typeCount + type])
    }

    /**
     * Команда игрока: правило [key] (у правила клетки — типа [cellType], ключ CellRule) в [value].
     * Незнакомый ключ или тип (запись из сборки с другими правилами) — ничего. Значение
     * зажимается в границы ползунка: журнал или сохранение не должны дать нулевую энергию клетки
     * или нулевую мягкость.
     */
    fun set(key: String, cellType: String, value: Float) {
        if (value.isNaN()) return
        if (cellType.isEmpty()) {
            val rule = WorldRule.byKey(key) ?: return
            put(rule, clamp(rule.format, rule.min, rule.max, standard[rule.ordinal], value))
        } else {
            val rule = CellRule.byKey(key) ?: return
            val type = cellKeys.indexOf(cellType)
            if (type < 0) return
            put(rule, type, clamp(rule.format, rule.min, rule.max, standardOf(rule, type), value))
        }
    }

    private fun clamp(format: RuleFormat, min: Float, max: Float, standard: Float, value: Float): Float {
        val low = format.fromSlider(min, standard)
        val high = format.fromSlider(max, standard)
        return value.coerceIn(minOf(low, high), maxOf(low, high))
    }

    private fun put(rule: WorldRule, value: Float) {
        values[rule.ordinal] = value
        val d = settings.data
        when (rule) {
            WorldRule.LIGHT -> d.amountOfSolarEnergy = value
            WorldRule.FOOD_ENERGY -> d.amountOfFoodEnergy = value
            WorldRule.TAIL_FORCE -> d.tailMaxSpeedCoefficient = value
            WorldRule.PUMP_RATE -> d.rateOfEnergyTransferForPumper = value
            WorldRule.PRODUCER_PAUSE -> d.producerRestoreTimeTickCoefficient = value
            WorldRule.LINK_TRANSFER -> {
                d.rateOfEnergyTransferInLinks = value
                // Тот же множитель, что был у DISimulationContainer.energyTransportRate.
                cellSystem.linkEnergyTransfer = value * LINK_TRANSFER_SCALE
            }
            // Среду физики WorldPhysics читает отсюда сам, раз в тик.
            WorldRule.GRAVITY, WorldRule.VISCOSITY, WorldRule.WATER_DRAG, WorldRule.FRICTION, WorldRule.SOFTNESS -> {}
        }
    }

    private fun put(rule: CellRule, type: Int, value: Float) {
        cellValues[rule.ordinal * typeCount + type] = value
        val cell = settings.cellsSettings[type]
        when (rule) {
            CellRule.MAX_ENERGY -> if (cell.maxEnergy != value) {
                cell.maxEnergy = value
                // Живые клетки держат свою копию (CellEntity.maxEnergy) — по ней идёт перенос по связям.
                val ce = cellEntity
                val t = type.toByte()
                for (i in 0..ce.lastId) if (ce.isAlive[i] && ce.cellType[i] == t) ce.maxEnergy[i] = value
            }
            CellRule.ACTION_COST -> cell.energyActionCost = value
            CellRule.SOFTNESS -> cellSoftness[type] = value
        }
    }

    // --- хэш и сохранение -------------------------------------------------------------------

    /** Часть хэша мира (SimulationData): все значения побитово, по порядку правил и типов. */
    fun hash(h: Long): Long {
        var x = h
        for (v in values) x = Fnv.word(x, java.lang.Float.floatToRawIntBits(v))
        for (v in cellValues) x = Fnv.word(x, java.lang.Float.floatToRawIntBits(v))
        return x
    }

    /**
     * Запись в сохранение мира: пары «ключ → значение», у правил клетки — с ключом типа. По
     * ключам, а не номерам: правила и типы клеток добавляются, и старое сохранение должно
     * читаться. Отличные от стандарта и равные ему — все: стандарт на другой машине может быть
     * другим (файл настроек), а мир должен жить по своим правилам.
     */
    fun encode(out: DataOutputStream) {
        out.writeInt(FORMAT)
        out.writeInt(ruleCount)
        for (rule in WorldRule.entries) { out.writeUTF(rule.key); out.writeFloat(values[rule.ordinal]) }
        out.writeInt(CellRule.entries.size * typeCount)
        for (rule in CellRule.entries) for (type in 0 until typeCount) {
            out.writeUTF(cellKeys[type]); out.writeUTF(rule.key); out.writeFloat(cellValues[rule.ordinal * typeCount + type])
        }
    }

    /** Чтение [encode]: сначала стандарт, поверх — что записано. Чего нет в записи — стандарт. */
    fun decode(input: DataInputStream) {
        reset()
        val format = input.readInt()
        check(format <= FORMAT) { "настройки мира записаны более новой версией игры (формат $format)" }
        repeat(input.readInt()) { val key = input.readUTF(); set(key, "", input.readFloat()) }
        repeat(input.readInt()) {
            val type = input.readUTF(); val key = input.readUTF(); set(key, type, input.readFloat())
        }
    }

    companion object {
        private const val FORMAT = 1

        /** Множитель переноса по связям: исторический, см. DISimulationContainer.energyTransportRate. */
        const val LINK_TRANSFER_SCALE = 8f

        /**
         * Своя копия настроек для мира: числа общих [base] и НОВЫЕ объекты настроек клеток по
         * всему реестру. Новые — потому что карта по умолчанию (defaultCellSettingsMap) общая, и
         * правка клетки в мире иначе поменяла бы её всем.
         */
        fun ownSettings(base: SubstrateSettings, defaults: Map<String, CellSettings>): SubstrateSettings {
            val cells = LinkedHashMap<String, CellSettings>()
            for ((name, default) in defaults) cells[name] = (base.data.cellsSettings[name] ?: default).copy()
            val data: GlobalSimulationSettings = base.data.copy(cellsSettings = cells)
            return SubstrateSettings(data, cells)
        }
    }
}
