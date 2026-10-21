package io.github.some_example_name.physics.lab.tuning

import io.github.some_example_name.physics.lab.inspection.DemoInspector
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.probeCells
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ТОПОЛОГИЯ НЕ ДУБЛИРУЕТСЯ. Всё, что выводится из графа — связи, треугольники,
 * граница, кластеры костей и мышц, поза покоя костей, — берётся готовым из
 * DemoSession через [Topology]. Здесь только снимок для независимого SwimSolver.
 */

/** Топология тела, вынутая из настоящего DemoSession. Только чтение, общая на все потоки. */
class Topology private constructor(
    val n: Int,
    val restX: FloatArray,
    val restY: FloatArray,
    /** Радиус клетки: из него берётся масса, см. invMass в SwimSolver. */
    val restR: FloatArray,
    val conA: IntArray,
    val conB: IntArray,
    val conRest: DoubleArray,
    val conMuscle: IntArray,
    /** Связи контура в порядке ядра; SwimSolver не разрушает топологию. */
    val boundaryConstraints: IntArray,
    val triA: IntArray,
    val triB: IntArray,
    val triC: IntArray,
    val triRestArea2: FloatArray,
    val triMuscle: IntArray,
    val boundA: IntArray,
    /** Внутренняя вершина граничного ребра, -1 у голого. См. DemoSession.boundOpp. */
    val boundOpp: IntArray,
    val boundaryOrientation: ByteArray,
    val boundB: IntArray,
    val rigidBones: Array<IntArray>,
    /** Номер жёсткой кости у клетки, -1 если не в кости. */
    val boneOf: IntArray,
    val boneRestQx: Array<DoubleArray>,
    val boneRestQy: Array<DoubleArray>,
    val muscleCount: Int,
    val meanLinkLength: Float,

    /** Организм каждой частицы: связные компоненты графа связей. См. flowVX в демо. */
    val organismOf: IntArray,
    val organismCount: Int,
    val organismSize: IntArray,

    /** Клетки без единой связи — свободные частицы, см. FREE_PARTICLES в демо. */
    val isFree: BooleanArray,

    /** Открытые клетки для контактов: сумма углов может открыть клетку даже
     * при отсутствии граничного ребра. isFree для этого недостаточно. */
    val contactProbes: BooleanArray,

    /**
     * ИЗГИБ ГРАНИЧНОГО КОНТУРА: пары «через одну» вдоль границы плюс длина покоя.
     *
     * Зачем вообще. Заворот ткани, при котором НИ ОДИН треугольник не вывернулся, —
     * не редкость и не недосмотр, а принципиальная слепота знаковой площади: она
     * ЛОКАЛЬНА. Лист может сложиться пополам, и каждый треугольник в нём останется
     * положительным — так же, как сложенный лист бумаги нигде не выворачивается
     * наизнанку. Ограничение площади про это узнать не может в принципе, сколько его
     * ни ужесточай.
     *
     * Чтобы складка стала дорогой, нужно наказывать не площадь, а ИЗГИБ контура.
     *
     * ПОЧЕМУ ЭТО НЕ УГОЛ, А РАССТОЯНИЕ. Ограничение на угол при вершине потребовало бы
     * atan2 или acos на каждую вершину границы и своего вывода градиентов. Но угол при
     * вершине однозначно связан с расстоянием между её двумя соседями (стороны
     * треугольника фиксированы связями), поэтому достаточно обычного ограничения
     * РАССТОЯНИЯ между соседями через одну, с длиной покоя из позы покоя. Это тот же
     * решатель, что и для связей, ни одной новой формулы — и никаких тригонометрий.
     */
    val bendA: IntArray,
    val bendB: IntArray,
    val bendRest: DoubleArray,
) {
    val bendCount: Int get() = bendA.size
    val conCount: Int get() = conA.size
    val triCount: Int get() = triA.size
    val boundCount: Int get() = boundA.size
    val maxBoneSize: Int get() = rigidBones.maxOfOrNull { it.size } ?: 1

    companion object {
        fun load(path: String): Topology {
            val demo = DemoSession(path)
            demo.buildFromFile()
            fun <T> f(name: String): T {

                @Suppress("UNCHECKED_CAST")
                return DemoInspector.read(demo, name) as T
            }
            val body: BodyDefinition = f("body")

            // --- пары изгиба вдоль границы ---
            //
            // Обходить контур по порядку не требуется и было бы хрупко: у рваных краёв
            // и развилок порядок неоднозначен. Достаточно локального признака — вершина,
            // у которой ровно ДВА граничных ребра, лежит внутри гладкого участка контура,
            // и её соседи через одну и образуют пару. Вершины развилок (три и более
            // граничных ребра) пропускаются: там «изгиб» не определён.
            val bc: Int = f("boundCount")
            val bA: IntArray = f<IntArray>("boundA").copyOf(bc)
            val bB: IntArray = f<IntArray>("boundB").copyOf(bc)
            val nb0 = IntArray(body.count) { -1 }
            val nb1 = IntArray(body.count) { -1 }
            val deg = IntArray(body.count)
            for (e in bA.indices) {
                for ((u, v) in listOf(bA[e] to bB[e], bB[e] to bA[e])) {
                    when (deg[u]) {
                        0 -> nb0[u] = v
                        1 -> nb1[u] = v
                    }
                    deg[u]++
                }
            }
            val ba = ArrayList<Int>(); val bb = ArrayList<Int>(); val br = ArrayList<Double>()
            for (v in 0 until body.count) {
                if (deg[v] != 2) continue
                val i = nb0[v]; val j = nb1[v]
                if (i < 0 || j < 0 || i == j) continue
                val dx = (body.x[i] - body.x[j]).toDouble()
                val dy = (body.y[i] - body.y[j]).toDouble()
                ba.add(i); bb.add(j); br.add(sqrt(dx * dx + dy * dy))
            }

            return Topology(
                n = f("n"),
                restX = body.x, restY = body.y, restR = body.radius,
                // Списки живут в массивах постоянной ёмкости, длина берётся из счётчиков.
                conA = f<IntArray>("conA").copyOf(f("conCount")), conB = f<IntArray>("conB").copyOf(f("conCount")),
                conRest = f<DoubleArray>("conRest").copyOf(f("conCount")),
                conMuscle = f<IntArray>("conMuscle").copyOf(f("conCount")),
                boundaryConstraints = (0 until f<Int>("conCount"))
                    .filter { f<BooleanArray>("conBoundary")[it] }.toIntArray(),
                triA = body.triA, triB = body.triB, triC = body.triC,
                triRestArea2 = body.triRestArea2, triMuscle = f("triMuscle"),
                boundA = f<IntArray>("boundA").copyOf(f("boundCount")),
                boundB = f<IntArray>("boundB").copyOf(f("boundCount")),
                boundOpp = f<IntArray>("boundOpp").copyOf(f("boundCount")),
                boundaryOrientation = demo.state.localBoundary.orientation.copyOf(f("boundCount")),
                rigidBones = f("rigidBones"),
                boneRestQx = f("boneRestQx"), boneRestQy = f("boneRestQy"),
                muscleCount = body.muscleClusters.size,
                meanLinkLength = body.meanLinkLength,
                organismOf = f("organismOf"),
                boneOf = f("boneOf"),
                isFree = f("isFree"),
                contactProbes = demo.state.probeCells().copyOf(),
                organismCount = f("organismCount"),
                organismSize = f("organismSize"),
                bendA = ba.toIntArray(), bendB = bb.toIntArray(),
                bendRest = br.toDoubleArray(),
            )
        }
    }
}

