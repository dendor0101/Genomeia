@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.some_example_name.old.systems.maps

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeProto
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/** Сохранённый мир в списке: [slot] — имя файла без расширения, оно же имя мира (см. MapNames). */
class SavedMap(val slot: String, val lastModified: Long)

/**
 * Сохранение мира целиком: сущности, геномы организмов, время симуляции, рецепт рельефа
 * и превью для списка миров.
 *
 * Мир лежит в maps/<имя>.map (имя выбирает игрок, см. MapNames; старые сохранения — номера):
 *  - preview.png        превью, первым — чтобы список миров читал его, не распаковывая остальное;
 *  - meta.bin           версия формата, размер сетки, время, рецепт рельефа (для «Перезапуска»);
 *  - genomes.bin        геномы, на которые ссылаются организмы;
 *  - cellActions.bin    из какой стадии генома взято действие каждой клетки;
 *  - types.bin          ключи типов клеток и формул по номерам, какими они были при сохранении;
 *  - entities/<имя>.bin состояние каждой сущности, см. EntityState.
 */
class MapSave(
    /** Сущности мира под стабильными именами: имя — это имя файла в архиве. */
    private val entities: List<Pair<String, Entity>>,
    private val cellEntity: CellEntity,
    private val organEntity: OrganEntity,
    private val genomeManager: GenomeManager,
    private val simulationData: SimulationData,
    private val simulationSystem: SimulationSystem,
    private val diContext: DIContext,
    /** Пересобрать сетку под размер загружаемого мира: у каждого мира он свой. */
    private val resizeWorld: (width: Int, height: Int) -> Unit
) {

    init {
        // Сущность, которую добавили в мир и забыли здесь, молча терялась бы при загрузке.
        val missing = diContext.entityList.filter { entity -> entities.none { it.second === entity } }
        check(missing.isEmpty()) {
            "сущности мира без имени в сохранении: ${missing.joinToString { it::class.java.simpleName }}"
        }
    }

    /**
     * Сохранить мир в [slot] (null — в новый) и вернуть слот, куда он записан.
     *
     * Состояние снимается между тиками, а сжатие и запись файла идут уже без лока —
     * симуляция стоит ровно столько, сколько занимает копирование массивов.
     */
    fun save(slot: String?, world: WorldSpec?, preview: Pixmap?): String {
        val entries = LinkedHashMap<String, ByteArray>()
        preview?.let { entries[PREVIEW] = encodePng(it) }
        val target = slot ?: nextSlot()

        simulationSystem.betweenTicks {
            // Точный тик сохранения — в журнал: при повторе мир будет ждать на нём.
            simulationSystem.onWorldSaved(target)
            entries[META] = encodeMeta(world)
            entries[GENOMES] = encodeGenomes()
            entries[CELL_ACTIONS] = encodeInts(cellEntity.cellActionStages(genomeManager.genomes))
            entries[TYPES] = encodeTypes()
            for ((name, entity) in entities) {
                entries[entityEntry(name)] = entity.encodeState()
            }
        }

        writeArchive(target, entries)
        return target
    }

    /**
     * Заменить текущий мир сохранённым и вернуть его рецепт рельефа (null — мир был
     * без рельефа, например тест генома из редактора).
     *
     * Звать до старта потока симуляции. Если загрузить не вышло, мир остаётся пустым,
     * а не полузагруженным, и исключение уходит вызывающему.
     */
    fun load(slot: String): WorldSpec? {
        val entries = readArchive(mapFile(slot))
        val meta = decodeMeta(entries[META] ?: throw IllegalStateException("в мире $slot нет $META"))

        // Размер мира задаёт редактор карты, поэтому сетка подстраивается под сохранение,
        // а не наоборот. Поток симуляции ещё не запущен (см. контракт выше).
        if (meta.gridWidth != diContext.gridWidth || meta.gridHeight != diContext.gridHeight) {
            resizeWorld(meta.gridWidth, meta.gridHeight)
        }

        return simulationSystem.betweenTicks {
            simulationSystem.dispose()
            try {
                restore(entries, meta)
            } catch (e: Exception) {
                simulationSystem.dispose()
                throw e
            }
            meta.world
        }
    }

    private fun restore(entries: Map<String, ByteArray>, meta: Meta) {
        for ((name, entity) in entities) {
            // Сущности, которой нет в сохранении, не было и в мире: она остаётся пустой.
            entries[entityEntry(name)]?.let { entity.decodeState(it) }
        }

        // До restoreAfterLoad: тот уже читает типы клеток.
        remapTypes(entries[TYPES] ?: throw IllegalStateException("в мире нет $TYPES"))

        remapGenomes(decodeGenomes(entries[GENOMES] ?: ByteArray(0)))

        // Только когда загружены все сущности: индексы клеток собираются по их организмам.
        entities.forEach { (_, entity) -> entity.restoreAfterLoad() }
        cellEntity.restoreCellActions(decodeInts(entries[CELL_ACTIONS] ?: ByteArray(0)), genomeManager.genomes)
        organEntity.restoreArenaLayouts(genomeManager.genomes)
        cellEntity.repairEdgeFlagsAfterLoad()

        simulationData.tickCounter = meta.tickCounter
        simulationData.timeSimulation = meta.timeSimulation
    }

    /**
     * Переназначить организмам индексы геномов на текущий список.
     *
     * Сохранённые индексы указывают в список, каким он был при сохранении, а сейчас в
     * genomeManager лежат геномы из папки — в другом порядке, возможно изменённые или
     * удалённые. Поэтому геном организма ищется среди загруженных побайтно (тот же
     * protobuf), а не найденный добавляется в конец: организм обязан жить по своему геному,
     * даже если файл с тем же именем с тех пор отредактировали.
     *
     * Берутся только геномы живых организмов, иначе при каждом цикле «загрузил — сохранил»
     * список рос бы на копии всех геномов из папки.
     */
    private fun remapGenomes(saved: List<ByteArray>) {
        val resolved = IntArray(saved.size) { -1 }
        val encoded = HashMap<Int, ByteArray>()

        fun resolve(savedBytes: ByteArray): Int {
            // Сначала в текущий формат: геном из старого сохранения записан номерами типов,
            // а не ключами, и побайтно не совпал бы ни с одним — копился бы дубль за дублем.
            val genome = GenomeProto.decodeFromByteArray(Genome.serializer(), savedBytes)
            val bytes = encodeGenome(genome)
            for (i in genomeManager.genomes.indices) {
                val current = encoded.getOrPut(i) { encodeGenome(genomeManager.genomes[i]) }
                if (current.contentEquals(bytes)) return i
            }
            return genomeManager.addSavedGenome(genome)
        }

        val organs = organEntity.aliveList
        for (i in 0 until organs.size) {
            val organIndex = organs.getInt(i)
            val savedIndex = organEntity.genomeIndex[organIndex]
            check(savedIndex in saved.indices && saved[savedIndex].isNotEmpty()) {
                "организм $organIndex ссылается на геном $savedIndex, которого нет в сохранении"
            }
            if (resolved[savedIndex] == -1) resolved[savedIndex] = resolve(saved[savedIndex])
            organEntity.genomeIndex[organIndex] = resolved[savedIndex]
        }
    }

    // ---------------------------------------------------------------- формат

    private class Meta(
        val gridWidth: Int,
        val gridHeight: Int,
        val tickCounter: Int,
        val timeSimulation: Float,
        val world: WorldSpec?
    )

    private fun encodeMeta(world: WorldSpec?): ByteArray = writeBytes { out ->
        out.writeInt(FORMAT_VERSION)
        out.writeInt(diContext.gridWidth)
        out.writeInt(diContext.gridHeight)
        out.writeInt(simulationData.tickCounter)
        out.writeFloat(simulationData.timeSimulation)
        out.writeBoolean(world != null)
        world?.let { writeWorldSpec(out, it) }
    }

    private fun decodeMeta(bytes: ByteArray): Meta {
        val input = DataInputStream(bytes.inputStream())
        val version = input.readInt()
        check(version <= FORMAT_VERSION) { "мир сохранён более новой версией игры (формат $version)" }
        // Формат 1 был только у сборок до выхода сохранений в релиз — переводить его некому.
        check(version >= FORMAT_VERSION) { "мир сохранён старой сборкой (формат $version) и не читается" }
        return Meta(
            gridWidth = input.readInt(),
            gridHeight = input.readInt(),
            tickCounter = input.readInt(),
            timeSimulation = input.readFloat(),
            world = if (input.readBoolean()) readWorldSpec(input) else null
        )
    }

    /** Рецепт, а не готовая карта рельефа: см. WorldSpec. Нужен «Перезапуску» загруженного мира. */
    private fun writeWorldSpec(out: DataOutputStream, world: WorldSpec) {
        out.writeInt(world.gridWidth)
        out.writeInt(world.gridHeight)
        out.writeUTF(world.seed)
        out.writeInt(world.dayNight)
        out.writeInt(world.smoothing)
        out.writeInt(world.edits.size)
        for (edit in world.edits) {
            when (edit) {
                is WorldEdit.Stroke -> {
                    out.writeByte(EDIT_STROKE)
                    out.writeInt(edit.gridX)
                    out.writeInt(edit.gridY)
                    out.writeInt(edit.brushSize)
                    out.writeBoolean(edit.erase)
                    out.writeBoolean(edit.circle)
                }

                WorldEdit.Clear -> out.writeByte(EDIT_CLEAR)
            }
        }
    }

    private fun readWorldSpec(input: DataInputStream): WorldSpec {
        val gridWidth = input.readInt()
        val gridHeight = input.readInt()
        val seed = input.readUTF()
        val dayNight = input.readInt()
        val smoothing = input.readInt()
        val edits = List(input.readInt()) {
            when (val kind = input.readByte().toInt()) {
                EDIT_STROKE -> WorldEdit.Stroke(
                    gridX = input.readInt(),
                    gridY = input.readInt(),
                    brushSize = input.readInt(),
                    erase = input.readBoolean(),
                    circle = input.readBoolean()
                )

                EDIT_CLEAR -> WorldEdit.Clear
                else -> throw IllegalStateException("неизвестная правка рельефа $kind")
            }
        }
        return WorldSpec(gridWidth, gridHeight, seed, dayNight, smoothing, edits)
    }

    /**
     * Пишутся только геномы живых организмов — запечённый геном весит сотню килобайт, а в
     * папке их может быть много. Остальные индексы остаются пустыми записями, чтобы
     * OrganEntity.genomeIndex по-прежнему указывал на свою позицию в списке.
     */
    private fun encodeGenomes(): ByteArray = writeBytes { out ->
        val genomes = genomeManager.genomes
        val used = BooleanArray(genomes.size)
        val organs = organEntity.aliveList
        for (i in 0 until organs.size) {
            val genomeIndex = organEntity.genomeIndex[organs.getInt(i)]
            if (genomeIndex in used.indices) used[genomeIndex] = true
        }

        out.writeInt(genomes.size)
        genomes.forEachIndexed { index, genome ->
            val bytes = if (used[index]) encodeGenome(genome) else ByteArray(0)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun decodeGenomes(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val input = DataInputStream(bytes.inputStream())
        return List(input.readInt()) {
            ByteArray(input.readInt()).also { input.readFully(it) }
        }
    }

    private fun encodeGenome(genome: Genome) = GenomeProto.encodeToByteArray(Genome.serializer(), genome)

    /**
     * Номер типа клетки (CellEntity.cellType) и формулы (NeuralEntity.activationFuncType) —
     * это место в реестре того запуска, который сохранял мир. Здесь записано, какой ключ
     * стоял под каким номером, и при загрузке номера переставляются под текущий реестр:
     * так сохранение переживает перестановку и удаление клеток и формул, а с модом —
     * его появление.
     */
    private fun encodeTypes(): ByteArray = writeBytes { out ->
        val cellKeys = cellEntity.cellList.keys()
        out.writeInt(cellKeys.size)
        cellKeys.forEach(out::writeUTF)
        val formulas = Formulas.all()
        out.writeInt(formulas.size)
        formulas.forEach { out.writeUTF(it.key) }
    }

    private fun remapTypes(bytes: ByteArray) {
        val input = DataInputStream(bytes.inputStream())
        val savedCellKeys = List(input.readInt()) { input.readUTF() }
        val savedFormulaKeys = List(input.readInt()) { input.readUTF() }

        val cellList = cellEntity.cellList
        cellEntity.remapCellTypes(IntArray(savedCellKeys.size) { cellList.indexOfKey(savedCellKeys[it]) }, savedCellKeys)
        neuralEntity.remapFormulas(IntArray(savedFormulaKeys.size) { Formulas.indexOfKey(savedFormulaKeys[it]) }, savedFormulaKeys)
    }

    private val neuralEntity: NeuralEntity
        get() = entities.map { it.second }.filterIsInstance<NeuralEntity>().single()

    private fun encodeInts(values: IntArray): ByteArray = writeBytes { out ->
        out.writeInt(values.size)
        values.forEach(out::writeInt)
    }

    private fun decodeInts(bytes: ByteArray): IntArray {
        if (bytes.isEmpty()) return IntArray(0)
        val input = DataInputStream(bytes.inputStream())
        return IntArray(input.readInt()) { input.readInt() }
    }

    private fun encodePng(pixmap: Pixmap): ByteArray {
        val bytes = ByteArrayOutputStream()
        val png = PixmapIO.PNG()
        try {
            png.setFlipY(false)
            png.write(bytes, pixmap)
        } finally {
            png.dispose()
        }
        return bytes.toByteArray()
    }

    private inline fun writeBytes(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return bytes.toByteArray()
    }

    // ---------------------------------------------------------------- файлы

    /**
     * Слот для нового сохранения. Решается при создании команды сохранения, а не внутри
     * неё: при повторе бага папка с мирами другая, и выбор по ней дал бы другой слот.
     */
    fun nextSlot(): String {
        val last = listMaps().maxOfOrNull { it.slot.toIntOrNull() ?: 0 } ?: 0
        return (last + 1).toString()
    }

    /**
     * Сначала во временный файл, потом на место: оборванная запись (кончилось место,
     * закрыли игру) не должна портить уже существующее сохранение.
     */
    private fun writeArchive(slot: String, entries: Map<String, ByteArray>) {
        val target = mapsDir().child("$slot$EXTENSION")
        val temporary = mapsDir().child("$slot$EXTENSION.tmp")
        MapFile.write(temporary.write(false, IO_BUFFER)).use { zip ->
            // Массивы сущностей — это в основном нули и повторы, они жмутся и на самом
            // быстром уровне, а лишние секунды на максимальном заметны при каждом сохранении.
            zip.setLevel(Deflater.BEST_SPEED)
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        temporary.moveTo(target)
    }

    companion object {
        /** Сторона квадратного превью в пикселях: его показывают в маленьком окошке списка. */
        const val PREVIEW_SIZE = 128

        private const val EXTENSION = MapFile.EXTENSION
        private const val IO_BUFFER = 64 * 1024

        /**
         * 2 — есть types.bin, типы клеток в геномах записаны ключами, а глаза, хвосты,
         * продюсеры и излучатели лежат своими данными в specialModData.bin. С него сохранения
         * миров выходят в релиз; формат 1 был только у сборок до этого и не читается.
         */
        private const val FORMAT_VERSION = 2

        private const val PREVIEW = "preview.png"
        private const val META = "meta.bin"
        private const val GENOMES = "genomes.bin"
        private const val CELL_ACTIONS = "cellActions.bin"
        private const val TYPES = "types.bin"
        private fun entityEntry(name: String) = "entities/$name.bin"

        private const val EDIT_STROKE = 0
        private const val EDIT_CLEAR = 1

        /** Сохранённые миры, последние сохранённые первыми. Без симуляции — нужен меню. */
        fun listMaps(): List<SavedMap> = mapsDir().list()
            .filter { !it.isDirectory && it.extension() == "map" }
            .map { SavedMap(slot = it.nameWithoutExtension(), lastModified = it.lastModified()) }
            .sortedByDescending { it.lastModified }

        /** Превью мира или null, если его нет. Pixmap принадлежит вызывающему. */
        fun readPreview(slot: String): Pixmap? {
            val file = mapFile(slot)
            if (!file.exists()) return null
            return try {
                MapFile.read(file.read(IO_BUFFER)).use { zip ->
                    generateSequence { zip.nextEntry }
                        .firstOrNull { it.name == PREVIEW }
                        ?.let { zip.readBytes() }
                        ?.let { Pixmap(it, 0, it.size) }
                }
            } catch (e: Exception) {
                Gdx.app.error("MapSave", "не удалось прочитать превью мира $slot", e)
                null
            }
        }

        /** Где лежат миры — см. GameFiles.mapsDir (при повторе бага это песочница). */
        private fun mapsDir(): FileHandle = GameFiles.mapsDir()

        fun mapFile(slot: String): FileHandle = mapsDir().child("$slot$EXTENSION")

        fun exists(slot: String): Boolean = mapFile(slot).exists()

        /** Свободное имя на основе [base]: «Мир», «Мир (2)»… — см. MapNames.unique. */
        fun uniqueSlot(base: String): String = MapNames.unique(base, ::exists)

        /** Удалить мир. false — файла не было (при повторе бага не снятый в .bug мир). */
        fun delete(slot: String): Boolean = mapFile(slot).delete()

        /** Проверка внешнего контейнера до помещения в библиотеку и до изменения мира. */
        fun validateImport(file: FileHandle) {
            val names = HashSet<String>()
            val buffer = ByteArray(IO_BUFFER)
            // Проверяем CRC всех записей потоком: вторую копию массивов мира в RAM не держим.
            MapFile.read(file.read(IO_BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    require(names.add(entry.name)) { "повторная запись в карте: ${entry.name}" }
                    if (entry.name == META) {
                        val meta = DataInputStream(zip)
                        require(meta.readInt() == FORMAT_VERSION) { "неподдерживаемый формат состояния мира" }
                        require(meta.readInt() > 0 && meta.readInt() > 0) { "некорректный размер мира" }
                        meta.readInt() // тик
                        meta.readFloat() // время
                        meta.readBoolean() // наличие рецепта рельефа
                    }
                    while (zip.read(buffer) != -1) { /* CRC проверяется в конце записи. */ }
                }
            }
            for (required in arrayOf(META, TYPES, GENOMES, CELL_ACTIONS)) {
                require(required in names) { "в карте нет $required" }
            }
            require(names.any { it.startsWith("entities/") }) { "в карте нет сущностей" }
        }

        private fun readArchive(file: FileHandle): Map<String, ByteArray> {
            check(file.exists()) { "файл мира не найден: ${file.path()}" }
            val entries = HashMap<String, ByteArray>()
            MapFile.read(file.read(IO_BUFFER)).use { zip ->
                generateSequence { zip.nextEntry }
                    .filterNot { it.isDirectory }
                    .forEach {
                        require(!entries.containsKey(it.name)) { "повторная запись в карте: ${it.name}" }
                        entries[it.name] = zip.readBytes()
                    }
            }
            return entries
        }
    }
}
