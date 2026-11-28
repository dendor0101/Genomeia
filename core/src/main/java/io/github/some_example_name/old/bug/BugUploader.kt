package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.net.HttpRequestBuilder
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.StreamUtils
import io.github.some_example_name.old.core.GameVersion

/**
 * Отправка .bug на сервер Genomeia по HTTPS.
 *
 * Запрос — multipart/form-data из двух частей:
 *  - meta — JSON (BugMeta): версия, платформа, устройство, подпись краша. По нему сервер
 *    сортирует и группирует отчёты, не распаковывая файл;
 *  - bug  — сам .bug.
 *
 * Успех — только подтверждение сервера с идентификатором и совпавшим размером файла.
 * Исходный .bug остаётся у игрока и при успехе, и при ошибке сети.
 */
object BugUploader {

    const val UPLOAD_URL = "https://199.189.252.141/upload"

    private const val TIMEOUT_MS = 120_000

    /** [onResult] приходит в поток рендера: можно сразу трогать интерфейс. */
    fun upload(bug: FileHandle, onResult: (success: Boolean, message: String) -> Unit) {
        val body = try {
            BugUploadBody(BugReports.meta(bug), bug)
        } catch (e: Exception) {
            onResult(false, e.message ?: e::class.java.simpleName)
            return
        }
        val request = HttpRequestBuilder()
            .newRequest()
            .method(Net.HttpMethods.POST)
            .url(UPLOAD_URL)
            .header("Content-Type", "multipart/form-data; boundary=${body.boundary}")
            .header("X-Genomeia-Version", GameVersion.NAME)
            .timeout(TIMEOUT_MS)
            .build()
        val stream = try {
            body.openStream()
        } catch (e: Exception) {
            onResult(false, e.message ?: e::class.java.simpleName)
            return
        }
        request.setContent(stream, body.length)

        val listener = object : Net.HttpResponseListener {
            override fun handleHttpResponse(response: Net.HttpResponse) {
                StreamUtils.closeQuietly(stream)
                val status = response.status.statusCode
                val ok = status == 201 && runCatching {
                    val receipt = JsonReader().parse(response.resultAsString)
                    receipt.getBoolean("uploaded", false) && receipt.getLong("size", -1) == body.fileSize &&
                        receipt.getString("id", "").matches(Regex("[0-9a-f]{32}"))
                }.getOrDefault(false)
                val message = if (ok) "OK" else if (status == 201) "Invalid upload response" else "HTTP $status"
                Gdx.app.postRunnable { onResult(ok, message) }
            }

            override fun failed(t: Throwable) {
                StreamUtils.closeQuietly(stream)
                Gdx.app.postRunnable { onResult(false, t.message ?: t::class.java.simpleName) }
            }

            override fun cancelled() {
                StreamUtils.closeQuietly(stream)
                Gdx.app.postRunnable { onResult(false, "cancelled") }
            }
        }
        try {
            Gdx.net.sendHttpRequest(request, listener)
        } catch (e: Exception) {
            listener.failed(e)
        }
    }
}
