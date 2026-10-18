package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoExit
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoGenomes
import io.github.some_example_name.old.commands.GoSettings
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.GoSupport
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISingleThreadSimulationContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionAttachments
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.maps.MapNames
import io.github.some_example_name.old.systems.maps.MapSave
import io.github.some_example_name.old.systems.maps.SavedMap
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Что игрок хочет сделать в главном меню.
 *
 * Просмотр списков (миров, геномов) — не команда: это только окно с выбором, игру оно
 * не меняет. Команда — то, что выбрали.
 */
@Serializable
sealed interface MenuIntent : GameCommand {

    /** Новый мир в редакторе мира. Стартовый сид выбран заранее, см. [openWorldEditor]. */
    @Serializable
    data class OpenWorldEditor(val seed: String) : MenuIntent {
        override val name get() = "OpenWorldEditor"
        override val detail get() = seed
    }

    @Serializable
    data class OpenSavedWorld(val slot: String) : MenuIntent {
        override val name get() = "OpenSavedWorld"
        override val detail get() = slot
    }

    /**
     * Удалить сохранённый мир. Это команда, а не просто стёртый файл: следующие команды
     * записи («открыть мир X», «сохранить в X») при повторе должны видеть ту же папку миров.
     */
    @Serializable
    data class DeleteWorld(val slot: String) : MenuIntent {
        override val name get() = "DeleteWorld"
        override val detail get() = slot
    }

    /**
     * Открыть геном в редакторе прямо из меню. Так было до экрана «Геномы»; теперь редактор
     * открывается оттуда (GenomesIntent.OpenEditor), а эта команда осталась для .bug
     * прошлых версий — номер команды это формат файла.
     */
    @Serializable
    data class OpenGenomeEditor(val genomeName: String?) : MenuIntent {
        override val name get() = "OpenGenomeEditor"
        override val detail get() = genomeName ?: "new"
    }

    /** Экран «Геномы»: свои и встроенные геномы, редактор, импорт. */
    @Serializable
    data object OpenGenomes : MenuIntent {
        override val name get() = "OpenGenomes"
    }

    /**
     * Импорт геномов из меню — до экрана «Геномы». Теперь импорт там (GenomesIntent), а эти
     * две команды остались для .bug прошлых версий: [GenomeImported] при повторе кладёт файл
     * из приложений сессии, [ImportGenome] при повторе и раньше ничего не делал.
     */
    @Serializable
    data object ImportGenome : MenuIntent {
        override val name get() = "ImportGenome"
    }

    /** См. [ImportGenome] и GenomesIntent.GenomeImported. */
    @Serializable
    data class GenomeImported(val fileName: String, val attachment: String) : MenuIntent {
        override val name get() = "GenomeImported"
        override val detail get() = fileName
    }

    @Serializable
    data class MapImported(val slot: String, val attachment: String) : MenuIntent {
        override val name get() = "MapImported"
        override val detail get() = slot
    }

    @Serializable
    data object OpenSettings : MenuIntent {
        override val name get() = "OpenSettings"
    }

    @Serializable
    data object OpenSupport : MenuIntent {
        override val name get() = "OpenSupport"
    }

    @Serializable
    data object Exit : MenuIntent {
        override val name get() = "Exit"
    }

    companion object {
        /** Кнопка нового мира: сид решается здесь, чтобы ехать в команде. */
        fun openWorldEditor(): OpenWorldEditor = OpenWorldEditor(WorldEditorIntent.newSeed().seed)
    }
}

