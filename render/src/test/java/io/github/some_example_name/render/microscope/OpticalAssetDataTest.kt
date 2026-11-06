package io.github.some_example_name.render.microscope

import com.badlogic.gdx.files.FileHandle
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class OpticalAssetDataTest {
    @Test fun sourceGzipAndAndroidExpandedAssetHaveIdenticalBytes(){
        val dir=FileHandle(Files.createTempDirectory("optical-assets-").toFile())
        try{
            val data=ByteArray(8976){(it*31).toByte()}
            GZIPOutputStream(dir.child("texture-0.bin.gz").write(false)).use{it.write(data)}
            assertContentEquals(data,OpticalAssetData.read(dir,"texture-0.bin.gz"))
            // Точно как в присланном Android APK: JSON ссылается на .gz, в assets только .bin.
            dir.child("texture-0.bin.gz").delete();dir.child("texture-0.bin").writeBytes(data,false)
            assertContentEquals(data,OpticalAssetData.read(dir,"texture-0.bin.gz"))
            assertContentEquals(data,OpticalAssetData.read(dir,"texture-0.bin"))
            assertFails{OpticalAssetData.read(dir,"missing.bin.gz")}
        }finally{dir.deleteDirectory()}
    }
    @Test fun corruptGzipIsNotSilentlyReplacedByAnotherAsset(){
        val dir=FileHandle(Files.createTempDirectory("optical-corrupt-").toFile())
        try{
            dir.child("map.bin.gz").writeBytes(byteArrayOf(1,2,3),false)
            dir.child("map.bin").writeBytes(byteArrayOf(4),false)
            assertFails{OpticalAssetData.read(dir,"map.bin.gz")}
        }finally{dir.deleteDirectory()}
    }
}
