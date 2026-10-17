package io.github.some_example_name.physics.lab.session

import io.github.some_example_name.physics.DRAG_MATCH_WEIGHT
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.scenes.fireBullet
import io.github.some_example_name.physics.lab.scenes.runScene
import io.github.some_example_name.physics.lab.scenes.slamOrganisms
import kotlin.math.abs
import kotlin.math.sqrt

internal fun DemoSession.openPlayerLog(path: String = PlayerLog.FILE) { with(state) {
    // ПРИ ВОСПРОИЗВЕДЕНИИ ЖУРНАЛ НЕ ПИШЕТСЯ ВОВСЕ.
    //
    // Записывать нечего — ввод берётся из файла, — а перезаписать можно как раз тот
    // журнал, который и воспроизводим. Я на этом уже потерял запись игрока: открыл
    // окно, оно затёрло player-log.txt, и записи с метками не стало.
    if (replayActive || pendingReplay != null) {
        println("[DemoSession] воспроизведение: журнал НЕ пишется, запись сохранена")
        return
    }
    val f = java.io.File(path)
    // СТАРЫЙ ЖУРНАЛ НЕ ВЫБРАСЫВАЕТСЯ, А СДВИГАЕТСЯ. Журнал — единственное, что
    // воспроизводится побитово, то есть единственное доказательство случая. Терять
    // его при каждом запуске окна нельзя: игрок записал прорыв, открыл окно ещё раз
    // посмотреть — и доказательства нет.
    if (f.isFile && f.length() > 0L) {
        val keep = java.io.File(f.parentFile, f.nameWithoutExtension + "-prev." + f.extension)
        try {
            keep.delete()
            if (f.renameTo(keep)) println("[DemoSession] прошлый журнал сохранён: ${keep.name}")
        } catch (e: Exception) {
            println("[DemoSession] прошлый журнал сдвинуть не удалось: ${e.message}")
        }
    }
    try {
        val w = java.io.BufferedWriter(java.io.OutputStreamWriter(
            java.io.FileOutputStream(f, false), Charsets.UTF_8))
        val bf = java.io.File(bodyPath)
        w.write("# GENOMEIA PLAYER LOG v1\n")
        w.write("# разбор: gradlew :lwjgl3:playerReplay\n")
        w.write("# начат ${java.time.LocalDateTime.now()}\n")
        w.write("HDR body ${bf.absolutePath}\n")
        w.write("HDR bodyCrc ${PlayerLog.crc(bf)}\n")
        w.write("HDR copies $copies\n")
        w.write("HDR free $freeParticles\n")
        w.write("HDR killFraction $labKillFraction\n")
        w.write("HDR killSeed $labKillSeed\n")
        w.write("HDR killOnDeep ${if (contacts?.killOnDeep == true) 1 else 0}\n")
        w.write("HDR ups ${Math.round(1.0 / DT)}\n")
        w.write("HDR substeps $SUBSTEPS\n")
        DemoSettings.writeHeader(w, physicsConfig)
        w.flush()
        plog = w
        println("[DemoSession] журнал игрока: ${f.absolutePath}")
    } catch (e: java.io.IOException) {
        println("[DemoSession] журнал игрока не открылся: ${e.message}")
    }
} }

internal fun DemoSession.closePlayerLog() { with(state) {
    val w = plog ?: return
    plog = null
    try { w.write("$playTick END\n"); w.close() } catch (_: java.io.IOException) { }
} }

internal fun DemoSession.plogWrite(s: String, flush: Boolean) { with(state) {
    val w = plog ?: return
    try {
        w.write(s); w.write("\n")
        if (flush) w.flush()
    } catch (e: java.io.IOException) {
        println("[DemoSession] журнал игрока: запись оборвалась, ${e.message}")
        plog = null
    }
} }

/**
 * ЕДИНСТВЕННАЯ ТОЧКА входа для действий, меняющих физику. Значения передаются
 * уже новые, а не «переключить»: так строка журнала читается сама по себе.
 */