/** Как устроена память среды. */
enum class FlowModel {
    /** Выключено: чисто резистивная среда, память отсутствует. */
    NONE,

    /**
     * Как в DemoSession сейчас: у КАЖДОГО граничного ребра свой запас, вектором
     * в мировых осях. Нужен только для побитовой сверки с демо.
     */
    PER_EDGE,

    /**
     * ОДИН запас на организм: вектор скорости увлечённой среды плюс её масса.
     *
     * ЧТО ЭТО ЛЕЧИТ. Запас, привязанный к ребру, ездит вместе с ребром. Тело
     * повернулось — и накопитель приехал в новое место вместе с ним, хотя жидкость
     * осталась там, где была. Отсюда толчок 308 на пустом повороте. У одного общего
     * вектора привязки к геометрии нет вообще, поэтому и артефакта нет.
     *
     * ЧТО ЭТО СОХРАНЯЕТ. Именно тот эффект, ради которого поток и вводился: разогнанное
     * тело какое-то время несёт разогнанная им же среда. Тело плывёт со скоростью V,
     * запас разгоняется к V, относительная скорость падает, сопротивление обнуляется —
     * длинный накат. А ТЯГУ это не портит, потому что тяга живёт на ЛОКАЛЬНЫХ скоростях
     * плавника (0.24..1.9), которые на два порядка больше скорости корпуса (около 0.01),
     * и вычитание общего вектора их почти не меняет.
     *
     * ПОЧЕМУ БЛУЖДАНИЯ БЫТЬ НЕ МОЖЕТ, по построению:
     *   - обмен строго симметричный: сколько импульса получило тело, ровно столько
     *     потерял запас. Значит тело не может получить импульс из ничего;
     *   - рассеяние только УНОСИТ импульс запаса в объём и никогда не создаёт;
     *   - запас — вектор в мировых осях, поворот тела его не переносит.
     * Итого: тело может получить назад только то, что само вложило, и только в том
     * направлении, в котором вкладывало.
     *
     * ЦЕНА ЧЕСТНОСТИ. Симметричный обмен означает, что за гребок запас набирает импульс,
     * ПРОТИВОПОЛОЖНЫЙ движению тела, и начинает его подтормаживать. Плавание работает
     * только потому, что этот импульс уходит в океан — то есть FLOW_DECAY здесь не
     * костыль, а физическая суть. Отсюда честный компромисс, которого раньше не было:
     * большое рассеяние — сильная тяга и короткий накат, малое — длинный накат и слабая
     * тяга. Вот эту ручку и крутит SwimTuner.
     *
     * ЧЕГО ЭТО НЕ ЛЕЧИТ, вопреки ожиданию. Поворотный толчок остаётся: 0.815 против
     * 0.833 у памяти по рёбрам, то есть почти не изменился. Причина в том, что запас
     * заряжается от импульсов на рёбрах, а они при жёстком вращении есть, и во время
     * зарядки у запаса появляется НЕвращающаяся составляющая — она и даёт разовый
     * толчок. Убирает это только [GLOBAL_TRACKING].
     */
    GLOBAL_RESERVOIR,

