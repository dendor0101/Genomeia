package io.github.some_example_name.lwjgl3.verification

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Несколько копий игры, запущенных одновременно, не роняют друг друга.
 *
 * Зачем: отчёты 13.11 — 8 крашей на старте под Windows (AccessDeniedException в
 * GameFilePaths.writeAtomically, из SessionAttachments). Четыре копии игры стартовали в одну
 * секунду и делили папку сессии: каждая стирала её и писала туда свои снимки (BugSession).
 *
 * Программа [ROUNDS] раз запускает [COPIES] копий лаунчера разом — с тем же java и classpath,
 * что у неё самой, в той же папке игры, — каждая снимает скриншот на кадре 90 и закрывается
 * (DevStartup). Успех — все вышли с кодом 0, сняли скриншот, каждая писала сессию в свою
 * папку, и в bugs не появилось ни одного нового отчёта о краше.
 *
 * Запуск: gradlew :lwjgl3:parallelLaunchCheck
 */
fun main() {
    val root = File(".").canonicalFile
    val bugs = File(root, "bugs")
    val before = bugs.listFiles()?.map { it.name }?.toSet().orEmpty()
    val out = File(root, "lwjgl3/build/parallelLaunchCheck").apply { deleteRecursively(); mkdirs() }

    var failures = 0
    for (round in 0 until ROUNDS) failures += runRound(root, out, round)

    val crashes = bugs.listFiles()?.map { it.name }?.filter { it !in before && it.endsWith("-crash.bug") }.orEmpty()
    if (crashes.isNotEmpty()) {
        failures++
        println("НОВЫЕ ОТЧЁТЫ О КРАШЕ: ${crashes.joinToString()}")
    }
    println(if (failures == 0) "Все $ROUNDS раунда по $COPIES копий отработали без краша, каждая в своей папке сессии."
    else "НЕ ПРОШЛО: $failures.")
    exitProcess(if (failures == 0) 0 else 1)
}

/** Один раунд: [COPIES] копий разом. Возвращает число провалов. */
private fun runRound(root: File, out: File, round: Int): Int {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val classpath = System.getProperty("java.class.path")
    // Восемь копий разом распаковывают натив LWJGL: в занятой временной папке LWJGL уходит в
    // рабочую (assets/.lwjgl), и следом список ассетов тянет эти dll в assets.txt.
    val natives = File(out, "lwjgl-natives").path
    val processes = (0 until COPIES).map { k ->
        val shot = File(out, "r$round-copy-$k.png")
        val log = File(out, "r$round-copy-$k.log")
        val process = ProcessBuilder(
            java, "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Dgenomeia.screenshot=${shot.path}", "-Dgenomeia.screenshotFrame=90",
            "-Dgenomeia.width=480", "-Dgenomeia.height=320",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=$natives",
            "-cp", classpath, "io.github.some_example_name.lwjgl3.Lwjgl3Launcher"
        )
            .directory(File(root, "assets"))
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        Triple(process, shot, log)
    }

    var failures = 0
    val sessions = HashSet<String>()
    processes.forEachIndexed { k, (process, shot, log) ->
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val code = if (finished) process.exitValue() else -1
        val session = log.readLines().firstOrNull { it.contains(SESSION_LINE) }?.substringAfter(SESSION_LINE)?.trim()
        val ownSession = session != null && sessions.add(session)
        val ok = finished && code == 0 && shot.isFile && ownSession
        if (!ok) failures++
        println("раунд $round, копия $k: ${if (!finished) "не закрылась за $TIMEOUT_SECONDS с" else "код $code"}, " +
            "скриншот ${if (shot.isFile) "есть" else "НЕТ"}, сессия ${session ?: "?"}${if (session != null && !ownSession) " — ОБЩАЯ С ДРУГОЙ КОПИЕЙ" else ""}")
    }
    return failures
}

private const val COPIES = 8
private const val ROUNDS = 3
private const val TIMEOUT_SECONDS = 240L
private const val SESSION_LINE = "сессия пишется в"
