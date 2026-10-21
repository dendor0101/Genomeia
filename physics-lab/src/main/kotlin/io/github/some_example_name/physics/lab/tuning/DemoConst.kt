package io.github.some_example_name.physics.lab.tuning

import io.github.some_example_name.physics.AREA_COMPLIANCE
import io.github.some_example_name.physics.AREA_COMPLIANCE_INVERTED
import io.github.some_example_name.physics.AREA_MAX_STEP
import io.github.some_example_name.physics.BONE_MAX_STEP
import io.github.some_example_name.physics.CANCEL_INTERNAL_SPIN
import io.github.some_example_name.physics.CCD_CORE
import io.github.some_example_name.physics.CONTACTS_ON
import io.github.some_example_name.physics.CONTACT_FRICTION
import io.github.some_example_name.physics.CONTACT_MAX_STEP
import io.github.some_example_name.physics.CONTACT_RESTITUTION
import io.github.some_example_name.physics.CONTACT_SCALE
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.FLAP_COMPLIANCE
import io.github.some_example_name.physics.FLOW_DECAY
import io.github.some_example_name.physics.FLOW_ENTRAIN
import io.github.some_example_name.physics.FLOW_MASS
import io.github.some_example_name.physics.GAIT_DUTY
import io.github.some_example_name.physics.GAIT_PERIOD
import io.github.some_example_name.physics.LINK_CRUSH_RATIO
import io.github.some_example_name.physics.LINK_MAX_STRETCH
import io.github.some_example_name.physics.LINK_TEAR_STRAIN
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
import io.github.some_example_name.physics.SOLVER_ITERS
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.SWEEP_FLIP
import io.github.some_example_name.physics.TANGENT_DRAG
import io.github.some_example_name.physics.VISCOSITY
import io.github.some_example_name.physics.lab.inspection.DemoInspector
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Параметры ядра, прочитанные явным инспектором один раз при запуске зеркала.
 *
 * Нужен, чтобы умолчания SwimParams не жили своей жизнью. Дублирование значений уже
 * трижды приводило к тому, что стенды мерили не тот режим, который стоит в демо:
 * сначала разъехались DT и SUBSTEPS, потом опорная точка тюнера с её gaitPeriod,
 * потом подписи вариантов в FoldRecovery. Каждый раз ошибка была молчаливой — числа
 * выглядели правдоподобно и просто относились к другому существу.
 *
 * Теперь источник один: DemoSession. Здесь только чтение.
 */
internal object DemoConst {
    private fun d(name: String): Double =
        (DemoInspector.constant(name) as Number).toDouble()

    private fun i(name: String): Int =
        (DemoInspector.constant(name) as Number).toInt()

    private fun b(name: String): Boolean =
        (DemoInspector.constant(name) as Boolean)

    val DT = d("DT")
    /** Податливость угла лоскута и выключатель снятия момента — берутся из демо. */
    val FLAP_COMPLIANCE = d("FLAP_COMPLIANCE")
    val CANCEL_INTERNAL_SPIN = b("CANCEL_INTERNAL_SPIN")
    val SOLVER_ITERS = i("SOLVER_ITERS")
    val SWEEP_FLIP = i("SWEEP_FLIP")
    val SUBSTEPS = i("SUBSTEPS")
    val NORMAL_DRAG = d("NORMAL_DRAG")
    val TANGENT_DRAG = d("TANGENT_DRAG")
    val NORMAL_DRAG_QUADRATIC = d("NORMAL_DRAG_QUADRATIC")
    val MEDIUM_DRAG = d("MEDIUM_DRAG")
    val VISCOSITY = d("VISCOSITY")
    val FLOW_MASS = d("FLOW_MASS")
    val FLOW_DECAY = d("FLOW_DECAY")
    val FLOW_ENTRAIN = d("FLOW_ENTRAIN")
    val MUSCLE_CONTRACTION = d("MUSCLE_CONTRACTION")
    val MUSCLE_RATE_CONTRACT = d("MUSCLE_RATE_CONTRACT")
    val MUSCLE_RATE_RELAX = d("MUSCLE_RATE_RELAX")
    val GAIT_PERIOD = i("GAIT_PERIOD")
    val GAIT_DUTY = d("GAIT_DUTY")
    val SOFT_COMPLIANCE = d("SOFT_COMPLIANCE")
    val AREA_COMPLIANCE = d("AREA_COMPLIANCE")
    val AREA_COMPLIANCE_INVERTED = d("AREA_COMPLIANCE_INVERTED")
    val AREA_MAX_STEP = d("AREA_MAX_STEP")
    val BONE_MAX_STEP = d("BONE_MAX_STEP")
    val CONTACT_SCALE = d("CONTACT_SCALE")
    val CCD_CORE = d("CCD_CORE")
    val CONTACT_MAX_STEP = d("CONTACT_MAX_STEP")
    val LINK_MAX_STEP = d("LINK_MAX_STEP")
    val PLASTIC_RATE = d("PLASTIC_RATE")
    val PLASTIC_YIELD = d("PLASTIC_YIELD")
    val PLASTIC_HOLD = i("PLASTIC_HOLD")
    val CONTACT_RESTITUTION = d("CONTACT_RESTITUTION")
    val CONTACT_FRICTION = d("CONTACT_FRICTION")
    val MAX_SPEED_CELLS_PER_TICK = d("MAX_SPEED_CELLS_PER_TICK")
    val LINK_MAX_STRETCH = d("LINK_MAX_STRETCH")
    val LINK_CRUSH_RATIO = d("LINK_CRUSH_RATIO")
    /** При каком растяжении от длины покоя связь рвётся: предел длины плюс запас. */
    val LINK_TEAR_TOTAL = d("LINK_MAX_STRETCH") + d("LINK_TEAR_STRAIN")
    val CONTACTS_ON = (DemoInspector.constant("CONTACTS_ON") as Boolean)
}

