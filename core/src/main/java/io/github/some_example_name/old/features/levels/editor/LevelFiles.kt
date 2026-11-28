package io.github.some_example_name.old.features.levels.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.MissingResourceException

/**
 * Файлы уровней: имя файла — имя уровня.
 *
 * Два источника, как у геномов (GenomeCatalog):
 *  - встроенные — в ассетах игры, `assets/levels-builtin/`, только для чтения. Ассеты едут в
 *    сборку на всех платформах; раньше уровни лежали в `levels/` в корне репозитория и
 *    находились только при запуске из исходников на ПК;
 *  - свои — в папке данных игрока (GameFiles.levelsDir), их пишет редактор уровней.
 *
 * Ссылка на уровень — строка: своё имя или [BUILTIN_PREFIX] + имя. В имени файла нет '/',
 * поэтому свой уровень с именем встроенного его не перекрывает: редактор сохраняет копию
 * в свою папку, встроенный остаётся как был.
 *
 * Формат — тот же [LevelInfo], что едет в команде GoSimulation; карта в нём — рецепт
 * [io.github.some_example_name.old.features.worldeditor.WorldSpec], а не готовое поле.
 */
object LevelFiles {
    private const val EXTENSION = "json"

    /** Приставка ссылки на встроенный уровень. */
    const val BUILTIN_PREFIX = "builtin/"

    /**
     * Папка встроенных уровней в ассетах. Не «levels»: на ПК внутренний файл ищется сначала в
     * рабочем каталоге, а у собранной игры там лежит папка уровней ИГРОКА с этим именем.
     */
    private const val BUILTIN_DIR = "levels-builtin"

    private val json = Json {
        prettyPrint = true
        // Новое поле в уровне не должно делать старые файлы неоткрываемыми, а убранное
        // (isBuiltin: встроенный теперь тот, что лежит в ассетах) — мешать их читать.
        ignoreUnknownKeys = true
    }

