package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.kotcrab.vis.ui.widget.VisTextField
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import io.github.some_example_name.old.journal.SessionJournal
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoGuide
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.features.guide.GuideIntent
import io.github.some_example_name.old.features.guide.GuideTab
import io.github.some_example_name.old.journal.CellLabInput
import java.io.File

/** Сквозной сценарий гайда перед основной проверкой .bug, включая снимки широкого и узкого окна. */
internal class CellLabReplayScenario(private val directory: File) {
    private var started = false
    private var index = 0
    private var phase = 0
    private var nextActionAt = 0L
    private var expectedScroll: Float? = null
    private var narrowListPrepared = false
    private val dragPosition = Vector2()
    private val keys by lazy {
        CellPalette.FULL.cellChoices(DIGameGlobalContainer.cellTypes, null).map { it.key }
    }

    /** false — ещё занят стендами, true — можно продолжать обычный bugReplayCheck. */
    private var updating = false
    fun update(): Boolean {
        // GLFW при resize синхронно вызывает ещё один render: сценарий в него не входит.
        if (updating) return false
        updating = true
        return try { advance() } finally { updating = false }
    }

    private fun advance(): Boolean {
        if (index > keys.size) return true
        val now = System.nanoTime()
        if (now < nextActionAt) return false
        if (!started) {
            started = true
            println("CellLab GL_RENDERER=${Gdx.gl.glGetString(GL20.GL_RENDERER)}")
            Gdx.graphics.setWindowedMode(1280, 900)
            DIGameGlobalContainer.navigationCommandsManager.performCommand(GoGuide)
            screen(GuideIntent.SelectTab(GuideTab.CELLS))
            nextActionAt = now + 100_000_000L
            return false
        }
        val narrow = index == keys.size
        val key = if (narrow) "eye" else keys[index]
        when (phase++) {
            0 -> {
                if (index == 0) screenshot("cells-list")
                if (!narrowListPrepared) {
                    val listStage = findStage(Gdx.input.inputProcessor, "guide-cells-list") ?: error("no guide list")
                    val list = listStage.root.findActor<ScrollPane>("guide-cells-list")
                    expectedScroll?.let { check(kotlin.math.abs(list.scrollY - it) < 1f) { "list scroll lost: ${list.scrollY} != $it" } }
                    list.scrollY = list.maxY * 0.6f
                    list.updateVisualScroll()
                    expectedScroll = list.scrollY
                    if (narrow) {
                        Gdx.graphics.setWindowedMode(400, 900)
                        narrowListPrepared = true
                        phase = 0
                        // Снимок только после отрисовки нового размера, не старого backbuffer.
                        nextActionAt = now + 100_000_000L
                        return false
                    }
                }
                if (narrow) {
                    screenshot("cells-list-narrow")
                    Gdx.graphics.setWindowedMode(600, 900)
                }
                screen(GuideIntent.OpenCellLab(key))
                screen(CellLabInput("set", "signal", 1f))
                if (key == "tail") screen(CellLabInput("set", "angle", 90f))
                if (key == "controller") {
                    val stage = findStage(Gdx.input.inputProcessor, "cell-lab-canvas")!!
                    val field = VisTextField("")
                    stage.addActor(field)
                    stage.keyboardFocus = field
                    val typingCount = SessionJournal.simCount
                    Gdx.input.inputProcessor.keyDown(Input.Keys.W)
                    Gdx.input.inputProcessor.keyUp(Input.Keys.W)
                    check(SessionJournal.simCount == typingCount) { "typing sent a controller command" }
                    stage.keyboardFocus = null
                    field.remove()
                    val before = SessionJournal.simCount
                    check(Gdx.input.inputProcessor.keyDown(Input.Keys.W))
                    check(SessionJournal.simCount == before + 1) { "keyboard was not journalled" }
                }
                if (key == "eye") {
                    screen(CellLabInput("set", "channels", 2f))
                    screen(CellLabInput("set", "angle", 0f))
                }
                nextActionAt = now + 1_200_000_000L
            }
            1 -> {
                screenshot(if (narrow) "eye-narrow" else key)
                dragThroughUi()
                nextActionAt = now + 250_000_000L
            }
            2 -> {
                if (key == "controller") check(Gdx.input.inputProcessor.keyUp(Input.Keys.W))
                Gdx.input.inputProcessor.touchUp(dragPosition.x.toInt(), dragPosition.y.toInt(), 0, 0)
                screen(CellLabInput("pause", value = 1f))
                screen(CellLabInput("set", "signal", -1f))
                screen(CellLabInput("reset"))
                nextActionAt = now + 100_000_000L
            }
            else -> {
                screen(GuideIntent.CloseCellLab)
                index++
                phase = 0
                if (index > keys.size) {
                    DIGameGlobalContainer.navigationCommandsManager.performCommand(GoMenu)
                    Gdx.graphics.setWindowedMode(900, 700)
                    println("CellLab: ${keys.size} сценариев записаны, снимки: ${directory.absolutePath}")
                    return true
                }
            }
        }
        return false
    }

    private fun dragThroughUi() {
        val input = Gdx.input.inputProcessor as InputMultiplexer
        val stage = findStage(input, "cell-lab-canvas") ?: error("no cell lab stage")
        val actor = stage.root.findActor<Actor>("cell-lab-canvas") ?: error("no cell lab canvas")
        val from = actor.localToStageCoordinates(Vector2(actor.width * 0.5f, actor.height * 0.5f))
        stage.stageToScreenCoordinates(from)
        val before = SessionJournal.simCount
        check(input.touchDown(from.x.toInt(), from.y.toInt(), 0, 0)) { "canvas did not accept press" }
        dragPosition.set(actor.width * 0.56f, actor.height * 0.56f)
        actor.localToStageCoordinates(dragPosition)
        stage.stageToScreenCoordinates(dragPosition)
        input.touchDragged(dragPosition.x.toInt(), dragPosition.y.toInt(), 0)
        check(SessionJournal.simCount >= before + 2) { "press/drag did not reach the SIM journal" }
    }

    private fun findStage(input: InputProcessor, name: String): Stage? = when (input) {
        is Stage -> input.takeIf { it.root.findActor<Actor>(name) != null }
        // Слой отчёта об ошибке оборачивает ввод экрана своим мультиплексором.
        is InputMultiplexer -> input.processors.firstNotNullOfOrNull { findStage(it, name) }
        else -> null
    }

    private fun screen(command: GameCommand) {
        val target = DIGameGlobalContainer.game.screen as ReplayTarget
        check(target.replay(command)) { "cell lab did not handle ${command.name}" }
    }

    private fun screenshot(name: String) {
        val image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        try {
            directory.mkdirs()
            PixmapIO.writePNG(Gdx.files.absolute(File(directory, "$name.png").absolutePath), image, -1, true)
        } finally { image.dispose() }
    }
}
