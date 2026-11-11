package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.net.HttpRequestBuilder
import io.github.some_example_name.old.core.GameVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Отправка .bug на сервер — ЗАГЛУШКА.
 *
 * Запрос — multipart/form-data из двух частей:
 *  - meta — JSON (BugMeta): версия, платформа, устройство, подпись краша. По нему сервер
 *    сортирует и группирует отчёты, не распаковывая файл;
 *  - bug  — сам .bug.
 *
 * Запрос настоящий (POST тела файла через Gdx.net, тот работает на всех платформах), но
 * сервера пока нет: адрес в домене .invalid, который по стандарту не существует нигде,
 * так что запрос гарантированно закончится ошибкой и никуда не уйдёт. Когда сервер
 * появится — поменять [UPLOAD_URL] и разобрать ответ.
 */
object BugUploader {

    const val UPLOAD_URL = "https://bugs.genomeia.invalid/upload"

    private const val TIMEOUT_MS = 15_000

    /** Разделитель частей: случайная строка, которой заведомо нет ни в JSON, ни в zip-заголовках. */
    private const val BOUNDARY = "----GenomeiaBug7f3c91e2a4d05b68"

    private fun multipart(metaJson: String, bug: FileHandle): ByteArray {
        val out = ByteArrayOutputStream(bug.length().toInt() + 4096)
        fun line(text: String) = out.write((text + "\r\n").toByteArray(Charsets.UTF_8))

        line("--$BOUNDARY")
        line("Content-Disposition: form-data; name=\"meta\"")
        line("Content-Type: application/json; charset=utf-8")
        line("")
        line(metaJson)

        line("--$BOUNDARY")
        line("Content-Disposition: form-data; name=\"bug\"; filename=\"${bug.name()}\"")
        line("Content-Type: application/octet-stream")
        line("")
        bug.read().use { it.copyTo(out) }
        line("")
        line("--$BOUNDARY--")
        return out.toByteArray()
    }

    /** [onResult] приходит в поток рендера: можно сразу трогать интерфейс. */
    fun upload(bug: FileHandle, onResult: (success: Boolean, message: String) -> Unit) {
        val body = try {
            multipart(BugReports.meta(bug), bug)
        } catch (e: Exception) {
            onResult(false, e.message ?: e::class.java.simpleName)
            return
        }
        val request = HttpRequestBuilder()
            .newRequest()
            .method(Net.HttpMethods.POST)
            .url(UPLOAD_URL)
            .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .header("X-Genomeia-Version", GameVersion.NAME)
            .timeout(TIMEOUT_MS)
            .build()
        request.setContent(ByteArrayInputStream(body), body.size.toLong())

        Gdx.net.sendHttpRequest(request, object : Net.HttpResponseListener {
            override fun handleHttpResponse(response: Net.HttpResponse) {
                val status = response.status.statusCode
                val ok = status in 200..299
                Gdx.app.postRunnable { onResult(ok, if (ok) "OK" else "HTTP $status") }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable { onResult(false, t.message ?: t::class.java.simpleName) }
            }

            override fun cancelled() {
                Gdx.app.postRunnable { onResult(false, "cancelled") }
            }
        })
    }
}
