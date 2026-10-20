package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import io.github.some_example_name.old.systems.genomics.genome.isGenomeFile

/**
 * Запекание генома: вырастить тело и снять с него размеры, раскладку арен и снимок для
 * превью — то же, что
 * делает кнопка «Сохранить» в редакторе. Живёт в редакторе (GenomeBaker), а сюда приходит
 * параметром: импорт не тянет за собой контейнер редактора и проверяется тестами без Gdx.
 *
 * [name] — имя, под которым геном ляжет в файл: редактор пишет его и внутрь генома.
 */
fun interface Baker {
    fun bake(genome: Genome, name: String): Genome
}

/**
 * Один внешний файл генома → файл в папке геномов.
 *
 * Формат определяется по содержимому, а не по расширению: на Android выбранный файл приходит
 * временной копией, и имя у неё может быть любым.
 *  - .genome кладётся как есть, байт в байт, только после проверки, что он читается. Кроме
 *    недопечённого — без раскладки или без снимка для превью: такой допекается ([withBaking]);
 *  - старый JSON переводится в геном ([LegacyGenomeJson]) и запекается ([Baker]) — после
 *    импорта он ничем не отличается от сохранённого в редакторе.
 *
 * Чужой геном не перезаписывается никогда: если имя занято другим содержимым, файл получает
 * имя «X (2)», «X (3)»… Тот же самый геном второй раз не дублируется — повторный импорт
 * (или повторный перенос старых JSON, если в прошлый раз их не удалось убрать) находит уже
 * лежащий файл с теми же байтами и возвращает его.
 */
object GenomeImport {

    /** Расширение файла генома, с точкой. */
    const val EXTENSION = ".genome"

    private const val JSON_EXTENSION = ".json"

    /** Имя по умолчанию, если от имени выбранного файла ничего не осталось. */
    private const val DEFAULT_NAME = "imported"

    /** Предел перебора «X (n)»: дальше — явно что-то не так с папкой, а не совпадение имён. */
    private const val MAX_SUFFIX = 1000

    /**
     * Положить [source] в [targetDir]. Возвращает файл в [targetDir] — новый или уже лежавший
     * там с тем же содержимым.
     *
     * @throws IllegalArgumentException файл не геном (или испорчен) — текст годится для лога.
     */
    fun importFile(source: FileHandle, targetDir: FileHandle, baker: Baker): FileHandle {
        val bytes = source.readBytes()
        val baseName = fileBaseName(source.name())

        if (isGenomeFile(bytes)) {
            // Проверка до записи: битый файл в папке ломал бы список геномов при каждом запуске.
            val genome = try {
                decodeGenome(bytes)
            } catch (e: Exception) {
                throw IllegalArgumentException("файл .genome не читается: ${e.message}", e)
            }
            val baked = withBaking(genome, baker) ?: return place(targetDir, baseName) { bytes }
            return place(targetDir, baseName) { encodeGenome(baked) }
        }

        require(LegacyGenomeJson.looksLikeJson(bytes)) { "ни .genome, ни JSON" }
        val genome = LegacyGenomeJson.parse(String(bytes, Charsets.UTF_8), baseName)
        // Имя внутри генома запекание не трогает: печётся один раз, а имя подставляется
        // под каждый перебираемый вариант.
        val baked = baker.bake(genome, baseName)
        return place(targetDir, baseName) { name -> encodeGenome(baked.copy(name = name)) }
    }

