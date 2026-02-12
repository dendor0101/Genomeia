package io.github.some_example_name.old.genome_editor

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT

class FullReplayStructure(
    val cellLastId: Int,
    val linksLastId: Int,
    //Cell
    var id: IntArray,
//    var organismId: IntArray,
    var parentId: IntArray,
    var firstChildId: IntArray,
    var gridId: IntArray,
    var x: FloatArray,
    var y: FloatArray,
    var angle: FloatArray,
    var vx: FloatArray,
    var vy: FloatArray,
//    var vxOld: FloatArray,
//    var vyOld: FloatArray,
//    var ax: FloatArray,
//    var ay: FloatArray,
    var colorR: FloatArray,
    var colorG: FloatArray,
    var colorB: FloatArray,
    var energyNecessaryToDivide: FloatArray,
    var energyNecessaryToMutate: FloatArray,
//    var youngCellStrength: IntArray,
//    var neuronImpulseImport: FloatArray,
//    var frictionLevel: FloatArray,
//    var isAliveWithoutEnergy: IntArray,
//    var elasticity: FloatArray,
//    var isLooseEnergy: BooleanArray,
//    var isDividedInThisStage: BooleanArray,
//    var isMutateInThisStage: BooleanArray,
    var cellType: IntArray,
    var energy: FloatArray,
//    var tickRestriction: IntArray,
    var linksAmount: IntArray,
    var links: IntArray,

    //Neural
    var activationFuncType: IntArray,
    var a: FloatArray,
    var b: FloatArray,
    var c: FloatArray,
//    var dTime: FloatArray,
//    var remember: FloatArray,
    var isSum: BooleanArray,

    //Directed
    var angleDiff: FloatArray,

    //Eye
    var colorDifferentiation: IntArray,
    var visibilityRange: FloatArray,

    //Muscle
//    var muscleContractionStep: FloatArray,

    //Tail
//    var speed: FloatArray,

    //Links
    var links1: IntArray,
    var links2: IntArray,
    var linksLength: FloatArray,
    var isNeuronLink: BooleanArray,
    var directedNeuronLink: IntArray,
//    var degreeOfShortening: FloatArray,
//    var isStickyLink: BooleanArray,
//    val linkIndexMap: UnorderedIntPairMap,

    val genomeIndex: Int,
    val genomeSize: Int,
    var stage: Int = 0,
    val dividedTimes: IntArray,
    val mutatedTimes: IntArray,
    var justChangedStage: Boolean = false,
    var timerToGrowAfterStage: Int = 0
)

