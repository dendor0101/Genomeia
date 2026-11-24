package io.github.some_example_name.physics

import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
 * ЗООПАРК СТАРЫХ БАГОВ (план стабильности, этап 1, пункт 8): gradlew :physics:bugZoo
 *
 * Каждая запись — задокументированная починка и способ её откатить: кусок исходника, каким он
 * стал, и каким был с багом. Задача по очереди берёт запись, откатывает починку в КОПИИ исходников
 * модуля, пересобирает её и гоняет все тесты модуля. Хоть один тест обязан упасть: иначе починку
 * никто не сторожит, и её можно потерять при следующей перестройке, не заметив.
 *
 * Мутационный прогон (:physics:mutationTest) портит код вслепую и мелко. Зоопарк возвращает
 * настоящие баги, которые уже случались, — те, что описаны в комментариях рядом с починкой.
 *
 *   -Ponly=часть названия   только эти записи
 *   -Pjobs=N                сколько записей собирать одновременно (по умолчанию 3)
 *
 * Запись устарела (её текста в исходнике больше нет или он встречается не один раз) — задача
 * падает и называет запись: зоопарк не должен молча проверять меньше, чем в нём записано.
 *
 * Цена: пересборка модуля и прогон тестов на запись — около минуты на каждую.
 */

/** Починка [name] в файле [file] (от корня исходников): текст с починкой [fixed] и с багом [broken]. */
class ZooBug(val name: String, val file: String, val fixed: String, val broken: String, val story: String)

private const val SOLVER = "io/github/some_example_name/physics"

