package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.DISimulationContainer as World
import io.github.some_example_name.old.features.simulation.SimulationScreen
import io.github.some_example_name.old.journal.SessionJournal
import java.util.UUID
import kotlin.math.*
import kotlinx.serialization.json.*

internal class ForwardDistance @JvmOverloads constructor(
    val dx: Double,
    val dy: Double,
    val target: Double,
    val duration: Int,
    val window: Int,
    val maxBackwardFraction: Double,
    val minForwardEfficiency: Double = 0.0,
) {
    var elapsed = 0
    var forward = 0.0
    var backward = 0.0
    var lateral = 0.0
    var maxLateral = 0.0
    var path = 0.0
    var windowStart = 0.0
    var reachedAt: Int? = null
    var peak = 0.0
    var maxRetreat = 0.0
    val segments = mutableListOf<Double>()

    fun record(sx: Double, sy: Double) {
        val along = sx * dx + sy * dy
        forward += max(0.0, along)
        backward += max(0.0, -along)
        lateral += sy * dx - sx * dy
        maxLateral = max(maxLateral, abs(lateral))
        path += hypot(sx, sy)
        elapsed++
        val net = forward - backward
        peak = max(peak, net)
        maxRetreat = max(maxRetreat, peak - net)
        if (net >= target && reachedAt == null) reachedAt = elapsed
        if (elapsed % window == 0 || elapsed == duration) {
            segments += net - windowStart
            windowStart = net
        }
    }

    val net
        get() = forward - backward

    val forwardEfficiency
        get() = if (path > 0.0) (net / path).coerceIn(-1.0, 1.0) else 0.0

    val sustained
        get() =
            elapsed == duration &&
                net >= target &&
                segments.all { it > 0 } &&
                backward <= maxBackwardFraction * (forward + backward) &&
                forwardEfficiency >= minForwardEfficiency
}

/** A single bounded observation on the live world. Never steps or unpauses the simulation. */
internal object MotionTrial {
    private class Trial(
        val slot: Int,
        val generation: Int,
        val dx: Double,
        val dy: Double,
        val target: Double,
        val duration: Int,
        val window: Int,
        tolerance: Double,
        efficiency: Double,
    ) {
        val id = "motion:" + UUID.randomUUID()
        val epoch = SessionJournal.epoch
        val start = World.simulationData.tickCounter
        var status = "running"
        var count = 0
        var lastX = 0.0
        var lastY = 0.0
        val track = ForwardDistance(dx, dy, target, duration, window, tolerance, efficiency)
        val elapsed
            get() = track.elapsed

        fun sample() {
            val o = World.organEntity
            val c = World.cellEntity
            if (epoch != SessionJournal.epoch) {
                status = "world_changed"
                return
            }
            if (
                slot !in o.isAlive.indices ||
                    !o.isAlive[slot] ||
                    generation != o.getGeneration(slot)
            ) {
                status = "organism_lost"
                return
            }
            val base = o.cellArenaBase[slot]
            if (base < 0) {
                status = "body_changed"
                return
            }
            var n = 0
            var x = 0.0
            var y = 0.0
            for (i in base until base + o.cellArenaCapacity[slot]) {
                if (!c.isAlive[i] || c.organIndex[i] != slot) continue
                n++
                x += c.getX(i)
                y += c.getY(i)
            }
            if (!x.isFinite() || !y.isFinite() || n == 0 || count != 0 && n != count) {
                status = "body_changed"
                return
            }
            x /= n
            y /= n
            if (count != 0) {
                val sx = x - lastX
                val sy = y - lastY
                track.record(sx, sy)
                if (elapsed == duration) status = "complete"
            }
            count = n
            lastX = x
            lastY = y
        }

        fun report() =
            with(track) {
                obj(
                    "id" to id,
                    "status" to status,
                    "startTick" to start,
                    "elapsedTicks" to elapsed,
                    "durationTicks" to duration,
                    "windowTicks" to window,
                    "axisX" to dx,
                    "axisY" to dy,
                    "targetDistance" to target,
                    "firstReachedAtTickOffset" to reachedAt,
                    "netForward" to forward - backward,
                    "positiveTravel" to forward,
                    "backwardTravel" to backward,
                    "pathLength" to path,
                    "forwardEfficiency" to forwardEfficiency,
                    "minForwardEfficiency" to minForwardEfficiency,
                    "maxLateralDeviation" to maxLateral,
                    "windowProgress" to segments,
                    "netForwardPer1000Ticks" to
                        if (elapsed > 0) (forward - backward) * 1000 / elapsed else null,
                    "maxRetreat" to maxRetreat,
                    "maxBackwardFraction" to maxBackwardFraction,
                    "sustainedForward" to (status == "complete" && sustained),
                )
            }
    }

    private var last: Trial? = null

    fun start(a: JsonObject): JsonObject {
        check(game.screen is SimulationScreen) {
            "Open the simulation and select an adult via read_state(view=organisms)."
        }
        return World.simulationSystem.betweenTicks {
            check(
                last?.status != "running" ||
                    World.simulationSystem.completedTickObserver == null ||
                    last?.epoch != SessionJournal.epoch
            ) {
                "A trial is running. Read or cancel it first."
            }
            val o = World.organEntity
            val slot = a.number("organism", -1, o.isAlive.indices)
            val generation = a.getValue("generation").jsonPrimitive.int
            require(
                o.isAlive[slot] && o.getGeneration(slot) == generation && o.alreadyGrownUp[slot]
            ) {
                "Select the current generation of a living adult."
            }
            val x = a.getValue("forwardX").jsonPrimitive.double
            val y = a.getValue("forwardY").jsonPrimitive.double
            val len = hypot(x, y)
            val target = a.getValue("distance").jsonPrimitive.double
            require(len.isFinite() && len > 0 && target.isFinite() && target > 0) {
                "Supply a nonzero world-space direction and positive distance."
            }
            val duration = a.number("ticks", 1200, 20..36000)
            val window = a.number("windowTicks", minOf(60, duration / 2), 1..duration / 2)
            require((duration + window - 1) / window <= 600) { "Use at most 600 windows." }
            val tolerance = a["maxBackwardFraction"]?.jsonPrimitive?.double ?: 0.1
            require(tolerance in 0.0..1.0) { "maxBackwardFraction must be between 0 and 1" }
            val efficiency = a["minForwardEfficiency"]?.jsonPrimitive?.double ?: 0.0
            require(efficiency in 0.0..1.0) { "minForwardEfficiency must be between 0 and 1" }
            val t = Trial(slot, generation, x / len, y / len, target, duration, window, tolerance, efficiency)
            t.sample()
            last = t
            if (t.status == "running")
                World.simulationSystem.completedTickObserver = {
                    t.sample()
                    if (t.status != "running") World.simulationSystem.completedTickObserver = null
                }
            t.report()
        }
    }

    fun read(a: JsonObject): JsonObject =
        World.simulationSystem.betweenTicks {
            val t = last
            require(t != null && t.id == a.string("id")) { "Unknown motion trial" }
            if (t.status == "running") {
                if (
                    game.screen !is SimulationScreen ||
                        t.epoch != SessionJournal.epoch ||
                        World.simulationSystem.completedTickObserver == null
                )
                    t.status = "world_changed"
                else if (a["cancel"]?.jsonPrimitive?.boolean == true) t.status = "cancelled"
                if (t.status != "running") World.simulationSystem.completedTickObserver = null
            }
            t.report()
        }
}
