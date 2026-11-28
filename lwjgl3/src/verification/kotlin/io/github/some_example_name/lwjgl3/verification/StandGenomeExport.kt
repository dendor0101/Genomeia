package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeImport
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * ТЕЛА НАСТОЯЩИХ ГЕНОМОВ ДЛЯ СТЕНДОВ ФИЗИКИ.
 *
 * Стенды (:physics-lab) не видят :core и геном прочитать не могут, а выращивать тело умеет
 * только игра. Поэтому эта программа — на настоящей игре: каждый файл проходит тот же импорт,
 * что кнопка «Импорт» в меню (GenomeImport: .genome как есть с допеканием недостающего, старый
 * JSON — перевод и запекание в мире редактора), и из запечённого генома выгружается ровно то,
 * из чего мост игры заводит тело в физике (LayoutBodies.bodyDefinitionOf): поза покоя и радиусы
 * по слотам раскладки, связи (связи поперёк граней — отдельно: физика их не получает, но кость
 * они скрепляют), треугольники раскладки — и стадии роста: кто от кого родился и когда стал
 * костью (growthStages, как в growthCheck).
 *
 * Формат файла тела — построчный текст, единицы игры:
 *   name / source              имя генома и откуда он взят
 *   P слот x y радиус тип кость
 *   L a b                      связь, которую получает физика
 *   X a b                      связь поперёк граней
 *   T a b c                    треугольник раскладки
 *   G стадия слот родитель     рождение клетки делением
 *   O стадия слот              клетка стала костью
 *
 * Одинаковые тела под разными именами (один геном в десяти рабочих копиях) выгружаются один раз.
 * То, что не геном или не выращивается, пропускается с причиной в skipped.tsv.
 *
 * Запуск: gradlew --no-daemon :lwjgl3:standGenomeExport -Pto=каталог -Pfrom=путь[;путь…]
 *   путь — файл, каталог (берутся *.genome и *.json) или @список (по пути на строку).
 * Уже выгруженные источники (sources.tsv в каталоге) второй раз не выращиваются.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val to = File(args.getOrNull(0) ?: "physics-lab/src/main/resources/stand-genomes")
    val from = args.getOrNull(1).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
    require(from.isNotEmpty()) { "не задано, откуда брать геномы: -Pfrom=путь[;путь…]" }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setPreferencesConfig(java.nio.file.Files.createTempDirectory("genomeia-verification-").toString(), com.badlogic.gdx.Files.FileType.Absolute)
        setTitle("Genomeia — тела геномов для стендов физики")
        setWindowedMode(640, 480)
        useVsync(false)
        // Как в Lwjgl3Launcher: контейнеры игры поднимают рендер, а ему нужен GL30.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(StandGenomeExport(MyGame(DesktopFileProvider()), sources(from), to), config)
    exitProcess(exportExit)
}

private var exportExit = 1

/** Начала сообщений GenomeImport и LegacyGenomeJson о том, что файл — не геном: до роста дело не дошло. */
private val PARSE_ERRORS = listOf("не JSON", "нет genomeStageInstruction", "ни .genome", "файл .genome не читается", "в геноме нет ни одной стадии")

private fun sources(from: List<String>): List<File> {
    val out = ArrayList<File>()
    fun add(path: String) {
        val f = File(path)
        when {
            path.startsWith("@") -> File(path.substring(1)).readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            f.isDirectory -> f.listFiles { c -> c.isFile && (c.extension.equals("genome", true) || c.extension.equals("json", true)) }
                ?.sortedBy { it.name }?.forEach { out.add(it) }
            f.isFile -> out.add(f)
            else -> println("нет такого пути: $path")
        }
    }
    from.forEach { add(it) }
    return out
}

private class StandGenomeExport(private val game: MyGame, private val files: List<File>, private val to: File) : ApplicationListener by game {
    private var frames = 0
    private var next = 0
    private val sourcesFile = File(to, "sources.tsv")
    private val known = HashMap<String, String>()
    private val bodyKeys = HashMap<String, String>()
    private val usedSlugs = HashSet<String>()
    private var exported = 0; private var skipped = 0; private var same = 0

