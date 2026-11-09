package io.github.some_example_name.old.core

import com.badlogic.gdx.files.FileHandle
import java.io.File

/** Ошибка входных данных повтора должна завершать сверку, в том числе для отчёта о краше. */
class ReplayFileException(message: String) : IllegalArgumentException(message)

/** Имена попадают сюда также из журнала, минуя диалоги и системный выбор файла. */
object GameFilePaths {
    private val windowsDevice = Regex("""(?i:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|(?:COM|LPT)[1-9¹²³])""")

    fun fileName(name: String): String {
        if (name.isEmpty() || name == "." || name == ".." || name.endsWith('.') || name.endsWith(' ') ||
            name.any { it < ' ' || it in "\\/:*?\"<>|" } ||
            windowsDevice.matches(name.substringBefore('.').trimEnd())) {
            throw ReplayFileException("Недопустимое имя файла: $name")
        }
        return name
    }

    /** Платформы с POSIX rename заменяют файл одним системным вызовом. На Windows
     * лаунчер устанавливает атомарную замену через NIO (на старом Android NIO нет). */
    var replaceFile: (File, File) -> Unit = { source, target ->
        check(source.renameTo(target)) { "Не удалось заменить ${target.name}" }
    }

    var redirected: (File) -> Boolean = { file ->
        file.canonicalFile != File(file.absoluteFile.parentFile.canonicalFile, file.name)
    }

    fun writeAtomically(target: FileHandle, tempDir: FileHandle = target.parent(), write: (FileHandle) -> Unit) {
        tempDir.mkdirs()
        val temp = FileHandle(File.createTempFile("staging-", ".tmp", tempDir.file()))
        try {
            write(temp)
            target.parent().mkdirs()
            replaceFile(temp.file(), target.file())
        } finally {
            temp.delete()
        }
    }

    fun child(parent: File, name: String): File = File(parent, fileName(name)).also(::checkPath)

    fun child(parent: FileHandle, name: String): FileHandle =
        parent.child(fileName(name)).also { checkPath(it.file()) }

    private fun checkPath(file: File) {
        val redirect = try {
            redirected(file)
        } catch (e: java.io.IOException) {
            throw ReplayFileException("Не удалось проверить путь ${file.name}: ${e.message}")
        }
        if (redirect) throw ReplayFileException("Перенаправление пути: ${file.name}")
    }
}
