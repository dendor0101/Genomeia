package io.github.some_example_name.render.microscope

import com.badlogic.gdx.files.FileHandle
import java.util.zip.GZIPInputStream

/** AAPT разворачивает gzip-файлы в assets и убирает суффикс. JSON хранит исходное имя.
 * На ПК читаем gzip, на Android — упакованный AAPT несжатый вариант тех же байтов. */
internal object OpticalAssetData {
    fun read(directory:FileHandle,name:String):ByteArray {
        val file=directory.child(name)
        if(!name.endsWith(".gz"))return file.readBytes()
        return if(file.exists())GZIPInputStream(file.read()).use{it.readBytes()}
        else directory.child(name.removeSuffix(".gz")).readBytes()
    }
}