class MenuViewModel(
    val simulationSystem: SingleThreadSimulationSystem = DISingleThreadSimulationContainer.simulationSystem,
    val renderSystem: RenderSystem = DISingleThreadSimulationContainer.renderSystem,
    val userCommandManager: UserCommandManager = DISingleThreadSimulationContainer.userCommandManager,
    val worldTerrainManager: WorldTerrainManager = DISingleThreadSimulationContainer.worldTerrainManager,
    val worldGenerator: WorldGenerator = WorldGenerator(),
    val genomeManager: GenomeManager = DISingleThreadSimulationContainer.genomeManager
) {

    // Состав фоновой сцены: имя встроенного генома и число организмов.
    private val sceneGenomes = listOf("bundled/Leech" to 5)

    private val navigation = DIGameGlobalContainer.navigationCommandsManager

    /** Перетащенную карту не удалось открыть — экран называет файл. */
    var onMapImportFailed: ((fileName: String) -> Unit)? = null

    private var tiltX = 0f
    private var tiltY = 0f

    private val maxTilt = 1.2f // максимальный «угол» в условных единицах
    private val sensitivity = 3.8f // насколько быстро набирается наклон
    private val damping = 0.92f // затухание (чтобы возвращалось в центр)

    /** Сохранённые миры для окна выбора. Запрос, а не команда: игру он не меняет. */
    fun savedWorlds(): List<SavedMap> = MapSave.listMaps()

    /**
     * Единственный вход для действий игрока в меню.
     *
     * В журнале получается две строки на нажатие: намерение ("нажали Настройки") и переход
     * ("экран стал GoSettings"). Это не дубль — это разные слои, и когда переход когда-нибудь
     * окажется заблокирован или перенаправлен, они разойдутся, и в логе это будет видно.
     * Переход — вложенная команда: при повторе его заново делает само намерение.
     */
    fun handle(intent: MenuIntent) = SessionJournal.execute(CommandSource.MENU, intent) { apply(it) }

    private fun apply(intent: MenuIntent) {
        when (intent) {
            is MenuIntent.OpenWorldEditor -> navigation.performCommand(GoWorldEditor(intent.seed))

            is MenuIntent.OpenSavedWorld -> navigation.performCommand(
                GoSimulation(world = null, genomeName = null, savedWorld = intent.slot)
            )

            is MenuIntent.DeleteWorld -> {
                // При повторе мира может не быть в песочнице: в .bug снимаются только
                // загруженные миры (SessionAttachments), а этот, возможно, не открывали.
                if (!MapSave.delete(intent.slot)) Gdx.app.log(TAG_MAPS, "мира ${intent.slot} нет — удалять нечего")
            }

            is MenuIntent.OpenGenomeEditor -> navigation.performCommand(GoGenomeEditor(intent.genomeName))

            is MenuIntent.OpenGenomes -> navigation.performCommand(GoGenomes)

            // Только из старых .bug, где окно выбора и при записи отработало без журнала.
            is MenuIntent.ImportGenome -> Unit

            // Только из старых .bug: файл приходит из приложений сессии, как и раньше.
            is MenuIntent.GenomeImported -> {
                SessionAttachments.restore(intent.attachment, GameFiles.genomesDir().child(intent.fileName))
            }

            is MenuIntent.MapImported -> {
                val target = GameFiles.mapsDir().child("${intent.slot}.map")
                check(SessionAttachments.restore(intent.attachment, target)) { "нет снимка импортированной карты" }
                // Снимок уже есть в attachment: не дублируем весь мир второй раз в .bug.
                SessionAttachments.markMapWritten(intent.slot)
                navigation.performCommand(GoSimulation(world = null, genomeName = null, savedWorld = intent.slot))
            }

            is MenuIntent.OpenSettings -> navigation.performCommand(GoSettings)

            is MenuIntent.OpenSupport -> navigation.performCommand(GoSupport)

            is MenuIntent.Exit -> navigation.performCommand(GoExit)
        }
    }

    /**
     * Карта открывается через команду, внешний файл остаётся нетронутым. Имя мира — имя
     * файла (занято — «имя (2)»), а если из него не вышло имени — следующий номер.
     */
    fun importMapFile(file: FileHandle) {
        if (BugReplay.isActive) return
        try {
            MapSave.validateImport(file)
            val slot = MapNames.fromFileName(file.nameWithoutExtension())?.let(MapSave::uniqueSlot)
                ?: ((MapSave.listMaps().maxOfOrNull { it.slot.toIntOrNull() ?: 0 } ?: 0) + 1).toString()
            val attachment = SessionAttachments.attachImported(file)
            handle(MenuIntent.MapImported(slot, attachment))
        } catch (e: Exception) {
            Gdx.app.error(TAG_MAPS, "не удалось открыть ${file.path()}", e)
            onMapImportFailed?.invoke(file.name())
        }
    }

    fun startMenuSimulation() {
        // Контейнер переживает экраны: новый вход в меню не должен добавлять ещё одну сцену.
        simulationSystem.dispose()
        userCommandManager.startSession(SCENE_SEED)
        val width = DISingleThreadSimulationContainer.gridWidth
        val height = DISingleThreadSimulationContainer.gridHeight
        val scale = WorldSpec.MAP_CELLS_PER_WORLD_CELL
        val map = worldGenerator.generateWorld(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            seed = SCENE_SEED
        )
        worldTerrainManager.map = map
        worldTerrainManager.initWorld(gridWidth = width, gridHeight = height)

        // Только список меню: файлы игрока и остальные встроенные геномы здесь не нужны.
        genomeManager.genomes.clear()
        val random = Random(SCENE_SEED)
        for ((name, count) in sceneGenomes) {
            val index = genomeManager.addSavedGenome(GenomeCatalog.load(name))
            repeat(count) {
                var x: Float
                var y: Float
                var attempts = 0
                do {
                    check(attempts++ < 1024) { "В фоновой сцене нет свободного места для $name" }
                    x = width * (0.2f + random.nextFloat() * 0.6f)
                    y = height * (0.2f + random.nextFloat() * 0.6f)
                } while (map[(y * scale).toInt()][(x * scale).toInt()])
                userCommandManager.pushInternal(PlayerCommand.Tap(x, y, true, index))
            }
        }
    }

    fun updateFrame() {
        simulationSystem.updateTick()
        renderSystem.render()
    }

    fun moveCamera(camera: Camera, delta: Float) {
        if (Gdx.input.isPeripheralAvailable(Input.Peripheral.Gyroscope)) {
            val rawX = Gdx.input.gyroscopeX
            val rawY = Gdx.input.gyroscopeY

            // Переназначаем оси в зависимости от текущей ориентации экрана
            val (gyroX, gyroY) = when (Gdx.input.rotation) {
                0 -> rawY to rawX          // Portrait (нормально)
                90 -> -rawX to rawY         // Landscape
                180 -> -rawY to -rawX        // Portrait перевёрнутый
                270 -> rawX to -rawY         // Landscape в другую сторону
                else -> rawY to rawX
            }

            // Интегрируем
            tiltX += gyroX * sensitivity * delta
            tiltY += gyroY * sensitivity * delta

            // Затухание
            tiltX *= damping
            tiltY *= damping

            // Ограничение
            tiltX = MathUtils.clamp(tiltX, -maxTilt, maxTilt)
            tiltY = MathUtils.clamp(tiltY, -maxTilt, maxTilt)

            camera.position.set(24f + tiltX, 24f - tiltY, 0f)
        } else {
            // fallback на мышь
            val x = (Gdx.input.x.toFloat() / Gdx.graphics.width - 0.5f) * 2f
            val y = (Gdx.input.y.toFloat() / Gdx.graphics.height - 0.5f) * 2f
            camera.position.set(24f + x, 24f - y, 0f)
        }
        camera.update()
    }

    private companion object {
        const val TAG_MAPS = "MapImport"
        const val SCENE_SEED = 12L
    }
}
