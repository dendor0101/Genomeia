package io.github.some_example_name.old.systems.rules

/**
 * НАСТРОЙКИ МИРА — что игрок меняет на ходу в окне «Настройки мира» (22.11.2026). Раньше часть из
 * них жила на отдельном экране «Экосистема» (GlobalSimulationSettings, CellSettings) и правилась
 * только до запуска мира через файл; кнопки того экрана были выключены. Остальное — среда
 * физики-библиотеки (WorldEnvironment), которая до этого была константами.
 *
 * [key] — формат журнала (команда SetWorldRule) и сохранения мира: не менять и не переиспользовать.
 * Порядок записей — порядок в хэше мира (WorldRules.hash): новые — только в конец.
 * Подпись и подсказка — `worldRules.<key>` и `worldRules.<key>.hint` в MyBundle*.properties.
 *
 * [cellKey] — настройка, которую читает только этот тип клетки (сила жгутика — Tail, насоса —
 * Pumper, пауза — Producer). Игрок ищет её у самой клетки, поэтому панель показывает её на
 * вкладке «Клетки» у этого типа (27.11, до этого — на «Организмах»). Значение по-прежнему одно
 * на мир и в журнале — без типа: ключ и место в хэше прежние, старые записи и сохранения читаются.
 *
 * Не вошли старые поля, которые уже ни на что не влияют: феромоны (rateOfPheromone*), голодная
 * смерть (theNumberOfTicksHungryCellDies), прежняя гравитация и вязкость среды прежней физики
 * (её заменили [VISCOSITY] и [WATER_DRAG] — среда мира физики), жёсткость клеток и связей
 * прежней физики (cellStiffness, linkStiffness) — их заменила мягкость ткани. И сила плавника
 * (finMaxSpeedCoefficient): плавник выведен из игры (CellPalette.RETIRED_CELLS).
 */
enum class WorldRule(
    val key: String,
    val section: RuleSection,
    val format: RuleFormat,
    /** Границы и шаг ползунка — в его единицах: проценты стандарта или само значение, см. [RuleFormat]. */
    val min: Float,
    val max: Float,
    val step: Float,
    /** Ключ типа клетки, который один читает эту настройку; null — она про весь мир. */
    val cellKey: String? = null
) {
    /** Гравитация вниз по экрану, клеток в секунду в квадрате. 0 — мир сверху, как всегда был. */
    GRAVITY("gravity", RuleSection.ENVIRONMENT, RuleFormat.NUMBER, 0f, 30f, 0.5f),
    /** Изотропное сопротивление среды (MEDIUM_DRAG): гасит любое движение, и еды тоже. */
    VISCOSITY("viscosity", RuleSection.ENVIRONMENT, RuleFormat.PERCENT, 0f, 500f, 5f),
    /** Сопротивление воды рёбрам контура (NORMAL_DRAG): об него гребут хвосты и мышцы. */
    WATER_DRAG("waterDrag", RuleSection.ENVIRONMENT, RuleFormat.PERCENT, 0f, 300f, 5f),
    /** Кулоново трение касания тел и стенок (CONTACT_FRICTION). */
    FRICTION("friction", RuleSection.ENVIRONMENT, RuleFormat.PERCENT, 0f, 300f, 5f),
    /** Энергия листа от света за тик (amountOfSolarEnergy). */
    LIGHT("light", RuleSection.ENVIRONMENT, RuleFormat.PERCENT, 0f, 500f, 5f),
    /** Энергия еды (amountOfFoodEnergy): сколько даёт съеденная и стоит выделенная. */
    FOOD_ENERGY("foodEnergy", RuleSection.ENVIRONMENT, RuleFormat.PERCENT, 0f, 500f, 5f),

    /** Мягкость ткани всех тел: множитель податливости связей и площадей (SOFT_COMPLIANCE). */
    SOFTNESS("softness", RuleSection.ORGANISMS, RuleFormat.PERCENT, 25f, 400f, 5f),
    /** Тяга жгутика (tailMaxSpeedCoefficient). */
    TAIL_FORCE("tailForce", RuleSection.CELLS, RuleFormat.PERCENT, 0f, 400f, 5f, cellKey = "tail"),
    /** Скорость перетекания энергии по связям тела (rateOfEnergyTransferInLinks). */
    LINK_TRANSFER("linkTransfer", RuleSection.ORGANISMS, RuleFormat.PERCENT, 0f, 400f, 5f),
    /** Сколько энергии насос забирает за касание (rateOfEnergyTransferForPumper). */
    PUMP_RATE("pumpRate", RuleSection.CELLS, RuleFormat.PERCENT, 0f, 400f, 5f, cellKey = "pumper"),
    /** Пауза производителя между потомками: тиков на клетку генома (producerRestoreTimeTickCoefficient). */
    PRODUCER_PAUSE("producerPause", RuleSection.CELLS, RuleFormat.NUMBER, 1f, 20f, 1f, cellKey = "producer");

    companion object {
        fun byKey(key: String): WorldRule? = entries.firstOrNull { it.key == key }
    }
}

/** Настройка своя у каждого типа клетки (вкладка «Клетки»). Ключ — как у [WorldRule]. */
enum class CellRule(
    val key: String,
    val format: RuleFormat,
    val min: Float,
    val max: Float,
    val step: Float
) {
    /** Сколько энергии клетка может держать (CellSettings.maxEnergy). Больше нуля всегда. */
    MAX_ENERGY("maxEnergy", RuleFormat.NUMBER, 0.5f, 50f, 0.5f),
    /** Цена действия клетки за тик (CellSettings.energyActionCost). */
    ACTION_COST("actionCost", RuleFormat.PERCENT, 0f, 500f, 5f),
    /** Мягкость ткани клетки (PhysicsWorld.setSoftness): ×1 — обычная, жир — ×5. */
    SOFTNESS("cellSoftness", RuleFormat.TIMES, 0.25f, 10f, 0.25f);

    companion object {
        fun byKey(key: String): CellRule? = entries.firstOrNull { it.key == key }
    }
}

enum class RuleSection(val key: String) { ENVIRONMENT("environment"), ORGANISMS("organisms"), CELLS("cells") }

/**
 * Как ползунок связан со значением. PERCENT — ползунок в процентах стандарта мира (100% — как в
 * новом мире), значение = стандарт × проценты / 100: игроку понятнее «вдвое сильнее», чем 0.0125.
 * NUMBER и TIMES — ползунок и есть значение; TIMES показывается множителем «×1.5».
 */
enum class RuleFormat {
    PERCENT, NUMBER, TIMES;

    fun toSlider(value: Float, standard: Float): Float =
        if (this != PERCENT) value else if (standard == 0f) 0f else value / standard * 100f

    fun fromSlider(slider: Float, standard: Float): Float =
        if (this != PERCENT) slider else standard * slider / 100f
}
