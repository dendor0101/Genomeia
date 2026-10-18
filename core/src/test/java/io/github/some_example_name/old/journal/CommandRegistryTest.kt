package io.github.some_example_name.old.journal

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.NavigationCommands
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.editor.system.Axial
import io.github.some_example_name.old.editor.system.SquareGrid
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.DivideDialog
import io.github.some_example_name.old.editor.system.logic.SetSymmetry
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TryToDivide
import io.github.some_example_name.old.editor.system.logic.UiEditorCommands
import io.github.some_example_name.old.features.devsupport.SupportIntent
import io.github.some_example_name.old.features.genomes.GenomesIntent
import io.github.some_example_name.old.features.menu.MenuIntent
import io.github.some_example_name.old.features.settings.SettingsIntent
import io.github.some_example_name.old.features.settings.SettingsTab
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.genomics.genome.LinkData
import io.github.some_example_name.render.RenderMode
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Реестр команд — это формат журнала. Команда, которой в нём нет, выполнится, но в журнал
 * не попадёт, и повтор бага по такому журналу молча разойдётся с игрой.
 */
class CommandRegistryTest {

    /** Каждое семейство команд целиком — по наследникам sealed-иерархий. */
    private val families: List<KClass<out GameCommand>> = listOf(
        NavigationCommands::class,
        MenuIntent::class,
        GenomesIntent::class,
        SettingsIntent::class,
        SupportIntent::class,
        WorldEditorIntent::class,
        SimulationIntent::class,
        PlayerCommand::class,
        UiEditorCommands::class,
        SimEvent::class,
    )

    @Test
    fun `каждая команда каждого семейства есть в реестре`() {
        val missing = families.flatMap { leaves(it) }.filterNot { CommandRegistry.isRegistered(it) }
        assertTrue(missing.isEmpty(), "нет в CommandRegistry: ${missing.joinToString { it.qualifiedName ?: it.toString() }}")
    }

