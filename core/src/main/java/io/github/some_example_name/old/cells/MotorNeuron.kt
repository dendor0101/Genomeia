package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

/**
 * Мотонейрон: нейрон, который управляет сразу всем кластером мышц, в котором стоит.
 *
 * Кластер — связная по физическим связям группа клеток Muscle и MotorNeuron во ВЗРОСЛОМ теле
 * (BakedLayout.motorClusterOfSlot, считается при запекании, MotorClusters.kt). Свой выход
 * мотонейрон считает как Neuron — по входам и формуле, — а нейрофаза (NeuralLinkManager)
 * отдаёт его каждой мышце кластера так, будто к ней проведена нейросвязь: задержка та же,
 * один тик, и у мышцы работает её собственная формула.
 *
 * Зачем: без него, чтобы сократить кластер разом, игрок тянет нейросвязь к каждой мышце.
 * Связь стоит около 100 байт (массивы связи, две хэш-таблицы пар, списки у обеих клеток) и
 * около пяти случайных чтений за тик. Мотонейрону нужны только список мышц кластера в
 * раскладке и одно чтение-запись входа на мышцу.
 *
 * Разрыв ткани кластер не делит: границы взяты из раскладки, а не из живых связей (решение
 * пользователя 20.11). Оторвавшийся кусок мышц продолжает слушаться своего мотонейрона.
 *
 * НАПРАВЛЕНИЕ (20.11). Клетка направленная: стрелка мотонейрона во взрослом теле — волокно его
 * мышц. Мышцы кластера сжимаются и растягиваются только вдоль стрелки, поперёк держат размер
 * (физика — PhysicsWorld.setCellFiber; закон — physics config/FiberParameters.kt, у игры одноосный:
 * показатель утолщения k = 0. В физике perf-experiment расслабление длину не меняет, а делает
 * ткань мягче на растяжение — тоже только вдоль стрелки). Направление снимается при
 * запекании (BakedLayout.motorNeuronFiber) в позе покоя, как и кластер: тело потом гнётся и
 * поворачивается, а волокно остаётся свойством ткани. Если мотонейронов в кластере несколько,
 * мышца берёт направление ближайшего к ней в позе покоя (BakedLayout.fiberXBySlot).
 */
class MotorNeuron(cellTypeId: Int) : Cell(
    key = "motor_neuron",
    defaultColor = redColors[1],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true
) {

    override fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int) {
        cellEntity.neuronImpulseOutput[cellIndex] = 0f
    }

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        cellEntity.energy[cellIndex] -= cellEntity.substrateSettings
            .cellsSettings[cellEntity.cellType[cellIndex].toInt()]
            .energyActionCost
    }
}