    override fun render() {
        game.render()
        // Пара кадров: игра доходит до меню, как у игрока, и только потом импорт.
        if (++frames < 3) return
        if (frames == 3) begin()
        // По одному файлу за кадр: окно остаётся живым, а в журнале видно, на каком геноме стоим.
        if (next < files.size && !restart) {
            val file = files[next++]
            try { one(file) } catch (t: Throwable) { skip(file, "не вырастился: $t"); restart = true }
            return
        }
        finish()
        // Код 3 — «запустите ещё раз»: выращивание упало посреди роста, и мир редактора после этого
        // не годится (замер 15.11: один геном с повторным номером клетки — и следующие 38 падали с
        // той же ошибкой). Разобранное записано в sources.tsv, повторный запуск продолжит с места.
        exportExit = if (restart) 3 else 0
        Gdx.app.exit()
    }

    /** Выращивание упало: остальные файлы — в следующем запуске, с чистым миром редактора. */
    private var restart = false

    private fun begin() {
        to.mkdirs()
        if (sourcesFile.isFile) for (line in sourcesFile.readLines()) {
            val f = line.split('\t')
            if (f.size >= 3) { known[f[0]] = f[1]; if (f[1] == "ok") { usedSlugs.add(f[2]); f.getOrNull(3)?.let { bodyKeys[it] = f[2] } } }
        }
        println("источников ${files.size}, уже разобрано ${known.size}; тела — в ${to.path}")
    }

    private fun record(file: File, status: String, slug: String, key: String = "") {
        sourcesFile.appendText("${digest(file)}\t$status\t$slug\t$key\t${file.path}\n")
    }

    private fun skip(file: File, reason: String) {
        skipped++
        println(String.format("%-44s ПРОПУЩЕН: %s", file.name.take(44), reason.take(160)))
        record(file, "skip", reason.replace('\t', ' ').replace('\n', ' ').take(200))
    }

