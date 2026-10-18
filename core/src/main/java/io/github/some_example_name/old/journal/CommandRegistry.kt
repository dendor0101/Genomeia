package io.github.some_example_name.old.journal

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.commands.EcoSystemScreenCellsSettings
import io.github.some_example_name.old.commands.EcoSystemScreenGlobalSettings
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoEcoSystem
import io.github.some_example_name.old.commands.GoExit
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSettings
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.GoSupport
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.DivideDialog
import io.github.some_example_name.old.editor.system.logic.ExportGenome
import io.github.some_example_name.old.editor.system.logic.FlingScreen
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.GoToStartOfTimeLine
import io.github.some_example_name.old.editor.system.logic.MutateDialog
import io.github.some_example_name.old.editor.system.logic.NextTickButtonTap
import io.github.some_example_name.old.editor.system.logic.PanScreen
import io.github.some_example_name.old.editor.system.logic.PrevTickButtonTap
import io.github.some_example_name.old.editor.system.logic.SaveGenome
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinkColor
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinking
import io.github.some_example_name.old.editor.system.logic.SetRepeatLastAction
import io.github.some_example_name.old.editor.system.logic.SetShowPhysicalLinks
import io.github.some_example_name.old.editor.system.logic.SetSymmetry
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TimeSlider
import io.github.some_example_name.old.editor.system.logic.TryToCancelMutation
import io.github.some_example_name.old.editor.system.logic.TryToChange
import io.github.some_example_name.old.editor.system.logic.TryToDivide
import io.github.some_example_name.old.editor.system.logic.TryToMutate
import io.github.some_example_name.old.editor.system.logic.TryToRemove
import io.github.some_example_name.old.features.devsupport.SupportIntent
import io.github.some_example_name.old.features.menu.MenuIntent
import io.github.some_example_name.old.features.settings.SettingsIntent
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import io.github.some_example_name.old.systems.genomics.genome.GenomeProto
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import io.github.some_example_name.old.editor.system.logic.SetRenderMode as EditorSetRenderMode
import io.github.some_example_name.old.editor.system.logic.TouchDown as EditorTouchDown
import io.github.some_example_name.old.editor.system.logic.ZoomCamera as EditorZoomCamera

/**
 * Все команды, которые умеет записывать журнал: стабильный номер типа ↔ сериализатор.
 *
 * ПРАВИЛА
 * -------
 *  - Номер типа — это формат файла. Его нельзя менять и нельзя отдавать другой команде,
 *    даже когда старую удалили: иначе старые .bug прочитаются как чужие команды.
 *    Новые команды — только новыми номерами.
 *  - Новая команда обязана попасть сюда. Иначе она выполнится, но в журнал не попадёт,
 *    и повтор по такому журналу разойдётся с игрой. Это ловит тест CommandRegistryTest:
 *    он обходит все наследники каждого семейства команд.
 *
 * Номера разбиты по семействам сотнями — чтобы было видно, откуда команда, и чтобы
 * семейству было куда расти.
 */
@OptIn(ExperimentalSerializationApi::class)
object CommandRegistry {

    class Type<T : GameCommand>(
        val id: Int,
        val kClass: KClass<T>,
        val serializer: KSerializer<T>
    )

