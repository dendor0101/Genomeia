package io.github.some_example_name.old.features.genomes

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionAttachments
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.genomics.library.GenomeImport
import kotlinx.serialization.Serializable

/** Вкладка экрана «Геномы». */
enum class GenomesTab {
    /** Свои геномы — из папки игрока. */
    MINE,

    /** Встроенные — из ассетов, только для чтения. */
    BUNDLED
}

/**
 * Что игрок хочет сделать на экране «Геномы».
 *
 * Открыть карточку генома, окно переименования или подтверждения — не команда: это только
 * окна, игру они не меняют. Команда — то, что в них выбрали.
 */
@Serializable
sealed interface GenomesIntent : GameCommand {

    @Serializable
    data class SelectTab(val tab: GenomesTab) : GenomesIntent {
        override val name get() = "SelectTab"
        override val detail get() = tab.name
    }

    /**
     * Открыть геном в редакторе. null — новый. Встроенный («bundled/…») открывается так же:
     * редактор сохраняет его в папку игрока, то есть копией, а сам встроенный не меняется.
     */
    @Serializable
    data class OpenEditor(val genomeName: String?) : GenomesIntent {
        override val name get() = "OpenEditor"
        override val detail get() = genomeName ?: "new"
    }

    /** Открыть системный выбор файлов генома. Что выбрали — см. [GenomeImported]. */
    @Serializable
    data object ImportGenome : GenomesIntent {
        override val name get() = "ImportGenome"
    }

    /**
     * Файл генома импортирован и лежит в папке геномов под именем [fileName]. Как и
     * MenuIntent.GenomeImported: какой файл выбрал игрок, решает система, поэтому готовый
     * .genome едет в приложениях сессии ([attachment]), а при повторе кладётся оттуда.
     */
    @Serializable
    data class GenomeImported(val fileName: String, val attachment: String) : GenomesIntent {
        override val name get() = "GenomeImported"
        override val detail get() = fileName
    }

    /** Удалить свой геном. Встроенные не удаляются. */
    @Serializable
    data class Delete(val genomeName: String) : GenomesIntent {
        override val name get() = "Delete"
        override val detail get() = genomeName
    }

    /** Переименовать свой геном. Новое имя проверено до команды ([GenomesViewModel.renameProblem]). */
    @Serializable
    data class Rename(val genomeName: String, val newName: String) : GenomesIntent {
        override val name get() = "Rename"
        override val detail get() = "$genomeName -> $newName"
    }

    /** Отдать файл генома игроку: на телефоне — окно «поделиться», на ПК — папка с файлом. */
    @Serializable
    data class Share(val genomeName: String) : GenomesIntent {
        override val name get() = "Share"
        override val detail get() = genomeName
    }
}

/** Итог импорта: имена получившихся геномов и имена файлов, которые не удалось импортировать. */
class GenomeImportResult(val imported: List<String>, val failed: List<String>)

/** Почему новое имя генома не годится. */
enum class RenameProblem { EMPTY, INVALID, TAKEN }

class GenomesViewModel {

    /** Через get: контейнер поднимает OpenGL, а тесты переименования и удаления идут без него. */
    private val navigation get() = DIGameGlobalContainer.navigationCommandsManager

    /** Вкладка живёт здесь, а не в экране: рекомпозиция пересобирает вёрстку целиком. */
    var currentTab: GenomesTab = GenomesTab.MINE
        private set

    /** Список геномов поменялся (удалили, переименовали, импортировали) — перестроить экран. */
    var onGenomesChanged: (() -> Unit)? = null

    /** Импорт закончился (что-то выбрали или отменили) — экран показывает итог. */
    var onImportFinished: ((GenomeImportResult) -> Unit)? = null

    /** Геномы текущей вкладки. Запрос, а не команда: игру он не меняет. */
    fun genomeNames(): List<String> = when (currentTab) {
        GenomesTab.MINE -> GenomeCatalog.playerNames()
        GenomesTab.BUNDLED -> GenomeCatalog.bundledNames()
    }

    fun displayName(name: String): String = GenomeCatalog.displayName(name)

    /**
     * Годится ли [newName] новым именем генома [genomeName]. null — годится. Проверка до
     * команды: в журнал идёт только то, что выполнится.
     *
     * Имя генома — это имя файла (GenomeCatalog), поэтому в нём нельзя того, что не пускают
     * файловые системы, — та же чистка, что у импорта (GenomeImport.fileBaseName).
     */
    fun renameProblem(genomeName: String, newName: String): RenameProblem? {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return RenameProblem.EMPTY
        if (GenomeImport.fileBaseName(trimmed + GenomeImport.EXTENSION) != trimmed) return RenameProblem.INVALID
        if (trimmed == genomeName) return null
        // Регистр не различает файловая система Windows и Android-хранилище: «glist» и
        // «Glist» там — один файл.
        if (GenomeCatalog.playerNames().any { it.equals(trimmed, ignoreCase = true) && it != genomeName }) {
            return RenameProblem.TAKEN
        }
        return null
    }

