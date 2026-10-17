package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * САМОКОНТАКТ ГРАНИЧНОГО КОНТУРА: хеш-сетка, DDA, CCD и импульсное разрешение.
 *
 * Перенесено из прототипа prototypes/xpbd-ccd-demo.html. Здесь ОДНА реализация на всех:
 * ею пользуются и DemoSession, и SwimSolver. Дублировать её было нельзя — за время
 * работы над стендом копия решателя трижды молча разъезжалась с оригиналом, и каждый
 * раз это стоило часа поисков. Общий класс делает совпадение структурным, а не удачей.
 *
 * ЧТО СЮДА ВХОДИТ И ПОЧЕМУ ИМЕННО ТАК
 * -----------------------------------
 *
 * ТОЛЬКО ГРАНИЧНЫЕ КЛЕТКИ. Внутренние в контактах не участвуют вообще — их держат
 * связи и площади. Это главная оптимизация: у тела 947 клеток и около 200 граничных,
 * то есть работы впятеро меньше, чем при честном переборе всех. Внутренняя клетка
 * физически не может оказаться снаружи, не протащив за собой границу, а границу мы
 * и стережём.
 *
 * СВЯЗАННЫЕ ПАРЫ НЕ СТАЛКИВАЮТСЯ. Это не оптимизация, а необходимость: в покое соседи
 * стоят на 0.6 при сумме радиусов 1.0, то есть перекрываются на 40% и были бы в
 * вечном контакте. Проверка идёт по списку смежности, а не по расстоянию.
 *
 * DDA ПО СВИП-ПУТИ (Amanatides & Woo). В сетку кладётся не точка, а весь отрезок,
 * пройденный за подшаг. Отсюда отсутствие туннелирования: даже если частица за подшаг
 * перелетела двадцать ячеек, обойдутся все двадцать, а не только концы.
 *
 * CCD ОБРЕЗАЕТ ПОДШАГ ПО ВРЕМЕНИ ПЕРВОГО КОНТАКТА. Ищется наименьшее t, при котором
 * круги сходятся до «ядра» core*(ri+rj). Обрезка только УМЕНЬШАЕТ перемещение, значит
 * энергии не добавляет никогда.
 *
 * ЭНЕРГИЯ. Позиционный решатель делает удар абсолютно неупругим — вся нормальная
 * скорость съедена. Скоростной проход возвращает ровно долю e от той скорости, с
 * которой тела РЕАЛЬНО сближались (она снимается ДО позиционного решателя, в cVn).
 * При e <= 1 энергия не может вырасти. Трение ограничено накопленным нормальным
 * импульсом — кулоновский конус, а не произвольное торможение.
 */