    /** Свои уровни игрока — имена без расширения, по алфавиту. */
    fun names(): List<String> = levelsDir().list()
        .filter { !it.isDirectory && it.extension().equals(EXTENSION, ignoreCase = true) }
        .map { it.nameWithoutExtension() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Встроенные уровни — ссылки с [BUILTIN_PREFIX]. Список из сборки, за запуск не меняется. */
    val builtinNames: List<String> by lazy { readBuiltinNames() }

    fun isBuiltin(ref: String): Boolean = ref.startsWith(BUILTIN_PREFIX)

    /** Имя файла без приставки. */
    fun displayName(ref: String): String = ref.removePrefix(BUILTIN_PREFIX)

    /**
     * Название для игрока. У встроенного — из бандла по [titleKey] (уровни переведены на
     * все языки игры), у своего — как его назвал игрок. Ключа нет — имя файла.
     *
     * Перевод по имени файла, а не по LevelInfo.nameKey: nameKey редактор уровней показывает
     * в поле «Имя» и по нему же называет файл при сохранении копии.
     */
    fun title(ref: String): String {
        if (!isBuiltin(ref)) return displayName(ref)
        return try {
            DIGameGlobalContainer.bundle.get(titleKey(ref))
        } catch (e: MissingResourceException) {
            displayName(ref)
        }
    }

    /** Ключ бандла с названием встроенного уровня: «Formulas Memory» → level.title.formulasmemory. */
    fun titleKey(ref: String): String =
        TITLE_PREFIX + displayName(ref).lowercase().filter { it.isLetterOrDigit() }

    private const val TITLE_PREFIX = "level.title."

    /** Уровень по ссылке — встроенный или свой. */
    fun read(ref: String): LevelInfo = decode(source(ref).readString("UTF-8"))

    /**
     * Мазки карты в файле — одной строкой [STROKES]: «x,y,кисть,стирание,круг» через «;»,
     * очистка — «C». В журнале (protobuf) WorldEdit пишется как есть, а в JSON каждый мазок
     * был объектом с полным именем класса (~70 байт) — файлы встроенных уровней весили
     * 10 МБ, и формат файла зависел от имени пакета. Старый вид (world.edits) тоже читается.
     */
    fun decode(text: String): LevelInfo {
        val tree = json.parseToJsonElement(text).jsonObject
        val world = tree[WORLD]?.jsonObject
        val strokes = (world?.get(STROKES) as? JsonPrimitive)?.contentOrNull
            ?: return json.decodeFromJsonElement(LevelInfo.serializer(), tree)
        val level = json.decodeFromJsonElement(
            LevelInfo.serializer(),
            JsonObject(tree + (WORLD to JsonObject(world - STROKES)))
        )
        return level.copy(world = level.world.copy(edits = parseStrokes(strokes)))
    }

    fun encode(level: LevelInfo): String {
        val tree = json.encodeToJsonElement(LevelInfo.serializer(), level).jsonObject
        val world = tree.getValue(WORLD).jsonObject
        val compactWorld = JsonObject(world - EDITS + (STROKES to JsonPrimitive(formatStrokes(level.world.edits))))
        return json.encodeToString(JsonObject.serializer(), JsonObject(tree + (WORLD to compactWorld)))
    }

    private fun formatStrokes(edits: List<WorldEdit>): String = edits.joinToString(";") { edit ->
        when (edit) {
            is WorldEdit.Stroke -> "${edit.gridX},${edit.gridY},${edit.brushSize},${if (edit.erase) 1 else 0},${if (edit.circle) 1 else 0}"
            WorldEdit.Clear -> CLEAR
        }
    }

    private fun parseStrokes(text: String): List<WorldEdit> {
        if (text.isBlank()) return emptyList()
        return text.split(';').map { part ->
            if (part == CLEAR) WorldEdit.Clear
            else {
                val v = part.split(',')
                require(v.size == 5) { "мазок карты '$part': нужно пять чисел" }
                WorldEdit.Stroke(v[0].toInt(), v[1].toInt(), v[2].toInt(), erase = v[3] == "1", circle = v[4] == "1")
            }
        }
    }

    private const val WORLD = "world"
    private const val EDITS = "edits"
    private const val STROKES = "strokes"
    private const val CLEAR = "C"

    /** Записать в свою папку игрока: встроенные ассеты только читаются. */
    fun write(level: LevelInfo): FileHandle {
        val file = levelsDir().child("${fileName(level.nameKey)}.$EXTENSION")
        file.writeString(encode(level), false, "UTF-8")
        return file
    }

    private fun source(ref: String): FileHandle =
        if (isBuiltin(ref)) Gdx.files.internal("$BUILTIN_DIR/${displayName(ref)}.$EXTENSION")
        else levelsDir().child("${fileName(ref)}.$EXTENSION")

    private fun levelsDir(): FileHandle = GameFiles.levelsDir()

    /**
     * Имена встроенных — по assets.txt, его пишет сборка (generateAssetList): папку внутри
     * jar internal().list() не перечисляет. list() — запасной путь, если assets.txt нет.
     */
    private fun readBuiltinNames(): List<String> {
        val prefix = "$BUILTIN_DIR/"
        val assetsList = Gdx.files.internal("assets.txt")
        val names = if (assetsList.exists()) {
            assetsList.readString("UTF-8").lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(prefix) && it.endsWith(".$EXTENSION") }
                .map { it.substring(prefix.length).removeSuffix(".$EXTENSION") }
                .toList()
        } else {
            Gdx.files.internal(BUILTIN_DIR).list()
                .filter { !it.isDirectory && it.extension().equals(EXTENSION, ignoreCase = true) }
                .map { it.nameWithoutExtension() }
        }
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER).map { BUILTIN_PREFIX + it }
    }

    /**
     * Имя уровня → имя файла. Слэши и служебные символы недопустимы в имени файла на
     * всех платформах; сам уровень при этом продолжает называться так, как его назвали.
     */
    private fun fileName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "level" }
}
