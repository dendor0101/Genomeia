package io.github.some_example_name.old.journal

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
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

    /** Каталог снимков. null — сессия не пишется (идёт повтор или запись не началась). */
    var dir: FileHandle? = null
        private set

    private val writtenMaps = HashSet<String>()
    private val attachedMaps = HashSet<String>()
    private var importCounter = 0

    /** Начало записи: настройки до контейнеров; геномы снимаются после переноса старых файлов. */
    fun startSession(target: FileHandle) {
        dir = target.apply { mkdirs() }
        writtenMaps.clear()
        attachedMaps.clear()
        importCounter = 0

        val settings = GameFiles.settingsDir().child(SubstrateSettings.FILE_NAME)
        if (settings.exists()) settings.copyTo(target.child(GameFiles.SETTINGS).child(settings.name()))
    }

    /**
     * Единственный снимок геномов: после GenomeLibrary.prepare, до первой команды.
     * Ранний снимок пришлось бы переписывать после миграции; два копирования всей папки
     * замедляли каждый запуск даже тогда, когда ни один геном не изменился.
     */
    fun captureGenomes() {
        val target = dir ?: return
        copyDirectory(GameFiles.genomesDir(), target.child(GameFiles.GENOMES)) { it.extension().equals("genome", true) }
    }

    /** Мир записан в этой сессии — снимать его при загрузке уже не нужно. */
    fun markMapWritten(slot: String) {
        writtenMaps += slot
    }

    /** Мир сейчас загрузится: снять файл, если это первая загрузка и записан он не нами. */
    fun attachMapBeforeLoad(slot: String) {
        val target = dir ?: return
        if (slot in writtenMaps || !attachedMaps.add(slot)) return
        val file = MapSave.mapFile(slot)
        if (!file.exists()) return
        try {
            file.copyTo(target.child(GameFiles.MAPS).child(file.name()))
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "не удалось снять мир $slot", e)
        }
    }

    /** Импортированный файл. Путь снимка едет в команде GenomeImported или MapImported. */
    fun attachImported(file: FileHandle): String {
        val path = "imported/${importCounter++}-${file.name()}"
        val target = dir ?: return path
        try {
            file.copyTo(target.child(path))
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "не удалось снять импортированный файл ${file.name()}", e)
        }
        return path
    }

    /** Положить снимок [path] на место [target]; false — снимка нет. */
    fun restore(path: String, target: FileHandle): Boolean {
        val source = (dir ?: return false).child(path)
        if (!source.exists()) {
            Gdx.app?.error(TAG, "в .bug нет файла $path")
            return false
        }
        source.copyTo(target)
        return true
    }

    /** Повтор: снимки сессии — это распакованный .bug, а не запись. */
    fun useForReplay(unpacked: FileHandle) {
        dir = unpacked
    }

    fun stop() {
        dir = null
    }

    private fun copyDirectory(from: FileHandle, to: FileHandle, filter: (FileHandle) -> Boolean) {
        if (!from.exists()) return
        to.mkdirs()
        from.list().filter { !it.isDirectory && filter(it) }.forEach { it.copyTo(to.child(it.name())) }
    }

    private const val TAG = "SessionAttachments"
}
