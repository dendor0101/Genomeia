package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Net
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.bug.BugUploader
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ручная сетевая проверка: отправляет указанный .bug тем же кодом, что кнопка игры. */
fun main(args: Array<String>) {
    val file = File(args.single()).absoluteFile
    require(file.isFile) { "Нет .bug: $file" }
    Gdx.app = Proxy.newProxyInstance(Application::class.java.classLoader, arrayOf(Application::class.java)) { _, method, values ->
        if (method.name == "postRunnable") (values!![0] as Runnable).run()
        null
    } as Application
    Gdx.net = Lwjgl3Net(Lwjgl3ApplicationConfiguration())
    val done = CountDownLatch(1)
    var success = false
    var message = "no response"
    val started = System.nanoTime()
    BugUploader.upload(FileHandle(file)) { ok, result ->
        success = ok
        message = result
        done.countDown()
    }
    check(done.await(150, TimeUnit.SECONDS)) { "Таймаут отправки" }
    check(success) { "Сервер не принял отчёт: $message" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    println("Отчёт принят: ${file.name}, ${file.length()} байт, ${(System.nanoTime() - started) / 1_000_000} мс")
    println("SHA-256: $sha256")
}
