package io.github.some_example_name.physics


/** Создание рабочих массивов из исходной топологии без файлов и платформенных API.
 * Порядок идентификаторов и построения компонент совпадает с выгрузкой.
 */
internal fun BodyState.initialize(definition: BodyDefinition) {
    body = definition
    n = body.count
    cellDead = BooleanArray(n)

    px = DoubleArray(n); py = DoubleArray(n)
    prevX = DoubleArray(n); prevY = DoubleArray(n)
    vx = DoubleArray(n); vy = DoubleArray(n)
    invMass = DoubleArray(n); matchWeight = DoubleArray(n)
    inContact = BooleanArray(n)

    boneOf = IntArray(n) { -1 }
    muscleOf = IntArray(n) { -1 }

    // Жёсткими считаются кластеры от трёх клеток: две дают лишь жёсткий стержень,
    // то есть ровно то же, что обычная связь, и заводить ради них проекцию незачем.
    val rigid = body.boneClusters.filter { it.size >= 3 }
    degenerateBones = body.boneClusters.size - rigid.size
    rigidBones = rigid.toTypedArray()
    rigidBones.forEachIndexed { b, ids -> for (i in ids) boneOf[i] = b }
    buildBoneRestPose()

    body.muscleClusters.forEachIndexed { m, ids -> for (i in ids) muscleOf[i] = m }
    growMuscles()
    muscleActivation = DoubleArray(body.muscleClusters.size)
    muscleScaleOf = DoubleArray(body.muscleClusters.size) { 1.0 }
    muscleTarget = DoubleArray(body.muscleClusters.size)

    // Свои копии топологии: дальше они живут отдельно от выгрузки и меняются
    // при разрывах. См. объявления выше.
    lnkCount = body.linkCount
    lnkA = body.linkA.copyOf(); lnkB = body.linkB.copyOf()
    triCount = body.triCount
    baked = BakedTopology(body)
    fractureOrientation = FractureOrientation(body, baked)
    lnkEdge = baked.linkEdge.copyOf()
    triId = IntArray(triCount) { it }
    triA = body.triA.copyOf(); triB = body.triB.copyOf(); triC = body.triC.copyOf()
    triRestArea2 = body.triRestArea2.copyOf()

    // --- связи: внутрикостные не создаются, их держит проекция ---

    conA = IntArray(lnkCount); conB = IntArray(lnkCount)
    conRest = DoubleArray(lnkCount); conMuscle = IntArray(lnkCount)
    conEdge = IntArray(lnkCount)
    conCount = fillConstraints()

    triMuscle = IntArray(triCount) { t -> muscleOfTri(triA[t], triB[t], triC[t]) }
    triInverted = BooleanArray(triCount)

    buildBoundary()
    buildBend(); buildFlaps()
    buildMasses()
    buildOrganisms()
    buildContacts()
    distanceSweepOrder = DistanceSweepOrder.create(this)
    initializeConstraintBlocks()

}

/** Возвращает рабочую топологию и численное состояние в исходную позу. */
internal fun BodyState.resetState() {
    // Ткань могла порваться — топология возвращается к целой вместе с позами.
    restoreTopology()
    for (i in 0 until n) {
        px[i] = body.x[i].toDouble(); py[i] = body.y[i].toDouble()
        vx[i] = 0.0; vy[i] = 0.0
        invMass[i] = restInvMass[i]; matchWeight[i] = 1.0
    }
    trapTicks.fill(0); foldTicks.fill(0)
    flowVX.fill(0.0); flowVY.fill(0.0)
    muscleActivation.fill(0.0)
    muscleTarget.fill(0.0)
    dragId = -1
    invertedPeak = 0
    boneCapHits = 0
    linkCapHits = 0
    linkTorn.fill(false)
    speedCapHits = 0
}