    fun handle(intent: GenomesIntent) = SessionJournal.execute(CommandSource.GENOMES, intent) { apply(it) }

    private fun apply(intent: GenomesIntent) {
        when (intent) {
            is GenomesIntent.SelectTab -> currentTab = intent.tab

            is GenomesIntent.OpenEditor -> navigation.performCommand(GoGenomeEditor(intent.genomeName))

            is GenomesIntent.ImportGenome -> {
                // При повторе окна выбора нет: файлы придут следующими командами из записи.
                if (BugReplay.isActive) return
                DIGameGlobalContainer.fileProvider.pickGenomeFiles { files ->
                    // Колбэк системного окна приходит не в поток игры.
                    Gdx.app.postRunnable { importGenomeFiles(files) }
                }
            }

            is GenomesIntent.GenomeImported -> {
                // В игре файл уже лежит в папке геномов (importGenomeFiles). При повторе его
                // там нет — кладём из приложений сессии.
                if (BugReplay.isActive) {
                    SessionAttachments.restore(intent.attachment, GameFiles.genomesDir().child(intent.fileName))
                }
                onGenomesChanged?.invoke()
            }

            is GenomesIntent.Delete -> {
                check(!GenomeCatalog.isBundled(intent.genomeName)) { "встроенный геном не удаляется" }
                genomeFile(intent.genomeName).delete()
                onGenomesChanged?.invoke()
            }

            is GenomesIntent.Rename -> {
                rename(intent.genomeName, intent.newName.trim())
                onGenomesChanged?.invoke()
            }

            // Окно «поделиться» при повторе бага ни к чему: файл и так в песочнице.
            is GenomesIntent.Share -> if (!BugReplay.isActive) {
                DIGameGlobalContainer.fileProvider.shareFile(
                    genomeFile(intent.genomeName).file(),
                    displayName(intent.genomeName)
                )
            }
        }
    }

    /**
     * Переименование — новый файл и удаление старого. Имя лежит и внутри генома (его пишет
     * редактор при сохранении), поэтому файл не просто переименовывается, а перезаписывается
     * с новым именем: иначе редактор предложил бы сохранить его под старым.
     *
     * Сначала пишется новый, потом удаляется старый: прерванное переименование оставит
     * копию, а не потеряет геном. Тот же файл с другим регистром имени («glist» → «Glist») —
     * на Windows и Android это один файл, и удаление после записи стёрло бы результат.
     */
    private fun rename(genomeName: String, newName: String) {
        if (newName == genomeName) return
        val from = genomeFile(genomeName)
        val genome = decodeGenome(from.readBytes())
        val bytes = encodeGenome(genome.copy(name = newName))
        if (newName.equals(genomeName, ignoreCase = true)) {
            from.delete()
            genomeFile(newName).writeBytes(bytes, false)
        } else {
            genomeFile(newName).writeBytes(bytes, false)
            from.delete()
        }
    }

    /**
     * Внешние файлы геномов — выбранные в системном окне или перетащенные в окно игры (ПК) —
     * в папку геномов. Старый JSON по дороге переводится в .genome и запекается (GenomeImport).
     *
     * Каждый удачный файл — своя команда [GenomesIntent.GenomeImported] со снимком получившегося
     * .genome. Неудачный в журнал не идёт: папку геномов он не изменил.
     *
     * Только в потоке игры: запекание выращивает геном в мире редактора.
     */
    fun importGenomeFiles(files: List<FileHandle>) {
        if (BugReplay.isActive) return
        val imported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (file in files) {
            val genome = try {
                GenomeImport.importFile(file, GameFiles.genomesDir(), DIGenomeEditorContainer.genomeBaker)
            } catch (e: Exception) {
                Gdx.app.error(TAG, "не удалось импортировать ${file.path()}", e)
                failed += file.name().removePrefix("~")
                continue
            }
            handle(GenomesIntent.GenomeImported(genome.name(), SessionAttachments.attachImported(genome)))
            imported += genome.nameWithoutExtension()
        }
        onImportFinished?.invoke(GenomeImportResult(imported, failed))
    }

    private fun genomeFile(name: String): FileHandle =
        GameFiles.genomesDir().child(name + GenomeImport.EXTENSION)

    private companion object {
        const val TAG = "GenomeImport"
    }
}
