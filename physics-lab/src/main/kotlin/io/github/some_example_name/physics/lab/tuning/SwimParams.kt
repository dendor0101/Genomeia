package io.github.some_example_name.physics.lab.tuning

import io.github.some_example_name.physics.AREA_COMPLIANCE
import io.github.some_example_name.physics.AREA_COMPLIANCE_INVERTED
import io.github.some_example_name.physics.AREA_MAX_STEP
import io.github.some_example_name.physics.BONE_MAX_STEP
import io.github.some_example_name.physics.CCD_CORE
import io.github.some_example_name.physics.CONTACTS_ON
import io.github.some_example_name.physics.CONTACT_FRICTION
import io.github.some_example_name.physics.CONTACT_MAX_STEP
import io.github.some_example_name.physics.CONTACT_RESTITUTION
import io.github.some_example_name.physics.CONTACT_SCALE
import io.github.some_example_name.physics.FLOW_DECAY
import io.github.some_example_name.physics.FLOW_ENTRAIN
import io.github.some_example_name.physics.FLOW_MASS
import io.github.some_example_name.physics.GAIT_DUTY
import io.github.some_example_name.physics.GAIT_PERIOD
import io.github.some_example_name.physics.LINK_MAX_STRETCH
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.MEDIUM_DRAG
import io.github.some_example_name.physics.MUSCLE_CONTRACTION
import io.github.some_example_name.physics.MUSCLE_RATE_CONTRACT
import io.github.some_example_name.physics.MUSCLE_RATE_RELAX
import io.github.some_example_name.physics.NORMAL_DRAG
import io.github.some_example_name.physics.NORMAL_DRAG_QUADRATIC
import io.github.some_example_name.physics.PLASTIC_HOLD
import io.github.some_example_name.physics.PLASTIC_RATE
import io.github.some_example_name.physics.PLASTIC_YIELD
import io.github.some_example_name.physics.SOFT_COMPLIANCE
import io.github.some_example_name.physics.TANGENT_DRAG
import io.github.some_example_name.physics.VISCOSITY
import io.github.some_example_name.physics.bendCompliance
import kotlin.math.abs
import kotlin.math.sqrt

/** Всё, что подбирается. Умолчания читаются из DemoSession, см. [DemoConst]. */
data class SwimParams(
    val normalDrag: Double = DemoConst.NORMAL_DRAG,
    val normalDragQuadratic: Double = DemoConst.NORMAL_DRAG_QUADRATIC,
    /** Трение ВДОЛЬ ребра, долей от normalDrag. См. TANGENT_DRAG в DemoSession. */
    val tangentDrag: Double = DemoConst.TANGENT_DRAG,
    val mediumDrag: Double = DemoConst.MEDIUM_DRAG,
    val viscosity: Double = DemoConst.VISCOSITY,

    /** Масса увлечённой среды в массах тела. Тело — n частиц массой 1. */
    val flowMass: Double = DemoConst.FLOW_MASS,
    val flowDecay: Double = DemoConst.FLOW_DECAY,
    val flowEntrain: Double = DemoConst.FLOW_ENTRAIN,

    val muscleContraction: Double = DemoConst.MUSCLE_CONTRACTION,
    val muscleRateContract: Double = DemoConst.MUSCLE_RATE_CONTRACT,
    val muscleRateRelax: Double = DemoConst.MUSCLE_RATE_RELAX,

    val gaitPeriod: Int = DemoConst.GAIT_PERIOD,
    /** Доля периода на рабочую фазу. */
    val gaitDuty: Double = DemoConst.GAIT_DUTY,

    val softCompliance: Double = DemoConst.SOFT_COMPLIANCE,
    val areaCompliance: Double = DemoConst.AREA_COMPLIANCE,

    /**
     * Отдельная податливость площади для ВЫВЕРНУТЫХ треугольников. -1 — выключено,
     * используется обычная (тогда поведение совпадает с DemoSession побитово).
     *
     * Зачем разделять. Податливость нужна, чтобы не усиливать шум float РЯДОМ С
     * РАВНОВЕСИЕМ — там невязка это чистое округление, и жёсткое ограничение его
     * раздувает. Вывернутый треугольник от равновесия бесконечно далеко: невязка у него
     * порядка удвоенной площади покоя, то есть на четыре порядка больше шума. Фильтровать
     * там нечего, а мягкость только мешает распрямиться.
     */
    val areaComplianceInverted: Double = DemoConst.AREA_COMPLIANCE_INVERTED,

    /**
     * ПОТОЛОК на норму поправки одного треугольника за подшаг, в долях средней связи.
     * 0 — выключено.
     *
     * Лечит РЫВОК. Жёсткая ветка выправляет вывернутый треугольник за один подшаг
     * целиком, а updateVelocities делит это смещение на h и превращает в скорость,
     * которой там взяться неоткуда. Потолок оставляет силу прежней, но растягивает
     * выправление на несколько подшагов: медленнее, зато без выстрела.
     */
    val areaMaxStep: Double = DemoConst.AREA_MAX_STEP,

    /** Потолок на смещение вершины проекцией кости за подшаг. См. BONE_MAX_STEP. */
    val boneMaxStep: Double = DemoConst.BONE_MAX_STEP,

    // --- самоконтакт границы, см. BoundaryContacts ---
    val contactsOn: Boolean = DemoConst.CONTACTS_ON,
    val contactScale: Double = DemoConst.CONTACT_SCALE,
    val ccdCore: Double = DemoConst.CCD_CORE,
    val contactMaxStep: Double = DemoConst.CONTACT_MAX_STEP,
    val plasticRate: Double = DemoConst.PLASTIC_RATE,
    val plasticYield: Double = DemoConst.PLASTIC_YIELD,
    val plasticHold: Int = DemoConst.PLASTIC_HOLD,
    val contactRestitution: Double = DemoConst.CONTACT_RESTITUTION,
    val contactFriction: Double = DemoConst.CONTACT_FRICTION,
    val maxSpeedCellsPerTick: Double = DemoConst.MAX_SPEED_CELLS_PER_TICK,
    val linkMaxStretch: Double = DemoConst.LINK_MAX_STRETCH,

    /**
     * Плавный переход между мягкой и жёсткой ветками вместо ступеньки.
     *
     * Лечит ДРЕБЕЗГ. Ступенька означает разрыв жёсткости в 55 раз ровно на нулевой
     * площади, и треугольник, болтающийся около неё, каждый подшаг попадает то в одну
     * ветку, то в другую. Плавный переход убирает саму границу.
     */
    val areaSmoothRamp: Boolean = true,

    /**
     * Податливость ограничения ИЗГИБА граничного контура. -1 — стадия выключена.
     *
     * Единственное, что вообще способно сделать заворот ткани дорогим: знаковая площадь
     * ЛОКАЛЬНА и сложенный лист для неё выглядит здоровым. См. [Topology.bendA].
     *
     * Ставить жёстко нельзя: контур это гребущая поверхность, и лишняя жёсткость там
     * бьёт прямо по тяге. Величину надо мерить, а не назначать.
     */
    val bendCompliance: Double = -1.0,
    val flowModel: FlowModel = FlowModel.GLOBAL_TRACKING,
    /** Предел поправки связи независим от контактов и числа подшагов. */
    val linkMaxStep: Double = DemoConst.LINK_MAX_STEP,
)