    /**
     * Геном .genome, в котором не хватает запечённого, — допечённым. null — печь нечего.
     *
     * Не хватать может двух вещей:
     *  - размеров тела и раскладки (cellsAmount == 0) — геном сохранён до появления
     *    запекания. Без них организм получает арену нулевой ёмкости и падает на первой же
     *    клетке (UserCommandManager, «арена клеток организма исчерпана (ёмкость 0)»): так
     *    падала игра, когда в мир ставили старый геном;
     *  - снимка для превью (preview == null) — геном сохранён до появления превью;
     *  - границы тела в раскладке (BakedLayout.interiorLinks) — геном сохранён до неё.
     *    Без неё граничные клетки определяются прежним порогом в шесть связей. Граница,
     *    запечённая прежним признаком (BakedLayout.interiorVersion меньше нынешнего), тоже
     *    перепекается: иначе у своего генома игрока остались бы клетки на краю тела, которые
     *    считаются внутренними и ни с чем не сталкиваются (A-11, отчёт 20.10 19:49).
     *  - пика нейросвязей (Genome.neuralLinksPeak) — без него у организма нет арены
     *    нейросвязей, и они идут в общий пул.
     *
     * Сам геном не трогается — берётся только недостающее, остальное как в файле: имя,
     * версия, порядок стадий. В том числе уже лежащая раскладка: от неё зависит, как
     * организм ляжет в арены, и перепекать её ради картинки незачем.
     */
    fun withBaking(genome: Genome, baker: Baker): Genome? {
        if (genome.stageInstruction.isEmpty()) return null
        val needsLayout = genome.cellsAmount <= 0
        val layout = genome.sortedGraph
        // Граница тела (BakedLayout.interiorLinks) появилась позже раскладки, а её признак
        // с тех пор менялся (BakedLayout.interiorVersion).
        val needsInterior = layout != null && layout.cellsInLayout > 0 && !layout.hasCurrentInterior
        // Пик нейросвязей (ёмкость их арены) появился ещё позже.
        val needsNeuralPeak = genome.neuralLinksPeak == null
        if (!needsLayout && genome.preview != null && !needsInterior && !needsNeuralPeak) return null

        val baked = baker.bake(genome, genome.name)
        // Раскладка остаётся своей — граница переносится в неё по cellGenomeId, порядок
        // слотов не меняется. Уже лежащий снимок тоже не трогается.
        if (!needsLayout) return genome.copy(
            preview = genome.preview ?: baked.preview,
            sortedGraph = layout?.withInteriorFrom(baked.sortedGraph),
            neuralLinksPeak = genome.neuralLinksPeak ?: baked.neuralLinksPeak
        )
        return genome.copy(
            cellsAmount = baked.cellsAmount,
            linksAmount = baked.linksAmount,
            neuralLinksAmount = baked.neuralLinksAmount,
            neuralLinksPeak = baked.neuralLinksPeak,
            sortedGraph = baked.sortedGraph,
            preview = baked.preview
        )
    }

    /**
     * Имя генома из имени выбранного файла: без каталогов, расширения и символов, которые
     * файловые системы не пускают в имя.
     *
     * ОТКУДА БЕРЁТСЯ ТИЛЬДА
     * ---------------------
     * На современном Android выбор файла всегда возвращает content:// URI, а не путь.
     * AndroidFileChooser.fileHandleFromUri в таком случае копирует поток во временный файл
     * в кеше приложения и даёт ему имя `"~" + nameFromUri(uri)` — тильда это МАРКЕР
     * временного файла библиотеки, а не часть имени. Раньше файл сохранялся под именем
     * file.name() как есть, и геном ложился в genomes/~foo.genome, а его именем (имя генома —
     * это имя файла без расширения) становилось "~foo". Тем же путём шёл импорт старых JSON
     * в user_genomes/, так что тильда бывает и у них.
     *
     * ПОЧЕМУ ЕЩЁ И ПУТЬ
     * -----------------
     * Если запрос DISPLAY_NAME у провайдера не отработал, nameFromUri падает в
     * uri.getLastPathSegment(), а это может быть "primary:Download/foo.genome" — с двоеточием
     * и слэшем внутри. Такое имя ломает GameFiles.genomesDir().child(name): слэш превращается
     * в подкаталог, и геном перестаёт находиться в списке.
     *
     * Побочный эффект: геном, чьё имя действительно начинается с тильды, потеряет её при
     * импорте. Отличить его от временного файла библиотеки нельзя — она добавляет тильду
     * ровно к тому же месту.
     */
    fun fileBaseName(rawName: String): String {
        var name = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
            .trim()
            .removePrefix("~")
        for (extension in listOf(EXTENSION, JSON_EXTENSION)) {
            if (name.endsWith(extension, ignoreCase = true)) name = name.dropLast(extension.length)
        }
        name = name.filter { it >= ' ' && it !in FORBIDDEN_IN_FILE_NAME }.trim().trimEnd('.')
        return name.ifEmpty { DEFAULT_NAME }
    }

    /**
     * Свободное место под геном: «X», «X (2)», «X (3)»… [content] строит байты под каждое
     * пробуемое имя (у JSON имя лежит и внутри генома). Занятое теми же байтами — это уже
     * импортированный этот же геном: он и возвращается, без второй копии.
     */
    private fun place(targetDir: FileHandle, baseName: String, content: (String) -> ByteArray): FileHandle {
        targetDir.mkdirs()
        for (n in 1..MAX_SUFFIX) {
            val name = if (n == 1) baseName else "$baseName ($n)"
            val target = targetDir.child(name + EXTENSION)
            val bytes = content(name)
            if (!target.exists()) {
                target.writeBytes(bytes, false)
                return target
            }
            if (target.readBytes().contentEquals(bytes)) return target
        }
        throw IllegalArgumentException("в ${targetDir.path()} занято $MAX_SUFFIX имён «$baseName (n)»")
    }

    /** Запрещены в именах файлов на Windows; на остальных системах — подмножество этих. */
    private const val FORBIDDEN_IN_FILE_NAME = "\\/:*?\"<>|"
}
