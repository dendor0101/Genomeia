package io.github.some_example_name.old.logic

import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.logic.ThreadManager.Companion.THREAD_COUNT

open class CrossThreadEditable {

    @Volatile
    var grabbedCell = -1
    @Volatile
    var deleteCell = -1

    @Volatile
    var grabbedX = 0f

    @Volatile
    var grabbedY = 0f

    @Volatile
    var cellMaxAmount = 120000

    @Volatile
    var linksMaxAmount = 120000

    @Volatile
    var cellLastId = -1

    @Volatile
    var linksLastId = -1

    //Cell
    var isPhantom = BooleanArray(cellMaxAmount) { true }
    var id = Array(cellMaxAmount) { "" }
    var gridId = IntArray(cellMaxAmount) { -1 }
    var x = FloatArray(cellMaxAmount) { 0f }
    var y = FloatArray(cellMaxAmount) { 0f }
    var vx = FloatArray(cellMaxAmount) { 0f }
    var vy = FloatArray(cellMaxAmount) { 0f }
    var vxOld = FloatArray(cellMaxAmount) { 0f }
    var vyOld = FloatArray(cellMaxAmount) { 0f }
    var ax = FloatArray(cellMaxAmount) { 0f }
    var ay = FloatArray(cellMaxAmount) { 0f }
    var colorR = FloatArray(cellMaxAmount) { 1f }
    var colorG = FloatArray(cellMaxAmount) { 1f }
    var colorB = FloatArray(cellMaxAmount) { 1f }
    var energyNecessaryToDivide = FloatArray(cellMaxAmount) { 2f }
    var energyNecessaryToMutate = FloatArray(cellMaxAmount) { 2f }
    var cellStrength = FloatArray(cellMaxAmount) { 2f }
    var linkStrength = FloatArray(cellMaxAmount) { 0.025f }
    var neuronImpulseImport = FloatArray(cellMaxAmount) { 0f }
    var frictionLevel = FloatArray(cellMaxAmount) { 0.93f }
    var isAliveWithoutEnergy = IntArray(cellMaxAmount) { 200 }//TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var elasticity = FloatArray(cellMaxAmount) { 3.7f }
    var isLooseEnergy = BooleanArray(cellMaxAmount) { true }
    var isDividedInThisStage = BooleanArray(cellMaxAmount) { true }
    var isMutateInThisStage = BooleanArray(cellMaxAmount) { true }
    var cellType = IntArray(cellMaxAmount) { 0 }//TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var energy = FloatArray(cellMaxAmount) { 0f }
    var maxEnergy = FloatArray(cellMaxAmount) { 5f }
    var tickRestriction = IntArray(cellMaxAmount) { 0 }//TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var linksAmount = IntArray(cellMaxAmount) { 0 }//TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var links = IntArray(cellMaxAmount * MAX_LINK_AMOUNT) { -1 }

    //Neural
    var activationFuncType = IntArray(cellMaxAmount) { 0 }
    var a = FloatArray(cellMaxAmount) { 0f }
    var b = FloatArray(cellMaxAmount) { 0f }
    var c = FloatArray(cellMaxAmount) { 0f }
    var dTime = FloatArray(cellMaxAmount) { -1f }

    //Directed
    var angle = FloatArray(cellMaxAmount) { 0f }
    var startAngleId = IntArray(cellMaxAmount) { -1 }

    //Muscle
    var muscleContractionStep = FloatArray(cellMaxAmount) { 1f }

    //Tail
    var speed = FloatArray(cellMaxAmount) { 0f }

    //Links
    var links1 = IntArray(linksMaxAmount) { -1 }
    var links2 = IntArray(linksMaxAmount) { -1 }
    var linksLength = FloatArray(linksMaxAmount) { -10f }
    var isNeuronLink = BooleanArray(linksMaxAmount) { false }
    var directedNeuronLink = IntArray(linksMaxAmount) { -1 }
    var degreeOfShortening = FloatArray(linksMaxAmount) { 1f }
    var isStickyLink = BooleanArray(linksMaxAmount) { false }
    val linkIdMap = UnorderedIntPairMap(1_000_000)

    val deletedCellSizes = IntArray(THREAD_COUNT) { -1 }
    val deleteCellLists = Array(THREAD_COUNT) { IntArray(301) { -1 } }

    val deletedLinkSizes = IntArray(THREAD_COUNT) { -1 }
    val deleteLinkLists = Array(THREAD_COUNT) { IntArray(300) { -1 } }

    fun addToDeleteList(threadId: Int, linkId: Int) {
        deletedLinkSizes[threadId] += 1
        deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
    }


}