    /**
     * ТО ЖЕ САМОЕ, НО ЗАПАС ЗАРЯЖАЕТСЯ ТОЛЬКО ОТ ПОСТУПАТЕЛЬНОГО ДВИЖЕНИЯ.
     *
     * Это и есть ответ на «хочу накат, но не хочу блуждание».
     *
     * Наблюдение, из которого всё следует: эффект, который нужен, — это ПОСТУПАТЕЛЬНОЕ
     * увлечение. «Разогнанное тело какое-то время несёт поток, который он сам разогнал» —
     * речь о скорости корпуса, а не о том, чем машет плавник. Значит запас и надо
     * заряжать от скорости ЦЕНТРА МАСС, а не от суммы импульсов на рёбрах.
     *
     * Что из этого выходит само:
     *   - при чистом вращении скорость центра масс равна нулю, значит запас не
     *     заряжается ВООБЩЕ, и поворотного толчка нет по построению;
     *   - запас всегда направлен туда, куда тело уже едет, поэтому подтолкнуть тело
     *     он может только ВПЕРЁД по его же движению. Это накат, а не блуждание —
     *     «в случайную сторону» тут физически неоткуда взяться;
     *   - тяга не страдает: она живёт на локальных скоростях плавника (0.24..1.9),
     *     а вычитается общий вектор порядка скорости корпуса (около 0.01).
     *
     * Импульс по-прежнему честный: разгон запаса телу СТОИТ, ровно mFlow * dFlow,
     * и эта плата размазывается по всем частицам. Рассеяние только уносит.
     *
     * Сопротивление на рёбрах при этом обменивается импульсом с ОКЕАНОМ, а не с
     * запасом (в отличие от GLOBAL_RESERVOIR) — именно поэтому вращение в запас
     * ничего не кладёт.
     */
    GLOBAL_TRACKING,
}

