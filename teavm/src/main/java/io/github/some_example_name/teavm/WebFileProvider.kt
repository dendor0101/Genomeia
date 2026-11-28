package io.github.some_example_name.teavm

import  io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import java.io.File

class WebFileProvider : FileProvider {
    override fun getGenomeFile(fileName: String): File {
        val baseDir = getJarDir()
        return File(baseDir, fileName)
    }

    override fun exportGenome(fileName: String) {

    }

    override fun getExternalFilesDir(type: String?): File? {
        return null
    }

    override val canPickTextFile: Boolean get() = true

    override fun pickTextFile(
        extensions: List<String>,
        callback: (name: String?, content: String?) -> Unit
    ) {
        val accept = extensions.joinToString(",") { ".$it" }
        WebFilePicker.pick(accept) { name, text -> callback(name, text) }
    }

    override val canSaveTextFile: Boolean get() = true

    override fun saveTextFile(
        suggestedName: String,
        content: String,
        callback: (saved: Boolean, name: String) -> Unit
    ) {
        val saved = runCatching { WebFilePicker.save(suggestedName, content) }.isSuccess
        callback(saved, suggestedName)
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
}