internal fun DemoSession.act(op: Char, a: Int = 0) { with(state) {
    // Сначала строка, потом исполнение: сцена внутри себя крутит тики, и их
    // строки обязаны лечь ПОСЛЕ строки самой сцены.
    plogWrite(if (op in "BDEGKNX") "$playTick $op $a" else "$playTick $op", flush = true)
    when (op) {
        'R' -> reset()
        'B' -> bonesRigid = a == 1
        'G' -> { gait = a == 1; gaitFrame = 0 }
        'K' -> contactsOn = a == 1
        'X' -> tearingOn = a == 1
        'E' -> bendLevel = a
        'N' -> sceneIndex = a
        'S' -> runScene()
        'P' -> fireBullet()
        'O' -> slamOrganisms()
        'D' -> {
            dragId = a; matchWeight[a] = DRAG_MATCH_WEIGHT
            // Курсор обязан попасть в журнал на первом же тике захвата.
            plogCurX = Double.NaN
            // Отсечка счётчиков на момент захвата: строка DRAG обязана
            // показывать, что натворила ИМЕННО ЭТА тяга. Счётчики
            // накопительные и обнуляются только по R, поэтому таран через O
            // красил строку у следующей же тяги, хотя она ни при чём.
            dragCapBase = speedCapHits
            // Пик именно ОБНУЛЯЕТСЯ, а не запоминается: это максимум, и
            // вычитать из максимума базу бессмысленно.
            peakSpeed2 = 0.0
        }
        'U' -> if (dragId >= 0) { matchWeight[dragId] = 1.0; dragId = -1 }
    }
} }

/**
 * Входы, которые физика читает КАЖДЫЙ тик: цели мышц и курсор схваченной клетки.
 * Пишутся только при изменении — «держу гребок» это одна строка, а не тысяча.
 * Пишутся здесь, а не в handleInput: за кадр может пройти и ноль тиков, и десять,
 * а значение имеет только то, что увидел тик.
 */
internal fun DemoSession.recordTickInputs() { with(state) {
    if (plog == null) return
    var changed = plogMuscles.size != muscleTarget.size
    if (!changed) for (m in muscleTarget.indices) if (muscleTarget[m] != plogMuscles[m]) { changed = true; break }
    if (changed) {
        plogMuscles = muscleTarget.copyOf()
        val sb = StringBuilder().append(playTick).append(" M ")
        var any = false
        for (m in muscleTarget.indices) {
            if (muscleTarget[m] == 0.0) continue
            if (any) sb.append(',')
            sb.append(m).append(':').append(muscleTarget[m])
            any = true
        }
        if (!any) sb.append('-')
        plogWrite(sb.toString(), flush = false)
    }
    if (dragId >= 0 && (mouseX != plogCurX || mouseY != plogCurY)) {
        plogCurX = mouseX; plogCurY = mouseY
        plogWrite("$playTick C $mouseX $mouseY", flush = false)
    }
} }

internal fun DemoSession.tickHash() { with(state) {
    if (plog != null) {
        val h = PlayerLog.stateHash(n, px, py, vx, vy)
        plogWrite("$playTick H ${java.lang.Long.toHexString(h)}", flush = true)
        return
    }
    // При воспроизведении сумма сверяется здесь же: внутри сцены до разбора
    // журнала в replayRun дело не дойдёт, пока сцена не кончится.
    val lines = replay ?: return
    while (replayAt < lines.size) {
        val ln = lines[replayAt]
        if (ln.tick != playTick || ln.op != "H") break
        replayAt++
        replayCheckHash(ln)
    }
} }

internal fun DemoSession.toggleMark(i: Int, drawnX: Float = state.px[i].toFloat(), drawnY: Float = state.py[i].toFloat(),
    paused: Boolean = false, timeScale: Double = 1.0, renderAlpha: Double = 0.0, interp: Int = 1) { with(state) {
    if (marks.remove(i)) {
        plogWrite("$playTick UNMARK $i", flush = true)
        println("[МЕТКА] снята: клетка #$i, тик $playTick")
        return
    }
    marks.add(i)
    // Рядом с номером — где клетка была НАРИСОВАНА и в каком режиме смотрели:
    // на замедлении картинка отстаёт от физики на долю тика, и это надо видеть.
    plogWrite("$playTick MARK $i ${drawnX} ${drawnY} paused=${if (paused) 1 else 0} " +
        "scale=$timeScale alpha=$renderAlpha interp=$interp", flush = true)
    println("[МЕТКА] клетка #$i, тик $playTick (%.2f с)".format(playTick * DT))
} }

internal fun DemoSession.replayStateHash(): Long = with(state) { PlayerLog.stateHash(n, px, py, vx, vy) }

