package io.github.some_example_name.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import io.github.some_example_name.old.core.GameFilePaths
import javax.imageio.ImageIO

class DesktopFileProvider : FileProvider {
    init {
        GameFilePaths.replaceFile = { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        GameFilePaths.tryLock = { file ->
            file.parentFile?.mkdirs()
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            // Замок этого же процесса (вторая игра в одной JVM, как в программах-проверках)
            // FileChannel не возвращает null, а бросает OverlappingFileLockException.
            val lock = try { channel.tryLock() } catch (e: OverlappingFileLockException) { null }
            if (lock == null) {
                channel.close()
                null
            } else {
                AutoCloseable { lock.release(); channel.close() }
            }
        }
        GameFilePaths.redirected = { file ->
            val path = file.toPath().toAbsolutePath()
            Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                path.toRealPath() != path.parent.toRealPath().resolve(path.fileName))
        }
    }

    override fun getGenomeFile(fileName: String): File {
        val baseDir = getJarDir()
        return File(baseDir, fileName)
    }

    override fun exportGenome(fileName: String) {

    }

    /**
     * Системное окно выбора, сразу несколько файлов: у игрока со старой версии их обычно
     * пачка. Как и у [pickImage] — в своём потоке и не на macOS. Там остаётся перетаскивание
     * файлов в окно игры (Lwjgl3Launcher).
     */
    override val canPickGenomeFiles: Boolean get() = !isMac

    override fun pickGenomeFiles(callback: (List<FileHandle>) -> Unit) {
        if (isMac) {
            callback(emptyList())
            return
        }
        Thread({
            val files = try {
                val dialog = FileDialog(null as Frame?, "Genomeia", FileDialog.LOAD)
                dialog.isMultipleMode = true
                // На Windows фильтр задаётся «именем файла», на остальных — FilenameFilter.
                dialog.file = GENOME_EXTENSIONS.joinToString(";") { "*.$it" }
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in GENOME_EXTENSIONS }
                dialog.isVisible = true
                dialog.files.filter { it.isFile }
            } catch (e: Exception) {
                System.err.println("Не удалось открыть выбор файла: ${e.message}")
                emptyList()
            }
            callback(files.map { Gdx.files.absolute(it.absolutePath) })
        }, "Pick-Genomes").apply { isDaemon = true }.start()
    }

    override fun getExternalFilesDir(type: String?): File? {
        return null
    }

    /** На ПК «поделиться» — это открыть папку с файлом: дальше игрок сам. */
    override fun shareFile(file: File, title: String): Boolean {
        val dir = file.absoluteFile.parentFile ?: return false
        val os = System.getProperty("os.name").lowercase()
        return try {
            // На macOS AWT рядом с GLFW может подвесить окно игры — там только системная команда.
            if ("mac" !in os && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir)
            } else {
                val command = when {
                    "win" in os -> listOf("explorer", dir.path)
                    "mac" in os -> listOf("open", dir.path)
                    else -> listOf("xdg-open", dir.path)
                }
                ProcessBuilder(command).start()
            }
            true
        } catch (e: Exception) {
            System.err.println("Не удалось открыть папку ${dir.path}: ${e.message}")
            false
        }
    }

    /**
     * Картинка из буфера: и сама картинка (снимок экрана, скопированная из браузера), и
     * скопированный в проводнике файл картинки. На macOS — нет: AWT там рядом с GLFW
     * может подвесить окно игры.
     */
    override fun clipboardImage(): Pair<ByteArray, String>? {
        if (isMac) return null
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            when {
                clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                    val image = clipboard.getData(DataFlavor.imageFlavor) as Image
                    toPng(image) to "png"
                }
                clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                    (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>)
                        .filterIsInstance<File>()
                        .firstOrNull { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                        ?.let { it.readBytes() to it.extension.lowercase() }
                }
                else -> null
            }
        } catch (e: Exception) {
            System.err.println("Не удалось взять картинку из буфера: ${e.message}")
            null
        }
    }

    /** Системное окно выбора файла — в своём потоке: оно модальное и держит поток до выбора. */
    override fun pickImage(callback: (bytes: ByteArray?, name: String?) -> Unit) {
        if (isMac) {
            callback(null, null)
            return
        }
        Thread({
            val file = try {
                val dialog = FileDialog(null as Frame?, "Genomeia", FileDialog.LOAD)
                dialog.file = IMAGE_EXTENSIONS.joinToString(";") { "*.$it" }
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                dialog.isVisible = true
                dialog.files.firstOrNull()
            } catch (e: Exception) {
                System.err.println("Не удалось открыть выбор файла: ${e.message}")
                null
            }
            callback(file?.takeIf { it.isFile }?.readBytes(), file?.name)
        }, "Pick-Image").apply { isDaemon = true }.start()
    }

    override fun deviceInfo(): Map<String, String> = mapOf(
        "cpuCores" to Runtime.getRuntime().availableProcessors().toString(),
        "maxMemoryMb" to (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toString(),
        "osArch" to (System.getProperty("os.arch") ?: "?")
    )

    private val isMac get() = "mac" in System.getProperty("os.name").lowercase()

    private fun toPng(image: Image): ByteArray {
        val buffered = image as? BufferedImage ?: BufferedImage(
            image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB
        ).also { copy ->
            val g = copy.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        return out.toByteArray()
    }

    private fun getJarDir(): File {
        return try {
            val path: String = SimulationSystem::class.java.protectionDomain.codeSource.location
                .toURI().path
            val jarFile = File(path)
            if (jarFile.isDirectory) jarFile else jarFile.parentFile
        } catch (e: Exception) {
            File(System.getProperty("user.dir"))
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        val GENOME_EXTENSIONS = listOf("genome", "json")
    }
}