fun CellManager.restoreFromFullReplayStructure(struct: FullReplayStructure) {
    cellLastId = struct.id.size - 1
    linksLastId = struct.links1.size - 1

    val cellSize = cellLastId + 1
    val linkSize = linksLastId + 1
    val cellLinksSize = cellSize * MAX_LINK_AMOUNT

    // Cell

    System.arraycopy(struct.id, 0, id, 0, cellSize)
//    System.arraycopy(struct.organismId, 0, organismId, 0, cellSize)
    organismId.fill(0, 0, cellSize)
    System.arraycopy(struct.parentId, 0, parentId, 0, cellSize)
    System.arraycopy(struct.firstChildId, 0, firstChildId, 0, cellSize)
    System.arraycopy(struct.gridId, 0, gridId, 0, cellSize)
    System.arraycopy(struct.x, 0, x, 0, cellSize)
    System.arraycopy(struct.y, 0, y, 0, cellSize)
    System.arraycopy(struct.angle, 0, angle, 0, cellSize)
    System.arraycopy(struct.vx, 0, vx, 0, cellSize)
    System.arraycopy(struct.vy, 0, vy, 0, cellSize)
//    System.arraycopy(struct.vxOld, 0, vxOld, 0, cellSize)
//    System.arraycopy(struct.vyOld, 0, vyOld, 0, cellSize)
//    System.arraycopy(struct.ax, 0, ax, 0, cellSize)
//    System.arraycopy(struct.ay, 0, ay, 0, cellSize)
    System.arraycopy(struct.colorR, 0, colorR, 0, cellSize)
    System.arraycopy(struct.colorG, 0, colorG, 0, cellSize)
    System.arraycopy(struct.colorB, 0, colorB, 0, cellSize)
    System.arraycopy(struct.energyNecessaryToDivide, 0, energyNecessaryToDivide, 0, cellSize)
    System.arraycopy(struct.energyNecessaryToMutate, 0, energyNecessaryToMutate, 0, cellSize)
//    System.arraycopy(struct.youngCellStrength, 0, youngCellStrength, 0, cellSize)
//    System.arraycopy(struct.neuronImpulseImport, 0, neuronImpulseImport, 0, cellSize)
//    System.arraycopy(struct.frictionLevel, 0, frictionLevel, 0, cellSize)
//    System.arraycopy(struct.isAliveWithoutEnergy, 0, isAliveWithoutEnergy, 0, cellSize)
//    System.arraycopy(struct.elasticity, 0, elasticity, 0, cellSize)
//    System.arraycopy(struct.isLooseEnergy, 0, isLooseEnergy, 0, cellSize)
//    System.arraycopy(struct.isDividedInThisStage, 0, isDividedInThisStage, 0, cellSize)
//    System.arraycopy(struct.isMutateInThisStage, 0, isMutateInThisStage, 0, cellSize)
    System.arraycopy(struct.cellType, 0, cellType, 0, cellSize)
    System.arraycopy(struct.energy, 0, energy, 0, cellSize)
//    System.arraycopy(struct.tickRestriction, 0, tickRestriction, 0, cellSize)
    System.arraycopy(struct.linksAmount, 0, linksAmount, 0, cellSize)
    System.arraycopy(struct.links, 0, links, 0, cellLinksSize)

    // Neural
//    System.arraycopy(struct.activationFuncType, 0, activationFuncType, 0, cellSize)
//    System.arraycopy(struct.a, 0, a, 0, cellSize)
//    System.arraycopy(struct.b, 0, b, 0, cellSize)
//    System.arraycopy(struct.c, 0, c, 0, cellSize)
//    System.arraycopy(struct.dTime, 0, dTime, 0, cellSize)
//    System.arraycopy(struct.remember, 0, remember, 0, cellSize)
//    System.arraycopy(struct.isSum, 0, isSum, 0, cellSize)

    // Directed
    System.arraycopy(struct.angleDiff, 0, angleDiff, 0, cellSize)

    // Eye
//    System.arraycopy(struct.colorDifferentiation, 0, colorDifferentiation, 0, cellSize)
//    System.arraycopy(struct.visibilityRange, 0, visibilityRange, 0, cellSize)

    // Muscle
//    System.arraycopy(struct.muscleContractionStep, 0, muscleContractionStep, 0, cellSize)

    // Tail
//    System.arraycopy(struct.speed, 0, speed, 0, cellSize)

    // Links
    System.arraycopy(struct.links1, 0, links1, 0, linkSize)
    System.arraycopy(struct.links2, 0, links2, 0, linkSize)
    System.arraycopy(struct.linksLength, 0, linksNaturalLength, 0, linkSize)
    System.arraycopy(struct.isNeuronLink, 0, isNeuronLink, 0, linkSize)
    System.arraycopy(struct.directedNeuronLink, 0, directedNeuronLink, 0, linkSize)
//    System.arraycopy(struct.degreeOfShortening, 0, degreeOfShortening, 0, linkSize)
//    System.arraycopy(struct.isStickyLink, 0, isStickyLink, 0, linkSize)
}

