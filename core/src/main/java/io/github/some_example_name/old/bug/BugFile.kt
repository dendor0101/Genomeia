package io.github.some_example_name.old.bug

import io.github.some_example_name.old.core.GameFilePaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Формат .bug — всё, что нужно разработчику, чтобы увидеть и повторить баг, одним файлом.
 *
 * Устроен как файл генома: 8 байт сигнатуры, версия формата, дальше — ZIP. Внутри:
 *  - manifest.properties — кто, когда, на чём, версия игры, контрольные суммы журнала;
 *  - description.txt     — что написал игрок;
 *  - crash.txt           — стектрейс, маршрут по экранам и хвост действий (если это краш);
 *  - console.log         — вывод игры за сессию (console.1.log — его начало, если он длинный);
 *  - journal.bin         — журнал команд сессии (JournalFormat), по нему идёт повтор;
 *  - actions.txt         — тот же журнал текстом, чтобы читать глазами;
 *  - meta.json           — краткое описание для сервера (BugMeta): версия, платформа,
 *                          устройство, подпись краша — для сортировки и группировки;
 *  - images/             — снимок экрана игры (если игрок отметил) и картинки, которые
 *                          игрок приложил сам (из буфера обмена, галереи, файла);
 *  - attachments/        — файлы игрока: геномы, настройки среды, загруженные миры.
 *
 * Сжатие — обычный Deflate: журнал и логи — это повторяющийся текст и однотипные записи,
 * сжимаются в разы, а стоит это десятки миллисекунд даже на телефоне.
 *
 * Только java.io, без Gdx: формат проверяется тестами без окна.
 */
object BugFile {

    /** "genombug": та же манера, что "genomeia" у геномов и "genomjrn" у журнала. */
    private val MAGIC = "genombug".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val EXTENSION = "bug"

    const val MANIFEST = "manifest.properties"
    const val DESCRIPTION = "description.txt"
    const val CRASH = "crash.txt"
    const val CONSOLE = "console.log"
    const val CONSOLE_PREVIOUS = "console.1.log"
    const val JOURNAL = "journal.bin"
    const val ACTIONS = "actions.txt"
    const val META = "meta.json"
    const val IMAGES = "images/"
    const val ATTACHMENTS = "attachments/"

    /** Содержимое одного файла внутри .bug: готовые байты или файл на диске. */
    sealed interface Content {
        class Bytes(val data: ByteArray) : Content
        class FromFile(val file: File) : Content
    }

    /**
     * Записать .bug. Сначала во временный файл, потом на место: оборванная запись (краш
     * посреди сборки, кончилось место) не оставит битый .bug под настоящим именем.
     */
    fun write(target: File, entries: List<Pair<String, Content>>) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { raw ->
            val buffered = BufferedOutputStream(raw, BUFFER)
            val header = DataOutputStream(buffered)
            header.write(MAGIC)
            header.writeInt(VERSION)
            header.flush()

            val zip = ZipOutputStream(buffered)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                when (content) {
                    is Content.Bytes -> zip.write(content.data)
                    is Content.FromFile -> FileInputStream(content.file).use { it.copyTo(zip, BUFFER) }
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
        GameFilePaths.replaceFile(temp, target)
    }

    /** Проверить сигнатуру и отдать поток ZIP-части. */
    private fun openZip(input: InputStream): ZipInputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "это не .bug Genomeia" }
        val version = data.readInt()
        require(version == VERSION) { ".bug версии $version, а игра читает $VERSION" }
        return ZipInputStream(data)
    }

    /** Все файлы .bug в память — для перепаковки (сменить описание). */
    fun readAll(file: File): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        openZip(BufferedInputStream(FileInputStream(file), BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    /** Один файл из .bug или null — без чтения остального (например, meta.json для отправки). */
    fun readEntry(file: File, name: String): ByteArray? =
        openZip(BufferedInputStream(FileInputStream(file), BUFFER)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == name }?.let { zip.readBytes() }
        }

    /** Распаковать .bug в каталог — для повтора. */
    fun unpack(file: File, toDir: File) {
        toDir.mkdirs()
        val names = HashSet<File>()
        openZip(BufferedInputStream(FileInputStream(file), BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val components = entry.name.removeSuffix("/").split('/')
                val target = components.fold(toDir) { parent, name -> GameFilePaths.child(parent, name) }
                require(names.add(target)) { "Повторяющееся имя в .bug: ${entry.name}" }
                if (entry.isDirectory) continue
                target.parentFile.mkdirs()
                FileOutputStream(target).use { zip.copyTo(it, BUFFER) }
            }
        }
    }

    /** Манифест: простые строки key=value в UTF-8. */
    fun formatManifest(values: Map<String, String>): ByteArray =
        values.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${v.replace('\n', ' ')}" }
            .toByteArray(Charsets.UTF_8)

    fun parseManifest(data: ByteArray): Map<String, String> =
        data.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    private const val BUFFER = 64 * 1024
}
