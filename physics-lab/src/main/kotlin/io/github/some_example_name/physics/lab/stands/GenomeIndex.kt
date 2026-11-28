package io.github.some_example_name.physics.lab.stands

import java.io.File
import java.util.Locale

/**
 * СПИСОК ТЕЛ ГЕНОМОВ: читает все *.body каталога и пишет рядом index.tsv — имя, размер, сколько
 * костей, мышц, присосок и стадий роста. По нему семейства решают, какие сцены ставить какому
 * телу, не читая все тела при каждом запуске (158 тел — 11 МБ текста).
 *
 * Заодно проверяет каждое тело: читается ли, связно ли по связям физики, все ли клетки рождаются.
 *
 * Запуск: gradlew :physics-lab:standGenomeIndex — после :lwjgl3:standGenomeExport.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0) ?: "physics-lab/src/main/resources/stand-genomes")
    val files = dir.listFiles { f -> f.isFile && f.extension == "body" }?.sortedBy { it.name }.orEmpty()
    require(files.isNotEmpty()) { "в ${dir.path} нет тел: сначала gradlew --no-daemon :lwjgl3:standGenomeExport" }
    class Row(val slug: String, val title: String, val cells: Int, val links: Int, val bones: Int, val muscles: Int, val cups: Int, val stages: Int, val source: String)
    val rows = ArrayList<Row>()
    var loose = 0
    for (f in files) {
        val slug = f.nameWithoutExtension
        val shape = try {
            GenomeBodies.parse(slug, f.readLines())
        } catch (t: Throwable) {
            println("НЕ ЧИТАЕТСЯ ${f.name}: $t"); continue
        }
        val g = shape.genome!!
        // Связно ли тело по связям, которые получает физика: кусок, висящий только на связях
        // поперёк граней, в мире физики — отдельное тело.
        val seen = BooleanArray(shape.count)
        val queue = IntArray(shape.count)
        var head = 0; var tail = 0
        queue[tail++] = 0; seen[0] = true
        while (head < tail) { val v = queue[head++]; for (s in shape.adjStart[v] until shape.adjStart[v + 1]) { val u = shape.adj[s]; if (!seen[u]) { seen[u] = true; queue[tail++] = u } } }
        val pieces = if (tail == shape.count) "" else { loose++; "  НЕ СВЯЗНО: из первой клетки достижимо $tail из ${shape.count}" }
        rows.add(Row(slug, g.title, shape.count, shape.linkCount, shape.boneCount, shape.muscleCount, shape.cups().size, g.stages, g.source))
        if (pieces.isNotEmpty()) println(String.format(Locale.ROOT, "%-40s клеток %5d%s", slug, shape.count, pieces))
    }
    rows.sortWith(compareBy({ it.cells }, { it.slug }))
    val sb = StringBuilder("slug\ttitle\tcells\tlinks\tbones\tmuscles\tcups\tstages\tsource\n")
    for (r in rows) sb.append(r.slug).append('\t').append(r.title).append('\t').append(r.cells).append('\t').append(r.links).append('\t')
        .append(r.bones).append('\t').append(r.muscles).append('\t').append(r.cups).append('\t').append(r.stages).append('\t').append(r.source).append('\n')
    File(dir, "index.tsv").writeText(sb.toString())
    println("тел ${rows.size}: клеток от ${rows.first().cells} до ${rows.last().cells}; с костями ${rows.count { it.bones > 0 }}, с мышцами ${rows.count { it.muscles > 0 }}, " +
        "с присосками ${rows.count { it.cups > 0 }}; несвязных по связям физики $loose. Список: ${File(dir, "index.tsv").path}")
    val bySize = rows.groupBy { GenomeEntry(it.slug, it.title, it.cells, it.links, it.bones, it.muscles, it.cups, it.stages, it.source).size }
    for ((size, list) in bySize) println("  $size: ${list.size}")
}
