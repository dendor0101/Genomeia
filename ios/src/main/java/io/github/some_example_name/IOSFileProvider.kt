package io.github.some_example_name

import io.github.some_example_name.old.platform_flag.FileProvider
import java.io.File

class IOSFileProvider: FileProvider {
    override fun getGenomeFile(fileName: String): File {
        return File("TODO")
    }

    override fun exportGenome(fileName: String) {
        //TODO("Not yet implemented")
    }

    override fun getExternalFilesDir(type: String?): File? {
        TODO("Not yet implemented")
    }
}