val BUG_ZOO: List<ZooBug> = listOf(
    ZooBug("кость: центр масс после контактной поправки", "$SOLVER/contacts/RigidContactBatch.kt",
        "            if (positions) { cx[b] += tx; cy[b] += ty }\n",
        "",
        "16.11.2026. Отскок считал плечи клеток от центра кости, снятого до контактной поправки того же подшага: " +
            "вращательная часть отскока давала линейный импульс из ничего (1.9e-4f импульса пары за подшаг касания)."),
    ZooBug("отскок: нормаль по нынешним положениям", "$SOLVER/contacts/BoundaryContacts.kt",
        "            var nx = px[i] - px[j]; var ny = py[i] - py[j]\n" +
            "            val nl = sqrt(nx * nx + ny * ny)\n" +
            "            if (nl > 1e-12f && nx * cNx[c] + ny * cNy[c] > 0.5f * nl) { nx /= nl; ny /= nl } else { nx = cNx[c]; ny = cNy[c] }\n",
        "            val nx = cNx[c]; val ny = cNy[c]\n",
        "16.11.2026. Толчок отскока шёл вдоль нормали, запомненной до позиционной поправки, а прикладывался к " +
            "нынешним центрам: пара получала момент импульса из ничего (частица в торец кости — 3.1e-3f P·связь)."),
    ZooBug("кость делится вместе с телом", "$SOLVER/topology/BoneSplit.kt",
        "    if (boneDirtyN == 0) return\n    // Новые кости в очередь не попадают",
        "    boneDirtyN = 0\n    if (boneDirtyN == 0) return\n    // Новые кости в очередь не попадают",
        "16.11.2026. Состав кости не пересматривался: половины разрезанной кости оставались одним твёрдым телом, " +
            "погибшая клетка — в кости с полным весом."),
    ZooBug("погибшая клетка замораживается", "$SOLVER/damage/DamageModel.kt",
        "    killIncidentEdges(v)\n    vx[v] = 0.0f; vy[v] = 0.0f; invMass[v] = 0.0f\n    contacts?.dropFromContacts(v)\n    refreshSurface()\n",
        "    killIncidentEdges(v)\n    vx[v] = 0.0f; vy[v] = 0.0f\n    contacts?.dropFromContacts(v)\n    refreshSurface()\n",
        "Обнулить скорость мало: следующий тик снова приложит к мёртвой клетке поток и тягу, и она полетит по сцене " +
            "без контактного радиуса — сквозь всё."),
    ZooBug("кость поворачивается конечным поворотом, а не по касательной", "$SOLVER/contacts/RigidContactBatch.kt",
        "            val sin = if (positions) StrictMath.sin((angle).toDouble()).toFloat() else angle\n            val cosMinusOne = if (positions) StrictMath.cos((angle).toDouble()).toFloat() - 1.0f else 0.0f\n",
        "            val sin = angle\n            val cosMinusOne = 0.0f\n",
        "Эйлеровское r + angle × r растягивало кость при каждом касании."),
    ZooBug("проекция кости: сумма поправок обнуляется явно", "$SOLVER/solver/BoneSolver.kt",
        "        px[i] += (boneDx[k] - mdx) * scale\n        py[i] += (boneDy[k] - mdy) * scale\n",
        "        px[i] += boneDx[k] * scale\n        py[i] += boneDy[k] * scale\n",
        "Проекция кости сдвигала центр масс на остаток суммы w·q каждый подшаг: «мышца дёрнулась пару раз, а тело " +
            "потом само куда-то уплывает» (утечка в 1170 раз больше обеих стадий связей и площади)."),
    ZooBug("отскок от кости считает массу всего кластера", "$SOLVER/contacts/BoundaryContacts.kt",
        "            val wi = effInvMassSplit(i, j, nx, ny, px, py, invMass)\n            val wj = effInvMassSplit(j, i, nx, ny, px, py, invMass)\n            val w = wi + wj\n            if (w <= 0.0f) continue\n",
        "            val wi = invMass[i]\n            val wj = invMass[j]\n            val w = wi + wj\n            if (w <= 0.0f) continue\n",
        "Частица в кость отскакивала на 0.00f–0.04f вместо 0.10f, то есть прилипала: проход считал кость одной клеткой."),
    ZooBug("потолок поправки предела длины", "$SOLVER/solver/ConstraintSolver.kt",
        "    if (dL < -capL) dL = -capL\n",
        "",
        "Предел длины снимал перерастяжение целиком за подшаг, и при загибе связи отсюда шёл выброс скорости."),
    ZooBug("потолок поправки площади", "$SOLVER/solver/BlockConstraintKernels.kt",
        "            if (corr > maxStep) dL *= maxStep / corr\n",
        "",
        "Вывернутый треугольник выстреливал вершиной: жёсткая поправка площади без потолка (AREA_MAX_STEP)."),
    ZooBug("перепонка односторонняя", "$SOLVER/solver/BlockConstraintKernels.kt",
        "        if (len >= rest || len < 1e-12f) continue\n",
        "        if (len < 1e-12f) continue\n",
        "Перепонка — страховка от схлопывания: она не должна стягивать разошедшиеся клетки."),
    ZooBug("удар будит спящего до решения контакта", "$SOLVER/solver/BodySimulation.kt",
        "            if (noteSleepContacts(finalContacts)) {\n                applyWakeRequests()\n                finalContacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = false)\n            }\n",
        "            noteSleepContacts(finalContacts)\n",
        "Спящий заморожен (обратная масса 0): контакт с ним решался как удар о стену, налетевшая частица отскакивала, " +
            "а импульс спящему не передавался (ClientInputsCheck: импульс цели 0)."),
    ZooBug("потолок скорости — последним, после снятия внутреннего импульса", "$SOLVER/solver/BodySimulation.kt",
        "            momentumCancel()\n            // Коррекция тоже меняет скорости: потолок должен оставаться последним.\n            clampSpeed()\n",
        "            momentumCancel()\n",
        "Коррекция импульса сама меняет скорости и могла вернуть частице скорость выше потолка."),
    ZooBug("запас среды — у каждого организма свой", "$SOLVER/solver/MediumSolver.kt",
        "    val vmx = (vx[i] + vx[j]) * 0.5f - flowVX[o]\n    val vmy = (vy[i] + vy[j]) * 0.5f - flowVY[o]\n",
        "    val vmx = (vx[i] + vx[j]) * 0.5f - flowVX[0]\n    val vmy = (vy[i] + vy[j]) * 0.5f - flowVY[0]\n",
        "Два тела делили один запас среды: движение одного толкало другое на любом расстоянии."),
    ZooBug("предел длины не двигает костный конец связи", "$SOLVER/solver/ConstraintSolver.kt",
        "        if (boneI && !boneJ && wj > 0.0f) wi = 0.0f else if (boneJ && !boneI && wi > 0.0f) wj = 0.0f\n    }\n    val w = wi + wj\n",
        "    }\n    val w = wi + wj\n",
        "16.11.2026. Предел длины мягкой связи стоит после проекции кости и вытягивал клетку кости из позы: " +
            "кость с мягким суставом при ударе 2 связи за тик искажалась на 0.18f связи."),
    ZooBug("ориентация контура не двигает клетку кости", "$SOLVER/solver/BoundaryOrientation.kt",
        "            if (ba) wa = 0.0f; if (bb) wb = 0.0f; if (bc) wc = 0.0f\n",
        "",
        "16.11.2026. Вывернутый треугольник на стыке кости и ткани расправлялся за счёт костных вершин: " +
            "кость оставалась смятой на 0.07f связи до следующего подшага."),
    ZooBug("масса кости делится между её касаниями", "$SOLVER/contacts/BoundaryContacts.kt",
        "        return if (z > 1) (1.0f / boneM[b] + rot) * z else 1.0f / boneM[b] + rot\n",
        "        return if (z > 1e9f) (1.0f / boneM[b] + rot) * z else 1.0f / boneM[b] + rot\n",
        "16.11.2026 (и раньше, в силовой ветке контакта: «рывок за кость разгонялся до 10.7f связей/с при вложенных 3.1f»). " +
            "Каждое из многих касаний кости разводило её целиком само: энергия пары костей с торцом в 8 клеток росла в 3.65f раза."),
    ZooBug("обрезка по времени удара откатывает кость целиком", "$SOLVER/contacts/BoundaryContacts.kt",
        "        val bonesHit = if (bo != null && boneIds.isNotEmpty()) gatherCcdBones(bo) else 0\n",
        "        val bonesHit = if (bo != null && boneIds.isEmpty()) gatherCcdBones(bo) else 0\n",
        "16.11.2026. Обрезка быстрого удара останавливала только задетые клетки торца кости, остальная кость " +
            "проходила свой путь: кость сминалась на длину обрезанного пути."),
    ZooBug("отскок костей не зависит от порядка касаний", "$SOLVER/contacts/BoundaryContacts.kt",
        "            val vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny\n            // Положенная скорость расхождения: доля отскока, если пара сближалась, и прежняя, если расходилась.\n",
        "            val zi = boneIdx(i); val zj = boneIdx(j)\n" +
            "            val vn = (vx[i] + (if (zi >= 0) rigidBatch.velocityX(zi, py[i] - boneCy[zi], boneM[zi], boneI[zi]) else 0.0f) -\n" +
            "                vx[j] - (if (zj >= 0) rigidBatch.velocityX(zj, py[j] - boneCy[zj], boneM[zj], boneI[zj]) else 0.0f)) * nx +\n" +
            "                (vy[i] + (if (zi >= 0) rigidBatch.velocityY(zi, px[i] - boneCx[zi], boneM[zi], boneI[zi]) else 0.0f) -\n" +
            "                vy[j] - (if (zj >= 0) rigidBatch.velocityY(zj, px[j] - boneCx[zj], boneM[zj], boneI[zj]) else 0.0f)) * ny\n" +
            "            // Положенная скорость расхождения: доля отскока, если пара сближалась, и прежняя, если расходилась.\n",
        "16.11.2026. Отскок шёл по касаниям кости по очереди: первое брало весь удар, и лобовой удар двух одинаковых " +
            "костей закручивал их в сторону, которую решал порядок клеток в памяти (нашла проверка симметрии стендов)."),
    ZooBug("рамка мира — опора, а не пресс", "$SOLVER/solver/MediumSolver.kt",
        "                    if (cancelSpinOn) tickMomentum.block(i)\n",
        "",
        "17.11.2026. Учёт импульса за тик не считал рамку мира опорой и возвращал телу импульс, отнятый стенкой: " +
            "лоскут 8×7, влетевший в рамку на 2 связи за тик, терял 22 клетки из 56."),
    ZooBug("упор в неподвижное — жёсткий", "$SOLVER/contacts/BoundaryContacts.kt",
        "        if (w[i] <= 0.0f || w[j] <= 0.0f) return KIND_FIXED\n",
        "",
        "17.11.2026. Мягкое касание не успевало остановить передний ряд тела о стенку из неподвижных частиц: " +
            "лоскут 8×7 на 2 связи за тик терял 29 клеток из 56, клетки входили в рельеф."),
    ZooBug("рельеф не выстреливает", "$SOLVER/contacts/BoundaryContacts.kt",
        "            if (dLam > 0.0f) { if (fixed) cFixed[c] = KIND_PUSHED else if (kind == KIND_OWN) cFixed[c] = KIND_OWN_PUSHED }\n",
        "            if (dLam > 0.0f && kind == KIND_OWN) cFixed[c] = KIND_OWN_PUSHED\n",
        "17.11.2026. Перекрытие, снятое упором разом, становилось скоростью «перекрытие / подшаг»: частица внахлёст " +
            "с неподвижной улетала на 0.5f–1.9f связи за тик, лоскут о стенку терял 22 клетки."),
    ZooBug("масса клетки делится между её упорами", "$SOLVER/contacts/BoundaryContacts.kt",
        "            // Масса мягкой клетки делится между её упорами; у кости деление своё (effInvMassSplit).\n" +
            "            if (boneIdx(i) < 0) wi *= zOf(i)\n            if (boneIdx(j) < 0) wj *= zOf(j)\n",
        "            // Масса мягкой клетки делится между её упорами; у кости деление своё (effInvMassSplit).\n",
        "17.11.2026. Клетка у стены касается двух-трёх частиц рельефа: без деления массы каждый упор выталкивал её " +
            "целиком сам, и она отлетала дальше расстояния касания."),
    ZooBug("гашение выстрела — всем упорам клетки разом", "$SOLVER/contacts/BoundaryContacts.kt",
        "                var wj = effInvMassSplit(j, i, nx, ny, px, py, invMass)\n" +
            "                if (boneIdx(i) < 0) wi *= zOf(i)\n                if (boneIdx(j) < 0) wj *= zOf(j)\n",
        "                var wj = effInvMassSplit(j, i, nx, ny, px, py, invMass)\n",
        "17.11.2026. Скорость после упора правилась по упорам по очереди: частица между двумя неподвижными в зеркальной " +
            "сцене уходила вбок на 0.24f связи за тик."),
    ZooBug("клетка с дорастающей связью упирается по-старому", "$SOLVER/contacts/BoundaryContacts.kt",
        "        if (killImmune(i, j)) return KIND_SOFT\n",
        "",
        "17.11.2026. С жёстким упором для растущих клеток тело, растущее в стену, оставалось к ней вплотную и врастало: " +
            "вход в рельеф рос в 59 сценах роста в стену из 131 и падал в 21."),
    ZooBug("касание клеток одного организма не выстреливает", "$SOLVER/contacts/BoundaryContacts.kt",
        "        return if (org[i] == org[j]) KIND_OWN else KIND_SOFT\n",
        "        return KIND_SOFT\n",
        "17.11.2026. Перекрытие клеток одного тела, снятое касанием, становилось скоростью: смятую мышцей или ударом ткань " +
            "разбрасывало, она разбалтывалась и входила в себя глубже. Мышечный червь 20×4, шесть раз сжатый и растянутый, " +
            "терял 21 клетку из 80."),
    ZooBug("своему касанию лишняя скорость снимается, а не только добавляется", "$SOLVER/contacts/BoundaryContacts.kt",
        "            if (if (own) dvn == 0.0f else dvn <= 0.0f) continue\n",
        "            if (dvn <= 0.0f) continue\n",
        "17.11.2026. Проход отскока умел только добавлять скорость расхождения до доли отскока. Скорость, которую пара " +
            "клеток одного тела получила от снятого перекрытия, он не трогал: пара внахлёст расходилась на 0.9f связи за тик."),
    ZooBug("потолок тяги мышцы: связь", "$SOLVER/solver/ConstraintSolver.kt",
        "        if (scale < 1.0f && over > MUSCLE_MAX_PULL * rest) over = MUSCLE_MAX_PULL * rest\n",
        "",
        "17.11.2026. Связь, отставшая от команды мышцы, тянула тем сильнее, чем больше отстала: половины червя 30×4 " +
            "набирали ход и сминали середину — гибло 35 клеток из 120, ни одна связь при этом не рвалась."),
    ZooBug("потолок тяги мышцы: площадь", "$SOLVER/solver/BlockConstraintKernels.kt",
        "        if (active < 1.0f) { val cap = 2.0f * MUSCLE_MAX_PULL * rest2[t]; if (excess > cap) excess = cap }\n",
        "",
        "17.11.2026. Площадь треугольника мышцы жёстче связей вдесятеро: без потолка она тянула отстающую ткань одна, " +
            "и потолок на связях ничего не давал."),
    ZooBug("учёт импульса: погибшая клетка не выключает организм", "$SOLVER/solver/TickMomentum.kt",
        "                if (o >= 0 && (outside == null || !outside[i])) { if (first[o] < 0) first[o] = i else union(first[o], i) }\n",
        "                if (o >= 0) { if (first[o] < 0) first[o] = i else union(first[o], i) }\n",
        "Первая же погибшая клетка навсегда выключала учёт импульса всему организму (BirthKickProbe 03.11f)."),
)

