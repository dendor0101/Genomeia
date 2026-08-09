@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.some_example_name.old.systems.maps

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEmitterEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.ProducerEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
import io.github.some_example_name.old.features.settings.GlobalSettings
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.systems.genomics.genome.Genome
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Метаданные мира. Лежат отдельно от world.bin, потому что world.bin пишется один раз
 * при создании карты, а размер сетки и время симуляции меняются по ходу игры.
 */
@Serializable
data class WorldMeta(
    @ProtoNumber(1) val gridWidth: Int = 0,
    @ProtoNumber(2) val gridHeight: Int = 0,
    @ProtoNumber(3) val tickCounter: Int = 0,
    @ProtoNumber(4) val timeSimulation: Float = 0f,
    @ProtoNumber(5) val currentGenomeIndex: Int = 0
)

/**
 * Геномы обязаны лежать в карте: OrganEntity.genomeIndex — это индекс в
 * genomeManager.genomes, а тот собирается из папки с геномами и меняется между запусками.
 */
@Serializable
data class GenomesSave(
    @ProtoNumber(1) val genomes: List<Genome> = emptyList()
)

/**
 * Результат загрузки карты.
 *
 * @param terrain карта рельефа для WorldTerrainManager
 * @param hasWorldState были ли в архиве сущности. Если нет — это только что созданная в
 *   редакторе карта, мир нужно сгенерировать заново через initMap()
 */
class LoadedMap(
    val terrain: Array<BooleanArray>,
    val hasWorldState: Boolean
)

class MapSave {
    var currentMap = -1

    // ---------------------------------------------------------------- сохранение

    /**
     * Создаёт новую карту: рельеф и скриншот. Сущности сюда не пишутся — на момент выхода
     * из редактора мира ещё нет, он создаётся уже в SimulationScreen.initMap().
     */
    fun saveMap(custom: Boolean, seed: Long, map: Array<BooleanArray>, canvasTexture: Pixmap) {
        val mapName = nextMapName()
        currentMap = mapName

        FileOutputStream(mapFile(mapName)).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry(WORLD_BIN))
                val dataOut = DataOutputStream(zos)

                dataOut.writeBoolean(custom)
                dataOut.writeInt(map.size)
                dataOut.writeInt(map[0].size)

                if (!custom) {
                    dataOut.writeLong(seed)
                } else {
                    dataOut.writeInt(map.size)
                    for (row in map) {
                        dataOut.writeInt(row.size)
                        for (value in row) {
                            dataOut.writeBoolean(value)
                        }
                    }
                }
                dataOut.writeInt(WORLD_BIN_END_MARKER)
                dataOut.flush()
                zos.closeEntry()

