package io.github.some_example_name.old.features.guide.celllab

import com.badlogic.gdx.graphics.Color
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.cells.synapseState
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.*
import io.github.some_example_name.old.journal.CellLabInput
import java.util.Locale
import kotlin.math.sqrt

/** Эксперимент рядом с управлением; на узком экране мир идёт первым. */
internal class CellLabView(private val session: CellLabSession) {
    private val canvas = CellLabCanvas(session)
    private var readout: VisLabel? = null
    private var spawnButton: VisTextButton? = null

    fun compose(table: VisTable, width: Float, onClose: () -> Unit, rebuild: () -> Unit) = with(table) {
        val model = session.model ?: return@with
        spawnButton = null
        val inner = width - 32.dp()
        defaults().left().width(inner)
        visTable {
            visTextButton(bundle.get("guide.lab.back"), onClick = onClose) { padRight(16.dp()) }
            if (inner < 460.dp()) row()
            visLabel(model.world.cellList[model.world.cellList.indexOfKey(model.key)].name, font = game.extraLargeFont) { growX().minWidth(0f) }.setWrap(true)
        }
        row()
        visLabel(bundle.get("guide.lab.${model.key}"), textColor = Color(0.5f, 0.85f, 1f, 1f), font = game.largeFont) { padTop(12.dp()) }.setWrap(true)
        row()
        if (width >= 1000.dp()) {
            val sceneWidth = (inner * 0.55f).coerceAtMost(600.dp())
            val controlsWidth = inner - sceneWidth - 24.dp()
            visTable({ padTop(12.dp()) }) {
                visTable({ width(sceneWidth).top().padRight(24.dp()) }) { scene(sceneWidth) }
                visTable({ width(controlsWidth).top() }) { controls(controlsWidth, rebuild) }
            }
        } else {
            val sceneSize = (inner * 0.58f).coerceAtMost(360.dp())
            visTable({ width(inner).padTop(12.dp()) }) {
                add(canvas).size(sceneSize).top().padRight(12.dp())
                readout = visLabel("", font = game.mediumFont) {
                    width(inner - sceneSize - 12.dp()).top().left()
                }.apply { setWrap(true) }
            }
            row()
            visTable({ width(inner).padTop(12.dp()) }) { controls(inner, rebuild) }
        }
    }

    private fun VisTable.scene(size: Float) {
        defaults().left().width(size)
        add(canvas).size(size)
        row()
        readout = visLabel("", font = game.mediumFont) { padTop(8.dp()) }.apply { setWrap(true) }
    }

    private fun VisTable.controls(width: Float, rebuild: () -> Unit) {
        val model = session.model ?: return
        defaults().left().width(width)
        visTable {
            visToggleButton(bundle.get("guide.lab.pause"), checked = model.paused,
                onCheckedChange = { session.input(CellLabInput("pause", value = if (it) 1f else 0f)) }) { padRight(12.dp()) }
            visTextButton(bundle.get("guide.lab.reset"), onClick = {
                session.input(CellLabInput("reset")); rebuild()
            })
        }
        row()
        for (control in model.controls) {
            val name = bundle.get("guide.lab.control.${control.key}")
            if (control.key == "channels") {
                visLabel(name) { padTop(12.dp()) }; row()
                visTable {
                    for ((i, channel) in listOf("R", "G", "B").withIndex()) {
                        val bit = 1 shl i
                        visToggleButton(channel, checked = model.value("channels").toInt() and bit != 0,
                            onCheckedChange = { on ->
                                val old = session.model!!.value("channels").toInt()
                                session.input(CellLabInput("set", "channels", (if (on) old or bit else old and bit.inv()).toFloat()))
                            }) { padRight(8.dp()) }
                    }
                }
            } else if (control.key in setOf("prey", "formula", "key", "wave")) {
                visLabel(name) { padTop(12.dp()) }; row()
                val items = when (control.key) {
                    "prey" -> CellLabModel.PREY_TYPES.map { model.world.cellList[model.world.cellList.indexOfKey(it)].name }.toTypedArray()
                    "key" -> CellLabModel.KEYS
                    "wave" -> arrayOf(bundle.get("guide.lab.wave.pulse"), bundle.get("guide.lab.wave.sine"))
                    else -> arrayOf("y = ax + b", "y = sigmoid(ax + b)", "x <= a", "y = sin(ax + b)")
                }
                visSelectBox(items = items, selectedIndex = model.value(control.key).toInt(),
                    onChange = { _, index -> session.input(CellLabInput("set", control.key, index.toFloat())) }) { fillX() }
            } else if (control.key == "feed" || control.key == "pressed" || control.key == "binary") {
                visToggleButton(name, checked = model.value(control.key) > 0f,
                    onCheckedChange = { session.input(CellLabInput("set", control.key, if (it) 1f else 0f)) }) { padTop(12.dp()).fillX() }
            } else {
                val label = visLabel("$name: ${format(model.value(control.key))}") { padTop(12.dp()) }
                label.setWrap(true)
                row()
                visSlider(control.min, control.max, control.step, value = model.value(control.key), onValueChange = {
                    session.input(CellLabInput("set", control.key, it))
                    label.setText("$name: ${format(it)}")
                }) { fillX().height(48.dp()) }
            }
            row()
        }
        if (model.key == "leaf" || model.key == "fat") {
            visTextButton(bundle.get("guide.lab.drain"), onClick = { session.input(CellLabInput("drain")) }) { padTop(12.dp()).fillX() }
            row()
        }
        if (model.key in setOf("sucker", "punisher", "pumper")) {
            spawnButton = visTextButton(bundle.get("guide.lab.add.${model.key}"),
                onClick = { session.input(CellLabInput("spawn")) }) { padTop(12.dp()).fillX() }
                .apply { name = "cell-lab-spawn"; isDisabled = !model.canSpawn }
            row()
        }
    }