/** Сколько упавших тестов собрать на запись: хватит, чтобы назвать сторожей. */
private const val FAILURES_ENOUGH = 4
/** Самый долгий прогон тестов на запись: дольше — баг завесил решатель, это тоже «пойман». */
private const val TESTS_TIMEOUT_MIN = 15L

private class ZooResult(val bug: ZooBug, val caught: List<String>, val problem: String?)

fun main() {
    val sources = File(System.getProperty("zoo.sources"))
    val work = File(System.getProperty("zoo.work"))
    val compiler = System.getProperty("zoo.compiler")
    val libs = System.getProperty("zoo.libs")
    val tests = System.getProperty("zoo.tests")
    val module = System.getProperty("zoo.module")
    val only = System.getProperty("zoo.only").orEmpty()
    val jobs = System.getProperty("zoo.jobs")?.toIntOrNull() ?: 3
    val java = File(System.getProperty("java.home"), "bin/java").path

    val names = BUG_ZOO.map { it.name }
    require(names.size == names.toSet().size) { "названия записей зоопарка повторяются" }
    val list = BUG_ZOO.filter { only.isEmpty() || it.name.contains(only) }
    println("ЗООПАРК СТАРЫХ БАГОВ: записей ${list.size} из ${BUG_ZOO.size}, одновременно $jobs")
    work.deleteRecursively(); work.mkdirs()

    val pool = Executors.newFixedThreadPool(jobs)
    val futures = list.mapIndexed { index, bug ->
        pool.submit<ZooResult> { runBug(index, bug, sources, work, java, compiler, libs, tests, module) }
    }
    val results = futures.map { it.get() }
    pool.shutdown()

    var bad = 0
    println()
    for (r in results) {
        when {
            r.problem != null -> { bad++; println("ЗАПИСЬ НЕ ГОДИТСЯ  ${r.bug.name}\n    ${r.problem}") }
            r.caught.isEmpty() -> { bad++; println("ВЫЖИЛ    ${r.bug.name}\n    откат починки не уронил ни одного теста: ${r.bug.story}") }
            else -> println("пойман   ${r.bug.name}\n    ${r.caught.joinToString("\n    ")}")
        }
    }
    val report = File(work, "report.md")
    report.writeText(buildString {
        append("# Зоопарк старых багов\n\nЗаписей ${results.size}, поймано ${results.count { it.problem == null && it.caught.isNotEmpty() }}, ")
        append("выжило ${results.count { it.problem == null && it.caught.isEmpty() }}, не годится ${results.count { it.problem != null }}.\n\n")
        append("| Починка | Итог | Кто ловит |\n|---|---|---|\n")
        for (r in results) append("| ${r.bug.name} | ${if (r.problem != null) "запись не годится" else if (r.caught.isEmpty()) "**выжил**" else "пойман"} | " +
            "${(r.problem ?: r.caught.joinToString("; ")).replace("|", "/")} |\n")
    })
    println("\nИТОГ: поймано ${results.size - bad} из ${results.size}. Отчёт: ${report.path}")
    exitProcess(if (bad == 0) 0 else 1)
}