    @Test
    fun `номера типов уникальны и не пересекаются`() {
        val ids = CommandRegistry.types.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 0..0xFFFF }, "номер типа пишется в short")
    }

    @Test
    fun `объекты переживают запись и чтение тем же экземпляром`() {
        assertSame(GoMenu, roundTrip(GoMenu))
        assertSame(CtrlZ, roundTrip(CtrlZ))
        assertSame(PlayerCommand.StopDrag, roundTrip(PlayerCommand.StopDrag))
        assertSame(SimRestarted, roundTrip(SimRestarted))
    }

    @Test
    fun `вложенная команда навигации и рецепт мира с правками`() {
        val world = WorldSpec(
            gridWidth = 192,
            gridHeight = 192,
            seed = "12345678",
            dayNight = 7,
            smoothing = 3,
            edits = listOf(WorldEdit.Stroke(10, 20, 5, erase = false, circle = true), WorldEdit.Clear)
        )
        val command = GoReplace(GoSimulation(world = world, genomeName = "bug", savedWorld = null))
        val decoded = roundTrip(command) as GoReplace
        val target = decoded.target as GoSimulation
        assertEquals(world, target.world)
        assertEquals("bug", target.genomeName)
        assertEquals(null, target.savedWorld)

        assertEquals("777", (roundTrip(GoWorldEditor("777")) as GoWorldEditor).seed)
    }

    @Test
    fun `числа с плавающей точкой — побитово`() {
        val zoom = SimulationIntent.ZoomCamera(0.0123456789f, -1.5e-7f, Float.MAX_VALUE)
        assertEquals(zoom, roundTrip(zoom))

        val drag = PlayerCommand.Drag(0.1f, 0.2f, -0.3f, Float.MIN_VALUE)
        assertEquals(drag, roundTrip(drag))

        assertEquals(StateHashed(Long.MIN_VALUE + 7), roundTrip(StateHashed(Long.MIN_VALUE + 7)))
    }

    @Test
    fun `касание с выбранным геномом и без`() {
        val resolved = roundTrip(PlayerCommand.Tap(1.5f, 2.5f, isLeftButton = false, genomeIndex = 3)) as PlayerCommand.Tap
        assertEquals(1.5f, resolved.x)
        assertEquals(false, resolved.isLeftButton)
        assertEquals(3, resolved.genomeIndex)

        val current = roundTrip(PlayerCommand.Tap(1f, 2f)) as PlayerCommand.Tap
        assertEquals(null, current.genomeIndex)
    }

    @Test
    fun `команды редактора с действием генома, клеткой и симметрией`() {
        val action = Action(
            id = 4,
            cellType = "bone",
            color = Color(0.5f, 0.25f, 1f, 1f),
            radius = 0.37f,
            physicalLink = mapOf(1 to LinkData(length = 0.9f, isNeuronal = true), 2 to null)
        )
        val divide = roundTrip(TryToDivide(12, 3.5f to -4.25f, action)) as TryToDivide
        assertEquals(12, divide.clickedCellIndex)
        assertEquals(3.5f to -4.25f, divide.newDividedCellPosition)
        assertEquals(action.copy(color = null), divide.divide.copy(color = null))
        assertEquals(Color.rgba8888(action.color), Color.rgba8888(divide.divide.color))

        val cell = EditorCell(1, 2, 3, 4, 5f, 6f, 0.5f, 1.25f, null, true, action, null, null)
        assertEquals(cell.copy(divide = null), (roundTrip(DivideDialog(cell, 1f to 2f)) as DivideDialog).clickedCell.copy(divide = null))

        assertEquals(SquareGrid(0.6f), (roundTrip(SetSymmetry(SquareGrid(0.6f))) as SetSymmetry).mode)
        assertSame(Axial, (roundTrip(SetSymmetry(Axial)) as SetSymmetry).mode)

        val tap = roundTrip(TapScreen(1f, 2f, isLeft = false, isCtrl = true)) as TapScreen
        assertEquals(false, tap.isLeft)
        assertEquals(true, tap.isCtrl)
    }

    @Test
    fun `перечисления и строки`() {
        assertEquals(SimulationIntent.SetRenderMode(RenderMode.NOSTALGIC), roundTrip(SimulationIntent.SetRenderMode(RenderMode.NOSTALGIC)))
        assertEquals(SettingsIntent.SelectTab(SettingsTab.LANGUAGE), roundTrip(SettingsIntent.SelectTab(SettingsTab.LANGUAGE)))
        assertEquals(SettingsIntent.SetLanguage("ru"), roundTrip(SettingsIntent.SetLanguage("ru")))
        assertEquals(MenuIntent.GenomeImported("Жук.genome", "imported/0-Жук.genome"), roundTrip(MenuIntent.GenomeImported("Жук.genome", "imported/0-Жук.genome")))
    }

    @Test
    fun `неизвестный тип и испорченное тело не роняют чтение`() {
        assertEquals(null, CommandRegistry.decode(0xFFFF, ByteArray(0)))
        val (id, _) = assertNotNull(CommandRegistry.encode(SimulationIntent.SetTargetUps(5)))
        assertEquals(null, CommandRegistry.decode(id, byteArrayOf(-1, -1, -1, -1, -1)))
    }

    private fun roundTrip(command: GameCommand): GameCommand {
        val (id, bytes) = assertNotNull(CommandRegistry.encode(command), "${command::class} не в реестре")
        val decoded = assertNotNull(CommandRegistry.decode(id, bytes))
        // И обратно в те же байты: формат стабилен, а не «примерно похож».
        assertContentEquals(bytes, assertNotNull(CommandRegistry.encode(decoded)).second)
        return decoded
    }

    private fun leaves(root: KClass<*>): List<KClass<*>> =
        if (root.isSealed) root.sealedSubclasses.flatMap { leaves(it) } else listOf(root)
}