    private fun digest(file: File): String =
        java.security.MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun one(file: File) {
        if (known.containsKey(digest(file))) return
        val started = System.nanoTime()
        val target = Gdx.files.absolute(File("lwjgl3/build/standGenomeExport").absolutePath)
        val imported = try {
            GenomeImport.importFile(Gdx.files.absolute(file.absolutePath), target, DIGenomeEditorContainer.genomeBaker)
        } catch (e: Exception) {
            skip(file, "не геном: ${e.message}")
            // Не разобрался сам файл — мир редактора не тронут; всё остальное упало уже в росте.
            val text = e.message.orEmpty()
            if (PARSE_ERRORS.none { text.startsWith(it) }) restart = true
            return
        }
        val genome = decodeGenome(imported.readBytes())
        val base = genome.sortedGraph
        if (base == null || !base.hasRestPose) { skip(file, "нет позы покоя после запекания"); return }
        val layout = base.withTrianglesFromLinks()
        val n = layout.cellsInLayout
        if (n < 3) { skip(file, "клеток $n — не тело"); return }
        val zygote = layout.slotByCellGenomeId.get(0)
        if (zygote < 0) { skip(file, "в раскладке нет зиготы"); return }

        // Одно и то же тело под другим именем или из другой рабочей копии — один раз.
        val key = bodyKey(layout.restX, layout.restY, layout.restRadius, layout.restBone, layout.linkPairsInSlotOrder)
        bodyKeys[key]?.let { same++; record(file, "same", it, key); return }

        val name = GenomeImport.fileBaseName(file.name)
        val slug = uniqueSlug(name)
        val types = cellTypes(genome, n) { layout.slotByCellGenomeId.get(it) }
        val stages = growthStages(genome, layout)
        val born = BooleanArray(n).also { it[zygote] = true }
        for (s in stages) for (slot in s.births) born[slot] = true
        val unborn = born.count { !it }

        val sb = StringBuilder(n * 64)
        sb.append("# genomeia stand body 1\n")
        sb.append("name\t").append(name).append('\n')
        sb.append("source\t").append(file.path.replace('\\', '/')).append('\n')
        for (k in 0 until n) sb.append(String.format(Locale.ROOT, "P %d %.5f %.5f %.4f %s %d\n",
            k, layout.restX[k], layout.restY[k], layout.restRadius[k], types[k], if (layout.restBone[k]) 1 else 0))
        val crossing = BooleanArray(layout.linksInLayout)
        for (s in layout.crossingLinks) crossing[s] = true
        val pairs = layout.linkPairsInSlotOrder
        var links = 0
        for (s in 0 until layout.linksInLayout) {
            val a = layout.slotByCellGenomeId.get(pairs[2 * s]); val b = layout.slotByCellGenomeId.get(pairs[2 * s + 1])
            if (a < 0 || b < 0 || a == b) continue
            sb.append(if (crossing[s]) "X " else "L ").append(a).append(' ').append(b).append('\n')
            if (!crossing[s]) links++
        }
        val tri = layout.triangleSlotsArray
        for (t in 0 until layout.trianglesInLayout) sb.append("T ").append(tri[3 * t]).append(' ').append(tri[3 * t + 1]).append(' ').append(tri[3 * t + 2]).append('\n')
        for ((index, s) in stages.withIndex()) {
            for (k in s.births.indices) sb.append("G ").append(index + 1).append(' ').append(s.births[k]).append(' ').append(s.parents[k]).append('\n')
            for (slot in s.bones) sb.append("O ").append(index + 1).append(' ').append(slot).append('\n')
        }
        File(to, "$slug.body").writeText(sb.toString())

        bodyKeys[key] = slug
        exported++
        val ms = (System.nanoTime() - started) / 1e6
        println(String.format("%-44s %7.0f мс  клеток %5d  связей %5d  костей %4d  мышц %4d  стадий %4d%s  -> %s.body",
            file.name.take(44), ms, n, links, layout.restBone.count { it }, types.count { it == "muscle" }, stages.size,
            if (unborn > 0) "  НЕ РОЖДАЕТСЯ $unborn" else "", slug))
        record(file, "ok", slug, key)
    }

    /** Тип взрослой клетки по слотам — из снимка для превью (там типы ключами); без снимка — «?». */
    private fun cellTypes(genome: Genome, n: Int, slotOf: (Int) -> Int): Array<String> {
        val types = Array(n) { "?" }
        val preview = genome.preview ?: return types
        for (k in 0 until preview.cellCount) {
            val slot = slotOf(preview.cellGenomeId[k])
            if (slot in 0 until n) types[slot] = preview.cellTypeKeys.getOrElse(preview.cellType[k]) { "?" }.replace(' ', '_')
        }
        return types
    }

    private fun bodyKey(x: List<Float>, y: List<Float>, r: List<Float>, bone: List<Boolean>, pairs: List<Int>): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val sb = StringBuilder()
        // От начала координат поза не зависит: тело могло расти в другом месте мира редактора.
        val x0 = x.min(); val y0 = y.min()
        for (k in x.indices) sb.append(Math.round((x[k] - x0) * 500)).append(',').append(Math.round((y[k] - y0) * 500)).append(',')
            .append(Math.round(r[k] * 100)).append(if (bone[k]) 'b' else 's')
        sb.append('|').append(pairs.size)
        md.update(sb.toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueSlug(name: String): String {
        val base = slug(name).ifEmpty { "genome" }
        var s = base; var k = 2
        while (!usedSlugs.add(s)) s = "$base-${k++}"
        return s
    }

    /** Имя латиницей для номера сцены: русские буквы — по звучанию, прочее — дефисом. */
    private fun slug(name: String): String {
        val map = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
        val sb = StringBuilder()
        for (ch in name.lowercase(Locale.ROOT)) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                map.containsKey(ch) -> sb.append(map[ch])
                else -> if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
        }
        return sb.toString().trim('-')
    }

    private fun finish() {
        println("выгружено тел $exported, повторов того же тела $same, пропущено $skipped; всего источников ${files.size}")
    }
}