fun CellManager.createFullReplayStructure(): FullReplayStructure {
    val cellSize = cellLastId + 1
    val linkSize = linksLastId + 1
    val cellLinksSize = cellSize * MAX_LINK_AMOUNT

    // Assuming UnorderedIntPairMap has a copy constructor or clone method; adjust as needed
//    val copiedMap = UnorderedIntPairMap(linkIndexMap) // Or linkIndexMap.clone() if available, or manually copy entries

    val organism = organismManager.organisms.first()

    return FullReplayStructure(
        cellLastId = cellLastId,
        linksLastId = linksLastId,
        // Cell
        id = id.copyOfRange(0, cellSize),
//        organismId = organismId.copyOfRange(0, cellSize),
        parentId = parentId.copyOfRange(0, cellSize),
        firstChildId = firstChildId.copyOfRange(0, cellSize),
        gridId = gridId.copyOfRange(0, cellSize),
        x = x.copyOfRange(0, cellSize),
        y = y.copyOfRange(0, cellSize),
        angle = angle.copyOfRange(0, cellSize),
        vx = vx.copyOfRange(0, cellSize),
        vy = vy.copyOfRange(0, cellSize),
//        vxOld = vxOld.copyOfRange(0, cellSize),
//        vyOld = vyOld.copyOfRange(0, cellSize),
//        ax = ax.copyOfRange(0, cellSize),
//        ay = ay.copyOfRange(0, cellSize),
        colorR = colorR.copyOfRange(0, cellSize),
        colorG = colorG.copyOfRange(0, cellSize),
        colorB = colorB.copyOfRange(0, cellSize),
        energyNecessaryToDivide = energyNecessaryToDivide.copyOfRange(0, cellSize),
        energyNecessaryToMutate = energyNecessaryToMutate.copyOfRange(0, cellSize),
//        youngCellStrength = youngCellStrength.copyOfRange(0, cellSize),
//        neuronImpulseImport = neuronImpulseImport.copyOfRange(0, cellSize),
//        frictionLevel = frictionLevel.copyOfRange(0, cellSize),
//        isAliveWithoutEnergy = isAliveWithoutEnergy.copyOfRange(0, cellSize),
//        elasticity = elasticity.copyOfRange(0, cellSize),
//        isLooseEnergy = isLooseEnergy.copyOfRange(0, cellSize),
//        isDividedInThisStage = isDividedInThisStage.copyOfRange(0, cellSize),
//        isMutateInThisStage = isMutateInThisStage.copyOfRange(0, cellSize),
        cellType = cellType.copyOfRange(0, cellSize),
        energy = energy.copyOfRange(0, cellSize),
//        tickRestriction = tickRestriction.copyOfRange(0, cellSize),
        linksAmount = linksAmount.copyOfRange(0, cellSize),
        links = links.copyOfRange(0, cellLinksSize),

        // Neural
        activationFuncType = activationFuncType.copyOfRange(0, cellSize),
        a = a.copyOfRange(0, cellSize),
        b = b.copyOfRange(0, cellSize),
        c = c.copyOfRange(0, cellSize),
//        dTime = dTime.copyOfRange(0, cellSize),
//        remember = remember.copyOfRange(0, cellSize),
        isSum = isSum.copyOfRange(0, cellSize),

        // Directed
        angleDiff = angleDiff.copyOfRange(0, cellSize),

        // Eye
        colorDifferentiation = colorDifferentiation.copyOfRange(0, cellSize),
        visibilityRange = visibilityRange.copyOfRange(0, cellSize),

        // Muscle
//        muscleContractionStep = muscleContractionStep.copyOfRange(0, cellSize),

        // Tail
//        speed = speed.copyOfRange(0, cellSize),

        // Links
        links1 = links1.copyOfRange(0, linkSize),
        links2 = links2.copyOfRange(0, linkSize),
        linksLength = linksNaturalLength.copyOfRange(0, linkSize),
        isNeuronLink = isNeuronLink.copyOfRange(0, linkSize),
        directedNeuronLink = directedNeuronLink.copyOfRange(0, linkSize),
//        degreeOfShortening = degreeOfShortening.copyOfRange(0, linkSize),
//        isStickyLink = isStickyLink.copyOfRange(0, linkSize),
//        linkIndexMap = copiedMap,
        genomeIndex = organism.genomeIndex,
        genomeSize = organism.genomeSize,
        stage = organism.stage,
        dividedTimes = organism.dividedTimes.copyOf(),
        mutatedTimes = organism.mutatedTimes.copyOf(),
        justChangedStage = organism.justChangedStage,
        timerToGrowAfterStage = organism.timerToGrowAfterStage,
    )
}
