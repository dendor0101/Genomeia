package io.github.some_example_name.old.platform_flag

import com.badlogic.gdx.files.FileHandle
import java.io.File

interface FileProvider {
    fun getGenomeFile(fileName: String): File

    fun exportGenome(fileName: String)
    fun importGenome(callback: (FileHandle?) -> Unit)

    fun getExternalFilesDir(type: String?): File?
}
