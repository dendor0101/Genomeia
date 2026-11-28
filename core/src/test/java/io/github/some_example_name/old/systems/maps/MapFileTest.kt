package io.github.some_example_name.old.systems.maps

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.GameFiles
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import kotlin.test.*

class MapFileTest {
    private fun archive(): ByteArray {
        val bytes = ByteArrayOutputStream()
        MapFile.write(bytes).use {
            it.putNextEntry(ZipEntry("preview.png"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun headerAndPayloadRoundTrip() {
        val bytes = archive()
        assertEquals("genomeia-map", String(bytes, 0, 12, Charsets.US_ASCII))
        assertContentEquals(byteArrayOf(0, 0, 0, 1), bytes.copyOfRange(12, 16))
        MapFile.read(bytes.inputStream()).use {
            assertEquals("preview.png", it.nextEntry.name)
            assertContentEquals(byteArrayOf(1, 2, 3), it.readBytes())
            assertNull(it.nextEntry)
        }
    }

    @Test fun zipWithoutMapHeaderIsRejected() {
        val bytes = archive().drop(16).toByteArray()
        assertFailsWith<IllegalArgumentException> { MapFile.read(bytes.inputStream()) }
    }

    @Test fun wrongMagicFutureVersionAndTruncatedHeaderAreRejected() {
        val original = archive()
        for (index in listOf(0, 15, 16)) {
            val bytes = original.copyOf()
            bytes[index] = 42
            assertFailsWith<IllegalArgumentException> { MapFile.read(bytes.inputStream()) }
        }
        for (size in 0 until 20) {
            assertFails { MapFile.read(original.copyOf(size).inputStream()) }
        }
    }

    @Test fun importChecksRequiredEntriesAndCrc() {
        val root = FileHandle(Files.createTempDirectory("map-import").toFile())
        try {
            fun map(version: Int, entries: List<String>): ByteArray {
                val bytes = ByteArrayOutputStream()
                MapFile.write(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("meta.bin"))
                    val out = java.io.DataOutputStream(zip)
                    out.writeInt(version)
                    out.writeInt(64)
                    out.writeInt(64)
                    out.writeInt(123)
                    out.writeFloat(1f)
                    out.writeBoolean(false)
                    zip.closeEntry()
                    for (name in entries) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(byteArrayOf(1, 2, 3))
                        zip.closeEntry()
                    }
                }
                return bytes.toByteArray()
            }
            val all = listOf("types.bin", "genomes.bin", "cellActions.bin", "entities/cell.bin", "physics.bin")
            val file = root.child("outside.map")
            val valid = map(5, all)
            file.writeBytes(valid, false)
            MapSave.validateImport(file)
            file.writeBytes(valid.copyOf(70), false)
            assertFails { MapSave.validateImport(file) }
            // Формат 2 — до мира физики-библиотеки: позиций клеток в нём нет.
            file.writeBytes(map(2, all), false)
            assertFailsWith<IllegalArgumentException> { MapSave.validateImport(file) }
            // Формат 3 — мягкий террейн из плиток: в мире с неподвижным террейном его не продолжить.
            file.writeBytes(map(3, all), false)
            assertFailsWith<IllegalArgumentException> { MapSave.validateImport(file) }
            // Формат 4 — мир физики от 0 до W, без начала координат в снимке моста.
            file.writeBytes(map(4, all), false)
            assertFailsWith<IllegalArgumentException> { MapSave.validateImport(file) }
            file.writeBytes(map(5, all - "physics.bin"), false)
            assertFailsWith<IllegalArgumentException> { MapSave.validateImport(file) }
        } finally {
            root.deleteDirectory()
        }
    }

    @Test fun libraryIgnoresZipFiles() {
        val root = FileHandle(Files.createTempDirectory("map-files").toFile())
        val previous = GameFiles.dataRoot
        try {
            GameFiles.dataRoot = root
            val maps = GameFiles.mapsDir()
            maps.child("1.zip").writeBytes(archive().drop(16).toByteArray(), false)
            maps.child("2.zip").writeBytes(archive().drop(16).toByteArray(), false)
            maps.child("2.map").writeBytes(archive(), false)
            maps.child("3.map.tmp").writeBytes(byteArrayOf(), false)
            assertEquals(setOf("2"), MapSave.listMaps().map { it.slot }.toSet())
            assertEquals("1.map", MapSave.mapFile("1").name())
            assertEquals("2.map", MapSave.mapFile("2").name())
            assertEquals("4.map", MapSave.mapFile("4").name())
            assertFailsWith<IllegalArgumentException> { MapSave.validateImport(maps.child("2.map")) }
        } finally {
            GameFiles.dataRoot = previous
            root.deleteDirectory()
        }
    }
}
