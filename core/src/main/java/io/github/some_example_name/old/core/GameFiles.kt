package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.game.MyGame
import java.io.File

/**
 * Все каталоги игры в одном месте.
 *
 * Зачем собирать их сюда: при повторе бага (см. BugReplay) игра должна работать с
 * файлами ИГРОКА — его геномами, сохранёнными мирами, настройками среды, — а не с
 * файлами разработчика, и ничего у разработчика не перезаписать. Для этого данные
 * игрока ([genomesDir], [mapsDir], [settingsDir]) переключаются на песочницу одним
 * присваиванием [dataRoot], и всё, что читает и пишет игру, обязано брать каталоги
 * отсюда, а не собирать пути само.
 *
 * Служебные каталоги ([sessionDir], [bugsDir], [replayInboxDir]) не переключаются:
 * они всегда лежат рядом с игрой.
 */
object GameFiles {

    /**
     * Данные игрока при повторе бага: распакованная песочница. null — обычная игра,
     * каталоги там же, где были всегда.
     */
    var dataRoot: FileHandle? = null

    /**
     * Каталог самой игры — туда, где игрок её «видит».
     *
     * На десктопе это папка с jar собранной игры. При запуске из исходников jar нет
     * (классы лежат в build/), и тогда это корень проекта: `gradlew :lwjgl3:run`
     * запускает игру из assets/, а всё, что окажется в assets/, generateAssetList
     * упакует в сборку, поэтому из assets/ поднимаемся на уровень выше.
     *
     * На Android — личное хранилище приложения (Gdx.files.local).
     */
    val gameDir: FileHandle by lazy {
        if (Gdx.app.type != Application.ApplicationType.Desktop) return@lazy Gdx.files.local("")

        // Собранная игра — один jar, в котором и игра, и лаунчер. При запуске из исходников
        // core тоже бывает jar-ом (core/build/libs), но лаунчер лежит отдельно — это не она.
        val codeSource = codeSourceOf(MyGame::class.java)
        val launcher = runCatching {
            codeSourceOf(Class.forName("io.github.some_example_name.lwjgl3.Lwjgl3Launcher"))
        }.getOrNull()
        if (codeSource != null && codeSource.isFile && codeSource == launcher) {
            return@lazy Gdx.files.absolute(codeSource.parentFile.absolutePath)
        }

        val working = File("").absoluteFile
        val root = if (working.name.equals("assets", ignoreCase = true)) working.parentFile else working
        Gdx.files.absolute(root.absolutePath)
    }

    /**
     * Файлы геномов рядом с игрой, на Android — в личном хранилище приложения.
     * Рабочий каталог проводника при открытии .map не должен менять библиотеку геномов.
     */
    fun genomesDir(): FileHandle =
        dataRoot?.let { GameFilePaths.child(it, GENOMES) } ?: localDataRoot().child(GENOMES)

    fun genomeFile(name: String): FileHandle = GameFilePaths.child(genomesDir(), "$name.genome")

    private fun localDataRoot(): FileHandle {
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            // При открытии .map рабочий каталог задаёт проводник, а не игра.
            // Геномы должны остаться рядом с игрой, как карты и настройки.
            return gameDir
        }
        return Gdx.files.local("")
    }

    /**
     * Сохранённые миры. На десктопе — не в рабочей папке, а рядом с игрой (см. [gameDir]
     * про assets/), как было и раньше.
     */
    fun mapsDir(): FileHandle {
        val dir = dataRoot?.let { GameFilePaths.child(it, MAPS) } ?: localDataRoot().child(MAPS)
        return dir.apply { mkdirs() }
    }

    /**
     * Уровни игрока. Там же, где миры: рядом с игрой на ПК, в личном хранилище
     * приложения на Android/iOS, в локальной ФС браузера на вебе. Список уровней
     * в кампании и редактор читают каталог отсюда, поэтому сохранение уровня в
     * системное окно «Сохранить как» не годится — файл окажется не в этой папке.
     */
    fun levelsDir(): FileHandle {
        val root = dataRoot ?: if (Gdx.app.type == Application.ApplicationType.Desktop) gameDir else Gdx.files.local("")
        return root.child(LEVELS).apply { mkdirs() }
    }

    /**
     * Настройки среды симуляции (GlobalSubstrateSettings). На десктопе — рядом с
     * классами игры, как было исторически.
     */
    fun settingsDir(): FileHandle {
        dataRoot?.let { return GameFilePaths.child(it, SETTINGS).apply { mkdirs() } }
        val dir = if (Gdx.app.type == Application.ApplicationType.Desktop) {
            val classes = File(MyGame::class.java.protectionDomain.codeSource.location.toURI())
            Gdx.files.absolute(classes.parentFile.absolutePath).child(SETTINGS)
        } else {
            Gdx.files.local(SETTINGS)
        }
        return dir.apply { mkdirs() }
    }

    /**
     * Сессия копии игры номер [slot]: журнал команд, лог консоли, снимки файлов. Очищается при
     * каждом запуске. У каждой одновременно запущенной копии свой номер (BugSession), 0 — "session".
     */
    fun sessionDir(slot: Int): FileHandle = gameDir.child(if (slot == 0) SESSION else "$SESSION-$slot")

    /** Файл-замок сессии [slot]: его держит запущенная копия игры, пока жива (BugSession). */
    fun sessionLockFile(slot: Int): File = gameDir.child(if (slot == 0) "$SESSION.lock" else "$SESSION-$slot.lock").file()

    /** Готовые отчёты об ошибках (*.bug). */
    fun bugsDir(): FileHandle = gameDir.child(BUGS).apply { mkdirs() }

    /**
     * Папка, куда разработчик кладёт присланные .bug: если в ней что-то есть, игра при
     * запуске проигрывает первый по алфавиту. Только на десктопе.
     */
    fun replayInboxDir(): FileHandle? =
        if (Gdx.app.type == Application.ApplicationType.Desktop) gameDir.child(REPLAY_INBOX) else null

    private fun codeSourceOf(type: Class<*>): File? = runCatching {
        File(type.protectionDomain.codeSource.location.toURI()).canonicalFile
    }.getOrNull()

    const val GENOMES = "genomes"
    const val MAPS = "maps"
    const val LEVELS = "levels"
    const val SETTINGS = "settings"
    const val SESSION = "session"
    const val BUGS = "bugs"
    const val REPLAY_INBOX = "bug-replay"
}
