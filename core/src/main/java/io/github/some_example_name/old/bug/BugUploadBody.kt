package io.github.some_example_name.old.bug

import com.badlogic.gdx.files.FileHandle
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Один неизменяемый снимок тела запроса вместо ByteArrayOutputStream + toByteArray:
 * игрок может переписать локальный .bug, пока сеть ещё читает его. Gdx.net на ПК сам
 * буферизует тело; поток прямо из файла не устраняет эту копию и мешает перепаковке.
 */
internal class BugUploadBody(meta: String, bug: FileHandle) {
    val boundary = "----GenomeiaBug" + UUID.randomUUID().toString().replace("-", "")
    val fileSize = bug.length()
    private val content: ByteArray
    val length: Long

    init {
        require(fileSize in 1..MAX_FILE_BYTES) { "Report must be between 1 byte and 100 MiB" }
        // Имя приходит с диска: кавычки и переводы строки не должны стать HTTP-заголовками.
        val name = bug.name().map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_' }
            .joinToString("")
        val prefix = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"meta\"\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n\r\n" + meta + "\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"bug\"; filename=\"$name\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        length = prefix.size.toLong() + fileSize + suffix.size
        content = ByteArray(length.toInt())
        prefix.copyInto(content)
        DataInputStream(bug.read()).use { it.readFully(content, prefix.size, fileSize.toInt()) }
        suffix.copyInto(content, prefix.size + fileSize.toInt())
    }

    fun openStream(): InputStream = ByteArrayInputStream(content)

    private companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
    }
}
