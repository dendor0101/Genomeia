package io.github.some_example_name.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import games.spooky.gdx.nativefilechooser.NativeFileChooser
import games.spooky.gdx.nativefilechooser.NativeFileChooserCallback
import games.spooky.gdx.nativefilechooser.NativeFilesChooserCallback
import games.spooky.gdx.nativefilechooser.NativeFileChooserConfiguration
import io.github.some_example_name.old.core.FileProvider
import java.io.File

class AndroidFileProvider(private val context: Context, private val fileChooser: NativeFileChooser) : FileProvider {

    override fun getGenomeFile(fileName: String): File {
        return File(context.filesDir, fileName)
    }

    override fun exportGenome(fileName: String) {
        shareGenomeFile(context, fileName)
    }

    /**
     * Системный выбор файлов, сразу несколько. Выбранное отдаётся как есть: в папку геномов
     * его кладёт игра (GenomeImport) — она же чистит имя временной копии и узнаёт формат.
     */
    override val canPickGenomeFiles: Boolean get() = true

    override fun pickGenomeFiles(callback: (List<FileHandle>) -> Unit) {
        val conf = NativeFileChooserConfiguration().apply {
            title = "Genome File"
            // Не application/octet-stream: старые геномы — .json (application/json), и с таким
            // фильтром система их просто не покажет. nameFilter на Android библиотека не
            // поддерживает, поэтому что выбрали, игра проверяет сама, по содержимому.
            mimeFilter = "*/*"
        }

        fileChooser.chooseFiles(conf, object : NativeFilesChooserCallback {
            override fun onFilesChosen(files: com.badlogic.gdx.utils.Array<FileHandle>) {
                callback(files.toList())
            }

            override fun onCancellation() {
                callback(emptyList())
            }

            override fun onError(exception: Exception?) {
                Gdx.app.log("FileChooser", "Error: ${exception?.message}")
                callback(emptyList())
            }
        })
    }

    override fun getExternalFilesDir(type: String?): File? {
        return (Gdx.app as AndroidLauncher).getExternalFilesDir(null)
    }

    /** Системное окно «поделиться»: файл уходит как вложение (почта, мессенджер, диск). */
    override fun shareFile(file: File, title: String): Boolean = try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Gdx.app.error("Share", "не удалось поделиться ${file.name}", e)
        false
    }

    /** Картинка из буфера: на Android туда кладут не байты, а ссылку на картинку (content://). */
    override fun clipboardImage(): Pair<ByteArray, String>? = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        var found: Pair<ByteArray, String>? = null
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                val type = context.contentResolver.getType(uri) ?: continue
                if (!type.startsWith("image/")) continue
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                found = bytes to type.substringAfter('/').substringBefore(';').ifEmpty { "png" }
                break
            }
        }
        found
    } catch (e: Exception) {
        Gdx.app.error("Clipboard", "не удалось взять картинку из буфера", e)
        null
    }

    /** Галерея: тот же системный выбор файла, что и у импорта генома, только картинки. */
    override fun pickImage(callback: (bytes: ByteArray?, name: String?) -> Unit) {
        val conf = NativeFileChooserConfiguration().apply {
            title = "Image"
            mimeFilter = "image/*"
        }
        fileChooser.chooseFile(conf, object : NativeFileChooserCallback {
            override fun onFileChosen(file: FileHandle) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                callback(bytes, file.name().removePrefix("~"))
            }

            override fun onCancellation() = callback(null, null)

            override fun onError(exception: Exception?) {
                Gdx.app.log("FileChooser", "Error: ${exception?.message}")
                callback(null, null)
            }
        })
    }

    override val canPickTextFile: Boolean get() = true

    /**
     * Тот же системный выбор, что у картинок. Фильтр по расширению Android не умеет, а
     * фильтр по MIME "text" скрыл бы .lua с нестандартным MIME, поэтому показываем все файлы.
     * (Без косой со звёздочкой в комментарии: в Kotlin она открывает вложенный комментарий.)
     */
    override fun pickTextFile(
        extensions: List<String>,
        callback: (name: String?, content: String?) -> Unit
    ) {
        val conf = NativeFileChooserConfiguration().apply {
            title = "Text File"
            mimeFilter = "*/*"
        }
        fileChooser.chooseFile(conf, object : NativeFileChooserCallback {
            override fun onFileChosen(file: FileHandle) {
                val text = runCatching { file.readString("UTF-8") }.getOrNull()
                callback(file.name().removePrefix("~"), text)
            }

            override fun onCancellation() = callback(null, null)

            override fun onError(exception: Exception?) {
                Gdx.app.log("FileChooser", "Error: ${exception?.message}")
                callback(null, null)
            }
        })
    }

    override val canSaveTextFile: Boolean get() = true

    /** SAF: игрок сам выбирает место, запись идёт в выданный content:// URI. */
    override fun saveTextFile(
        suggestedName: String,
        content: String,
        callback: (saved: Boolean, name: String) -> Unit
    ) {
        val launcher = context as? AndroidLauncher
        if (launcher == null) {
            callback(false, "")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        launcher.runOnUiThread {
            launcher.startForResult(intent) { resultCode, data ->
                val uri = data?.data
                val saved = resultCode == android.app.Activity.RESULT_OK && uri != null &&
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            true
                        } ?: false
                    }.getOrDefault(false)
                // Имя файла SAF не отдаёт (только content:// URI) — возвращаем предложенное.
                callback(saved, if (saved) suggestedName else "")
            }
        }
    }

    override fun deviceInfo(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
        "cpuCores" to Runtime.getRuntime().availableProcessors().toString(),
        "maxMemoryMb" to (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toString()
    )

}

fun shareGenomeFile(context: Context, relativeFileName: String) {
    val file = File(context.filesDir, relativeFileName)
    if (!file.exists()) {
        println("File to share does not exist: ${file.absolutePath}")
        return
    }

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream" // или другой тип, если знаешь
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(intent, "Share genome file"))
}