class BoundaryContacts(
    private val n: Int,
    /** Индексы граничных клеток. Только они участвуют в контактах. */
    private var verts: IntArray,
    /** Смежность в формате CSR: adjStart[i]..adjStart[i+1] — соседи клетки i. */
    private var adjStart: IntArray,
    private var adj: IntArray,
    private val radius: DoubleArray,
    /** Сторона ячейки сетки. Должна быть не меньше наибольшего диаметра контакта. */
    private var cellSize: Double,
    private val contactScale: Double,
    private val ccdCore: Double,
    /**
     * Доля скорости сближения, возвращаемая отскоком. См. solveRestitution.
     */
    @Suppress("unused")
    private val restitution: Double,
    private val friction: Double,
    /** Средняя длина связи — масштаб, в котором задан потолок поправки. */
    private val meanLink: Double,
    /** Потолок поправки контакта за подшаг, в долях средней связи. */
    private val contactMaxStep: Double,
    /**
     * РАДИУС КОНТАКТА, взятый из ГЕОМЕТРИИ ГРАНИЦЫ, а не из радиуса клетки.
     *
     * Непроницаемость мембраны держится на одном условии: круги двух СОСЕДНИХ
     * граничных клеток обязаны перекрываться, иначе между ними остаётся щель, и
     * пройти сквозь неё можно не нарушив ни одного правила. Радиус клетки этого не
     * гарантирует — он рисовальный и задаётся в редакторе как угодно. В теле с
     * клетками 0.5 и 0.2 при шаге решётки 0.6 круги мелких по прежней формуле
     * накрывали 0.48 из 0.6, то есть в мембране была дыра шириной 0.12.
     *
     * Поэтому радиус берётся как ПОЛОВИНА САМОГО ДЛИННОГО ГРАНИЧНОГО РЕБРА клетки,
     * умноженная на небольшой запас. Тогда соседи по контуру перекрываются ВСЕГДА,
     * какие бы радиусы ни нарисовал игрок.
     *
     * Окно для этого числа узкое с обеих сторон, и обе границы измерены:
     * длиннейшее граничное ребро 0.0364 — ниже него щель; ближайшая НЕСВЯЗАННАЯ
     * пара граничных клеток 0.0418 — выше него они попадают в вечный ложный
     * контакт и тело само себя распирает. Запас между ними всего 1.15x.
     *
     * ПРИ ПЕРЕНОСЕ: тело растёт, и длина рёбер меняется. Радиус надо пересчитывать
     * при изменении топологии, а не один раз на старте. И проверять, что запас
     * 1.15x не съеден: если у какой-то клетки граничное ребро станет длиннее
     * ближайшей несвязанной пары, кругами на вершинах мембрану уже не сшить, и
     * придётся сталкиваться с РЕБРОМ как с отрезком.
     */
    private val contactRadius: DoubleArray,
    config: ContactConfig = ContactConfig(),
) {
    private val optionContactOmegaH = config.CONTACT_OMEGA_H
    private val optionBpMargin = config.BP_MARGIN
    private val optionCtSweepFlip = config.CT_SWEEP_FLIP
    private val optionCcdAlways = config.CCD_ALWAYS
    private val optionCcdGate = config.CCD_GATE
    private val optionPierceTear = config.PIERCE_TEAR
    private val optionPierceDepth = config.PIERCE_DEPTH
    private val optionRestFull = config.REST_FULL
    private val optionBpCheck = config.BP_CHECK
    private val optionXpbdWarmStart = config.XPBD_WARM_START
    private var optionAblegacy = config.abLegacy
    private val optionContactCompliance = config.CONTACT_COMPLIANCE
    private val optionContactOmegaPair = config.CONTACT_OMEGA_PAIR
    private val optionContactSeal = config.CONTACT_SEAL
    var abLegacy: Boolean
        get() = optionAblegacy
        set(value) { optionAblegacy = value }

    // --- сетка широкой фазы ---
    //
    // ОТКРЫТАЯ АДРЕСАЦИЯ НА МАССИВАХ, А НЕ HashMap<Long, IntArray>. Сетка пересобирается
    // на КАЖДОМ подшаге (16 за тик), и каждая вставка с каждым запросом упаковывали ключ
    // в Long и шли в хеш-карту. Замер на теле из 1900 клеток: подготовка контактов —
    // пятая часть тика.
    //
    // Ячейка занята, если её метка равна метке этого подшага: чистить таблицу между
    // подшагами не нужно вовсе. Клетки ячейки лежат односвязным списком в общих массивах,
    // и новая цепляется В ХВОСТ — порядок обхода ячейки прежний, а от него зависит порядок
    // пар и, через него, результат.
    private val grid = SweptContactGrid()
    // --- пары-кандидаты ---
    private var pairA = IntArray(4096)
    private var pairB = IntArray(4096)
    // Номер записи покоя стабилен между перестройками широкой фазы. JFR 13.10:
    // повторный поиск по строке rest занимал 5.4% CPU на записи из 5620 тиков.
    // Добавлено 4 байта на кандидата: 4096 пар = 16 КБ (часть L1), 100k пар = 0.4 МБ (часть L2).
    // Массив читается потоком вместе с pairA/B, без новой хеш-таблицы и обхода клеток.
    private var pairRestSlot = IntArray(4096)
    private var pairN = 0

    /**
     * СПИСОК ПАР ЖИВЁТ ДОЛЬШЕ ОДНОГО ПОДШАГА.
     *
     * Широкая фаза строилась заново каждый подшаг — шестнадцать раз за тик по всему
     * контуру. На настоящей сессии игрока это 16.9% тика, при том что сам решатель
     * контактов — 0.6%: почти всё время уходило на то, чтобы заново выяснить, кто с кем
     * РЯДОМ, хотя за подшаг никто далеко не уезжает.
     *
     * Теперь ячейка сетки берётся с запасом (`optionBpMargin` от наибольшего радиуса), а
     * список строится заново, только когда хоть одна вершина отъехала от места постройки
     * дальше половины запаса. Пока это не случилось, список заведомо полон: две вершины
     * могут коснуться, только если между ними меньше суммы радиусов, а на момент
     * постройки между ними было меньше стороны ячейки — значит пара в переборе 3x3 была.
     * Быстрое движение сразу возвращает прежнее поведение: перестройка каждый подшаг.
     */
    private var bpX = DoubleArray(n)
    private var bpY = DoubleArray(n)
    private var bpValid = false
    private var bpMargin = 0.0
    /** Разбор: сколько раз за прогон широкая фаза строилась заново и сколько раз звали. */
    var bpBuilds = 0L
    var bpCalls = 0L
    /** Разбор: сколько перекрывшихся пар не попало в список кандидатов. См. CT_BPCHECK. */
    var bpMissed = 0L
    private val mark = IntArray(n)
    private var stamp = 0

    // --- контакты ---
    private var cI = IntArray(4096)
    private var cJ = IntArray(4096)
    private var cNx = DoubleArray(4096)
    private var cNy = DoubleArray(4096)
    private var cVn = DoubleArray(4096)
    private var cLam = DoubleArray(4096)
    /** Накопленный множитель контакта. См. optionXpbdWarmStart. */
    private var cLamTot = DoubleArray(4096)
    /** Перенесённая часть множителя после потолка; ниже неё множитель не опускается. -1 — ещё не считана. */
    private var cLamInit = DoubleArray(4096)
    private var cN = 0

    private val toi = DoubleArray(n)
    /** Острова CCD: клетки, связанные опасными парами. См. ccdClamp. */
    private val ccdParent = IntArray(n)
    private val islM = DoubleArray(n)
    private val islX = DoubleArray(n)
    private val islY = DoubleArray(n)

    /** Накопитель сил на клетку за подшаг. См. applyForces. */
    private val frcX = DoubleArray(n)
    private val frcY = DoubleArray(n)
    private val frcW = DoubleArray(n)
    /** Доля, на которую урезан суммарный толчок клетки потолком. См. applyForces. */
    private val frcS = DoubleArray(n)
    /** Импульс каждого контакта, чтобы урезать его СИММЕТРИЧНО обоим концам. */
    private var cAx = DoubleArray(4096)
    private var cAy = DoubleArray(4096)
    private val frcZ = IntArray(n)
    /**
     * Число контактов ЖЁСТКОГО КЛАСТЕРА, а не отдельной его клетки.
     *
     * Нужно потому, что applyRigid двигает весь кластер целиком: пять касающихся
     * клеток одной кости дают кости пять полных сил, то есть пятикратную жёсткость
     * при её же массе в знаменателе. Считая контакты по клетке, расщепление массы
     * этого не видит. Замер на кирпиче: рывок за кость разгонялся до 10.7 связей/с
     * при вложенных рукой 3.1, и потолок скорости срабатывал 262 раза.
     */
    private var boneZ = IntArray(0)
    private var boneZStamp = IntArray(0)
    private val frcStamp = IntArray(n) { -1 }
    private var frcList = IntArray(256)
    private var frcN = 0
    private var pass = 0
    /**
     * ЗАЯВКИ НА СМЕРТЬ КЛЕТКИ: центр одной зашёл внутрь радиуса другой.
     *
     * ЛИШНИХ ПРОВЕРОК ЗДЕСЬ НЕТ НИ ОДНОЙ, и это важно. Условие «центр внутри радиуса»
     * строго строже условия контакта: контакт есть при d < ri + rj, а центр внутри —
     * при d < rj, что заведомо меньше. Значит все кандидаты уже лежат в списке
     * контактов, и проверка сводится к одному сравнению в цикле, который и так идёт.
     * Ни широкая фаза, ни CCD для этого не нужны.
     *
     * Решатель сам никого не убивает: он складывает номера сюда, а хозяин тела
     * разбирается с топологией после подшага. Убивать посреди обхода нельзя — список
     * контактов и смежность собраны на текущую топологию.
     */
    var killN = 0
        private set
    var killList = IntArray(64)
        private set

    /**
     * ПРОБИТАЯ МЕМБРАНА: рёбра контура, сквозь которые ПРОШЁЛ центр чужой клетки.
     *
     * Исходная посылка «контур непроницаем» оказалась неверной: контакт — сила, а силу
     * всегда можно пересилить. Рукой свободную частицу протаскивало сквозь медузу
     * насквозь, не порвав ни одной связи (стенд PERF_PUSH). Значит мембрана должна не
     * «не пускать», а ЛИБО не пустить, ЛИБО порваться — и последнее надо ловить прямо,
     * геометрией, а не надеяться на силу.
     *
     * Проверка точная: отрезок пути клетки за подшаг против отрезка граничного ребра.
     * Центр не может оказаться внутри, не пересёкши контур. Замер на том же стенде:
     * за 3840 подшагов пересечение случилось РОВНО ОДИН раз — в момент входа. То есть
     * правило срабатывает по делу и не сыплет разрывами.
     *
     * Складываем пары клеток ребра, хозяин тела сам найдёт связь и порвёт её: рвать
     * посреди обхода нельзя, список контактов собран на текущую топологию.
     */
    var pierceN = 0
        private set
    var pierceA = IntArray(16)
        private set
    var pierceB = IntArray(16)
        private set

    /** Граничные рёбра каждой клетки: bndStart[i]..bndStart[i+1] — номера в bndOther. */
    private var bndStart = IntArray(0)
    private var bndOther = IntArray(0)
    /** Внутренняя вершина ребра, параллельно bndOther. -1 у голого ребра. */
    private var bndOpp = IntArray(0)

    /** Хозяин тела разобрал пробои — список можно чистить. */
    fun clearPierce() { pierceN = 0 }

    private fun pierce(a: Int, b: Int) {
        for (k in 0 until pierceN) if ((pierceA[k] == a && pierceB[k] == b) || (pierceA[k] == b && pierceB[k] == a)) return
        if (pierceN == pierceA.size) { pierceA = pierceA.copyOf(pierceN * 2); pierceB = pierceB.copyOf(pierceN * 2) }
        pierceA[pierceN] = a; pierceB[pierceN] = b; pierceN++
    }

    /**
     * ПРОШЛА ЛИ КЛЕТКА СКВОЗЬ РЕБРО. Отрезок пути (1->2) против отрезка ребра (3-4).
     *
     * Мало пересечься — надо УЙТИ ЗА ребро на заметную глубину. На покоящемся стыке двух
     * кусков клетки стоят вплотную к чужому контуру и дрожат около его линии в последнем
     * знаке: голое пересечение отрезков там срабатывает постоянно, и тело рвёт само себя
     * (проверка «тело не должно ехать» падала на 6.5 при пороге 1). Поэтому требуем, чтобы
     * конец пути отстоял от линии ребра не меньше чем на `depth`.
     */
    private fun segCross(x1: Double, y1: Double, x2: Double, y2: Double,
                         x3: Double, y3: Double, x4: Double, y4: Double, depth: Double): Boolean {
        val d1 = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
        val d2 = (x2 - x1) * (y4 - y1) - (y2 - y1) * (x4 - x1)
        if ((d1 > 0.0) == (d2 > 0.0)) return false
        val ex = x4 - x3; val ey = y4 - y3
        val d3 = ex * (y1 - y3) - ey * (x1 - x3)
        val d4 = ex * (y2 - y3) - ey * (x2 - x3)
        if ((d3 > 0.0) == (d4 > 0.0)) return false
        val len2 = ex * ex + ey * ey
        if (len2 < 1e-18) return false
        // |d4| / |ребро| — это и есть расстояние от конца пути до линии ребра.
        return d4 * d4 > depth * depth * len2
    }

    /**
     * ПРОВЕРКА ПРОБОЯ ПОСЛЕ КАЖДОЙ ИТЕРАЦИИ РЕШАТЕЛЯ, а не только на нулевой.
     *
     * Поиск пробоя живёт в prepare, а prepare зовётся раз в подшаг, на нулевой итерации.
     * Значит проверяется только движение, которое клетка успела сделать к этому моменту:
     * интегрирование плюс стадии нулевой итерации. Смещение остальных трёх итераций —
     * связей, площадей, изгиба, проекции кости и контактов — не проверялось вовсе, а на
     * таране именно решатель и заводит клетку внутрь, расталкивая сжатую ткань.
     *
     * Список пар-кандидатов тот же, что собрал prepare в этом подшаге, поэтому проверка
     * ничего не строит заново и стоит столько же, сколько стоила одна.
     */
    fun scanPierce(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        if (optionPierceTear) findPierce(px, py, qx, qy)
    }

    /**
     * ПЕРЕСЕЧЕНИЕ ТОЛЬКО СНАРУЖИ ВНУТРЬ. Именно этого правилу пробоя и не хватало.
     *
     * Голое пересечение отрезков не различает сторон, и на покоящемся стыке двух кусков
     * срабатывало постоянно: клетки стоят вплотную к чужому контуру (круги соседей по
     * контуру обязаны перекрываться, см. optionContactSeal), то есть почти НА линии соседнего
     * ребра, и дрожание в последнем знаке переставляет их с одной стороны прямой на
     * другую. Проверка «тело не трогают, оно не должно ехать» падала на 127 при пороге 1 —
     * мембрана шинковала сама себя. Лечили это порогом по глубине, и он одновременно и не
     * спасал (1.37 при пороге 1), и терял настоящие пробои.
     *
     * Сторона известна локально: у граничного ребра ровно один живой треугольник, и его
     * третья вершина лежит ВНУТРИ тела (boundOpp). Значит внешняя нормаль берётся без
     * обхода контура и без намотки, а пробоем считается только переход с внешней стороны
     * на внутреннюю ЗА ОДИН ПОДШАГ — то есть по свёрнутому пути, как и просит CCD.
     * Дрожание около линии такого перехода не даёт: оно симметрично, а это условие нет.
     */
    private fun inward(qx: DoubleArray, qy: DoubleArray, px: DoubleArray, py: DoubleArray,
                       i: Int, a: Int, b: Int, opp: Int): Boolean {
        // Голое ребро: ни одного треугольника, внутренней стороны нет вовсе.
        if (opp < 0) return false
        // ЗНАК СЧИТАЕТСЯ В СИСТЕМЕ САМОГО РЕБРА, ОТДЕЛЬНО НА НАЧАЛО И НА КОНЕЦ ПОДШАГА.
        //
        // Сначала я брал нормаль по КОНЕЧНОМУ положению ребра и сравнивал с ним обе точки
        // пути клетки. На таране это почти не срабатывает: там не клетка влетает в ткань,
        // а ТКАНЬ НАБЕГАЕТ НА КЛЕТКУ, и собственный путь клетки конечного положения ребра
        // может не пересекать вовсе. Замер: 23 срабатывания за 90 тиков при семистах
        // проникших клетках. В системе ребра движение становится относительным, и
        // набегающая мембрана ловится так же, как влетающая клетка.
        val ex0 = qx[b] - qx[a]; val ey0 = qy[b] - qy[a]
        val ex1 = px[b] - px[a]; val ey1 = py[b] - py[a]
        if (ex0 * ex0 + ey0 * ey0 < 1e-18 || ex1 * ex1 + ey1 * ey1 < 1e-18) return false
        var n0x = -ey0; var n0y = ex0
        if (n0x * (qx[opp] - qx[a]) + n0y * (qy[opp] - qy[a]) > 0.0) { n0x = -n0x; n0y = -n0y }
        var n1x = -ey1; var n1y = ex1
        if (n1x * (px[opp] - px[a]) + n1y * (py[opp] - py[a]) > 0.0) { n1x = -n1x; n1y = -n1y }
        val s0 = n0x * (qx[i] - qx[a]) + n0y * (qy[i] - qy[a])
        val s1 = n1x * (px[i] - px[a]) + n1y * (py[i] - py[a])
        val el2 = ex1 * ex1 + ey1 * ey1
        val t = ((px[i] - px[a]) * ex1 + (py[i] - py[a]) * ey1) / el2
        // РАЗБОР: по какой именно причине ребро не считается пробитым. Без разделения
        // «не дошла» и «уже была внутри» починить нечего — это разные болезни.
        if (i == pierceWatch) {
            pwEdges++
            if (s1 < 0.0) {
                pwEndedIn++
                if (t <= -0.1 || t >= 1.1) pwOffEnd++
                else if (s0 <= 0.0) pwWasIn++
            }
        }
        // Было снаружи, стало внутри — и ровно это пробой.
        if (s0 <= 0.0 || s1 >= 0.0) return false
        // Поперёк ребра, а не мимо его концов: иначе рвалось бы ребро, через которое
        // клетка и не проходила. Запас по десятой доле — на наклон ребра за подшаг.
        return t > -0.1 && t < 1.1
    }

    /**
     * ПРОБОЙ КЛЕТКИ [i] ПО РЁБРАМ ВОКРУГ КЛЕТКИ КОНТУРА [j] И ЕЁ СОСЕДЕЙ. См. findPierce.
     */
    private fun pierceAround(qx: DoubleArray, qy: DoubleArray, px: DoubleArray, py: DoubleArray,
                             i: Int, j: Int, org: IntArray) {
        if (org[i] == org[j]) return
        for (s2 in bndStart[j] until bndEnd[j]) {
            val o = bndOther[s2]
            if (o != i && org[o] == org[j] && inward(qx, qy, px, py, i, j, o, bndOpp[s2])) {
                pierce(j, o); if (i == pierceWatch) pwHits++
            }
            // Второй шаг: рёбра соседа по контуру.
            if (org[o] != org[j]) continue
            for (s3 in bndStart[o] until bndEnd[o]) {
                val o2 = bndOther[s3]
                if (o2 == i || o2 == j || org[o2] != org[o]) continue
                if (inward(qx, qy, px, py, i, o, o2, bndOpp[s3])) {
                    pierce(o, o2); if (i == pierceWatch) pwHits++
                }
            }
        }
    }

    /**
     * ПОИСК ПРОБОЯ. Идёт по УЖЕ ГОТОВОМУ списку пар-кандидатов, поэтому почти ничего не
     * стоит: у клетки контура не больше пары граничных рёбер, то есть на пару приходится
     * до четырёх проверок отрезков по двадцать операций каждая.
     *
     * Полный перебор (каждая вершина против всех 436 рёбер) обошёлся бы в 190 тысяч
     * проверок за подшаг, три миллиона за тик — вот это было бы дорого. По кандидатам
     * выходит порядка тысячи за подшаг.
     */
    private fun findPierce(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        if (bndStart.size != n + 1) return
        val org = organismOf ?: return
        // ДВА ОГРАНИЧИТЕЛЯ, без которых правило рвёт тело само по себе.
        //
        // Первый: пробить можно только ЧУЖУЮ мембрану. Своя клетка лежит у своего же
        // контура вплотную (круги соседей по контуру обязаны перекрываться, см.
        // optionContactSeal), то есть почти на линии соседнего ребра, и любое дрожание в
        // последнем знаке переставляет её с одной стороны прямой на другую. Проверка
        // «тело не трогают, оно не должно ехать» падала на 127 при пороге 1: мембрана
        // шинковала сама себя.
        //
        // Второй: клетка должна ЗАМЕТНО сдвинуться. Пересечение на нулевом отрезке —
        // всегда про округление, а не про движение.
        val minMove = 1e-3 * meanLink
        val minMove2 = minMove * minMove
        for (p in 0 until pairN) {
            val i = pairA[p]; val j = pairB[p]
            if (org[i] == org[j]) continue
            val mi = (px[i] - qx[i]) * (px[i] - qx[i]) + (py[i] - qy[i]) * (py[i] - qy[i])
            val mj = (px[j] - qx[j]) * (px[j] - qx[j]) + (py[j] - qy[j]) * (py[j] - qy[j])
            // ДВА ШАГА ПО КОНТУРУ, А НЕ ОДИН.
            //
            // Раньше клетка проверялась только против рёбер СВОЕГО партнёра по паре, и
            // покрытие выходило 6%: 78 пробоев на 1004 входа в ткань. Пересечь она может
            // ребро, оба конца которого ей не партнёры — достаточно оказаться ближе к
            // третьей клетке. Второй шаг по соседям контура это закрывает, а стоит он
            // по-прежнему ничего: у клетки контура не больше двух граничных рёбер, то
            // есть добавляется до четырёх проверок на пару.
            if (mi > minMove2) pierceAround(qx, qy, px, py, i, j, org)
            if (mj > minMove2) pierceAround(qx, qy, px, py, j, i, org)
            if (i == pierceWatch || j == pierceWatch) pwPairs++
        }
        // ОДНА СТРОКА НА ПОДШАГ, И ТОЛЬКО КОГДА КЛЕТКА РЕАЛЬНО ШЛА: «чужих пар 0»
        // означает, что пробой её даже не смотрел, а «уже была внутри» — что смотрел и
        // отказался, потому что начало подшага она встретила уже за линией.
        if (pierceWatch >= 0 && pwLeft > 0) {
            val mw = sqrt((px[pierceWatch] - qx[pierceWatch]) * (px[pierceWatch] - qx[pierceWatch]) +
                (py[pierceWatch] - qy[pierceWatch]) * (py[pierceWatch] - qy[pierceWatch])) / meanLink
            if (mw > pwMinMove) {
                pwLeft--
                // ПОЛНЫЙ ПЕРЕБОР ПО ОДНОЙ КЛЕТКЕ: нашёл бы пробой тот же знаковый фильтр,
                // если бы смотрел ВСЕ рёбра контура, а не только рёбра вокруг партнёров по
                // паре? Это и есть проверка на покрытие. Счётчики причин на время перебора
                // сохраняются, иначе разбор сам себя затопчет.
                val sE = pwEdges; val sI = pwEndedIn; val sW = pwWasIn; val sO = pwOffEnd
                var full = 0; var fa = -1; var fb = -1
                val iw = pierceWatch
                for (a in 0 until n) {
                    if (bndEnd[a] <= bndStart[a] || org[a] == org[iw]) continue
                    for (s2 in bndStart[a] until bndEnd[a]) {
                        val b = bndOther[s2]
                        if (a > b || a == iw || b == iw) continue
                        if (inward(qx, qy, px, py, iw, a, b, bndOpp[s2])) {
                            full++; if (fa < 0) { fa = a; fb = b }
                        }
                    }
                }
                pwEdges = sE; pwEndedIn = sI; pwWasIn = sW; pwOffEnd = sO
                if (full > 0) {
                    val dla = sqrt((px[iw] - px[fa]) * (px[iw] - px[fa]) + (py[iw] - py[fa]) * (py[iw] - py[fa])) / meanLink
                    val dlb = sqrt((px[iw] - px[fb]) * (px[iw] - px[fb]) + (py[iw] - py[fb]) * (py[iw] - py[fb])) / meanLink
                    val el = sqrt((px[fa] - px[fb]) * (px[fa] - px[fb]) + (py[fa] - py[fb]) * (py[fa] - py[fb])) / meanLink
                    println(("[полный] тик %d #%d: ПОЛНЫЙ ПЕРЕБОР НАШЁЛ %d, ребро #%d-#%d длиной %.2f св, " +
                        "до концов %.2f и %.2f св, радиусы %.2f/%.2f/%.2f (своё/конец/конец)")
                        .format(tickNo, iw, full, fa, fb, el, dla, dlb,
                            contactRadius[iw] / meanLink, contactRadius[fa] / meanLink, contactRadius[fb] / meanLink))
                }
                println("[пробой] тик %d #%d ход %.3f св, чужих пар %d, рёбер %d, кончила внутри %d (мимо концов %d, была внутри %d), пробоев %d"
                    .format(tickNo, pierceWatch, mw, pwPairs, pwEdges, pwEndedIn, pwOffEnd, pwWasIn, pwHits))
            }
        }
        pwPairs = 0; pwEdges = 0; pwEndedIn = 0; pwWasIn = 0; pwOffEnd = 0; pwHits = 0
    }

    /** Кто в каком организме — ставит хозяин тела. Нужно поиску пробоя. */
    var organismOf: IntArray? = null

    // --- РАЗБОР ПРОБОЯ ПО ОДНОЙ КЛЕТКЕ (CT_WATCH=номер). Только для дознания. ---
    /** Клетка, по которой печатать разбор пробоя. -1 — молчать. */
    var pierceWatch = config["CT_WATCH"]?.toIntOrNull() ?: -1
    /** Номер тика — ставит хозяин. Нужен только трассе, чтобы привязать её к журналу. */
    var tickNo = 0
    /** Печатать только подшаги, где клетка прошла больше этого в долях связи. */
    private val pwMinMove = config["CT_WATCH_MOVE"]?.toDoubleOrNull() ?: 0.2
    private var pwLeft = config["CT_WATCH_LINES"]?.toIntOrNull() ?: 300
    private var pwPairs = 0
    private var pwEdges = 0
    private var pwEndedIn = 0
    private var pwWasIn = 0
    private var pwOffEnd = 0
    private var pwHits = 0

    /**
     * Сколько раз контакт ПРИТЯНУЛ пару вместо того, чтобы оттолкнуть.
     *
     * У ограничения с накопленным множителем это возможно: пока пара ещё перекрыта,
     * но уже расходится, приращение выходит отрицательным и растворяет накопленный
     * множитель. Для двустороннего ограничения это правильно, для контакта — нет:
     * контакт умеет только толкать. Отсюда и берётся прилипание.
     */
    var attractN = 0
        private set

    /** Порог смерти в долях контактного радиуса и выключатель. Ставит хозяин. */
    var killOnDeep = false
    var killDepth = 1.0

    /** Сколько пар в списке контактов этого подшага и какие. Нужно снятию остатка по группам. */
    /**
     * ДАВЛЕНИЕ КОНТАКТА НА КЛЕТКУ: сумма модулей толчков, которые контакты дали клетке,
     * в единицах смещения. Копится, пока хозяин не обнулит. Null — не копить.
     */
    var pressure: DoubleArray? = null

    /** Сколько толчков контакта получила клетка за тик. Пара к pressure. */
    var pressureN: IntArray? = null

    val contactCount: Int get() = cN
    fun contactI(c: Int): Int = cI[c]
    fun contactJ(c: Int): Int = cJ[c]

    private var toVelocity = false
    private var velH = 1.0
    /** Перенесённые множители: при optionXpbdWarmStart или в старом режиме optionAblegacy. */
    private val lam = HashMap<Long, Double>()

    /** Пары, которые могут касаться в позе покоя, — запечены на тело. См. RestPairs. */
    private var rest = RestPairs.EMPTY
    /**
     * Что сейчас с каждой записью rest: REST_NONE; REST_TOUCH — перекрыта в покое и из
     * контактов исключена (см. bonded); REST_SPACING — бывшие соседи, упор на расстоянии
     * покоя вместо суммы радиусов (см. CONTACT_REST_SPACING).
     */
    private var restState = ByteArray(0)
    private var restTouchN = 0
    private var restSpacingN = 0
    /** Клетка в контактах на этой топологии. */
    private val onBound = BooleanArray(n)
    private var vertCount = verts.size
    private val vertSlot = IntArray(n) { -1 }
    private var adjEnd = IntArray(n) { adjStart[it + 1] }
    private var bndEnd = IntArray(n)
    private var maxRadius = 0.0
    private val radiusMinimum = ContactRadiusMinimum(n)
    val minContactRadius: Double get() = radiusMinimum.value

    // Контакты получают только граница и свободные клетки. После разрыва новая
    // поверхность включается через updateSurface до следующего прохода решателя.

    /** Расстояние упора каждого контакта этого подшага. */
    private var cRR = DoubleArray(4096)

    private fun restStateOf(i: Int, j: Int): Int {
        val rp = rest
        for (s in rp.start[i] until rp.start[i + 1]) if (rp.other[s] == j) return restState[s].toInt()
        return REST_NONE
    }

    private fun contactDistance(i: Int, j: Int): Double {
        val rr = contactRadius[i] + contactRadius[j]
        if (!CONTACT_REST_SPACING || optionAblegacy || restSpacingN == 0) return rr
        val rp = rest
        for (s in rp.start[i] until rp.start[i + 1]) {
            if (rp.other[s] != j) continue
            if (restState[s].toInt() != REST_SPACING) return rr
            val d = rp.dist[s]
            return if (d < rr) d else rr
        }
        return rr
    }

    private fun pairContactDistance(p: Int, rr: Double): Double {
        if (!CONTACT_REST_SPACING || optionAblegacy || restSpacingN == 0) return rr
        var s = pairRestSlot[p]
        if (s == -2) {
            s = -1
            val i = pairA[p]; val j = pairB[p]; val rp = rest
            for (k in rp.start[i] until rp.start[i + 1]) {
                if (rp.other[k] == j) { s = k; break }
            }
            pairRestSlot[p] = s
        }
        if (s < 0 || restState[s].toInt() != REST_SPACING) return rr
        val d = rest.dist[s]
        return if (d < rr) d else rr
    }

    // --- жёсткие кластеры: масса, центр, момент инерции на подшаг ---
    private var boneOf: IntArray? = null
    private var boneM = DoubleArray(0)
    private var boneCx = DoubleArray(0)
    private var boneCy = DoubleArray(0)
    private var boneI = DoubleArray(0)
    private var boneIds: Array<IntArray> = emptyArray()

    // --- буфер DDA ---
    private val ddaX = IntArray(DDA_MAX)
    private val ddaY = IntArray(DDA_MAX)

    /** Диагностика: сколько контактов и сколько раз CCD обрезал подшаг. */
    var lastContacts = 0
        private set
    var lastToiClamps = 0
        private set
    var dbgCcdTotal = 0L
    var dbgCcdWorst = 0.0
    var dbgCcdI = -1
    var dbgCcdJ = -1
    var dbgCcdMoveA = 0.0
    var dbgCcdMoveJ = 0.0

    /**
     * Суммарный нормальный импульс контактов с последнего обнуления.
     *
     * Мера ДРЕБЕЗГА, и она работает только потому, что гравитации нет: покоящийся
     * контакт ничем не нагружен, поэтому любой ненулевой импульс в спокойной фазе —
     * это работа решателя против самого себя, а не удержание веса. Обнуляется
     * снаружи, копится сама. Одно сложение на контакт.
     */
    var impulseAccum = 0.0

    /**
     * Сколько перекрывшихся пар НЕ попало в список контактов.
     *
     * Полный перебор, как и maxPenetration, — то есть истина, не зависящая от
     * широкой фазы. Отличает две совсем разные причины проникновения: если число
     * ноль, а проникновение есть, значит решатель не справился (мягкость, нехватка
     * подшагов, потолок поправки). Если число большое — пары до решателя вовсе не
     * дошли, и виновата широкая фаза или момент, в который её строят.
     */
    fun missedOverlaps(px: DoubleArray, py: DoubleArray): Int {
        val inList = HashSet<Long>(cN * 2)
        for (c in 0 until cN) {
            val a = minOf(cI[c], cJ[c]).toLong(); val b = maxOf(cI[c], cJ[c]).toLong()
            inList.add(a * 1000003L + b)
        }
        var missed = 0
        for (a in 0 until vertCount) {
            val i = verts[a]
            for (b in a + 1 until vertCount) {
                val j = verts[b]
                if (bonded(i, j)) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val rr = contactDistance(i, j)
                if (dx * dx + dy * dy >= rr * rr) continue
                val k = minOf(i, j).toLong() * 1000003L + maxOf(i, j).toLong()
                if (!inList.contains(k)) missed++
            }
        }
        return missed
    }

    /**
     * Наибольшее проникновение среди НЕСВЯЗАННЫХ граничных пар, в долях порога контакта.
     * 0 — никто никого не касается, 1 — пара сошлась в точку. Прямая проверка того,
     * что контакты работают: в нормальной работе должно оставаться заметно меньше 1.
     */
    /** Радиус контакта клетки — для отрисовки настоящей геометрии, а не рисовального радиуса. */
    /** Участвует ли клетка в контактах вообще: только для диагностики. */
    fun inContactSet(i: Int): Boolean = onBound[i]

    fun contactRadiusOf(i: Int): Double = contactRadius[i]

    /**
     * КЛЕТКА УМЕРЛА ПОСРЕДИ ТИКА: вывести её из столкновений, НЕ перестраивая топологию.
     *
     * Полная пересборка стоит дороже всего остального разрыва вместе взятого, а смерть
     * приходит из контактов, то есть изнутри итерации решателя: до 64 раз за тик. Разрыв
     * ткани эту цену не платит — он гасит ребро на месте и перестраивается один раз в
     * конце тика (см. killEdgeNow). Смерть теперь работает так же: радиус обнуляется
     * здесь, список пар перестраивается следующим prepare, структура — в конце тика.
     */
    fun dropFromContacts(i: Int) { contactRadius[i] = 0.0; radiusMinimum.update(i, 0.0) }

    /** Нынешняя мягкость контакта — для стендов. См. optionContactOmegaPair. */
    val omegaPair: Double get() = optionContactOmegaPair
    val compliance: Double get() = optionContactCompliance

    fun restTouchingCount(): Int = restTouchN

    /** Разбор журнала игрока: расстояние упора пары (сумма радиусов или расстояние покоя). */
    fun contactDistanceOf(i: Int, j: Int): Double = contactDistance(i, j)

    /** Разбор журнала игрока: исключена ли пара из столкновений (связь или касание в покое). */
    fun isBonded(i: Int, j: Int): Boolean = bonded(i, j)

    /** Разбор журнала игрока: накопленный множитель пары; ноль, если пара не в контакте. */
    fun lambdaOf(i: Int, j: Int): Double {
        for (c in 0 until cN) if ((cI[c] == i && cJ[c] == j) || (cI[c] == j && cJ[c] == i)) return cLamTot[c]
        return lam.getOrDefault(pairKey(i, j), 0.0)
    }

    /** Отладка одной пары: сколько контакт оттолкнул и сколько ПРИТЯНУЛ, в смещении. */
    var dbgPair = -1L
    var dbgPairPush = 0.0
    var dbgPairPull = 0.0
    var dbgPairLam = 0.0
    fun pairKeyOf(i: Int, j: Int): Long = pairKey(i, j)

    /**
     * Какие пары перекрыты в позе покоя на ЭТОЙ топологии. Зовётся при каждой сборке.
     *
     * Условие прежнее: обе клетки в контактах, в позе покоя ближе суммы их нынешних
     * контактных радиусов и не связаны сейчас. Бывшие соседи сталкиваются с упором на
     * расстоянии покоя, остальные из контактов исключаются — см. bonded.
     *
     * ПЕРЕБОР ЗАПЕЧЁННЫХ КАНДИДАТОВ, А НЕ СЕТКИ. Раньше клетки контура раскладывались по
     * хеш-сетке позы покоя, а пары складывались в HashSet и HashMap — и так на каждой
     * пересборке после разрыва, то есть десятки раз за тик на ударе. Кандидаты — все пары,
     * которые могут перекрыться при каком-нибудь контуре, — от разрывов не зависят и
     * считаются один раз, см. bakeRestPairs.
     */
    /**
     * СОСТОЯНИЕ ПАР ПОКОЯ ПЕРЕСЧИТЫВАЕТСЯ ТОЛЬКО У ЗАДЕТЫХ КЛЕТОК.
     *
     * Состояние пары (i, j) зависит ровно от трёх вещей: радиусов обеих клеток, того,
     * на контуре ли j, и того, связаны ли i с j. Значит поменяться оно может только у
     * клетки, у которой изменилось хоть что-то из этого. Пересборка гасит одно-два ребра,
     * то есть задетых клеток единицы, а проход шёл по всем — на тяжёлом прогоне это
     * 5–7 мс из сорока, больше любого другого куска пересборки.
     *
     * Задетых находим сравнением с прошлым состоянием: радиус, признак контура и СТЕПЕНЬ
     * смежности. Степени довольно, потому что пересборка только УДАЛЯЕТ связи — при
     * удалении степень обязана измениться, а список соседей при той же степени остаться
     * прежним не может.
     */
    private var prevRadius = DoubleArray(n)
    private var prevBound = BooleanArray(n)
    private var prevDeg = IntArray(n)
    private var restValid = false
    private var dirtyBuf = IntArray(64)
    /** Разбор: сколько клеток пришлось пересчитать в парах покоя за прогон. */
    var restDirtyTotal = 0L
    var restFullRebuilds = 0L

    private fun applyRestPairs(rp: RestPairs) {
        val sameRp = rest === rp && restState.size == rp.other.size
        rest = rp
        if (restState.size != rp.other.size) { restState = ByteArray(rp.other.size); restValid = false }
        var maxR = 0.0
        for (vi in 0 until vertCount) { val v = verts[vi]; if (contactRadius[v] > maxR) maxR = contactRadius[v] }

        // Список задетых клеток: у кого изменился радиус, признак контура или степень.
        var dirtyN = 0
        var tooMany = false
        if (restValid && sameRp) {
            for (i in 0 until n) {
                val d = adjEnd[i] - adjStart[i]
                if (contactRadius[i] == prevRadius[i] && onBound[i] == prevBound[i] && d == prevDeg[i]) continue
                if (dirtyN == dirtyBuf.size) {
                    if (dirtyBuf.size >= n / 4) { tooMany = true; break }
                    dirtyBuf = dirtyBuf.copyOf(dirtyBuf.size * 2)
                }
                dirtyBuf[dirtyN++] = i
            }
        }
        val local = restValid && sameRp && !tooMany && !optionRestFull
        if (!local) {
            restState.fill(0)
            restTouchN = 0; restSpacingN = 0
            restFullRebuilds++
        } else {
            restDirtyTotal += dirtyN.toLong()
            // Гасим прежнее состояние только у задетых пар: и сама пара, и её зеркало.
            for (k in 0 until dirtyN) {
                val i = dirtyBuf[k]
                for (sIdx in rp.start[i] until rp.start[i + 1]) {
                    val st = restState[sIdx].toInt()
                    if (st == REST_NONE) continue
                    if (st == REST_SPACING) restSpacingN-- else restTouchN--
                    restState[sIdx] = 0; restState[rp.mirror[sIdx]] = 0
                }
            }
        }
        for (i in 0 until n) { prevRadius[i] = contactRadius[i]; prevBound[i] = onBound[i]; prevDeg[i] = adjEnd[i] - adjStart[i] }
        restValid = true
        if (maxR <= 0.0) return
        val scan: IntArray = if (local) dirtyBuf else verts
        val scanN = if (local) dirtyN else vertCount
        for (kk in 0 until scanN) {
            val i = scan[kk]
            if (!onBound[i]) continue
            for (s in rp.start[i] until rp.start[i + 1]) {
                val j = rp.other[s]
                // При локальном пересчёте пара берётся С ОБЕИХ сторон: задета может быть
                // как раз клетка с большим номером, а пара хранится у обеих.
                if (!local && j <= i) continue
                if (!onBound[j]) continue
                val rr = contactRadius[i] + contactRadius[j]
                if (rp.d2[s] >= rr * rr) continue
                var linked = false
                for (k in adjStart[i] until adjEnd[i]) if (adj[k] == j) { linked = true; break }
                if (linked) continue
                // ПАРА, КОТОРАЯ КОГДА-ЛИБО БЫЛА СВЯЗАНА, ОСВОБОЖДЕНИЯ НЕ ПОЛУЧАЕТ.
                // Кандидаты считаются по ИСХОДНОЙ позе покоя, где связь ещё цела. После
                // разрыва такая пара перестаёт быть «связанной», в позе покоя стоит
                // вплотную — и попадала в исключения, то есть столкновения выключались
                // ровно на свежем изломе. Замер: 704 из 751 порванной пары (94%).
                val st = if (rp.ever[s]) REST_SPACING else REST_TOUCH
                if (restState[s].toInt() == st) continue      // уже стоит — не считать дважды
                restState[s] = st.toByte(); restState[rp.mirror[s]] = st.toByte()
                if (st == REST_SPACING) restSpacingN++ else restTouchN++
            }
        }
    }

    /**
     * ПЕРЕСОБРАТЬ ПОД НОВУЮ ТОПОЛОГИЮ НА МЕСТЕ, не заводя объект заново.
     *
     * После разрыва меняются связи и контур, а всё остальное — массивы на клетку, буферы
     * контактов, сетка — остаётся. Новый объект на каждой пересборке заводил их заново,
     * вместе с сеткой широкой фазы, и это было заметной долей цены разрыва. Результат тот же, что у нового объекта: всё, что
     * переживает подшаг, живёт на метках прохода (stamp, pass) и к старой топологии не
     * привязано, а счётчики разбора обнуляются здесь, как обнулялись у нового.
     */
    fun reconfigure(
        conA: IntArray, conB: IntArray, conCount: Int,
        boundA: IntArray, boundB: IntArray, boundCount: Int,
        /** Внутренняя вершина каждого граничного ребра, -1 у голого. См. findPierce. */
        boundOpp: IntArray,
        restX: FloatArray, restY: FloatArray,
        /** Клетки без единой связи в ПОЛНОМ графе связей. */
        isolated: BooleanArray?,
        /** Умершие клетки в контактах не участвуют вовсе. См. cellDead в демо. */
        dead: BooleanArray?,
        /** Запечённые кандидаты касания в покое. Null — запечь по нынешним радиусам, см. build. */
        restPairs: RestPairs?,
        /** Пары, связанные на ЦЕЛОМ теле. Нужны, только если restPairs не передан. */
        everLinked: Set<Long>?,
    ) {
        var tCfg = System.nanoTime()
        fun cfgLap(k: Int) { val now = System.nanoTime(); cfgNs[k] += now - tCfg; tCfg = now }
        adjStart = IntArray(n + 1)
        adjEnd = IntArray(n)
        bndStart = IntArray(n + 1); bndEnd = IntArray(n)
        val deg = cfgDeg
        java.util.Arrays.fill(deg, 0)
        for (c in 0 until conCount) { deg[conA[c]]++; deg[conB[c]]++ }
        adjStart[0] = 0
        for (i in 0 until n) adjStart[i + 1] = adjStart[i] + deg[i]
        if (adj.size < adjStart[n]) adj = IntArray(adjStart[n] * 2)
        System.arraycopy(adjStart, 0, deg, 0, n)
        for (c in 0 until conCount) {
            adj[deg[conA[c]]++] = conB[c]
            adj[deg[conB[c]]++] = conA[c]
        }

        for (i in 0 until n) adjEnd[i] = adjStart[i + 1]

        cfgLap(0)
        java.util.Arrays.fill(onBound, false)
        for (e in 0 until boundCount) { onBound[boundA[e]] = true; onBound[boundB[e]] = true }
        // Одиночные клетки тоже участвуют в контактах, хотя ни на каком контуре
        // не лежат: связей у них нет вовсе, поэтому граничным ребром их не поймать.
        //
        // Признак приходит СНАРУЖИ и не выводится из смежности выше. Смежность
        // построена по conA/conB, а там намеренно НЕТ внутрикостных связей, и
        // «степень ноль» пометила бы всю внутренность костей — они полезли бы в
        // контакты со своим же телом. На этом уже спотыкались, когда по conA/conB
        // считали связные компоненты и получили 126 организмов вместо двух.
        if (isolated != null) for (i in 0 until n) if (isolated[i]) onBound[i] = true
        if (dead != null) for (i in 0 until n) if (dead[i]) onBound[i] = false
        var nv = 0
        for (i in 0 until n) if (onBound[i]) nv++
        if (verts.size < n) verts = IntArray(n)
        vertCount = nv
        vertSlot.fill(-1)
        run { var k = 0; for (i in 0 until n) if (onBound[i]) { vertSlot[i] = k; verts[k++] = i } }

        // Радиус контакта — половина самого длинного граничного ребра клетки,
        // с запасом. См. contactRadius: круги соседей по контуру обязаны
        // перекрываться, иначе в мембране остаётся щель.
        // Граничные рёбра каждой клетки — для поиска пробоя, см. findPierce.
        if (bndStart.size != n + 1) bndStart = IntArray(n + 1)
        java.util.Arrays.fill(bndStart, 0)
        for (e in 0 until boundCount) { bndStart[boundA[e]]++; bndStart[boundB[e]]++ }
        run {
            var acc = 0
            for (i in 0 until n) { val d = bndStart[i]; bndStart[i] = acc; acc += d }
            bndStart[n] = acc
            if (bndOther.size < acc) bndOther = IntArray(acc)
            if (bndOpp.size < acc) bndOpp = IntArray(acc)
            val fill = IntArray(n + 1)
            System.arraycopy(bndStart, 0, fill, 0, n + 1)
            for (e in 0 until boundCount) {
                bndOpp[fill[boundA[e]]] = boundOpp[e]
                bndOther[fill[boundA[e]]++] = boundB[e]
                bndOpp[fill[boundB[e]]] = boundOpp[e]
                bndOther[fill[boundB[e]]++] = boundA[e]
            }
        }
        for (i in 0 until n) bndEnd[i] = bndStart[i + 1]
        cfgLap(1)
        java.util.Arrays.fill(contactRadius, 0.0)
        for (e in 0 until boundCount) {
            val a = boundA[e]; val b = boundB[e]
            val dx = (restX[a] - restX[b]).toDouble()
            val dy = (restY[a] - restY[b]).toDouble()
            val half = 0.5 * Math.sqrt(dx * dx + dy * dy) * optionContactSeal
            if (half > contactRadius[a]) contactRadius[a] = half
            if (half > contactRadius[b]) contactRadius[b] = half
        }
        // НУЛЕВОЙ РАДИУС ПОЛОЖЕН ТОЛЬКО УКРЫТОЙ КЛЕТКЕ.
        //
        // Ноль означает «клетка со всех сторон закрыта живой тканью»: пройти к ней можно
        // только сквозь контур, поэтому собственный контакт ей не нужен. Ради этого
        // внутренние клетки из столкновений и исключены — их большинство, и на них
        // держится вся скорость контактов.
        //
        // НО РОВНО ТО ЖЕ СОСТОЯНИЕ — ни одного граничного ребра — возникает у клетки,
        // которая ПОТЕРЯЛА все связи: разрушение оторвало её от тела. Граничных рёбер у
        // неё больше нет, радиус остаётся нулевым, и клетка превращается в призрак,
        // который летит сквозь любую ткань и любую стенку, потому что для контактов её
        // не существует. Именно так помеченный игроком кусок прошёл сквозь неподвижные
        // частицы бассейна (журнал marked-log.txt, клетки #340..#342: «связей живых 0 из
        // 0, контактный радиус 0.000»).
        //
        // Укрывает клетку ЖИВАЯ СВЯЗЬ, поэтому проверка идёт по графу живых связей: в
        // reconfigure приходят только живые, списки сжимаются на разрыве.
        //
        // Радиус отломанной клетке даётся тот же, что свободной частице: она ею и стала.
        for (i in 0 until n) {
            if (contactRadius[i] > 0.0) continue
            val lone = adjEnd[i] == adjStart[i]
            if (lone || (isolated != null && isolated[i])) contactRadius[i] = radius[i] * contactScale
        }
        // У МЁРТВОЙ КЛЕТКИ КОНТАКТА НЕТ, И ЭТО НЕ ДЕЛАЕТ ЕЁ ПРИЗРАКОМ: хозяин её
        // замораживает (invMass = 0, см. applyKills). Невидимая мёртвая клетка, которая
        // продолжала бы толкаться, была бы хуже, чем ничего: игрок видел бы, как ткань
        // отлетает от пустого места.
        if (dead != null) for (i in 0 until n) if (dead[i]) contactRadius[i] = 0.0

        // Сторона ячейки — наибольший диаметр контакта ПЛЮС ЗАПАС. Меньше диаметра
        // нельзя: пара из соседних ячеек тогда могла бы не попасть в перебор 3x3.
        // Запас — чтобы список пар жил дольше одного подшага, см. bpMargin.
        var maxR = 0.0
        for (vi in 0 until vertCount) { val v = verts[vi]; if (contactRadius[v] > maxR) maxR = contactRadius[v] }
        // Отрицательный запас — только увеличить ячейку, список всё равно строить каждый
        // подшаг. Нужно, чтобы отделить влияние РАЗМЕРА ЯЧЕЙКИ от влияния того, что
        // список живёт дольше подшага: иначе не понять, из-за чего поменялась сцена.
        maxRadius = maxR
        radiusMinimum.reset(contactRadius)
        bpMargin = Math.abs(optionBpMargin) * maxR
        cellSize = maxOf(2.0 * maxR + bpMargin, 1e-6)
        bpValid = false

        // Как у нового объекта: списков этого подшага нет, счётчики разбора с нуля.
        cN = 0; pairN = 0; killN = 0; attractN = 0; pierceN = 0
        lastContacts = 0; lastToiClamps = 0; impulseAccum = 0.0
        dbgCcdTotal = 0L; dbgCcdWorst = 0.0; dbgCcdI = -1; dbgCcdJ = -1; dbgCcdMoveA = 0.0; dbgCcdMoveJ = 0.0
        dbgPair = -1L; dbgPairPush = 0.0; dbgPairPull = 0.0; dbgPairLam = 0.0

        cfgLap(2)
        applyRestPairs(restPairs ?: bakeRestPairs(n, contactRadius, restX, restY,
            everLinked ?: linkedPairs(conA, conB, conCount)))
        cfgLap(3)
    }

    /** Подключение происходит только при загрузке/сбросе; строки имеют неизменную ёмкость. */
    fun attachSurface(surface: LocalSurface) {
        adjStart = surface.start; adj = surface.adj; adjEnd = surface.adjEnd
        bndStart = surface.start; bndOther = surface.boundary
        bndOpp = surface.opposite; bndEnd = surface.boundaryEnd
    }

    /** Обновить контактную геометрию только у затронутых клеток, не сбрасывая множители. */
    fun updateSurface(surface: LocalSurface, changed: IntArray, count: Int) {
        for (k in 0 until count) {
            val v = changed[k]
            val active = surface.active[v]
            if (active != onBound[v]) {
                if (active) { vertSlot[v] = vertCount; verts[vertCount++] = v }
                else {
                    val slot = vertSlot[v]; val last = verts[--vertCount]
                    verts[slot] = last; vertSlot[last] = slot; vertSlot[v] = -1
                }
                onBound[v] = active
            }
            contactRadius[v] = surface.radius[v]
            radiusMinimum.update(v, contactRadius[v])
            maxRadius = maxOf(maxRadius, contactRadius[v])
            // reconfigure при сбросе сравнивает с последней, а не исходной поверхностью.
            prevRadius[v] = contactRadius[v]; prevBound[v] = onBound[v]
            prevDeg[v] = adjEnd[v] - adjStart[v]
        }
        // Размер ячейки не уменьшаем при разрыве; увеличение требуется только при
        // открытии более длинного ребра. Список кандидатов сразу недействителен.
        bpMargin = Math.abs(optionBpMargin) * maxRadius
        cellSize = maxOf(2.0 * maxRadius + bpMargin, 1e-6)
        bpValid = false
        val rp = rest
        for (k in 0 until count) {
            val v = changed[k]
            for (s in rp.start[v] until rp.start[v + 1]) {
                val old = restState[s].toInt()
                if (old == REST_NONE) continue
                if (old == REST_SPACING) restSpacingN-- else restTouchN--
                restState[s] = 0; restState[rp.mirror[s]] = 0
            }
        }
        for (k in 0 until count) {
            val v = changed[k]
            if (!onBound[v]) continue
            for (s in rp.start[v] until rp.start[v + 1]) {
                val j = rp.other[s]
                if (!onBound[j]) continue
                val rr = contactRadius[v] + contactRadius[j]
                if (rp.d2[s] >= rr * rr) continue
                var linked = false
                for (a in adjStart[v] until adjEnd[v]) if (adj[a] == j) { linked = true; break }
                if (linked || restState[s].toInt() != REST_NONE) continue
                val state = if (rp.ever[s]) REST_SPACING else REST_TOUCH
                restState[s] = state.toByte(); restState[rp.mirror[s]] = state.toByte()
                if (state == REST_SPACING) restSpacingN++ else restTouchN++
            }
        }
        restDirtyTotal += count
    }

    /** Разбор: смежность, контур, радиусы, пары покоя — наносекунды за прогон. */
    val cfgNs = LongArray(4)

    private val cfgDeg = IntArray(n + 1)

    /** Разбор: пара последнего maxPenetration. */
    var worstI = -1
    var worstJ = -1

    fun maxPenetration(px: DoubleArray, py: DoubleArray): Double {
        worstI = -1; worstJ = -1
        var worst = 0.0
        for (a in 0 until vertCount) {
            val i = verts[a]
            for (b in a + 1 until vertCount) {
                val j = verts[b]
                if (bonded(i, j)) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val rr = contactDistance(i, j)
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr) continue
                val pen = 1.0 - sqrt(d2) / rr
                if (pen > worst) { worst = pen; worstI = i; worstJ = j }
            }
        }
        return worst
    }

    private fun key(ix: Int, iy: Int): Long =
        ((ix + BIAS).toLong() shl 32) or ((iy + BIAS).toLong() and 0xFFFFFFFFL)

    /**
     * Растеризация отрезка в ячейки. Возвращает число ячеек, координаты в ddaX/ddaY.
     * Это и есть защита от туннелирования — обходятся ВСЕ ячейки пути, а не концы.
     */
    private fun dda(x0: Double, y0: Double, x1: Double, y1: Double): Int {
        var ix = floor(x0 / cellSize).toInt()
        var iy = floor(y0 / cellSize).toInt()
        val ex = floor(x1 / cellSize).toInt()
        val ey = floor(y1 / cellSize).toInt()
        val dx = x1 - x0
        val dy = y1 - y0
        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val adx = abs(dx); val ady = abs(dy)
        val tDX = if (adx > 1e-12) cellSize / adx else Double.MAX_VALUE
        val tDY = if (ady > 1e-12) cellSize / ady else Double.MAX_VALUE
        var tMX = if (adx > 1e-12)
            (if (dx > 0) (ix + 1) * cellSize - x0 else x0 - ix * cellSize) / adx else Double.MAX_VALUE
        var tMY = if (ady > 1e-12)
            (if (dy > 0) (iy + 1) * cellSize - y0 else y0 - iy * cellSize) / ady else Double.MAX_VALUE
        var c = 0
        ddaX[c] = ix; ddaY[c] = iy; c++
        while ((ix != ex || iy != ey) && c < DDA_MAX) {
            if (tMX < tMY) { tMX += tDX; ix += stepX } else { tMY += tDY; iy += stepY }
            ddaX[c] = ix; ddaY[c] = iy; c++
        }
        return c
    }

    /** Связаны ли клетки напрямую. Линейный поиск: степень вершины не больше восьми. */
    /**
     * Пара НЕ сталкивается: либо связана, либо перекрыта уже в позе покоя.
     *
     * Про связанные было с самого начала: соседи по решётке стоят ближе суммы
     * радиусов и были бы в вечном контакте.
     *
     * Про ПОКОЙ пришлось добавить после кирпича — тела целиком из кости. Радиус
     * контакта берётся по самому длинному граничному ребру клетки, иначе мембрану
     * не сшить; но тот же радиус накрывает и другие, более близкие соседства той
     * же клетки, если они не связаны. На теле с мягкой тканью запас был 1.62x и
     * всё сходилось, на кирпиче окна нет вовсе: в покое насчитывалось 134
     * постоянных контакта глубиной 0.0585.
     *
     * Постоянный контакт сам по себе не двигал бы тело — он симметричен. Но
     * позиционный решатель обходит контакты по Гауссу-Зейделю, порядок обхода
     * несимметричен, и за подшаг накапливается момент. Кирпич от этого крутился
     * со скоростью 0.066 рад/с из полного покоя, а с выключенными контактами
     * стоял идеально — по этому расхождению причина и нашлась.
     *
     * Пары, перекрытые в покое, — это ТКАНЬ, а не столкновение, и сталкивать их
     * не нужно: они и должны оставаться рядом. Считаются один раз при сборке.
     *
     * ПРИ ПЕРЕНОСЕ: тело растёт, и набор таких пар меняется вместе с топологией.
     * Пересчитывать вместе с contactRadius.
     */
    private fun bonded(i: Int, j: Int): Boolean {
        for (k in adjStart[i] until adjEnd[i]) if (adj[k] == j) return true
        return restTouchN > 0 && restStateOf(i, j) == REST_TOUCH
    }

    private fun pairKey(i: Int, j: Int): Long =
        (if (i < j) i else j).toLong() * 1000003L + (if (i < j) j else i).toLong()

    private fun addPair(i: Int, j: Int) {
        if (pairN >= pairA.size) {
            pairA = pairA.copyOf(pairA.size * 2)
            pairB = pairB.copyOf(pairB.size * 2)
            pairRestSlot = pairRestSlot.copyOf(pairA.size)
        }
        pairA[pairN] = i; pairB[pairN] = j; pairN++
    }

    /** Широкая фаза по свип-путям. prevX/prevY — позиция на начало подшага. */
    /**
     * Ячейки свип-пути каждой вершины — считаются ОДИН раз за подшаг.
     *
     * Растеризация шла дважды: сперва чтобы разложить вершины по сетке, потом чтобы
     * их же опросить. Путь между этими двумя проходами не меняется, так что второй
     * раз считался ровно тот же список. Пары и их порядок от этого не зависят —
     * проверено побитовыми суммами по журналам.
     */
    private var sweepStart = IntArray(0)
    private var sweepX = IntArray(0)
    private var sweepY = IntArray(0)

    /** Уехал ли кто-нибудь от места постройки дальше половины запаса. См. bpX. */
    private fun bpStale(px: DoubleArray, py: DoubleArray): Boolean {
        val lim = 0.5 * bpMargin
        val lim2 = lim * lim
        for (vi in 0 until vertCount) { val v = verts[vi];
            val dx = px[v] - bpX[v]; val dy = py[v] - bpY[v]
            if (dx * dx + dy * dy > lim2) return true
        }
        return false
    }

    private fun broadphase(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        grid.begin(vertCount)
        if (sweepStart.size < vertCount + 1) sweepStart = IntArray(maxOf(vertCount + 1, sweepStart.size * 2))
        if (sweepX.size < vertCount * 2) { sweepX = IntArray(vertCount * 2); sweepY = IntArray(vertCount * 2) }
        var sn = 0
        for (a in 0 until vertCount) {
            val v = verts[a]
            sweepStart[a] = sn
            val c = dda(qx[v], qy[v], px[v], py[v])
            if (sn + c > sweepX.size) {
                var cap = sweepX.size * 2
                while (cap < sn + c) cap *= 2
                sweepX = sweepX.copyOf(cap); sweepY = sweepY.copyOf(cap)
            }
            for (k in 0 until c) {
                sweepX[sn] = ddaX[k]; sweepY[sn] = ddaY[k]; sn++
                grid.insert(key(ddaX[k], ddaY[k]), v)
            }
        }
        sweepStart[vertCount] = sn
        pairN = 0
        for (a in 0 until vertCount) {
            val v = verts[a]
            stamp++
            mark[v] = stamp
            for (k in sweepStart[a] until sweepStart[a + 1]) {
                val cx = sweepX[k]; val cy = sweepY[k]
                for (ox in -1..1) for (oy in -1..1) {
                    var e = grid.first(key(cx + ox, cy + oy))
                    while (e >= 0) {
                        val j = grid.ids[e]
                        e = grid.next[e]
                        if (mark[j] == stamp) continue
                        mark[j] = stamp
                        if (j < v) continue           // пара берётся ровно один раз
                        if (bonded(v, j)) continue    // соседи по ткани не сталкиваются
                        addPair(v, j)
                    }
                }
            }
        }
        sortPairs()
        // Ищем только для реально сблизившихся пар. Предварительный поиск для
        // всех кандидатов тратил больше, чем экономил, на короткой записи с ударами.
        java.util.Arrays.fill(pairRestSlot, 0, pairN, -2)
    }

    /**
     * ПОРЯДОК ПАР КАНОНИЧЕН И НЕ ЗАВИСИТ ОТ СЕТКИ.
     *
     * Контакты решаются Гауссом-Зейделем по списку, то есть порядок — часть физики.
     * А складывался он из порядка обхода ячеек, и стоило поменять их СТОРОНУ, как
     * менялся и он: стенд ловил это сразу — в сцене «бассейн одиночных» дребезг за
     * шесть спокойных секунд прыгал с 4.2 до 1045.6, хотя ни одна пара не терялась
     * (проверено полным перебором, см. CT_BPCHECK). Запас у ячейки нужен, чтобы список
     * жил дольше подшага, поэтому порядок задаётся отдельно и явно: по меньшему номеру
     * клетки, потом по большему. Пар десятки, сортировка идёт только при перестройке.
     */
    private var pairKeys = LongArray(256)

    /** Текущее направление обхода контактов. См. optionCtSweepFlip. */
    private var ctBackwards = false

    private fun sortPairs() {
        if (pairN < 2) return
        if (pairKeys.size < pairN) pairKeys = LongArray(pairN * 2)
        for (p in 0 until pairN) {
            val i = pairA[p]; val j = pairB[p]
            val lo = if (i < j) i else j
            val hi = if (i < j) j else i
            pairKeys[p] = (lo.toLong() shl 32) or hi.toLong()
        }
        java.util.Arrays.sort(pairKeys, 0, pairN)
        for (p in 0 until pairN) {
            val k = pairKeys[p]
            pairA[p] = (k ushr 32).toInt()
            pairB[p] = (k and 0xFFFFFFFFL).toInt()
        }
    }

    /**
     * Обрезка подшага по времени первого контакта.
     *
     * ОБРЕЗАЕТСЯ ТОЛЬКО ВЗАИМНОЕ ДВИЖЕНИЕ, центр масс острова свой ход сохраняет.
     *
     * Прежняя версия откатывала каждую клетку к её собственному toi. Это откат и
     * общего переноса пары, а updateVelocities превращает откат в скорость — то есть
     * CCD отнимал у пары импульс, которого у неё никто не забирал. Замер по стадиям на
     * решётке без среды: все стадии давали dP ровно ноль, кроме этой — 26.7, после
     * четырёх срабатываний на втором кадре. Этого хватило, чтобы тело уехало на 13
     * связей.
     *
     * Остров — клетки, связанные опасными парами. После отката каждому острову
     * возвращается отнятый у него импульс, поровну на единицу массы. Проскоку это не
     * мешает: общий сдвиг всех клеток острова взаимных положений не меняет, и в
     * конце подшага пара стоит ровно там же, где стояла бы при старом откате.
     */
    private fun ccdClamp(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray,
                         invMass: DoubleArray) {
        lastToiClamps = 0
        if (pairN == 0) return
        var hit = false
        for (p in 0 until pairN) {
            val a = pairA[p]; val j = pairB[p]
            val d0x = qx[a] - qx[j]; val d0y = qy[a] - qy[j]
            val dvx = (px[a] - qx[a]) - (px[j] - qx[j])
            val dvy = (py[a] - qy[a]) - (py[j] - qy[j])
            val rc = ccdCore * (contactRadius[a] + contactRadius[j])

            // Достаточный критерий пропуска — путь короче ЗАЗОРА до ядра.
            // Полный контактный диаметр здесь неверен: при начальном зазоре 0.1
            // центры могут поменяться местами, пройдя всего 0.9 диаметра.
            val move2 = dvx * dvx + dvy * dvy
            val gate = optionCcdGate * (contactRadius[a] + contactRadius[j])
            if (move2 <= gate * gate) {
                // Дальняя пара: (rc + |dv|)^2 <= 2*(rc^2 + |dv|^2).
                // Берём более свободную верхнюю оценку с запасом от округления;
                // если она уже не достигает пары, прежний sqrt тоже отверг бы её.
                if (d0x * d0x + d0y * d0y > 4.0 * (rc * rc + move2)) continue
                val reach = rc + sqrt(move2)
                if (d0x * d0x + d0y * d0y >= reach * reach) continue
            }
            if (move2 > dbgCcdWorst * dbgCcdWorst * rc * rc) {
                dbgCcdWorst = sqrt(move2) / rc; dbgCcdI = a; dbgCcdJ = j
                dbgCcdMoveA = sqrt((px[a] - qx[a]) * (px[a] - qx[a]) + (py[a] - qy[a]) * (py[a] - qy[a]))
                dbgCcdMoveJ = sqrt((px[j] - qx[j]) * (px[j] - qx[j]) + (py[j] - qy[j]) * (py[j] - qy[j]))
            }

            val c = d0x * d0x + d0y * d0y - rc * rc
            if (c <= 0) continue                       // уже внутри ядра — дело решателя
            val aa = dvx * dvx + dvy * dvy
            if (aa < 1e-18) continue
            val bb = 2.0 * (d0x * dvx + d0y * dvy)
            if (bb >= 0) continue                      // расходятся
            val disc = bb * bb - 4.0 * aa * c
            if (disc < 0) continue
            val t = (-bb - sqrt(disc)) / (2.0 * aa)
            if (t < 0 || t >= 1) continue
            if (!hit) {
                // До первого пересечения рабочие массивы никому не нужны.
                // В покое пропускаем записи по всей границе на каждом проходе CCD.
                for (vi in 0 until vertCount) { val v = verts[vi]; toi[v] = 1.0; ccdParent[v] = v }
                hit = true
            }
            if (t < toi[a]) toi[a] = t
            if (t < toi[j]) toi[j] = t
            val ra = ccdFind(a); val rj = ccdFind(j)
            if (ra != rj) ccdParent[ra] = rj
        }
        if (!hit) return
        var clamps = 0
        for (vi in 0 until vertCount) { val v = verts[vi];
            if (toi[v] >= 1.0) continue
            val r = ccdFind(v)
            islM[r] = 0.0; islX[r] = 0.0; islY[r] = 0.0
        }
        for (vi in 0 until vertCount) { val v = verts[vi];
            val tt = toi[v]
            if (tt >= 1.0) continue
            val ex = (px[v] - qx[v]) * (1.0 - tt)
            val ey = (py[v] - qy[v]) * (1.0 - tt)
            px[v] -= ex; py[v] -= ey
            clamps++
            if (invMass[v] <= 0.0) continue
            val m = 1.0 / invMass[v]
            val r = ccdFind(v)
            islM[r] += m; islX[r] += m * ex; islY[r] += m * ey
        }
        if (clamps > 0) for (vi in 0 until vertCount) { val v = verts[vi];
            if (toi[v] >= 1.0 || invMass[v] <= 0.0) continue
            val r = ccdFind(v)
            val mm = islM[r]
            if (mm <= 0.0) continue
            px[v] += islX[r] / mm; py[v] += islY[r] / mm
        }
        lastToiClamps = clamps
        dbgCcdTotal += clamps
    }

    private fun ccdFind(v: Int): Int {
        var r = v
        while (ccdParent[r] != r) r = ccdParent[r]
        var x = v
        while (ccdParent[x] != r) { val nx = ccdParent[x]; ccdParent[x] = r; x = nx }
        return r
    }

    private val passCache = ContactPassCache()

    private fun buildContacts(px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray, continuing: Boolean) {
        cN = 0
        for (p in 0 until pairN) {
            val i = pairA[p]; val j = pairB[p]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d2 = dx * dx + dy * dy
            val rr0 = contactRadius[i] + contactRadius[j]
            if (d2 >= rr0 * rr0) continue
            val rr = pairContactDistance(p, rr0)
            if (d2 >= rr * rr) continue
            val d = sqrt(d2)
            val nx: Double; val ny: Double
            if (d > 1e-12) { nx = dx / d; ny = dy / d } else { nx = 1.0; ny = 0.0 }
            if (cN >= cI.size) {
                cRR = cRR.copyOf(cRR.size * 2)
                cI = cI.copyOf(cI.size * 2); cJ = cJ.copyOf(cJ.size * 2)
                cNx = cNx.copyOf(cNx.size * 2); cNy = cNy.copyOf(cNy.size * 2)
                cVn = cVn.copyOf(cVn.size * 2); cLam = cLam.copyOf(cLam.size * 2)
                cLamTot = cLamTot.copyOf(cLamTot.size * 2); cLamInit = cLamInit.copyOf(cLamInit.size * 2)
                cAx = cAx.copyOf(cAx.size * 2); cAy = cAy.copyOf(cAy.size * 2)

            }
            cI[cN] = i; cJ[cN] = j; cNx[cN] = nx; cNy[cN] = ny; cRR[cN] = rr
            // Скорость сближения снимается ДО позиционного решателя: именно она задаёт
            // отскок. Возьми её после — и энергия появится из воздуха.
            cVn[cN] = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
            cLam[cN] = 0.0
            cLamTot[cN] = if (optionXpbdWarmStart || optionAblegacy) lam.getOrDefault(pairKey(i, j), 0.0) else 0.0
            cLamInit[cN] = -1.0
            if (continuing) passCache.restore(cN, i, j, cLamTot, cLamInit, cVn)
            cN++
        }
        lastContacts = cN
    }

    /**
     * Подготовка подшага: сетка, CCD, список контактов. Вызывается ПОСЛЕ integrate
     * и ДО позиционных ограничений.
     */
    fun prepare(
        px: DoubleArray, py: DoubleArray,
        qx: DoubleArray, qy: DoubleArray,
        vx: DoubleArray, vy: DoubleArray,
        invMass: DoubleArray,
        continuing: Boolean = false,
    ) {
        if (continuing) passCache.save(cN, cI, cJ, cLamTot, cLamInit, cVn)
        // Перенос множителя: таблица каждый подшаг собирается заново из контактов
        // прошлого подшага. Пара, выскочившая из радиуса, в неё просто не попадёт —
        // протухший множитель не доживёт до следующего касания.
        if (!optionAblegacy) lam.clear()
        if (optionXpbdWarmStart && !optionAblegacy) for (c in 0 until cN) if (cLamTot[c] > 0.0) lam[pairKey(cI[c], cJ[c])] = cLamTot[c]
        bpCalls++
        if (!bpValid || bpMargin <= 0.0 || optionBpMargin < 0.0 || bpStale(px, py)) {
            broadphase(px, py, qx, qy)
            for (vi in 0 until vertCount) { val v = verts[vi]; bpX[v] = px[v]; bpY[v] = py[v] }
            bpValid = true
            bpBuilds++
        }
        // ПРОВЕРКА ПОЛНОТЫ СПИСКА: CT_BPCHECK=1. Полный перебор — ищем пары, которые
        // ПЕРЕКРЫЛИСЬ, но в списке кандидатов их нет. Таких быть не должно ни одной.
        if (optionBpCheck) {
            for (a in 0 until vertCount) {
                val i = verts[a]
                for (b in a + 1 until vertCount) {
                    val j = verts[b]
                    if (bonded(i, j)) continue
                    val dx = px[i] - px[j]; val dy = py[i] - py[j]
                    val rr = contactDistance(i, j)
                    if (dx * dx + dy * dy >= rr * rr) continue
                    var found = false
                    for (q in 0 until pairN) if ((pairA[q] == i && pairB[q] == j) || (pairA[q] == j && pairB[q] == i)) { found = true; break }
                    if (!found) {
                        if (bpMissed == 0L) println("  CT_BPCHECK: ПРОПУЩЕНА ПАРА #%d-#%d, перекрытие %.4f".format(
                            i, j, 1.0 - Math.sqrt(dx * dx + dy * dy) / rr))
                        bpMissed++
                    }
                }
            }
        }
        ccdClamp(px, py, qx, qy, invMass)
        // Обновление контактов не должно повторять разрушение контура. Для
        // дополнительных проверок пробоя у стенда есть отдельный RB_PIERCE_ITER.
        if (optionPierceTear && !continuing) findPierce(px, py, qx, qy)
        buildContacts(px, py, vx, vy, continuing)
    }

    /**
     * Позиционное разрешение. Жёсткое (податливость ноль): проникать граница не должна.
     * Ставится ПОСЛЕ projectBone — иначе проекция кости затрёт результат, и кость
     * будет проходить сквозь тело.
     */
    /**
     * КОНТАКТ НА ЖЁСТКОЙ КОСТИ РЕШАЕТСЯ КАК КОНТАКТ ТВЁРДОГО ТЕЛА.
     *
     * Иначе контакт вдевятеро слабее нужного, и это арифметика, а не ощущение.
     * Поправка считается по invMass ОДНОЙ клетки, а projectBone на следующем
     * подшаге подгоняет весь кластер под жёсткую позу: от поправки в каждой клетке
     * остаётся примерно k/N, где k это число касающихся клеток, а N размер
     * кластера. При десяти касаниях на девяносто клеток теряется девять десятых.
     *
     * Наблюдалось это на телах ЦЕЛИКОМ ИЗ КОСТИ: одиночный таран проходил чисто,
     * а после четырёх подряд тела начинали проваливаться друг в друга — 2.38 связи
     * насквозь при проникновении 0.871. У мягкой ткани такого нет, потому что там
     * ответом служит разрыв, а кость порваться не может.
     *
     * Правильно — считать сопротивление ВСЕГО кластера. Сила в точке на расстоянии
     * r от центра тела ещё и крутит, поэтому эффективная обратная масса вдоль
     * нормали равна 1/M + (r x n)^2/I. Поправка после этого разносится по кластеру
     * жёстко: поступательная часть всем поровну, вращательная по радиусу. Поза при
     * этом не нарушается, и проекции нечего исправлять.
     *
     * Зовётся каждый подшаг перед решателем: центр и момент инерции меняются с
     * движением тела, а кластеров десятки, так что это дёшево.
     */
    fun updateBones(px: DoubleArray, py: DoubleArray, invMass: DoubleArray,
                    boneOfArg: IntArray?, ids: Array<IntArray>) {
        boneOf = boneOfArg
        boneIds = ids
        if (boneOfArg == null || ids.isEmpty()) return
        if (boneM.size != ids.size) {
            boneM = DoubleArray(ids.size); boneCx = DoubleArray(ids.size)
            boneCy = DoubleArray(ids.size); boneI = DoubleArray(ids.size)
        }
        if (cN == 0) return
        for (b in ids.indices) {
            var m = 0.0; var cx = 0.0; var cy = 0.0
            for (k in ids[b]) {
                if (invMass[k] <= 0.0) continue
                val w = 1.0 / invMass[k]
                m += w; cx += w * px[k]; cy += w * py[k]
            }
            boneM[b] = m
            if (m <= 0.0) { boneI[b] = 0.0; continue }
            cx /= m; cy /= m
            boneCx[b] = cx; boneCy[b] = cy
            var inert = 0.0
            for (k in ids[b]) {
                if (invMass[k] <= 0.0) continue
                val rx = px[k] - cx; val ry = py[k] - cy
                inert += (rx * rx + ry * ry) / invMass[k]
            }
            boneI[b] = inert
        }
    }

    /** Эффективная обратная масса точки i вдоль нормали, с учётом жёсткой кости. */
    private fun effInvMass(i: Int, nx: Double, ny: Double, px: DoubleArray, py: DoubleArray,
                           invMass: DoubleArray): Double {
        val bo = boneOf ?: return invMass[i]
        val b = bo[i]
        if (b < 0 || b >= boneM.size || boneM[b] <= 0.0) return invMass[i]
        val rx = px[i] - boneCx[b]; val ry = py[i] - boneCy[b]
        val rn = rx * ny - ry * nx
        val rot = if (boneI[b] > 1e-18) rn * rn / boneI[b] else 0.0
        return 1.0 / boneM[b] + rot
    }

    private val rigidBatch = RigidContactBatch()

    /**
     * КОНТАКТ КАК СИЛА: пружина, демпфер и трение. Один канал вместо трёх.
     *
     * ЗАЧЕМ ЭТО ЗАМЕНИЛО ЖЁСТКУЮ ПРОЕКЦИЮ. Раньше контакт состоял из трёх
     * несогласованных частей: жёсткая позиционная проекция, отдельный скоростной
     * отскок и отдельное разгребание проникновения. У такой связки нет функции
     * энергии, то есть нет величины, обязанной убывать, и потому нет и покоя.
     * В набитой ёмкости это давало вечное дрожание, и ни одна локальная поправка
     * его не снимала — перепробовано пять штук, все описаны в истории правок.
     *
     * У пружины с демпфером функция энергии есть: кинетическая плюс k*d*d/2.
     * Пружина её только перекладывает, демпфер и трение только отнимают, а
     * отсечение по нулю запрещает притяжение. Значит куча ОБЯЗАНА осесть.
     *
     * КАК ЗАДАНЫ КОЭФФИЦИЕНТЫ. Не в ньютонах на метр, а через собственную частоту
     * пары, потому что только она и определяет устойчивость явной схемы. Для
     * приведённой массы mu = 1/w жёсткость k = mu*omega^2, демпфер c = 2*z*mu*omega.
     * В коде хранится безразмерное произведение omega*h, и тогда изменение
     * ПЕРЕКРЫТИЯ за подшаг выходит без единиц вовсе:
     *
     *      w * dl = (omega*h)^2 * d  -  2*z*(omega*h) * h*vn
     *
     * Отсюда сразу читаются оба предела. Устойчивость явной схемы требует
     * omega*h < 2. Максимальное перекрытие на ударе со скоростью v равно v/omega,
     * то есть жёсткость выбирается не на глаз, а из допустимого продавливания.
     *
     * ОТСКОК ЗДЕСЬ НЕ ОТДЕЛЬНАЯ СТАДИЯ, а следствие затухания:
     *
     *      e = exp(-z*pi/sqrt(1 - z*z))
     *
     * При z = 0.59 это даёт e = 0.1, прежнее значение CONTACT_RESTITUTION.
     *
     * ПОЧЕМУ СИЛА ПРИКЛАДЫВАЕТСЯ К ПОЗИЦИЯМ, А НЕ К СКОРОСТЯМ. Решатель тела
     * позиционный, и updateVelocities всё равно пересчитает скорости из позиций
     * как (px - prevX)/h. Поэтому сдвиг w*F*h^2 — это ровно полушаговый Эйлер,
     * записанный в тех единицах, в которых работает остальной решатель, и
     * скорость из него получится сама. Так же здесь сделана и гравитация.
     */
    /**
     * [scale] — доля силы, прикладываемая за один заход общего цикла.
     *
     * Связи это ПРОЕКЦИИ: повторение их сближает к решению. Контакт это СИЛА: повторив
     * её K раз, получишь силу в K раз больше. Замер на решётке подтвердил прямо —
     * скорость росла 0.096, 0.25, 0.92, 6.25 при одном, двух, четырёх и восьми
     * заходах. Поэтому за заход прикладывается 1/K силы, и сумма за подшаг остаётся
     * прежней, но связи успевают ответить между заходами.
     */
    fun solveContacts(
        px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray,
        invMass: DoubleArray, h: Double, scale: Double = 1.0,
        /**
         * Прикладывать силу к СКОРОСТИ, а не к позициям.
         *
         * Это и есть лекарство от вечной дрожи нагруженного контакта. Сила, попавшая
         * в позиции ПОСЛЕ проекций, до конца подшага никем не исправляется и целиком
         * уходит в скорость: updateVelocities считает (px - prevX) / h. Замер на
         * решётке 10x10 в покое: 0.0956 клетки за тик и ровно столько же через
         * тысячу двести тиков, то есть предельный цикл, а не затухание.
         *
         * Приложенная к скорости ДО интегрирования, она ведёт себя как гравитация:
         * шаг сдвигает клетку, связи в том же подшаге возвращают её назад, и
         * восстановленная скорость выходит нулём. Равновесие становится настоящим.
         */
        toVelocity: Boolean = false,
    ) {
        this.toVelocity = toVelocity
        this.velH = h
        pass++
        frcN = 0
        killN = 0
        // СКОЛЬКО КОНТАКТОВ У КЛЕТКИ — считается отдельным проходом, до сил.
        // Зачем, см. деление в основном цикле ниже.
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val rr = cRR[c]
            if (dx * dx + dy * dy >= rr * rr) continue
            bumpZ(i); bumpZ(j)
        }
        pass++
        val kSpring = optionContactOmegaH * optionContactOmegaH
        val cDamp = 2.0 * CONTACT_DAMPING * optionContactOmegaH * h
        val splitScale = (optionContactOmegaH / CONTACT_SPLIT_LIMIT) * (optionContactOmegaH / CONTACT_SPLIT_LIMIT)
        // НАПРАВЛЕНИЕ ОБХОДА КОНТАКТОВ ЧЕРЕДУЕТСЯ — по той же причине, что и у связей,
        // см. SWEEP_FLIP в DemoSession. Список решается Гауссом-Зейделем, поэтому первая
        // пара всегда получает нетронутую геометрию, а последняя — уже исправленную, и
        // перекос копится в одну сторону. У связей это крутило тело, здесь — трясёт кучу.
        if (optionCtSweepFlip) ctBackwards = !ctBackwards
        val step = if (ctBackwards) -1 else 1
        var cc0 = if (ctBackwards) cN - 1 else 0
        var left = cN
        while (left > 0) {
            left--
            val c = cc0
            cc0 += step
            val i = cI[c]; val j = cJ[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            val rr = cRR[c]
            val nx: Double; val ny: Double; val cc: Double
            if (d < 1e-12) { nx = cNx[c]; ny = cNy[c]; cc = -rr }
            else { nx = dx / d; ny = dy / d; cc = d - rr }
            cNx[c] = nx; cNy[c] = ny
            cLam[c] = 0.0
            cAx[c] = 0.0; cAy[c] = 0.0
            // Пара разошлась — накопленный множитель обязан обнулиться, иначе при
            // следующем касании он отработает как разжатая пружина.
            if (cc >= 0) {
                if (XPBD) { cLamTot[c] = 0.0; cLamInit[c] = 0.0; if (optionAblegacy) lam.remove(pairKey(i, j)) }
                continue
            }
            // Сопротивление считается по ТЕЛУ, а не по клетке: для клетки в жёсткой
            // кости это масса всего кластера плюс вклад вращения. См. updateBones.
            val wi = effInvMass(i, nx, ny, px, py, invMass)
            val wj = effInvMass(j, nx, ny, px, py, invMass)
            val w = wi + wj
            if (w <= 0) continue

            // ЦЕНТР ВНУТРИ ЧУЖОГО РАДИУСА — заявка на смерть. См. killList.
            if (killOnDeep && d < killDepth * maxOf(contactRadius[i], contactRadius[j])) {
                requestKill(i, j)
            }

            val rvx = vx[i] - vx[j]
            val rvy = vy[i] - vy[j]
            val vn = rvx * nx + rvy * ny

            // Пружина плюс демпфер, сразу в единицах перекрытия за подшаг.
            if (XPBD) {
                // КОНТАКТ КАК ОГРАНИЧЕНИЕ С НАКОПЛЕНИЕМ МНОЖИТЕЛЯ.
                //
                // Сила в равновесии не обнуляется никогда: нагруженный контакт давит
                // каждый подшаг, и его вклад целиком уходит в скорость. Замер: остаток
                // строго пропорционален жёсткости (0.0956, 0.0374, 0.0183, 0.0057 при
                // omega*h 0.2, 0.1, 0.05, 0.02), то есть это и есть сама сила.
                //
                // У ограничения с накопленным множителем всё иначе. Множитель растёт,
                // пока нарушение не уравновешено податливостью, после чего ПРИРАЩЕНИЕ
                // становится нулём — а в скорость идёт именно приращение. Равновесие
                // получается настоящим: позиции стоят, скорость ноль.
                //
                // Множитель переносится между подшагами, но в пределах, в которых он ещё
                // сила, а не клей. См. optionXpbdWarmStart.
                // ПОДАТЛИВОСТЬ НА ПАРУ, А НЕ ОДНА НА ВСЕХ. См. optionContactOmegaPair.
                //
                // Глобальная податливость означает одну и ту же ЖЁСТКОСТЬ для любой пары,
                // а собственная частота пары есть sqrt(k/m) — значит тяжёлая клетка
                // получает мягкий контакт, лёгкая жёсткий, и одно значение никогда не
                // годится обоим телам сразу (замер 22.09: 1e-5 лечит медузу и ломает
                // body-export). Если же взять alpha = w / omega^2, то omega у всех пар
                // одна, а поправка перестаёт зависеть от массы вовсе.
                val alphaT = if (optionContactOmegaPair > 0.0) w / (optionContactOmegaPair * optionContactOmegaPair)
                             else optionContactCompliance / (h * h)
                var l = cLamTot[c]
                if (optionAblegacy) cLamInit[c] = 0.0
                if (cLamInit[c] < 0.0) {
                    // ПОТОЛОК: перенесённое не больше, чем держит нынешнее перекрытие.
                    val cap = -cc / alphaT
                    if (l > cap) l = cap
                    cLamInit[c] = l
                }
                var dLam = (-cc - alphaT * l) / (w + alphaT)
                if (dLam < 0.0) attractN++
                // ПОЛ: забрать назад можно только толчок ЭТОГО подшага. Перенесённая
                // часть уже отработала в прошлых подшагах, забрать её — значит притянуть.
                val floor = cLamInit[c]
                if (l + dLam < floor) dLam = floor - l
                if (dbgPair >= 0 && pairKey(i, j) == dbgPair) {
                    if (dLam < 0.0) dbgPairPull += -dLam * w else dbgPairPush += dLam * w
                    dbgPairLam = l + dLam
                }
                l += dLam
                cLamTot[c] = l
                if (optionAblegacy) lam.put(pairKey(i, j), l)
                cLam[c] = if (dLam > 0.0) dLam else 0.0
                impulseAccum += cLam[c]
                if (dLam != 0.0) {
                    cAx[c] = dLam * nx; cAy[c] = dLam * ny
                    addForce(i, cAx[c], cAy[c], wi)
                    addForce(j, -cAx[c], -cAy[c], wj)
                }
                continue
            }

            // ДЕЛЕНИЕ НА ЧИСЛО КОНТАКТОВ — ЭТО РАСЩЕПЛЕНИЕ МАССЫ, а не ослабление
            // ради красоты. Предел устойчивости явной схемы стоит НА КЛЕТКУ: у клетки
            // с z соседями суммарная жёсткость вырастает в z раз, собственная частота
            // в корень из z, а затухание в z. При шести соседях затухание вылетает за
            // предел вчетверо, и куча идёт вразнос — первый замер силового контакта
            // дал 6-8 клеток за тик, то есть упор в потолок скорости.
            //
            // Делитель ОДИН НА ПАРУ, наибольший из двух концов. Так вклад в i и в j
            // остаётся равным и противоположным, и импульс пары сохраняется точно.
            // Брать каждому свой делитель нельзя: это разъедет импульс и даст тягу.
            val zi = zOf(i); val zj = zOf(j)
            val zMax = if (zi > zj) zi else zj
            // Делитель НЕ равен числу контактов: это был бы запас в корень из z.
            // Собственная частота клетки с z контактами равна корень(z/делитель)
            // умножить на optionContactOmegaH, и держать её надо не на исходном
            // значении, а на пределе устойчивости CONTACT_SPLIT_LIMIT.
            var z = zMax * splitScale
            if (z < 1.0) z = 1.0
            // ГЛУБИНА В ПРУЖИНЕ НАСЫЩАЕТСЯ. Дальше порога сила перестаёт расти, и
            // запасённая энергия растёт с глубиной ЛИНЕЙНО, а не квадратично. Без
            // этого рывок мышью за кость вдавливает тело глубоко, пружина копит
            // энергию руки и выстреливает: замер дал 262 срабатывания потолка
            // скорости, а он импульс не сохраняет. Демпфер при этом работает в
            // полную силу и разряд гасит.
            var depth = -cc
            val dCap = CONTACT_DEPTH_CAP * meanLink
            if (depth > dCap) depth = dCap
            val pull = kSpring * depth - cDamp * vn
            if (pull <= 0.0) continue         // растягивать контакт нельзя никогда
            val dl = scale * pull / (w * z)
            cLam[c] = dl
            impulseAccum += dl

            var ax = dl * nx; var ay = dl * ny

            // ТРЕНИЕ ТОЖЕ СИЛОЙ, В ТОМ ЖЕ ПРОХОДЕ. Оно только отнимает: больше, чем
            // нужно на обнуление касательной скорости пары, не берётся никогда, и
            // сверх того ограничено конусом Кулона по нормальной части.
            if (friction > 0.0) {
                val tvx = rvx - vn * nx
                val tvy = rvy - vn * ny
                val tl = sqrt(tvx * tvx + tvy * tvy)
                if (tl > 1e-12) {
                    var dt = scale * tl * h / w    // ровно до остановки скольжения
                    val coulomb = friction * dl
                    if (dt > coulomb) dt = coulomb
                    ax -= dt * tvx / tl; ay -= dt * tvy / tl
                }
            }

            cAx[c] = ax; cAy[c] = ay
            addForce(i, ax, ay, wi)
            addForce(j, -ax, -ay, wj)
        }
        applyForces(px, py, invMass, vx, vy)
    }

    /** Номер жёсткого кластера клетки, или -1. */
    private fun boneIdx(i: Int): Int {
        val bo = boneOf ?: return -1
        val b = bo[i]
        if (b < 0 || b >= boneM.size || boneM[b] <= 0.0) return -1
        return b
    }

    private fun bumpZ(i: Int) {
        val b = boneIdx(i)
        if (b < 0) {
            if (frcStamp[i] != pass) { frcStamp[i] = pass; frcZ[i] = 0 }
            frcZ[i]++
            return
        }
        if (boneZ.size < boneM.size) {
            boneZ = IntArray(boneM.size); boneZStamp = IntArray(boneM.size) { -1 }
        }
        if (boneZStamp[b] != pass) { boneZStamp[b] = pass; boneZ[b] = 0 }
        boneZ[b]++
    }

    private fun zOf(i: Int): Int {
        val b = boneIdx(i)
        val z = if (b < 0) frcZ[i] else boneZ[b]
        return if (z < 1) 1 else z
    }

    /**
     * СНЯТИЕ СКОРОСТИ СБЛИЖЕНИЯ у перекрытых пар. Зовётся ПОСЛЕ updateVelocities.
     *
     * Позиционное ограничение теперь податливое, и быстрый удар оно только сминает,
     * а не останавливает: проверки показали 369 связей насквозь. Останавливает удар
     * именно это — приведение скорости сближения к нулю.
     *
     * Отскока тут нет намеренно: цель ровно ноль, ни клеткой больше. Поэтому проход
     * только ОТНИМАЕТ и энергии добавить не может. В покое позиционная часть уже
     * сошлась, скорость сближения там нулевая, и проход не делает ничего — этим он и
     * отличается от прежнего скоростного отскока, который в покое качал сам себя.
     */
    fun solveVelocityNoApproach(vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray) {
        for (c in 0 until cN) {
            if (cLam[c] <= 0.0) continue
            val i = cI[c]; val j = cJ[c]
            val nx = cNx[c]; val ny = cNy[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w <= 0.0) continue
            val vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
            if (vn >= 0.0) continue
            val p = -vn / w
            vx[i] += p * nx * wi; vy[i] += p * ny * wi
            vx[j] -= p * nx * wj; vy[j] -= p * ny * wj
        }
    }

    /**
     * Кого из пары записать в покойники.
     *
     * Умирает ОДНА клетка, как и задумано: две смерти на одно событие проделали бы в
     * ткани дыру вдвое шире нужного. Костную клетку не трогаем — кости здесь
     * бессмертны, и если внутрь зашла кость, умрёт мягкая. Если обе костные, не
     * умирает никто, иначе жёсткая поза кластера развалится на ровном месте.
     *
     * При прочих равных выбор идёт по номеру, чтобы прогон был повторяем.
     */
    /**
     * ОТСКОК: вернуть паре долю той скорости, с которой она РЕАЛЬНО сближалась.
     *
     * Зовётся ПОСЛЕ updateVelocities. Позиционная часть удар уже погасила целиком,
     * поэтому без этого прохода отскок выходит ровно нулевым — замер на паре клеток
     * в лоб давал 0.000 на всех скоростях от 0.1 до 4 клеток за тик, при заданных
     * 0.10. Выглядит это как слипание: пара останавливается друг об друга и остаётся
     * висеть, а трение держит её ещё и вбок.
     *
     * ТОЛЬКО ТАМ, ГДЕ БЫЛ УДАР. Цель считается из cVn, снятой ДО решателя, и проход
     * пропускает пары, которые не сближались. Раньше такой же проход брал целью ноль
     * для любой пары и в плотной куче работал храповиком, разгоняя её сам. Теперь
     * этого не может быть по двум причинам: покоящаяся пара сюда не попадает вовсе,
     * а у сошедшегося позиционного решателя её скорость расхождения и так нулевая.
     *
     * Энергии не добавляет: при доле не больше единицы возвращается меньше, чем было
     * принесено.
     */
    fun solveRestitution(px: DoubleArray, py: DoubleArray,
                         vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray) {
        if (restitution <= 0.0) return
        rigidBatch.begin(boneM.size)
        for (c in 0 until cN) {
            // Отбор по СКОРОСТИ СБЛИЖЕНИЯ, а не по множителю. Множитель отражает
            // только последний заход решателя, а к последнему заходу позиционная часть
            // уже сошлась и приращение нулевое — удар при этом был, и отскок положен.
            // С отбором по множителю частица в кость отскакивала на 0.04 вместо 0.10
            // на медленном подлёте, то есть прилипала.
            //
            // ПОРОГА ПО СКОРОСТИ ТОЖЕ НЕТ, и он проверялся. Решётка без среды уезжала на
            // 13.2 связи, и выглядело это как раскачка покоящихся пар отскоком. Замер
            // импульса по стадиям показал другое: все стадии, включая этот проход, дают
            // ровно ноль, а весь импульс внёс CCD четырьмя обрезками на втором кадре.
            // После исправления CCD порог 0 и порог 0.2 клетки за тик дают одну и ту же
            // решётку до четвёртого знака. Отскок на дрожи покоя ничтожен сам: он равен
            // доле restitution от скорости сближения, а не добавляется к ней.
            val approach = cVn[c]
            if (approach >= 0.0) continue
            val i = cI[c]; val j = cJ[c]
            val nx = cNx[c]; val ny = cNy[c]
            // МАССА КОСТИ — МАССА ВСЕГО КЛАСТЕРА. Замер на кирпиче: частица в кость
            // отскакивала на 0.00-0.04 вместо 0.10, то есть прилипала. Проход считал
            // кость одной клеткой, делил толчок пополам и половину отдавал клетке
            // кости, а её проекция на следующем подшаге эту половину стирала.
            val wi = effInvMass(i, nx, ny, px, py, invMass)
            val wj = effInvMass(j, nx, ny, px, py, invMass)
            val w = wi + wj
            if (w <= 0.0) continue
            val bi = boneIdx(i); val bj = boneIdx(j)
            val vix = vx[i] + if (bi >= 0) rigidBatch.velocityX(bi, py[i] - boneCy[bi], boneM[bi], boneI[bi]) else 0.0
            val viy = vy[i] + if (bi >= 0) rigidBatch.velocityY(bi, px[i] - boneCx[bi], boneM[bi], boneI[bi]) else 0.0
            val vjx = vx[j] + if (bj >= 0) rigidBatch.velocityX(bj, py[j] - boneCy[bj], boneM[bj], boneI[bj]) else 0.0
            val vjy = vy[j] + if (bj >= 0) rigidBatch.velocityY(bj, px[j] - boneCx[bj], boneM[bj], boneI[bj]) else 0.0
            val vn = (vix - vjx) * nx + (viy - vjy) * ny
            val dvn = -restitution * approach - vn
            if (dvn <= 0.0) continue
            val p = dvn / w
            if (!applyRigidVel(i, p * nx, p * ny, px, py, vx, vy, invMass)) {
                vx[i] += p * nx * invMass[i]; vy[i] += p * ny * invMass[i]
            }
            if (!applyRigidVel(j, -p * nx, -p * ny, px, py, vx, vy, invMass)) {
                vx[j] -= p * nx * invMass[j]; vy[j] -= p * ny * invMass[j]
            }
        }
        if (boneOf != null) rigidBatch.apply(boneIds, boneM, boneI,
            boneCx, boneCy, px, py, invMass, vx, vy, positions = false)
    }

    /**
     * То же, что позиционный проход, но для СКОРОСТЕЙ: толчок в клетку кости раздаётся по
     * всему кластеру как жёсткое движение, поступательное плюс вращательное. Отдать
     * его одной клетке нельзя — проекция кости вернёт её на позу и толчок пропадёт.
     */
    private fun applyRigidVel(i: Int, jx: Double, jy: Double, px: DoubleArray, py: DoubleArray,
                              vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray): Boolean {
        val b = boneIdx(i)
        if (b < 0) return false
        rigidBatch.add(b, px[i] - boneCx[b], py[i] - boneCy[b], jx, jy)
        return true
    }

    /**
     * ЗАЯВКА НА СМЕРТЬ: центр одной клетки зашёл внутрь радиуса другой.
     *
     * УМИРАЮТ ОБЕ. Проникновение это не вина одной из двух: в сжатом «поезде» переднюю
     * клетку вдавливает в стену вся колонна за ней. Убить только переднюю значит
     * освободить место следующей — колонна пройдёт стену по клетке за раз, и смерть
     * из запрета на проникновение превратится в его смазку.
     *
     * Клетка кости не умирает (кость бессмертна), но её партнёр умирает: иначе удар
     * костью о кость был бы единственным способом пройти сквозь тело.
     */
    private fun requestKill(i: Int, j: Int) {
        if (boneIdx(i) < 0) addKill(i)
        if (boneIdx(j) < 0) addKill(j)
    }

    private fun addKill(v: Int) {
        for (k in 0 until killN) if (killList[k] == v) return
        if (killN >= killList.size) killList = killList.copyOf(killList.size * 2)
        killList[killN++] = v
    }

    private fun addForce(k: Int, ax: Double, ay: Double, w: Double) {
        if (frcStamp[k] != pass) {
            frcStamp[k] = pass
            frcX[k] = 0.0; frcY[k] = 0.0; frcW[k] = 0.0
            if (frcN >= frcList.size) frcList = frcList.copyOf(frcList.size * 2)
            frcList[frcN++] = k
        }
        frcX[k] += ax; frcY[k] += ay
        if (w > frcW[k]) frcW[k] = w
    }

    /**
     * ВТОРОЙ ПРОХОД: сложить силы на клетке ВЕКТОРОМ и сдвинуть её один раз.
     *
     * Для сил это не приближение, а единственная верная запись. Сила это вектор,
     * силы складываются, и все они считаются от ОДНОГО состояния — того, что было
     * на входе в подшаг. Обходить контакты по очереди, двигая клетку после каждого,
     * значит считать каждую следующую силу от уже искажённого состояния.
     *
     * ЭТИМ ЖЕ ДЕРЖИТСЯ УСТОЙЧИВОСТЬ. Предел явной схемы стоит на клетку, а не на
     * контакт: у клетки в набитой ёмкости шесть соседей, и складывая их силы по
     * очереди, она получает шестикратную жёсткость и шестикратное затухание. Первый
     * замер силового контакта именно так и вышел: 6-8 клеток за тик, то есть
     * упирается в потолок скорости. Сложенные вектором силы противоположных соседей
     * взаимно гасятся, и зажатая клетка просто стоит.
     */

    /**
     * ПОЗИЦИИ НАЧАЛА ТИКА. Знак «пришла снаружи» берётся по ним, а не по началу подшага.
     *
     * ЗАЧЕМ. По началу ПОДШАГА упор ломал таран двух тел: 4.93 связи за встречное тело
     * при пределе 1.0. На ударе внутри друг друга оказываются десятки клеток сразу, они
     * пересекают контур в каком-то из подшагов, и упор выталкивает их все разом — то есть
     * работает насосом, раздвигающим тела. По началу ТИКА клетка, уже сидевшая внутри на
     * входе в тик, не считается «пришедшей снаружи», и бульк тарана упор не трогает;
     * остаётся ровно то, ради чего он сделан — одиночное пересечение контура.
     */

    private fun applyForces(px: DoubleArray, py: DoubleArray, invMass: DoubleArray,
                            pxOut: DoubleArray, pyOut: DoubleArray) {
        val cap = contactMaxStep * meanLink
        // ПОТОЛОК СЧИТАЕТСЯ ПО КЛЕТКЕ, А ПРИКЛАДЫВАЕТСЯ ПО КОНТАКТАМ, и это не
        // придирка к стилю, а сохранение импульса.
        //
        // Раньше я срезал суммарный вектор прямо на клетке. У пары это ломает
        // равенство действия и противодействия: одному концу срезали, другому нет,
        // и разница остаётся в теле как тяга. Замер на решётке 10x10 из чистой
        // мягкой ткани, брошенной в полном покое без единой внешней силы: за 20
        // секунд импульс 9.05 и снос 10.7 связи, причём все прочие стадии внесли
        // ровно 0.00000, а контакты 2862.66. Выключение контактов останавливало тело
        // намертво, выключение среды — нет.
        //
        // Теперь доля считается по клетке, как и раньше, но пара берёт МЕНЬШУЮ из
        // долей двух своих концов и урезается ею целиком. Потолок остаётся тем же
        // предохранителем, а импульс пары сходится точно.
        for (k in 0 until frcN) {
            val i = frcList[k]
            val w = frcW[i]
            var s = 1.0
            if (w > 0.0) {
                val d2 = (frcX[i] * frcX[i] + frcY[i] * frcY[i]) * w * w
                if (d2 > cap * cap) s = cap / sqrt(d2)
            }
            frcS[i] = s
            frcX[i] = 0.0; frcY[i] = 0.0
        }
        for (c in 0 until cN) {
            val ax = cAx[c]; val ay = cAy[c]
            if (ax == 0.0 && ay == 0.0) continue
            val i = cI[c]; val j = cJ[c]
            val si = frcS[i]; val sj = frcS[j]
            val s = if (si < sj) si else sj
            // XPBD хранит приложенную поправку. Потолок урезает и действие пары,
            // и её множитель, иначе следующий проход считает невыполненную работу.
            if (XPBD && s < 1.0) {
                cLamTot[c] -= (1.0 - s) * (ax * cNx[c] + ay * cNy[c])
                cLam[c] *= s
                if (optionAblegacy) lam[pairKey(i, j)] = cLamTot[c]
            }
            if (s <= 0.0) continue
            frcX[i] += ax * s; frcY[i] += ay * s
            frcX[j] -= ax * s; frcY[j] -= ay * s
            val pr = pressure
            if (pr != null) {
                val push = sqrt(ax * ax + ay * ay) * s
                pr[i] += push * invMass[i]; pr[j] += push * invMass[j]
                // Сколько раз клетку толкнули за тик — вместе с суммой толчков это даёт
                // ДАВЛЕНИЕ НА ОДИН КОНТАКТ. Клетка в толпе получает много мелких толчков,
                // раздавленная в ткани — мало, но огромных, и различить их можно только
                // так. См. PRESSURE_BURST_LINKS.
                val pn = pressureN
                if (pn != null) { pn[i]++; pn[j]++ }
            }
        }
        rigidBatch.begin(boneM.size)
        for (k in 0 until frcN) {
            val i = frcList[k]
            val ax = frcX[i]; val ay = frcY[i]
            if (ax == 0.0 && ay == 0.0) continue
            if (toVelocity) {
                // Кластер здесь не нужен: сила пойдёт в скорость, а жёсткую позу
                // восстановит projectBone уже после интегрирования.
                pxOut[i] += invMass[i] * ax / velH; pyOut[i] += invMass[i] * ay / velH
            } else {
                val b = boneIdx(i)
                if (b >= 0) rigidBatch.add(b, px[i] - boneCx[b], py[i] - boneCy[b], ax, ay)
                else { px[i] += invMass[i] * ax; py[i] += invMass[i] * ay }
            }
        }
        if (!toVelocity && boneOf != null) rigidBatch.apply(boneIds, boneM, boneI,
            boneCx, boneCy, px, py, invMass, px, py, positions = true)
    }

    companion object {
        private const val BIAS = 1 shl 20
        private const val DDA_MAX = 4096

        /**
         * Запас на сшивание мембраны. Окно у этого числа узкое с обеих сторон:
         * ниже единицы круги соседей по контуру перестают перекрываться и в
         * мембране появляется щель, выше 1.15 несвязанные граничные клетки
         * попадают в вечный ложный контакт и тело распирает само себя.
         * Замер на теле 947 клеток: длиннейшее граничное ребро 0.0364,
         * ближайшая несвязанная граничная пара 0.0418.
         */
        /**
         * СОБСТВЕННАЯ ЧАСТОТА КОНТАКТНОЙ ПАРЫ, умноженная на подшаг. Безразмерна.
         *
         * Единственное число, задающее жёсткость. Через него сразу видны оба предела:
         * устойчивость явной схемы требует значения меньше 2, а перекрытие на ударе
         * со скоростью v равно v/omega, то есть v*h/CONTACT_OMEGA_H.
         *
         * При 16 подшагах, ударе 6.4 связи за тик и этом значении расчётное
         * продавливание выходит около 0.4 средней связи. Демпфер срезает его ещё.
         */
        /** Переменная окружения CT_OMEGA — мягкость контакта на живом стенде. */
        val CONTACT_OMEGA_H = 1.0

        /**
         * СТЕПЕНЬ ЗАТУХАНИЯ КОНТАКТА. Отскок отсюда и берётся, отдельной стадии нет:
         *
         *      e = exp(-z*pi/sqrt(1 - z*z))
         *
         * 0.59 даёт e = 0.1, то есть прежнее CONTACT_RESTITUTION. Единица это
         * критическое затухание, отскока нет вовсе.
         */
        private const val CONTACT_DAMPING = 0.59

        /**
         * ГЛУБИНА НАСЫЩЕНИЯ ПРУЖИНЫ, в долях средней связи. Дальше неё сила не
         * растёт. Это предохранитель от катапульты: тело, оказавшееся внутри другого
         * разом, после разрушения или рывка мышью, иначе получило бы силу
         * пропорционально глубине в полтела.
         */
        private const val CONTACT_DEPTH_CAP = 0.35

        /**
         * ПРЕДЕЛ СОБСТВЕННОЙ ЧАСТОТЫ КЛЕТКИ ПОСЛЕ РАСЩЕПЛЕНИЯ МАССЫ, тоже на подшаг.
         *
         * Явная схема живёт до 2, поэтому здесь запас. Чем ближе к 2, тем жёстче
         * контакт в тесноте и тем меньше продавливание, но тем ближе куча к разносу.
         */
        private const val CONTACT_SPLIT_LIMIT = 1.1

        /** Контакт как ограничение с накоплением множителя вместо силы. */
        private const val XPBD = true

        /**
         * ПЕРЕНОСИТЬ ЛИ МНОЖИТЕЛЬ КОНТАКТА МЕЖДУ ПОДШАГАМИ. Нельзя — это клей.
         *
         * Внутри подшага отрицательное приращение множителя законно: итерация забирает
         * назад часть толчка, который сама же перестаралась дать. Но если множитель
         * пережил подшаг, забирать можно уже толчки ПРОШЛЫХ подшагов — и контакт тянет
         * пару к себе, пока запас не иссякнет. Контакт не умеет тянуть, это прилипание.
         *
         * Замер на журнале игрока (клетка #948 на одной связи у кости): при переносе
         * множитель копился до 0.48, контакт в покое ПРИТЯГИВАЛ на 0.016 связи за тик и
         * ни разу не толкал, а когда клетку тащили мышью, держал её полтора секунды,
         * притягивая до 0.63 связи за тик. Вдобавок множитель пары, выскочившей из
         * радиуса за один подшаг, оставался в таблице и при следующем касании клеил
         * её снова. Та же запись с выключенным переносом с тика 2845: клетка
         * отходит на следующем же тике, притяжение ноль, мышь уводит её свободно, при
         * новом касании множитель меньше 0.001.
         *
         * ПОЧЕМУ ПЕРЕНОС ВООБЩЕ БЫЛ: без него решётка в покое дрожала на 1.92
         * клетки/тик. Причина оказалась не в переносе, а в споре мембранного запаса с
         * тканью — см. CONTACT_REST_SPACING. После неё решётка стоит и без переноса.
         *
         * Вариант «перенос с потолком и полом» (true): перенесённое не больше, чем
         * держит перекрытие, а забрать назад можно только толчок своего подшага. Клея
         * нет, но и выигрыша нет. Замер стендом столкновений на медузе, наибольшее
         * проникновение (старый / без переноса / с потолком): лобовой 0.62 / 0.28 /
         * 0.61, вскользь 0.50 / 0.20 / 0.52, два толчка 0.73 / 0.33 / 0.56, куча плотнее
         * 0.52 / 0.23 / 0.54, куча в теле 0.58 / 0.37 / 0.36, бассейн 0.33 / 0.46 /
         * 0.04, ёмкость плотно 0.64 / 0.71 / 0.84, битком 0.57 / 0.78 / 0.84.
         *
         * Без переноса хуже только плотные ёмкости: давление толпы за 4 итерации на
         * подшаг не расходится. Это цена, и она записана здесь, а не спрятана.
         */
        /**
         * Запас стороны ячейки в долях наибольшего радиуса контакта. См. bpX.
         * 0 — прежнее поведение, перестройка каждый подшаг.
         */
        val BP_MARGIN = 1.0
        /** Чередовать ли направление обхода контактов. Переменная окружения CT_SWEEP. */
        val CT_SWEEP_FLIP = true
        /**
         * СЧИТАТЬ ВРЕМЯ УДАРА ДЛЯ ВСЕХ ПАР СПИСКА. CT_CCD_ALWAYS=0 возвращает прежний
         * ранний выход. См. ccdClamp.
         */
        val CCD_ALWAYS = true

        /**
         * ПОРОГ ОБРЕЗКИ в долях упора: сближение медленнее него целиком отдаётся
         * контакту, быстрее — режется по времени удара. Переменная CT_CCD_GATE.
         */
        val CCD_GATE = 1.0

        /**
         * РВАТЬ РЕБРО КОНТУРА, СКВОЗЬ КОТОРОЕ ПРОШЁЛ ЦЕНТР ЧУЖОЙ КЛЕТКИ. Переменная
         * CT_PIERCE. ПО УМОЛЧАНИЮ ВЫКЛЮЧЕНО, и вот почему.
         *
         * Сама мысль верна: посылка «контур непроницаем» неверна, потому что контакт —
         * сила, а силу можно пересилить; значит мембрана должна либо не пустить, либо
         * порваться. И стоит это почти ничего: проверка идёт по уже готовому списку пар,
         * у клетки контура не больше двух граничных рёбер, то есть до четырёх проверок
         * отрезков на пару — около тысячи за подшаг. Полный перебор (каждая вершина
         * против всех 436 рёбер) стоил бы 190 тысяч за подшаг, три миллиона за тик, вот
         * это было бы дорого. Время тика с включённым правилом не изменилось: 9.94 мс.
         *
         * НЕ РАБОТАЕТ ПРИЗНАК. «Центр пересёк отрезок ребра» стоит на уровне шума: шов
         * контакта специально держит круги соседей перекрытыми (CONTACT_SEAL), поэтому у
         * покоящегося стыка двух кусков клетка лежит почти НА линии чужого ребра и дрожит
         * около неё в последнем знаке. Проверка «тело не трогают, оно не должно ехать»
         * (порог 1.0):
         *   без ограничителей                       — 127.9, мембрана шинкует сама себя
         *   только чужой организм и заметный сдвиг  — 6.55
         *   плюс глубина захода 0.25 радиуса        — 1.37, и настоящий пробой уже НЕ ловится
         * То есть зазор между дрожанием и настоящим проникновением по этому признаку
         * слишком узок.
         *
         * ЧТО ДЕЛАТЬ ВМЕСТО. Признак должен быть не «пересёк ребро», а «центр ВНУТРИ
         * чужого контура» — он устойчив и уже реализован в scanTrapped (точка в
         * треугольнике по сетке треугольников). Правильный ход: оставить обнаружение
         * scanTrapped, но вместо того чтобы убивать клетку через десять тиков, РВАТЬ
         * мембрану в первый же тик, когда клетка найдена внутри. Тогда отлов перестаёт
         * быть костылём и становится причиной разрушения, а новой цены не появляется.
         */
        /**
         * ВКЛЮЧЕНО: ПРОБОЙ КОНТУРА РВЁТ РЕБРО. Выключить — CT_PIERCE=0.
         *
         * Правило старое, но до сих пор было непригодно, потому что считало пересечение
         * ПО СТАТИКЕ и не различало сторон. Что изменилось:
         *   - сторона берётся по внутренней вершине ребра (boundOpp), и пробоем считается
         *     только переход СНАРУЖИ ВНУТРЬ. Прежний порог по глубине больше не нужен: он
         *     и не спасал от самошинковки (1.37 при пороге 1), и терял настоящие пробои;
         *   - знак считается В СИСТЕМЕ САМОГО РЕБРА, отдельно на начало и на конец подшага.
         *     Прежний вариант брал нормаль по конечному положению и почти не срабатывал на
         *     таране, где не клетка влетает в ткань, а ТКАНЬ НАБЕГАЕТ НА КЛЕТКУ: 23
         *     срабатывания за 90 тиков против 78 после правки.
         * ЗАМЕР на таране клавишей O (медуза, осталось внутри из 1894): 651 → 473.
         * physicsCheck 30 из 30, кость 0.1015 при пределе 0.15. По времени тика
         * БЕСПЛАТНО: 10.873 против 10.898 мс без правила — проверка идёт по уже готовому
         * списку пар, у клетки контура не больше двух граничных рёбер.
         */
        val PIERCE_TEAR = true

        /** Насколько глубоко за ребро надо уйти, чтобы это считалось пробоем. */
        /**
         * ПОРОГ ПО ГЛУБИНЕ БОЛЬШЕ НЕ НУЖЕН: сторону различает направление перехода, см.
         * inward. Ноль — чистое пересечение. Оставлен знаком среды для проб.
         */
        val PIERCE_DEPTH = 0.0
        /** Пересчитывать пары покоя целиком, а не по задетым. Для сверки. См. applyRestPairs. */
        val REST_FULL = false
        /** Полная проверка списка кандидатов перебором. Дорого, только для разбора. */
        val BP_CHECK = false
        /**
         * ПЕРЕНОС МНОЖИТЕЛЯ КОНТАКТА между подшагами. Переменная окружения CT_WARM.
         *
         * Лечит дребезг заклиненной кучи, но платит импульсом из ниоткуда. Замер на
         * стенде, body-export (дребезг / |P| / проникновение):
         *   ёмкость плотно  523 361 / 4.27 / 0.743  ->  3 014 / 198.02 / 0.810
         *   ёмкость битком  413 238 / 12.50 / 0.749 -> 49 487 / 30.38 / 0.802
         *   куча в теле        537 / 71.47 / 0.046  ->    393 / 71.14 / 0.045
         * Дребезг падает в сотни раз, но |P| в ЗАМКНУТОЙ ёмкости вырастает с 4 до 198 —
         * это движение из ничего, ради которого дребезг терпеть нельзя. Выключено.
         */
        val XPBD_WARM_START = false

        /**
         * БЫВШИЕ СОСЕДИ УПИРАЮТСЯ НА РАССТОЯНИИ ПОКОЯ, а не на сумме радиусов.
         *
         * Контактный радиус нарочно больше половины связи — это запас, без которого
         * мембрана не герметична (см. «seal margin»). Значит ЛЮБЫЕ две клетки на
         * расстоянии связи перекрыты на этот запас. Пока связь жива, пара из контактов
         * исключена. Когда связь умерла, пара сталкивается — так и надо, иначе ткань
         * проходит сама сквозь себя на изломе. Но упор на полной сумме радиусов
         * расталкивал её на запас мембраны КАЖДЫЙ подшаг, а остальная ткань возвращала
         * обратно: вечный спор двух жёстких ограничений.
         *
         * Замер на решётке, треть связей убита, 40 секунд покоя. Упор на сумме радиусов:
         * без переноса множителя скорость дрожи 1.92 клетки/тик — ровно перекрытие
         * 0.025, снятое за подшаг (0.025 * 480 = 12 ед/с); с переносом дрожь гасилась,
         * но только потому, что множитель превращал контакт в клей. Упор на расстоянии
         * покоя: скорость 0.0000, снос 0.000, проникновение 0.000 — ровно как без
         * контактов вовсе.
         *
         * Ближе расстояния покоя пара по-прежнему не подойдёт, то есть сквозь бывшего
         * соседа клетка не проваливается.
         */
        const val CONTACT_REST_SPACING = true

        /**
         * Сравнение на журнале игрока: контакт В ТОЧНОСТИ как до исправления прилипания
         * (перенос множителя без потолка и пола, упор на сумме радиусов). Общий на все
         * экземпляры: контакты пересобираются после разрыва посреди тика.
         */

        /** Податливость контакта. Ноль — жёсткий, больше — мягче. */
        /**
         * ПОДАТЛИВОСТЬ КОНТАКТА. Переменная окружения CT_SOFT.
         *
         * 1e-8 — это практически жёсткий упор: клетки ведут себя как стекло. Мягкая
         * клетка — это БОЛЬШАЯ податливость: упор поддаётся, перекрытие растёт, а
         * решателю не приходится каждый подшаг выталкивать пару из глубины, откуда и
         * берётся дребезг заклиненной кучи.
         */
        val CONTACT_COMPLIANCE = 1.0e-8

        /**
         * СОБСТВЕННАЯ ЧАСТОТА КОНТАКТНОЙ ПАРЫ на подшаг, безразмерная. 0 — прежняя
         * глобальная податливость. Переменная окружения CT_OMEGA_PAIR.
         *
         * Через неё податливость становится свойством ПАРЫ: alpha = w / omega^2, где w —
         * сумма обратных эффективных масс. Тогда поправка не зависит от массы, и одно
         * число годится и медузе, и body-export. Больше — жёстче.
         *
         * СТОИТ 0.3 (решение игрока 02.10): мягкость должна быть ВИДНА. Стенд
         * `PERF_SQUASH` давит одной клеткой в другую рукой и меряет перекрытие НА ГРАНИЦЕ
         * ТИКА, то есть ровно то, что на экране (body-export):
         *   ω·h = 3   — 0.0012 упора, одна десятая процента, не разглядеть
         *   ω·h = 1   — 0.0103, процент
         *   ω·h = 0.3 — 0.1037, десять процентов, видно отчётливо
         * До этого значение по умолчанию было 0, то есть жёсткая глобальная податливость:
         * мягкость просто НЕ БЫЛА ВКЛЮЧЕНА, и жалоба «клетки как стекло» была буквальной.
         *
         * ПОЧЕМУ НЕ 0.3, КАК ХОТЕЛОСЬ. Мягкость платится проникновением, и плата растёт
         * обвально. Регрессионная проверка (27 пунктов) и глубина продавливания:
         *   ω·h = 0.3 — 10.4% продавливания, но 25/27: «тело не трогают, оно не должно
         *               ехать» даёт 176 против порога 1, перекрытие за 20 с 0.97 против 0.5
         *   ω·h = 0.5 — 4.0%, тоже 25/27
         *   ω·h = 1   — 1.0%, ВСЕ 27 проходят
         *   ω·h = 2   — 0.26%, 27 проходят
         *
         * И ВСЁ ЖЕ СТОИТ 2, А НЕ 1. При единице тело становится ПРОХОДИМЫМ: свободную
         * частицу мышью протаскивает сквозь медузу насквозь, не порвав ни одной связи
         * (стенд PERF_PUSH: внутри чужой ткани 237 кадров из 240). Рука просто сильнее
         * мягкого контакта. При 2 и выше — 0 кадров внутри. Непроницаемость важнее
         * заметной мягкости, поэтому 2.
         */
        val CONTACT_OMEGA_PAIR = 2.0

        /**
         * ЗНАКОВЫЙ УПОР КОНТУРА: потолок поправки за подшаг, в долях средней связи.
         * Ноль — выключено. См. solveContourSide.
         */

        val CONTACT_SEAL = 1.05

        /**
         * Строит CSR-смежность и список граничных вершин из рёбер границы и связей.
         *
         * Смежность берётся по ВСЕМ связям, а не только граничным: связанные клетки не
         * сталкиваются независимо от того, лежит связь на контуре или уходит внутрь.
         * Всё считает reconfigure — тем же путём идёт и пересборка после разрыва.
         */
        fun build(
            n: Int,
            conA: IntArray, conB: IntArray, conCount: Int,
            boundA: IntArray, boundB: IntArray, boundCount: Int, boundOpp: IntArray,
            radius: DoubleArray,
            contactScale: Double, ccdCore: Double, restitution: Double, friction: Double,
            /** Позиции покоя — из них берутся длины граничных рёбер. */
            restX: FloatArray, restY: FloatArray,
            meanLink: Double, contactMaxStep: Double,
            /** Клетки без единой связи в ПОЛНОМ графе связей. */
            isolated: BooleanArray? = null,
            /** Пары, связанные на ЦЕЛОМ теле: считаются один раз и живут вечно. */
            everLinked: Set<Long>? = null,
            /** Умершие клетки в контактах не участвуют вовсе. См. cellDead в демо. */
            dead: BooleanArray? = null,
            /** Запечённые кандидаты касания в покое, если тело рвётся. См. bakeRestPairs. */
            restPairs: RestPairs? = null,
            config: ContactConfig = ContactConfig(),
        ): BoundaryContacts {
            // ОСВОБОЖДАТЬ ОТ КОНТАКТА СОСЕДЕЙ ЧЕРЕЗ ОДНУ КЛЕТКУ ПРОБОВАЛИ — НЕЛЬЗЯ.
            //
            // Соблазн большой: у оторвавшегося куска из 29 клеток 19 из 31 ложной
            // перекрытой пары приходились именно на них, и с таким освобождением кусок
            // вставал намертво, 0.0000 клеток за тик вместо 1.63, обе проверки
            // оставались зелёными, самопроникновение падало с 0.306 до 0.169.
            //
            // И всё равно нельзя. Клетка, висящая на ОДНОЙ связи, окажется освобождена
            // от контакта со всеми соседями своего единственного соседа — то есть
            // провалится сквозь родную ткань. Дыра в мембране куплена за спокойствие
            // куска, и это плохая сделка.
            //
            // Причина ложных контактов не здесь, а в том, что ЛЮБАЯ позиционная
            // поправка превращается в скорость делением на крошечный подшаг.
            val bc = BoundaryContacts(
                n, IntArray(0), IntArray(n + 1), IntArray(0), radius, 1e-6,
                contactScale, ccdCore, restitution, friction, meanLink, contactMaxStep,
                DoubleArray(n), config,
            )
            bc.reconfigure(conA, conB, conCount, boundA, boundB, boundCount, boundOpp,
                restX, restY, isolated, dead, restPairs, everLinked)
            return bc
        }

        /** Пары концов связей, ключами как в pairKey. См. applyRestPairs. */
        fun linkedPairs(conA: IntArray, conB: IntArray, conCount: Int): HashSet<Long> {
            val s = HashSet<Long>(conCount * 2)
            for (c in 0 until conCount) {
                val i = conA[c]; val j = conB[c]
                s.add((if (i < j) i else j).toLong() * 1000003L + (if (i < j) j else i).toLong())
            }
            return s
        }

        private const val REST_NONE = 0
        private const val REST_TOUCH = 1
        private const val REST_SPACING = 2

        /**
         * Наибольший контактный радиус, какой клетка может получить при ЛЮБОМ контуре:
         * половина самой длинной её связи с запасом шва или собственный радиус пробника.
         * Та же формула, что в reconfigure, — сверху это оценка точная.
         */
        fun maxContactRadius(
            n: Int, linkA: IntArray, linkB: IntArray, linkCount: Int,
            radius: DoubleArray, contactScale: Double, restX: FloatArray, restY: FloatArray,
            contactSeal: Double = CONTACT_SEAL,
        ): DoubleArray {
            val r = DoubleArray(n) { radius[it] * contactScale }
            for (k in 0 until linkCount) {
                val a = linkA[k]; val b = linkB[k]
                val dx = (restX[a] - restX[b]).toDouble()
                val dy = (restY[a] - restY[b]).toDouble()
                val half = 0.5 * Math.sqrt(dx * dx + dy * dy) * contactSeal
                if (half > r[a]) r[a] = half
                if (half > r[b]) r[b] = half
            }
            return r
        }

        /**
         * ЗАПЕЧЬ КАНДИДАТОВ КАСАНИЯ В ПОКОЕ: все пары, которые перекрываются в позе покоя
         * при наибольших возможных радиусах [maxR]. Один раз на тело; какие из них касаются
         * на нынешней топологии, решает applyRestPairs.
         */
        fun bakeRestPairs(n: Int, maxR: DoubleArray, restX: FloatArray, restY: FloatArray,
                          everLinked: Set<Long>): RestPairs {
            var gmax = 0.0
            for (i in 0 until n) if (maxR[i] > gmax) gmax = maxR[i]
            if (gmax <= 0.0) return RestPairs(IntArray(n + 1), IntArray(0), IntArray(0), DoubleArray(0), DoubleArray(0), BooleanArray(0))
            val cs = 2.0 * gmax
            fun ck(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
            val cells = HashMap<Long, IntArray>(n * 2)
            val cellX = IntArray(n); val cellY = IntArray(n)
            for (i in 0 until n) {
                if (maxR[i] <= 0.0) continue
                cellX[i] = Math.floor(restX[i] / cs).toInt(); cellY[i] = Math.floor(restY[i] / cs).toInt()
                val key = ck(cellX[i], cellY[i])
                val old = cells[key]
                cells[key] = if (old == null) intArrayOf(i) else old.copyOf(old.size + 1).also { it[old.size] = i }
            }
            var pa = IntArray(1024); var pb = IntArray(1024); var pn = 0
            for (i in 0 until n) {
                if (maxR[i] <= 0.0) continue
                for (ox in -1..1) for (oy in -1..1) {
                    val bucket = cells[ck(cellX[i] + ox, cellY[i] + oy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        val dx = (restX[i] - restX[j]).toDouble()
                        val dy = (restY[i] - restY[j]).toDouble()
                        // Запас на округление: лишний кандидат безвреден, настоящее
                        // условие проверяется при сборке.
                        val lim = (maxR[i] + maxR[j]) * (1.0 + 1e-9)
                        if (dx * dx + dy * dy >= lim * lim) continue
                        if (pn == pa.size) { pa = pa.copyOf(pn * 2); pb = pb.copyOf(pn * 2) }
                        pa[pn] = i; pb[pn] = j; pn++
                    }
                }
            }
            val start = IntArray(n + 1)
            for (p in 0 until pn) { start[pa[p] + 1]++; start[pb[p] + 1]++ }
            for (i in 0 until n) start[i + 1] += start[i]
            val fill = start.copyOf()
            val other = IntArray(2 * pn); val mirror = IntArray(2 * pn)
            val d2 = DoubleArray(2 * pn); val dist = DoubleArray(2 * pn); val ever = BooleanArray(2 * pn)
            for (p in 0 until pn) {
                val i = pa[p]; val j = pb[p]
                val dx = (restX[i] - restX[j]).toDouble()
                val dy = (restY[i] - restY[j]).toDouble()
                val q = dx * dx + dy * dy
                val e = everLinked.contains(i.toLong() * 1000003L + j.toLong())
                val si = fill[i]++; val sj = fill[j]++
                other[si] = j; other[sj] = i
                mirror[si] = sj; mirror[sj] = si
                d2[si] = q; d2[sj] = q
                dist[si] = sqrt(q); dist[sj] = dist[si]
                ever[si] = e; ever[sj] = e
            }
            return RestPairs(start, other, mirror, d2, dist, ever)
        }
    }
}

/**
 * КАНДИДАТЫ КАСАНИЯ В ПОКОЕ, запечённые на тело: для каждой клетки — соседи, с которыми она
 * может перекрыться в позе покоя при каком-нибудь контуре. Записи парные: пара (i, j) лежит
 * и у i, и у j, mirror указывает на вторую половину.
 */
class RestPairs(
    val start: IntArray,
    val other: IntArray,
    val mirror: IntArray,
    /** Квадрат расстояния в позе покоя — как считала прежняя сетка, из разности float. */
    val d2: DoubleArray,
    val dist: DoubleArray,
    /** Пара связана на целом теле — после разрыва это бывшие соседи. */
    val ever: BooleanArray,
) {
    companion object {
        val EMPTY = RestPairs(IntArray(1), IntArray(0), IntArray(0), DoubleArray(0), DoubleArray(0), BooleanArray(0))
    }
}
