package io.github.some_example_name.physics.lab.inspection

import io.github.some_example_name.physics.AREA_COMPLIANCE
import io.github.some_example_name.physics.AREA_COMPLIANCE_INVERTED
import io.github.some_example_name.physics.AREA_MAX_STEP
import io.github.some_example_name.physics.AREA_SMOOTH_RAMP
import io.github.some_example_name.physics.BONE_MAX_STEP
import io.github.some_example_name.physics.CANCEL_INTERNAL_SPIN
import io.github.some_example_name.physics.CCD_CORE
import io.github.some_example_name.physics.CONTACTS_ON
import io.github.some_example_name.physics.CONTACT_FRICTION
import io.github.some_example_name.physics.CONTACT_MAX_STEP
import io.github.some_example_name.physics.CONTACT_RESTITUTION
import io.github.some_example_name.physics.CONTACT_SCALE
import io.github.some_example_name.physics.DRAG_ACCEL
import io.github.some_example_name.physics.DRAG_MATCH_WEIGHT
import io.github.some_example_name.physics.DRAG_SPEED_LIMIT
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
import io.github.some_example_name.physics.BoundaryContacts
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.BULLET_SPEED_FRACTION
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.BakedTopology

internal object DemoInspector {
    fun read(d: DemoSession, name: String): Any? = when(name) {
        "body" -> d.state.body
        "n" -> d.state.n
        "px" -> d.state.px
        "py" -> d.state.py
        "prevX" -> d.state.prevX
        "prevY" -> d.state.prevY
        "vx" -> d.state.vx
        "vy" -> d.state.vy
        "invMass" -> d.state.invMass
        "restInvMass" -> d.state.restInvMass
        "contacts" -> d.state.contacts
        "everLinked" -> d.state.everLinked
        "contactsOn" -> d.state.contactsOn
        "radius" -> d.state.radius
        "isFree" -> d.state.isFree
        "probe" -> d.state.probe
        "probeAngle" -> d.state.probeAngle
        "cellDead" -> d.state.cellDead
        "deadCount" -> d.state.deadCount
        "matchWeight" -> d.state.matchWeight
        "conCount" -> d.state.conCount
        "conA" -> d.state.conA
        "conB" -> d.state.conB
        "conRest" -> d.state.conRest
        "conMuscle" -> d.state.conMuscle
        "bendCount" -> d.state.bendCount
        "bendA" -> d.state.bendA
        "bendB" -> d.state.bendB
        "bendRest" -> d.state.bendRest
        "bendLevel" -> d.state.bendLevel
        "boundCount" -> d.state.boundCount
        "boundOpp" -> d.state.boundOpp
        "boundA" -> d.state.boundA
        "boundB" -> d.state.boundB
        "organismOf" -> d.state.organismOf
        "organismCount" -> d.state.organismCount
        "organismSize" -> d.state.organismSize
        "organismMass" -> d.state.organismMass
        "flowVX" -> d.state.flowVX
        "flowVY" -> d.state.flowVY
        "lnkA" -> d.state.lnkA
        "lnkB" -> d.state.lnkB
        "lnkCount" -> d.state.lnkCount
        "triA" -> d.state.triA
        "triB" -> d.state.triB
        "triC" -> d.state.triC
        "triRestArea2" -> d.state.triRestArea2
        "triCount" -> d.state.triCount
        "baked" -> d.state.baked
        "lnkEdge" -> d.state.lnkEdge
        "triId" -> d.state.triId
        "conEdge" -> d.state.conEdge
        "conOfEdge" -> d.state.conOfEdge
        "triMuscle" -> d.state.triMuscle
        "rigidBones" -> d.state.rigidBones
        "boneOf" -> d.state.boneOf
        "boneRestQx" -> d.state.boneRestQx
        "boneRestQy" -> d.state.boneRestQy
        "muscleOf" -> d.state.muscleOf
        "muscleActivation" -> d.state.muscleActivation
        "hoveredMuscle" -> d.hoveredMuscle
        "muscleTarget" -> d.state.muscleTarget
        "gait" -> d.gait
        "gaitFrame" -> d.gaitFrame
        "bonesRigid" -> d.state.bonesRigid
        "dragId" -> d.state.dragId
        "mouseX" -> d.state.mouseX
        "mouseY" -> d.state.mouseY
        "piercedCount" -> d.state.piercedCount
        "muscleContraction" -> d.state.muscleContraction
        "muscleRateContract" -> d.state.muscleRateContract
        "muscleRateRelax" -> d.state.muscleRateRelax
        "gaitPeriod" -> d.state.gaitPeriod
        "gaitDuty" -> d.state.gaitDuty
        "muscleTouch" -> d.state.muscleTouch
        "muscleScaleOf" -> d.state.muscleScaleOf
        "muscleActivationPrev" -> d.state.muscleActivationPrev
        "conHold" -> d.state.conHold
        "triHold" -> d.state.triHold
        "linkTorn" -> d.state.linkTorn
        "tearingOn" -> d.state.tearingOn
        "conDead" -> d.state.conDead
        "tearsPending" -> d.state.tearsPending
        "tearQueue" -> d.state.tearQueue
        "tearQueueN" -> d.state.tearQueueN
        "tornTotal" -> d.state.tornTotal
        "scenes" -> d.scenes
        "dbgNoClamp" -> d.state.dbgNoClamp
        "speedCapHits" -> d.state.speedCapHits
        "peakSpeed2" -> d.state.peakSpeed2
        "sweepBackwards" -> d.state.sweepBackwards
        "dbgStages" -> d.state.dbgStages
        "dbgStageMove" -> d.state.dbgStageMove
        "dbgStageL" -> d.state.dbgStageL
        "dbgStagePx" -> d.state.dbgStagePx
        "dbgStagePy" -> d.state.dbgStagePy
        "dbgStageHits" -> d.state.dbgStageHits
        "dbgOnly" -> d.state.dbgOnly
        "dbgDragOff" -> d.state.dbgDragOff
        "pressureBurstCount" -> d.state.pressureBurstCount
        "crushBurstCount" -> d.state.crushBurstCount
        "conBoundary" -> d.state.conBoundary
        "trapKillCount" -> d.state.trapKillCount
        "trapTicks" -> d.state.trapTicks
        "trapOrg" -> d.state.trapOrg
        "foldTicks" -> d.state.foldTicks
        "trapCrash" -> d.state.trapCrash
        "replay" -> d.replay
        else -> error("Unknown demo field: $name")
    }
    fun write(d: DemoSession, name: String, value: Any?) { when(name) {
        "body" -> { d.state.body = (value as BodyDefinition) }
        "n" -> { d.state.n = (value as Int) }
        "px" -> { d.state.px = (value as DoubleArray) }
        "py" -> { d.state.py = (value as DoubleArray) }
        "prevX" -> { d.state.prevX = (value as DoubleArray) }
        "prevY" -> { d.state.prevY = (value as DoubleArray) }
        "vx" -> { d.state.vx = (value as DoubleArray) }
        "vy" -> { d.state.vy = (value as DoubleArray) }
        "invMass" -> { d.state.invMass = (value as DoubleArray) }
        "restInvMass" -> { d.state.restInvMass = (value as DoubleArray) }
        "contacts" -> { d.state.contacts = (value as BoundaryContacts?) }
        "everLinked" -> { d.state.everLinked = (value as Set<Long>?) }
        "contactsOn" -> { d.state.contactsOn = (value as Boolean) }
        "radius" -> { d.state.radius = (value as DoubleArray) }
        "isFree" -> { d.state.isFree = (value as BooleanArray) }
        "probe" -> { d.state.probe = (value as BooleanArray) }
        "probeAngle" -> { d.state.probeAngle = (value as DoubleArray) }
        "cellDead" -> { d.state.cellDead = (value as BooleanArray) }
        "deadCount" -> { d.state.deadCount = (value as Int) }
        "matchWeight" -> { d.state.matchWeight = (value as DoubleArray) }
        "conCount" -> { d.state.conCount = (value as Int) }
        "conA" -> { d.state.conA = (value as IntArray) }
        "conB" -> { d.state.conB = (value as IntArray) }
        "conRest" -> { d.state.conRest = (value as DoubleArray) }
        "conMuscle" -> { d.state.conMuscle = (value as IntArray) }
        "bendCount" -> { d.state.bendCount = (value as Int) }
        "bendA" -> { d.state.bendA = (value as IntArray) }
        "bendB" -> { d.state.bendB = (value as IntArray) }
        "bendRest" -> { d.state.bendRest = (value as DoubleArray) }
        "bendLevel" -> { d.state.bendLevel = (value as Int) }
        "boundCount" -> { d.state.boundCount = (value as Int) }
        "boundOpp" -> { d.state.boundOpp = (value as IntArray) }
        "boundA" -> { d.state.boundA = (value as IntArray) }
        "boundB" -> { d.state.boundB = (value as IntArray) }
        "organismOf" -> { d.state.organismOf = (value as IntArray) }
        "organismCount" -> { d.state.organismCount = (value as Int) }
        "organismSize" -> { d.state.organismSize = (value as IntArray) }
        "organismMass" -> { d.state.organismMass = (value as DoubleArray) }
        "flowVX" -> { d.state.flowVX = (value as DoubleArray) }
        "flowVY" -> { d.state.flowVY = (value as DoubleArray) }
        "lnkA" -> { d.state.lnkA = (value as IntArray) }
        "lnkB" -> { d.state.lnkB = (value as IntArray) }
        "lnkCount" -> { d.state.lnkCount = (value as Int) }
        "triA" -> { d.state.triA = (value as IntArray) }
        "triB" -> { d.state.triB = (value as IntArray) }
        "triC" -> { d.state.triC = (value as IntArray) }
        "triRestArea2" -> { d.state.triRestArea2 = (value as FloatArray) }
        "triCount" -> { d.state.triCount = (value as Int) }
        "baked" -> { d.state.baked = (value as BakedTopology) }
        "lnkEdge" -> { d.state.lnkEdge = (value as IntArray) }
        "triId" -> { d.state.triId = (value as IntArray) }
        "conEdge" -> { d.state.conEdge = (value as IntArray) }
        "conOfEdge" -> { d.state.conOfEdge = (value as IntArray) }
        "triMuscle" -> { d.state.triMuscle = (value as IntArray) }
        "rigidBones" -> { d.state.rigidBones = (value as Array<IntArray>) }
        "boneOf" -> { d.state.boneOf = (value as IntArray) }
        "boneRestQx" -> { d.state.boneRestQx = (value as Array<DoubleArray>) }
        "boneRestQy" -> { d.state.boneRestQy = (value as Array<DoubleArray>) }
        "muscleOf" -> { d.state.muscleOf = (value as IntArray) }
        "muscleActivation" -> { d.state.muscleActivation = (value as DoubleArray) }
        "hoveredMuscle" -> { d.hoveredMuscle = (value as Int) }
        "muscleTarget" -> { d.state.muscleTarget = (value as DoubleArray) }
        "gait" -> { d.gait = (value as Boolean) }
        "gaitFrame" -> { d.gaitFrame = (value as Int) }
        "bonesRigid" -> { d.state.bonesRigid = (value as Boolean) }
        "dragId" -> { d.state.dragId = (value as Int) }
        "mouseX" -> { d.state.mouseX = (value as Double) }
        "mouseY" -> { d.state.mouseY = (value as Double) }
        "piercedCount" -> { d.state.piercedCount = (value as Int) }
        "muscleContraction" -> { d.state.muscleContraction = (value as Double) }
        "muscleRateContract" -> { d.state.muscleRateContract = (value as Double) }
        "muscleRateRelax" -> { d.state.muscleRateRelax = (value as Double) }
        "gaitPeriod" -> { d.state.gaitPeriod = (value as Int) }
        "gaitDuty" -> { d.state.gaitDuty = (value as Double) }
        "muscleTouch" -> { d.state.muscleTouch = (value as Int) }
        "muscleScaleOf" -> { d.state.muscleScaleOf = (value as DoubleArray) }
        "muscleActivationPrev" -> { d.state.muscleActivationPrev = (value as DoubleArray) }
        "conHold" -> { d.state.conHold = (value as ByteArray) }
        "triHold" -> { d.state.triHold = (value as ByteArray) }
        "linkTorn" -> { d.state.linkTorn = (value as BooleanArray) }
        "tearingOn" -> { d.state.tearingOn = (value as Boolean) }
        "conDead" -> { d.state.conDead = (value as BooleanArray) }
        "tearsPending" -> { d.state.tearsPending = (value as Boolean) }
        "tearQueue" -> { d.state.tearQueue = (value as IntArray) }
        "tearQueueN" -> { d.state.tearQueueN = (value as Int) }
        "tornTotal" -> { d.state.tornTotal = (value as Int) }
        "dbgNoClamp" -> { d.state.dbgNoClamp = (value as Boolean) }
        "speedCapHits" -> { d.state.speedCapHits = (value as Int) }
        "peakSpeed2" -> { d.state.peakSpeed2 = (value as Double) }
        "sweepBackwards" -> { d.state.sweepBackwards = (value as Boolean) }
        "dbgStages" -> { d.state.dbgStages = (value as Boolean) }
        "dbgOnly" -> { d.state.dbgOnly = (value as BooleanArray?) }
        "dbgDragOff" -> { d.state.dbgDragOff = (value as Boolean) }
        "pressureBurstCount" -> { d.state.pressureBurstCount = (value as Int) }
        "crushBurstCount" -> { d.state.crushBurstCount = (value as Int) }
        "conBoundary" -> { d.state.conBoundary = (value as BooleanArray) }
        "trapKillCount" -> { d.state.trapKillCount = (value as Int) }
        "trapTicks" -> { d.state.trapTicks = (value as IntArray) }
        "trapOrg" -> { d.state.trapOrg = (value as IntArray) }
        "foldTicks" -> { d.state.foldTicks = (value as IntArray) }
        "trapCrash" -> { d.state.trapCrash = (value as Int) }
        "replay" -> { d.replay = (value as List<PlayerLog.Line>?) }
        else -> error("Not a mutable demo field: $name")
    } }
    fun constant(name: String, d: DemoSession? = null): Any = when(name) {
        "DT" -> DT
        "SUBSTEPS" -> SUBSTEPS
        "FLAP_COMPLIANCE" -> FLAP_COMPLIANCE
        "SOFT_COMPLIANCE" -> SOFT_COMPLIANCE
        "AREA_COMPLIANCE" -> AREA_COMPLIANCE
        "AREA_COMPLIANCE_INVERTED" -> AREA_COMPLIANCE_INVERTED
        "AREA_MAX_STEP" -> AREA_MAX_STEP
        "AREA_SMOOTH_RAMP" -> AREA_SMOOTH_RAMP
        "BONE_MAX_STEP" -> BONE_MAX_STEP
        "CONTACTS_ON" -> CONTACTS_ON
        "CONTACT_SCALE" -> CONTACT_SCALE
        "CONTACT_MAX_STEP" -> (d?.state?.config ?: DemoSettings.defaults).CONTACT_MAX_STEP
        "CCD_CORE" -> CCD_CORE
        "CONTACT_RESTITUTION" -> CONTACT_RESTITUTION
        "CONTACT_FRICTION" -> CONTACT_FRICTION
        "MAX_SPEED_CELLS_PER_TICK" -> MAX_SPEED_CELLS_PER_TICK
        "LINK_MAX_STRETCH" -> LINK_MAX_STRETCH
        "LINK_TEAR_STRAIN" -> LINK_TEAR_STRAIN
        "LINK_CRUSH_RATIO" -> LINK_CRUSH_RATIO
        "PLASTIC_RATE" -> PLASTIC_RATE
        "PLASTIC_YIELD" -> PLASTIC_YIELD
        "PLASTIC_HOLD" -> PLASTIC_HOLD
        "DRAG_ACCEL" -> (d?.state?.config ?: DemoSettings.defaults).DRAG_ACCEL
        "DRAG_MATCH_WEIGHT" -> DRAG_MATCH_WEIGHT
        "DRAG_SPEED_LIMIT" -> (d?.state?.config ?: DemoSettings.defaults).DRAG_SPEED_LIMIT
        "BULLET_SPEED_FRACTION" -> BULLET_SPEED_FRACTION
        "MEDIUM_DRAG" -> MEDIUM_DRAG
        "NORMAL_DRAG" -> NORMAL_DRAG
        "NORMAL_DRAG_QUADRATIC" -> NORMAL_DRAG_QUADRATIC
        "TANGENT_DRAG" -> TANGENT_DRAG
        "FLOW_ENTRAIN" -> FLOW_ENTRAIN
        "FLOW_MASS" -> FLOW_MASS
        "FLOW_DECAY" -> FLOW_DECAY
        "VISCOSITY" -> VISCOSITY
        "CANCEL_INTERNAL_SPIN" -> CANCEL_INTERNAL_SPIN
        "SOLVER_ITERS" -> SOLVER_ITERS
        "SWEEP_FLIP" -> SWEEP_FLIP
        "MUSCLE_CONTRACTION" -> MUSCLE_CONTRACTION
        "GAIT_PERIOD" -> GAIT_PERIOD
        "GAIT_DUTY" -> GAIT_DUTY
        "MUSCLE_RATE_CONTRACT" -> MUSCLE_RATE_CONTRACT
        "MUSCLE_RATE_RELAX" -> MUSCLE_RATE_RELAX
        else -> error("Unknown physics parameter: $name")
    }
}
