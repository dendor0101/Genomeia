package io.github.some_example_name.old.journal

import io.github.some_example_name.old.core.ReplayFileException
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.GameFilePaths
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.systems.maps.MapSave

/**
 * Файлы игрока, без которых журнал не повторить: команды говорят «открыть геном X»,
 * «загрузить мир 3», а что в этих файлах — знает только машина игрока.
 *
 * Поэтому сессия держит рядом с журналом снимки (session/attachments/), и они целиком
 * уезжают в .bug:
 *  - genomes/  — все геномы на момент запуска игры;
 *  - settings/ — настройки среды симуляции на момент запуска;
 *  - maps/     — сохранённый мир, когда его впервые загрузили за сессию. Не все миры
 *                сразу: их может быть много и они тяжёлые, а нужны только загруженные.
 *                Мир, записанный в этой же сессии, не снимается — повтор запишет его сам;
 *  - imported/ — импортированные геномы (окно выбора, перетаскивание в окно на ПК) — уже
 *                в виде .genome, старый JSON снимается переведённым и запечённым.
 *
 * Пути внутри [dir] — те же, что в .bug под attachments/, и те же, что в песочнице
 * повтора относительно её корня данных.
 */
object SessionAttachments {

    /** При записи — каталог снимков, при повторе — вложения исходного отчёта. */
    var dir: FileHandle? = null
        private set

    private var replay = false
    private val writtenMaps = HashSet<String>()
    private val attachedMaps = HashSet<String>()
    private var importCounter = 0

    /** Начало записи: настройки до контейнеров; геномы снимаются после переноса старых файлов. */
    fun startSession(target: FileHandle) {
        replay = false
        dir = target.apply { mkdirs() }
        writtenMaps.clear()
        attachedMaps.clear()
        importCounter = 0

        val settings = GameFiles.settingsDir().child(SubstrateSettings.FILE_NAME)
        if (settings.exists()) {
            GameFilePaths.writeAtomically(target.child(GameFiles.SETTINGS).child(settings.name()), target.parent()) { settings.copyTo(it) }
        }
    }

    /**
     * Единственный снимок геномов: после GenomeLibrary.prepare, до первой команды.
     * Ранний снимок пришлось бы переписывать после миграции; два копирования всей папки
     * замедляли каждый запуск даже тогда, когда ни один геном не изменился.
     */
    fun captureGenomes() {
        val target = dir ?: return
        val genomes = GameFiles.genomesDir()
        for (file in genomes.list()) {
            if (file.isDirectory || !file.extension().equals("genome", true)) continue
            GameFilePaths.writeAtomically(target.child(GameFiles.GENOMES).child(file.name()), target.parent()) { file.copyTo(it) }
        }
    }

    /** Мир записан в этой сессии — снимать его при загрузке уже не нужно. */
    fun markMapWritten(slot: String) {
        writtenMaps += slot
    }

    /** Мир сейчас загрузится: снять файл, если это первая загрузка и записан он не нами. */
    fun attachMapBeforeLoad(slot: String): FileHandle {
        val file = MapSave.mapFile(slot)
        val root = dir ?: return file
        if (replay || slot in writtenMaps || slot in attachedMaps || !file.exists()) return file
        val snapshot = root.child(GameFiles.MAPS).child(file.name())
        GameFilePaths.writeAtomically(snapshot, root.parent()) { file.copyTo(it) }
        attachedMaps += slot
        // Загружаем именно снятые байты, даже если внешний файл в этот момент заменили.
        return snapshot
    }

    /** Путь отдаётся команде только после публикации полного снимка. */
    fun attachImported(name: String, write: (FileHandle) -> Unit): String {
        val root = dir ?: return ""
        check(!replay)
        val leaf = "${importCounter++}-${GameFilePaths.fileName(name)}"
        GameFilePaths.writeAtomically(root.child(IMPORTED).child(leaf), root.parent(), write)
        return "$IMPORTED/$leaf"
    }

    fun restoreGenome(path: String, fileName: String) {
        if (!fileName.endsWith(".genome", ignoreCase = true)) throw ReplayFileException("Недопустимое имя генома: $fileName")
        restore(path, GameFilePaths.child(GameFiles.genomesDir(), fileName))
    }

    fun restoreMap(path: String, slot: String) = restore(path, MapSave.mapFile(slot))

    private fun restore(path: String, target: FileHandle) {
        val root = dir ?: throw ReplayFileException("Нет каталога вложений")
        if (!path.startsWith("$IMPORTED/")) throw ReplayFileException("Недопустимый путь вложения: $path")
        val source = GameFilePaths.child(GameFilePaths.child(root, IMPORTED), path.removePrefix("$IMPORTED/"))
        if (!source.file().isFile) throw ReplayFileException("В .bug нет файла $path")
        if (target.isDirectory) throw ReplayFileException("На месте файла каталог: ${target.name()}")
        try {
            GameFilePaths.writeAtomically(target) { source.copyTo(it) }
        } catch (e: Exception) {
            throw ReplayFileException("Не удалось восстановить $path: ${e.message}")
        }
    }

    /** Повтор: снимки сессии — это распакованный .bug, а не запись. */
    fun useForReplay(unpacked: FileHandle) {
        replay = true
        dir = unpacked
    }

    fun stop() {
        dir = null
    }

    private const val IMPORTED = "imported"
}
