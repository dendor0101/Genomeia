package io.github.some_example_name.teavm

import com.badlogic.gdx.files.FileHandle
import  io.github.some_example_name.old.core.FileProvider
import java.io.File

class WebFileProvider : FileProvider {

    override fun getGenomeFile(fileName: String): File {
        return File("")
    }

    override fun exportGenome(fileName: String) {
        // Stub
    }

    override fun importGenome(callback: (FileHandle?) -> Unit) {
        callback(null)
    }

    override fun getExternalFilesDir(type: String?): File? {
        return null
    }
}