                zos.writeEntry(WORLD_META, ProtoBuf.encodeToByteArray(WorldMeta.serializer(), currentWorldMeta()))
                zos.writeScreenshot(mapName, canvasTexture)
            }
        }
    }

    /**
     * Перезаписывает текущую карту вместе с живым состоянием мира.
     * Симуляция на время сохранения ставится на паузу, иначе в архив попадёт состояние,
     * собранное из разных тиков.
     */
    fun resaveMap() {
        if (currentMap < 0) {
            println("MapSave: карта не выбрана (currentMap = $currentMap), сохранять некуда")
            return
        }

        val file = mapFile(currentMap)
        // world.bin пишется только при создании карты, поэтому его надо перенести в новый архив
        val oldWorldBin = if (file.exists()) readArchive(file)[WORLD_BIN] else null
        if (oldWorldBin == null) {
            println("MapSave: в карте $currentMap нет $WORLD_BIN, сохранение отменено")
            return
        }

        // Скриншот снимаем до паузы: он берётся с текущего кадра GL
        val screenshot = ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.width, Gdx.graphics.height)

        val wasPlaying = DISimulationContainer.simulationSystem.pauseAndAwaitTick()
        try {
            FileOutputStream(file).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.writeEntry(WORLD_BIN, oldWorldBin)
                    zos.writeEntry(WORLD_META, ProtoBuf.encodeToByteArray(WorldMeta.serializer(), currentWorldMeta()))
                    zos.writeEntry(
                        GENOMES,
                        ProtoBuf.encodeToByteArray(
                            GenomesSave.serializer(),
                            GenomesSave(DISimulationContainer.genomeManager.genomes.toList())
                        )
                    )
                    saveEntity(zos)
                    zos.writeScreenshot(currentMap, screenshot)
                }
            }
        } finally {
            DISimulationContainer.simulationSystem.resumeAfterPause(wasPlaying)
            screenshot.dispose()
        }
    }

    private fun saveEntity(zos: ZipOutputStream) = with(DISimulationContainer) {
        cellEntity.serializeEntity()
        zos.writeEntry(CELL_ENTITY, ProtoBuf.encodeToByteArray(CellEntity.serializer(), cellEntity))

        specialEntity.serializeEntity()
        zos.writeEntry(SPECIAL_ENTITY, ProtoBuf.encodeToByteArray(SpecialEntity.serializer(), specialEntity))

        substancesEntity.serializeEntity()
        zos.writeEntry(SUBSTANCE_ENTITY, ProtoBuf.encodeToByteArray(SubstancesEntity.serializer(), substancesEntity))

        specialModDataEntity.serializeEntity()
        zos.writeEntry(SPECIAL_MOD_DATA_ENTITY, ProtoBuf.encodeToByteArray(SpecialModDataEntity.serializer(), specialModDataEntity))

        tailEntity.serializeEntity()
        zos.writeEntry(TAIL_ENTITY, ProtoBuf.encodeToByteArray(TailEntity.serializer(), tailEntity))

        eyeEntity.serializeEntity()
        zos.writeEntry(EYE_ENTITY, ProtoBuf.encodeToByteArray(EyeEntity.serializer(), eyeEntity))

        linkEntity.serializeEntity()
        zos.writeEntry(LINK_ENTITY, ProtoBuf.encodeToByteArray(LinkEntity.serializer(), linkEntity))

        neuralEntity.serializeEntity()
        zos.writeEntry(NEURAL_ENTITY, ProtoBuf.encodeToByteArray(NeuralEntity.serializer(), neuralEntity))

        organEntity.serializeEntity()
        zos.writeEntry(ORGAN_ENTITY, ProtoBuf.encodeToByteArray(OrganEntity.serializer(), organEntity))

        particleEntity.serializeEntity()
        zos.writeEntry(PARTICLE_ENTITY, ProtoBuf.encodeToByteArray(ParticleEntity.serializer(), particleEntity))

        pheromoneEmitterEntity.serializeEntity()
        zos.writeEntry(PHEROMONE_EMITTER_ENTITY, ProtoBuf.encodeToByteArray(PheromoneEmitterEntity.serializer(), pheromoneEmitterEntity))

        pheromoneEntity.serializeEntity()
        zos.writeEntry(PHEROMONE_ENTITY, ProtoBuf.encodeToByteArray(PheromoneEntity.serializer(), pheromoneEntity))

        producerEntity.serializeEntity()
        zos.writeEntry(PRODUCER_ENTITY, ProtoBuf.encodeToByteArray(ProducerEntity.serializer(), producerEntity))
    }

    // ---------------------------------------------------------------- загрузка

    /**
     * Загружает карту целиком: размер мира, рельеф, геномы, все сущности и время симуляции.
     *
     * Порядок шагов важен:
     * 1. размер мира применяется до всего остального, потому что resizeWorld() пересоздаёт
     *    сетку и потоковые буферы — после восстановления частиц это стёрло бы их регистрацию;
     * 2. dispose() чистит сетку и сущности (иначе частицы прошлой сессии останутся в сетке
     *    и дадут дубли индексов);
     * 3. сущности копируются в существующие объекты контейнера, а не подменяют их —
     *    все системы держат ссылки, полученные в конструкторе;
     * 4. сетка и потоковые списки связей восстанавливаются последними, по уже готовым данным.
     */
    fun loadMap(fileName: String): LoadedMap = with(DISimulationContainer) {
        val entries = readArchive(File(DISimulationContainer.baseMapDir + fileName))

        val terrain = entries[WORLD_BIN]?.let { readTerrain(it) } ?: emptyArray()
        val meta = entries[WORLD_META]?.let { ProtoBuf.decodeFromByteArray(WorldMeta.serializer(), it) }

        // 1. Размер мира
        if (meta != null && meta.gridWidth > 0 && meta.gridHeight > 0) {
            GlobalSettings.GRID_WIDTH = meta.gridWidth
            GlobalSettings.GRID_HEIGHT = meta.gridHeight
        }
        resizeWorld()

        // 2. Полный сброс мира
        simulationSystem.dispose()

        currentMap = fileName.substringBefore('.').toIntOrNull() ?: -1

        // Сущностей нет — карту только что создали в редакторе, мир будет сгенерирован из рельефа
        if (!entries.containsKey(PARTICLE_ENTITY)) {
            return@with LoadedMap(terrain, hasWorldState = false)
        }

        try {
            restoreWorldState(entries, meta)
            LoadedMap(terrain, hasWorldState = true)
        } catch (e: Exception) {
            // Архив из несовместимой версии формата или повреждён. Мир уже частично залит
            // мусором, поэтому сбрасываем его и отдаём карту как пустую — рельеф сгенерируется заново.
            println("MapSave: не удалось прочитать состояние мира из $fileName (${e.message}), карта открыта без организмов")
            simulationSystem.dispose()
            LoadedMap(terrain, hasWorldState = false)
        }
    }

    private fun restoreWorldState(
        entries: Map<String, ByteArray>,
        meta: WorldMeta?
    ) = with(DISimulationContainer) {
        // 3. Геномы до сущностей: organEntity.genomeIndex индексирует именно этот список
        entries[GENOMES]?.let { bytes ->
            val saved = ProtoBuf.decodeFromByteArray(GenomesSave.serializer(), bytes).genomes
            if (saved.isNotEmpty()) genomeManager.restoreGenomes(saved)
        }

        // 4. Сущности
        entries.decode(CELL_ENTITY, CellEntity.serializer())?.let { cellEntity.copyFrom(it) }
        entries.decode(SPECIAL_ENTITY, SpecialEntity.serializer())?.let { specialEntity.copyFrom(it) }
        entries.decode(SUBSTANCE_ENTITY, SubstancesEntity.serializer())?.let { substancesEntity.copyFrom(it) }
        entries.decode(SPECIAL_MOD_DATA_ENTITY, SpecialModDataEntity.serializer())?.let { specialModDataEntity.copyFrom(it) }
        entries.decode(TAIL_ENTITY, TailEntity.serializer())?.let { tailEntity.copyFrom(it) }
        entries.decode(EYE_ENTITY, EyeEntity.serializer())?.let { eyeEntity.copyFrom(it) }
        entries.decode(LINK_ENTITY, LinkEntity.serializer())?.let { linkEntity.copyFrom(it) }
        entries.decode(NEURAL_ENTITY, NeuralEntity.serializer())?.let { neuralEntity.copyFrom(it) }
        entries.decode(ORGAN_ENTITY, OrganEntity.serializer())?.let { organEntity.copyFrom(it) }
        entries.decode(PARTICLE_ENTITY, ParticleEntity.serializer())?.let { particleEntity.copyFrom(it) }
        entries.decode(PHEROMONE_EMITTER_ENTITY, PheromoneEmitterEntity.serializer())?.let { pheromoneEmitterEntity.copyFrom(it) }
        entries.decode(PHEROMONE_ENTITY, PheromoneEntity.serializer())?.let { pheromoneEntity.copyFrom(it) }
        entries.decode(PRODUCER_ENTITY, ProducerEntity.serializer())?.let { producerEntity.copyFrom(it) }

        // 5. Transient-структуры, которые считаются по уже загруженным данным
        cellEntity.restoreCellActions(organEntity, genomeManager.genomes)

        val repairedParticles = particleEntity.restoreGridManager()
        val skippedLinks = linkEntity.restoreLinkLists(
            evenLinkLists = worldCommandsManager.evenLinkLists,
            oddLinkLists = worldCommandsManager.oddLinkLists
        )

        // 6. Время симуляции
        meta?.let {
            simulationData.tickCounter = it.tickCounter
            simulationData.timeSimulation = it.timeSimulation
            simulationData.currentGenomeIndex = it.currentGenomeIndex.coerceIn(0, maxOf(0, genomeManager.genomes.size - 1))
        }

        println(
            "Мир восстановлен: клеток ${cellEntity.aliveList.size}, " +
                "частиц ${particleEntity.aliveList.size}, связей ${linkEntity.aliveList.size}, " +
                "организмов ${organEntity.aliveList.size}, геномов ${genomeManager.genomes.size}"
        )
        if (repairedParticles > 0) println("MapSave: починено частиц с битыми координатами — $repairedParticles")
        if (skippedLinks > 0) println("MapSave: связей с мёртвыми клетками пропущено — $skippedLinks")
    }

    private fun readTerrain(bytes: ByteArray): Array<BooleanArray> =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val custom = data.readBoolean()
            val width = data.readInt()
            val height = data.readInt()

            if (!custom) {
                WorldGenerator().generateWorld(width, height, data.readLong())
            } else {
                val rows = data.readInt()
                Array(rows) {
                    val rowWidth = data.readInt()
                    BooleanArray(rowWidth) { data.readBoolean() }
                }
            }
        }

    // ---------------------------------------------------------------- файлы и утилиты

    fun getMaps(): List<String> = mapsDir().listFiles()
        ?.filter { it.isFile && it.name.endsWith(".zip") }
        ?.map { it.name }
        ?.sortedBy { it.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        ?: emptyList()

    fun getTexturePixmap(fileName: String): Pixmap? {
        val imageName = fileName.substringBefore('.') + ".png"
        val bytes = readArchive(File(DISimulationContainer.baseMapDir + fileName))[imageName] ?: return null
        return Pixmap(bytes, 0, bytes.size)
    }

    private fun mapsDir() = File(DISimulationContainer.baseMapDir).apply { mkdirs() }

    private fun mapFile(name: Int) = File(mapsDir(), "$name.zip")

    /** Берём максимальный существующий номер + 1, иначе после удаления карты имена столкнутся. */
    private fun nextMapName(): Int {
        val maxExisting = getMaps().mapNotNull { it.substringBefore('.').toIntOrNull() }.maxOrNull() ?: 0
        return maxExisting + 1
    }

    private fun currentWorldMeta() = WorldMeta(
        gridWidth = GlobalSettings.GRID_WIDTH,
        gridHeight = GlobalSettings.GRID_HEIGHT,
        tickCounter = DISimulationContainer.simulationData.tickCounter,
        timeSimulation = DISimulationContainer.simulationData.timeSimulation,
        currentGenomeIndex = DISimulationContainer.simulationData.currentGenomeIndex
    )

    private fun readArchive(file: File): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        if (!file.exists()) return entries

        ZipInputStream(FileInputStream(file)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zipIn.readBytes()
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return entries
    }

    /**
     * Декодирует сущность и разворачивает её служебные списки обратно в массивы.
     *
     * loadSerialize() обязателен: generation/isAlive/aliveList помечены @Transient и после
     * декодирования пустые, все живые данные лежат в generationList/isAliveList/aliveListData.
     * Без этого шага copyFrom() скопировал бы нули.
     */
    private fun <T : Entity> Map<String, ByteArray>.decode(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T? = this[name]?.let {
        ProtoBuf.decodeFromByteArray(serializer, it).apply { loadSerialize() }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes, 0, bytes.size)
        closeEntry()
    }

    /** Pixmap не освобождаем: владеет им вызывающий код. */
    private fun ZipOutputStream.writeScreenshot(mapName: Int, pixmap: Pixmap) {
        putNextEntry(ZipEntry("$mapName.png"))
        PixmapIO.PNG().apply { setFlipY(true) }.write(this@writeScreenshot, pixmap)
        closeEntry()
    }

    companion object {
        private const val WORLD_BIN = "world.bin"
        private const val WORLD_META = "world_meta.bin"
        private const val GENOMES = "genomes.bin"
        private const val WORLD_BIN_END_MARKER = -555

        private const val CELL_ENTITY = "CellEntity.bin"
        private const val SPECIAL_ENTITY = "SpecialEntity.bin"
        private const val SUBSTANCE_ENTITY = "SubstanceEntity.bin"
        private const val SPECIAL_MOD_DATA_ENTITY = "SpecialModDataEntity.bin"
        private const val TAIL_ENTITY = "TailEntity.bin"
        private const val EYE_ENTITY = "EyeEntity.bin"
        private const val LINK_ENTITY = "LinkEntity.bin"
        private const val NEURAL_ENTITY = "NeuralEntity.bin"
        private const val ORGAN_ENTITY = "OrganEntity.bin"
        private const val PARTICLE_ENTITY = "ParticleEntity.bin"
        private const val PHEROMONE_EMITTER_ENTITY = "PheromoneEmitterEntity.bin"
        private const val PHEROMONE_ENTITY = "PheromoneEntity.bin"
        private const val PRODUCER_ENTITY = "ProducerEntity.bin"
    }
}
