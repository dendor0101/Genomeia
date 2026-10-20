package io.github.some_example_name.old.bug

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.net.HttpStatus
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugUploaderTest {
    private lateinit var dir: File
    private lateinit var bug: FileHandle
    private var previousApp: Application? = null
    private var previousNet: Net? = null

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bug-upload").toFile()
        bug = FileHandle(File(dir, "report.bug"))
        BugFile.write(bug.file(), listOf(
            BugFile.META to BugFile.Content.Bytes("{\"game\":\"alpha-0.2.4\"}".toByteArray()),
            BugFile.DESCRIPTION to BugFile.Content.Bytes("Проверка с нулём \u0000 и кириллицей".toByteArray())
        ))
        previousApp = Gdx.app
        previousNet = Gdx.net
        Gdx.app = proxy(Application::class.java) { name, args ->
            if (name == "postRunnable") (args!![0] as Runnable).run()
            null
        }
    }

    @AfterTest
    fun tearDown() {
        Gdx.app = previousApp
        Gdx.net = previousNet
        dir.deleteRecursively()
    }

    @Test
    fun `multipart сохраняет бинарный файл и длину тела`() {
        val body = BugUploadBody("{\"text\":\"русский\"}", bug)
        val bytes = body.openStream().use { it.readBytes() }
        assertEquals(body.length, bytes.size.toLong())
        val content = bytes.toString(Charsets.ISO_8859_1)
        val fileStart = content.indexOf("Content-Type: application/octet-stream\r\n\r\n") +
            "Content-Type: application/octet-stream\r\n\r\n".length
        assertContentEquals(bug.readBytes(), bytes.copyOfRange(fileStart, fileStart + bug.length().toInt()))
        assertTrue(content.endsWith("\r\n--${body.boundary}--\r\n"))
    }

    @Test
    fun `перезапись локального отчёта не меняет уже подготовленный запрос`() {
        val body = BugUploadBody("{}", bug)
        val before = body.openStream().use { it.readBytes() }
        bug.writeString("changed", false)
        assertContentEquals(before, body.openStream().use { it.readBytes() })
    }

    @Test
    fun `подтверждение сервера обязательно и проверяется по размеру`() {
        assertTrue(send(201, """{"uploaded":true,"id":"${"a".repeat(32)}","size":${bug.length()}}"""))
        assertFalse(send(201, """{"uploaded":true,"id":"${"a".repeat(32)}","size":1}"""))
        assertFalse(send(201, """{"uploaded":true,"id":"../etc/passwd","size":${bug.length()}}"""))
        assertFalse(send(201, "<html>error</html>"))
        assertFalse(send(201, """{"uploaded":{}}"""))
        assertFalse(send(200, "OK"))
        assertFalse(send(413, "too large"))
    }

    @Test
    fun `ошибка отправки сохраняет локальный отчёт`() {
        val before = bug.readBytes()
        Gdx.net = proxy(Net::class.java) { _, _ -> throw IllegalStateException("offline") }
        var result: Boolean? = null
        BugUploader.upload(bug) { success, _ -> result = success }
        assertEquals(false, result)
        assertContentEquals(before, bug.readBytes())
    }

    private fun send(status: Int, text: String): Boolean {
        Gdx.net = proxy(Net::class.java) { name, args ->
            if (name == "sendHttpRequest") {
                val request = args!![0] as Net.HttpRequest
                assertEquals(BugUploader.UPLOAD_URL, request.url)
                assertEquals(request.contentLength, request.contentStream.use { it.readBytes() }.size.toLong())
                val response = proxy(Net.HttpResponse::class.java) { method, _ ->
                    when (method) {
                        "getStatus" -> HttpStatus(status)
                        "getResultAsString" -> text
                        else -> null
                    }
                }
                (args[1] as Net.HttpResponseListener).handleHttpResponse(response)
            }
            null
        }
        var result: Boolean? = null
        BugUploader.upload(bug) { success, _ -> result = success }
        return checkNotNull(result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args) } as T
}