private fun runBug(index: Int, bug: ZooBug, sources: File, work: File, java: String, compiler: String, libs: String,
                   tests: String, module: String): ZooResult {
    val dir = File(work, "bug$index")
    val src = File(dir, "src"); val out = File(dir, "classes")
    sources.copyRecursively(src, overwrite = true)
    out.mkdirs()
    val file = File(src, bug.file)
    if (!file.isFile) return ZooResult(bug, emptyList(), "нет файла ${bug.file}")
    val text = file.readText().replace("\r\n", "\n")
    val at = text.indexOf(bug.fixed)
    if (at < 0) return ZooResult(bug, emptyList(), "текста починки в ${bug.file} больше нет — запись устарела")
    if (text.indexOf(bug.fixed, at + 1) >= 0) return ZooResult(bug, emptyList(), "текст починки в ${bug.file} встречается не один раз")
    file.writeText(text.substring(0, at) + bug.broken + text.substring(at + bug.fixed.length))

    val sep = File.pathSeparator
    val compile = ProcessBuilder(java, "-Xmx2g", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-d", out.path, "-classpath", libs, "-jvm-target", "21", "-module-name", module, "-no-stdlib", "-no-reflect", "-nowarn", src.path)
        .redirectErrorStream(true).redirectOutput(File(dir, "compile.log")).start()
    if (!compile.waitFor(20, TimeUnit.MINUTES)) { compile.destroyForcibly(); return ZooResult(bug, emptyList(), "сборка не закончилась за 20 минут") }
    if (compile.exitValue() != 0) return ZooResult(bug, emptyList(), "откат не собирается: ${File(dir, "compile.log").readLines().take(3).joinToString(" / ")}")

    val log = File(dir, "tests.log")
    val run = ProcessBuilder(java, "-Xmx1g", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dzoo.tests=$tests",
        "-cp", out.path + sep + tests + sep + libs, "io.github.some_example_name.physics.ZooTests")
        .redirectErrorStream(true).redirectOutput(log).start()
    if (!run.waitFor(TESTS_TIMEOUT_MIN, TimeUnit.MINUTES)) { run.destroyForcibly(); return ZooResult(bug, listOf("тесты не закончились за $TESTS_TIMEOUT_MIN минут: решатель завис"), null) }
    val caught = log.readLines().filter { it.startsWith("УПАЛ ") }.map { it.removePrefix("УПАЛ ") }
    println("  ${if (caught.isEmpty()) "ВЫЖИЛ " else "пойман"}  ${bug.name}")
    return ZooResult(bug, caught, null)
}

/**
 * Прогон всех тестов модуля в дочернем процессе зоопарка: классы *Test из каталогов zoo.tests.
 * Останавливается, набрав достаточно упавших. SoftnessTest пропускается: он красный и без отката
 * (см. physics/build.gradle, mutationTest).
 */
object ZooTests {
    @JvmStatic fun main(args: Array<String>) = zooTestsMain()
}

fun zooTestsMain() {
    val classes = ArrayList<Class<*>>()
    for (root in System.getProperty("zoo.tests").split(File.pathSeparator).map { File(it) }) {
        root.walkTopDown().filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }.sortedBy { it.path }.forEach { f ->
            val name = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            if (!name.endsWith(".SoftnessTest")) classes.add(Class.forName(name))
        }
    }
    val core = JUnitCore()
    var failures = 0
    core.addListener(object : RunListener() {
        override fun testFailure(failure: Failure) { report(failure.description, failure.message) }
        override fun testAssumptionFailure(failure: Failure) {}
        private fun report(d: Description, message: String?) {
            println("УПАЛ ${d.className.substringAfterLast('.')} :: ${d.methodName} — ${(message ?: "").lineSequence().firstOrNull().orEmpty().take(160)}")
            if (++failures >= FAILURES_ENOUGH) { System.out.flush(); exitProcess(0) }
        }
    })
    core.run(*classes.toTypedArray())
    System.out.flush()
    exitProcess(0)
}
