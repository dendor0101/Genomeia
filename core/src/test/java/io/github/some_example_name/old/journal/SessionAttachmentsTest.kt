package io.github.some_example_name.old.journal

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.GameFilePaths
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.core.ReplayFileException
import io.github.some_example_name.old.systems.maps.MapSave
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SessionAttachmentsTest {
    private val temp = FileHandle(Files.createTempDirectory("replay-attachments").toFile())
    private val attachments = temp.child("attachments")
    private val oldRoot = GameFiles.dataRoot

    init {
        GameFiles.dataRoot = temp.child("data")
        SessionAttachments.startSession(attachments)
    }

    @AfterTest fun cleanUp() {
        SessionAttachments.stop()
        GameFiles.dataRoot = oldRoot
        temp.deleteDirectory()
    }

    @Test fun portableFileNames() {
        assertEquals("Мир №1 (2).map", GameFilePaths.fileName("Мир №1 (2).map"))
        for (invalid in listOf("", ".", "..", "folder/file", "folder\\file", "a:b", "AUX.genome", "end.")) {
            assertFailsWith<ReplayFileException>(invalid) { GameFilePaths.fileName(invalid) }
        }
    }

    @Test fun restoreRequiresAnImportedFileAndKeepsPreviousBytesOnFailure() {
        val original = byteArrayOf(1, 2, 3)
        val name = "Лист.genome"
        val path = SessionAttachments.attachImported(name) { it.writeBytes(original, false) }
        SessionAttachments.useForReplay(attachments)
        SessionAttachments.restoreGenome(path, name)
        val target = GameFiles.genomeFile("Лист")
        assertContentEquals(original, target.readBytes())
        for (invalid in listOf("missing", "imported/missing.genome", "maps/Лист.genome", "imported/nested/file")) {
            assertFailsWith<ReplayFileException> { SessionAttachments.restoreGenome(invalid, name) }
        }
        assertFailsWith<ReplayFileException> { SessionAttachments.restoreGenome(path, "nested/Лист.genome") }
        assertFailsWith<ReplayFileException> { SessionAttachments.restoreMap(path, "nested/world") }
        assertContentEquals(original, target.readBytes())
    }

    @Test fun failedSnapshotCanBeRetried() {
        val original = byteArrayOf(7, 8)
        MapSave.mapFile("старый").writeBytes(original, false)
        val maps = attachments.child("maps")
        maps.writeBytes(byteArrayOf(), false)
        assertFailsWith<Exception> { SessionAttachments.attachMapBeforeLoad("старый") }
        maps.delete()
        SessionAttachments.attachMapBeforeLoad("старый")
        assertContentEquals(original, maps.child("старый.map").readBytes())
        assertFailsWith<IllegalStateException> {
            SessionAttachments.attachImported("test.genome") {
                it.writeBytes(original, false)
                error("ошибка чтения")
            }
        }
        assertFalse(attachments.child("imported").exists())
    }
}