    val types: List<Type<*>> = listOf(
        // --- Навигация ---
        t<GoExit>(100),
        t<GoBack>(101),
        t<GoReplace>(102),
        t<GoMenu>(103),
        t<GoWorldEditor>(104),
        t<GoGenomeEditor>(105),
        t<GoSettings>(106),
        t<GoEcoSystem>(107),
        t<GoSupport>(108),
        t<GoSimulation>(109),
        t<EcoSystemScreenGlobalSettings>(110),
        t<EcoSystemScreenCellsSettings>(111),

        // --- Главное меню ---
        t<MenuIntent.OpenWorldEditor>(200),
        t<MenuIntent.OpenSavedWorld>(201),
        t<MenuIntent.OpenGenomeEditor>(202),
        t<MenuIntent.OpenSettings>(203),
        t<MenuIntent.OpenSupport>(204),
        t<MenuIntent.Exit>(205),
        t<MenuIntent.ImportGenome>(206),
        t<MenuIntent.GenomeImported>(207),
        t<MenuIntent.MapImported>(208),
        t<MenuIntent.DeleteWorld>(210),

        // --- Настройки ---
        t<SettingsIntent.SelectTab>(300),
        t<SettingsIntent.SetMusicVolume>(301),
        t<SettingsIntent.SetSoundVolume>(302),
        t<SettingsIntent.SetLanguage>(303),
        t<SettingsIntent.OpenGithub>(304),
        t<SettingsIntent.SetRenderMode>(305),

        // --- Поддержка ---
        t<SupportIntent.CopyAddress>(400),

        // --- Редактор мира ---
        t<WorldEditorIntent.NewSeed>(500),
        t<WorldEditorIntent.SetSeed>(501),
        t<WorldEditorIntent.SetDayNight>(502),
        t<WorldEditorIntent.SetSmoothing>(503),
        t<WorldEditorIntent.SetBrushSize>(504),
        t<WorldEditorIntent.SetErasing>(505),
        t<WorldEditorIntent.SetCircleBrush>(506),
        t<WorldEditorIntent.Paint>(507),
        t<WorldEditorIntent.ClearMap>(508),
        t<WorldEditorIntent.SetSettingsOpen>(509),
        t<WorldEditorIntent.SetWorldWidth>(510),
        t<WorldEditorIntent.SetWorldHeight>(511),

        // --- Экран симуляции ---
        t<SimulationIntent.SetPaused>(600),
        t<SimulationIntent.SetPutOrganism>(601),
        t<SimulationIntent.SelectGenome>(602),
        t<SimulationIntent.SetTargetUps>(603),
        t<SimulationIntent.SetMaxSpeed>(604),
        t<SimulationIntent.Restart>(605),
        t<SimulationIntent.SetRenderMode>(606),
        t<SimulationIntent.SetControllerKeysVisible>(607),
        t<SimulationIntent.SetControllerKey>(608),
        t<SimulationIntent.SaveWorld>(609),
        t<SimulationIntent.SetUiVisible>(610),
        t<SimulationIntent.AppPaused>(611),
        t<SimulationIntent.AppResumed>(612),
        t<SimulationIntent.MoveCamera>(613),
        t<SimulationIntent.ZoomCamera>(614),

        // --- Касания мира ---
        t<PlayerCommand.TouchDown>(700),
        t<PlayerCommand.Drag>(701),
        t<PlayerCommand.Tap>(702),
        t<PlayerCommand.StopDrag>(703),

        // --- Редактор генома ---
        t<PrevTickButtonTap>(800),
        t<NextTickButtonTap>(801),
        t<CtrlZ>(802),
        t<CtrlY>(803),
        t<PanScreen>(804),
        t<FlingScreen>(805),
        t<TapScreen>(806),
        t<TimeSlider>(807),
        t<GoToEndOfTimeLine>(808),
        t<GoToStartOfTimeLine>(809),
        t<EditorTouchDown>(810),
        t<DivideDialog>(811),
        t<MutateDialog>(812),
        t<TryToRemove>(813),
        t<TryToChange>(814),
        t<TryToMutate>(815),
        t<TryToCancelMutation>(816),
        t<TryToDivide>(817),
        t<SetNeuralLinking>(818),
        t<SetRepeatLastAction>(819),
        t<SetNeuralLinkColor>(820),
        t<SetSymmetry>(821),
        t<SetShowPhysicalLinks>(822),
        t<EditorSetRenderMode>(823),
        t<SaveGenome>(824),
        t<ExportGenome>(825),
        t<EditorZoomCamera>(826),

        // --- События потока симуляции ---
        t<SessionStarted>(900),
        t<ControllerKeys>(901),
        t<SimRestarted>(902),
        t<StateHashed>(903),
        t<WorldSaveCommitted>(904),
    )

    private val byClass: Map<KClass<*>, Type<*>> = types.associateBy { it.kClass }
    private val byId: Map<Int, Type<*>> = types.associateBy { it.id }

    init {
        check(byId.size == types.size) { "в реестре команд повторяется номер типа" }
        check(byClass.size == types.size) { "в реестре команд один класс записан дважды" }
    }

    fun isRegistered(kClass: KClass<*>): Boolean = kClass in byClass

    /** Номер типа и тело команды, или null — команды нет в реестре (это ошибка, см. правила). */
    fun encode(command: GameCommand): Pair<Int, ByteArray>? {
        @Suppress("UNCHECKED_CAST")
        val type = byClass[command::class] as Type<GameCommand>? ?: return null
        return type.id to GenomeProto.encodeToByteArray(type.serializer, command)
    }

    /** Команда по номеру типа и телу, или null — тип неизвестен или тело испорчено. */
    fun decode(typeId: Int, payload: ByteArray): GameCommand? {
        val type = byId[typeId] ?: return null
        return try {
            GenomeProto.decodeFromByteArray(type.serializer, payload)
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "не удалось прочитать команду типа $typeId", e)
            null
        }
    }

    fun nameOf(typeId: Int): String = byId[typeId]?.kClass?.simpleName ?: "#$typeId"

    private inline fun <reified T : GameCommand> t(id: Int): Type<T> = Type(id, T::class, serializer<T>())

    private const val TAG = "CommandRegistry"
}