    fun update(delta: Float) {
        session.update(delta)
        canvas.renderPreview()
        spawnButton?.apply {
            isDisabled = session.model?.canSpawn != true
            color.a = if (isDisabled) 0.45f else 1f
        }
        refreshReadout()
    }

    private fun refreshReadout() {
        val model = session.model ?: return
        val c = model.c
        val a = model.primary
        val b = model.companion
        val text = buildString {
            if (!model.primaryAlive) {
                append(bundle.get("guide.lab.dead"))
                return@buildString
            }
            fun metric(key: String, v: Float) {
                if (isNotEmpty()) append('\n')
                append(bundle.get("guide.lab.metric.$key")).append(": ").append(format(v))
            }
            if (model.key in ENERGY_CELLS) metric("energy", c.energy[a])
            when (model.key) {
                "eye", "touch_trigger", "controller", "neuron", "pheromone_sensor" -> metric("output", c.neuronImpulseOutput[a])
                "muscle" -> {
                    metric("length", c.degreeOfShortening[a])
                    if (c.isAlive[b] && c.isAlive[model.source]) {
                        val dx = c.getX(b) - c.getX(model.source); val dy = c.getY(b) - c.getY(model.source)
                        metric("span", sqrt(dx * dx + dy * dy))
                    }
                }
                "suction_cup" -> metric("friction", model.p.dragCoefficient[c.getParticleIndex(a)])
                "tail" -> {
                    val vx = c.getVx(a); val vy = c.getVy(a)
                    metric("speed", sqrt(vx * vx + vy * vy))
                }
                "punisher" -> metric("kills", model.kills.toFloat())
                "synapse" -> { model.world.specialEntity.synapseState(a)?.let { metric("weight", it.weight) }; metric("output", c.neuronImpulseOutput[a]) }
                "pheromone_emitter" -> if (c.isAlive[b]) metric("sensor", c.neuronImpulseOutput[b])
                "zygote", "producer" -> metric("organisms", model.world.organEntity.aliveList.size.toFloat())
                "fat" -> if (b >= 0 && c.isAlive[b]) metric("otherEnergy", c.energy[b])
                "pumper" -> {
                    if (b >= 0 && c.isAlive[b]) metric("donorEnergy", c.energy[b])
                    else append('\n').append(bundle.get("guide.lab.donorDead"))
                    if (model.source >= 0 && c.isAlive[model.source]) metric("storedEnergy", c.energy[model.source])
                }
                "chameleon" -> metric("output", c.neuronImpulseOutput[a])
                "delay" -> {
                    if (c.isAlive[model.source]) metric("input", c.neuronImpulseOutput[model.source])
                    metric("output", c.neuronImpulseOutput[a])
                }
            }
        }
        readout?.setText(text)
    }

    private companion object {
        val ENERGY_CELLS = setOf("leaf", "fat", "tail", "sucker", "punisher", "pumper", "zygote", "producer")
    }

    fun dispose() = canvas.dispose()
    private fun format(v: Float): String = String.format(Locale.ROOT, "%.3f", v)
}
