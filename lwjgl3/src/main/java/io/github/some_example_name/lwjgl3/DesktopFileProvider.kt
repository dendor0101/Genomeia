package io.github.some_example_name.lwjgl3

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.world_logic.CellManager
import java.io.File

class DesktopFileProvider : FileProvider {
    override fun getGenomeFile(fileName: String): File {
        val baseDir = getJarDir()
        return File(baseDir, fileName)
    }

    override fun exportGenome(fileName: String) {

    }

    override fun importGenome(callback: (FileHandle?) -> Unit) {

    }

    override fun getExternalFilesDir(type: String?): File? {
        return null
    }

    private fun getJarDir(): File {
        return try {
            val path: String = CellManager::class.java.protectionDomain.codeSource.location
                .toURI().path
            val jarFile = File(path)
            if (jarFile.isDirectory) jarFile else jarFile.parentFile
        } catch (e: Exception) {
            File(System.getProperty("user.dir"))
        }
    }
}

