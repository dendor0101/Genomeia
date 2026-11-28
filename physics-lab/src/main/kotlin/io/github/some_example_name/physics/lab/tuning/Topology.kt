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
 * DemoSession через [Topology]. Здесь только снимок графа тела для стендов, которым не нужен живой мир
 * (physicsCheck, solverBench, precisionScale, dragReplay). Зеркало решателя SwimSolver и подбор swimTune, ради
 * которых снимок заводился, удалены 26.11.2026: организмы подстраиваются под физику, а не физика под них.
 */

/** Топология тела, вынутая из настоящего DemoSession. Только чтение, общая на все потоки. */
class Topology private constructor(
    val n: Int,
    val restX: FloatArray,
    val restY: FloatArray,
    /** Радиус клетки: из него берётся масса (invMass в DemoSession). */
    val restR: FloatArray,
    val conA: IntArray,
    val conB: IntArray,
    val conRest: FloatArray,
    val conMuscle: IntArray,
    /** Связи контура в порядке ядра. */
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
    val boneRestQx: Array<FloatArray>,
    val boneRestQy: Array<FloatArray>,
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
    val bendRest: FloatArray,
    /** Запечённый граф тела (все рёбра, и внутрикостные): место кости у шва — ConstraintBlocks.boneBucket. */
    val vertStart: IntArray,
    val vertOther: IntArray,
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
            val ba = ArrayList<Int>(); val bb = ArrayList<Int>(); val br = ArrayList<Float>()
            for (v in 0 until body.count) {
                if (deg[v] != 2) continue
                val i = nb0[v]; val j = nb1[v]
                if (i < 0 || j < 0 || i == j) continue
                val dx = (body.x[i] - body.x[j]).toFloat()
                val dy = (body.y[i] - body.y[j]).toFloat()
                ba.add(i); bb.add(j); br.add(sqrt(dx * dx + dy * dy))
            }

            return Topology(
                n = f("n"),
                restX = body.x, restY = body.y, restR = body.radius,
                // Списки живут в массивах постоянной ёмкости, длина берётся из счётчиков.
                conA = f<IntArray>("conA").copyOf(f("conCount")), conB = f<IntArray>("conB").copyOf(f("conCount")),
                conRest = f<FloatArray>("conRest").copyOf(f("conCount")),
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
                bendRest = br.toFloatArray(),
                vertStart = demo.state.baked.vertStart.copyOf(), vertOther = demo.state.baked.vertOther.copyOf(),
            )
        }
    }
}