/** То же, что create(), но без окна: тело, затем сброс. */
internal fun DemoSession.replayBoot() { with(state) {
    buildFromFile()
    reset()
} }

/**
 * Исполнить журнал. onEvent зовётся ПОСЛЕ исполнения каждой строки-события —
 * для MARK это ровно то состояние, при котором игрок нажал кнопку.
 */
internal fun DemoSession.replayRun(lines: List<PlayerLog.Line>, onEvent: (PlayerLog.Line) -> Unit,
                       onTick: () -> Unit) { with(state) {
    replay = lines
    replayAt = 0
    replayMuscle = DoubleArray(muscleTarget.size)
    try {
        while (replayAt < lines.size) {
            val ln = lines[replayAt]
            if (ln.tick < playTick) {
                println("[воспроизведение] РАССИНХРОН: строка «${ln.raw}» пришла на тике $playTick")
                replayAt++
                continue
            }
            if (ln.tick > playTick || ln.op == "M" || ln.op == "C") {
                stepOnce(); onTick()
                continue
            }
            replayAt++
            when (ln.op) {
                "R", "B", "G", "K", "X", "E", "N", "S", "P", "O", "D", "U" ->
                    act(ln.op[0], ln.args.getOrNull(0)?.toInt() ?: 0)
                "H" -> replayCheckHash(ln)
            }
            onEvent(ln)
            if (ln.op == "END") break
        }
    } finally {
        replay = null
    }
} }

internal fun DemoSession.replayLoad(lines: List<PlayerLog.Line>) { with(state) {
    replay = lines
    replayAt = 0
    replayMuscle = DoubleArray(muscleTarget.size)
} }

internal fun DemoSession.replayStepOne(): Boolean { with(state) {
    val lines = replay ?: return false
    while (replayAt < lines.size) {
        val ln = lines[replayAt]
        if (ln.tick < playTick) {
            // Рассинхрон: строка опоздала. В окне не печатаем на каждый кадр, счётчик
            // ниже всё равно покажет расхождение сумм.
            replayAt++
            continue
        }
        if (ln.tick > playTick || ln.op == "M" || ln.op == "C") {
            stepOnce()
            return true
        }
        replayAt++
        when (ln.op) {
            "R", "B", "G", "K", "X", "E", "N", "S", "P", "O", "D", "U" ->
                act(ln.op[0], ln.args.getOrNull(0)?.toInt() ?: 0)
            "H" -> replayCheckHash(ln)
            // Метки игрока показываются тем же цветом, что и при записи: по ним и
            // смотрят, та ли клетка и в тот ли момент.
            "MARK" -> ln.args.getOrNull(0)?.toIntOrNull()?.let { marks.add(it) }
            "UNMARK" -> ln.args.getOrNull(0)?.toIntOrNull()?.let { marks.remove(it) }
        }
        if (ln.op == "END") { replay = null; return false }
    }
    replay = null
    return false
} }

internal fun DemoSession.replayTickInputs() { with(state) {
    val lines = replay!!
    while (replayAt < lines.size) {
        val ln = lines[replayAt]
        if (ln.tick != playTick) break
        if (ln.op == "M") {
            replayMuscle.fill(0.0)
            val spec = ln.args.getOrNull(0) ?: "-"
            if (spec != "-") for (part in spec.split(',')) {
                val k = part.indexOf(':')
                val m = part.substring(0, k).toInt()
                if (m < replayMuscle.size) replayMuscle[m] = part.substring(k + 1).toDouble()
            }
        } else if (ln.op == "C") {
            mouseX = ln.args[0].toDouble(); mouseY = ln.args[1].toDouble()
        } else break
        replayAt++
    }
    System.arraycopy(replayMuscle, 0, muscleTarget, 0, muscleTarget.size)
} }

internal fun DemoSession.replayCheckHash(ln: PlayerLog.Line) { with(state) {
    val want = java.lang.Long.parseUnsignedLong(ln.args[0], 16)
    if (PlayerLog.stateHash(n, px, py, vx, vy) == want) { replayHashOk++; return }
    replayHashBad++
    if (replayFirstBadTick < 0) {
        replayFirstBadTick = ln.tick
        println("[воспроизведение] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ на тике ${ln.tick}: " +
            "дальше состояние уже не то, что видел игрок")
    }
} }
