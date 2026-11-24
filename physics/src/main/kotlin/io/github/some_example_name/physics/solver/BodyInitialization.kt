package io.github.some_example_name.physics


/** Создание рабочих массивов из исходной топологии без файлов и платформенных API.
 * Порядок идентификаторов и построения компонент совпадает с выгрузкой.
 */
internal fun BodyState.initialize(definition: BodyDefinition) {
    body = definition
    n = body.count
    val scale = worldScale ?: WorldScale.of(definition)
    worldScale = scale
    linkScale = scale.meanLinkLength
    cellDead = BooleanArray(n)
    cellUnborn = BooleanArray(n)
    conGrowTarget = FloatArray(0); conGrowAge = ByteArray(0); triGrowTarget = FloatArray(0); growEdgeN = 0; growTriN = 0

    px = FloatArray(n); py = FloatArray(n)
    prevX = FloatArray(n); prevY = FloatArray(n)
    vx = FloatArray(n); vy = FloatArray(n)
    invMass = FloatArray(n); matchWeight = FloatArray(n)
    inContact = BooleanArray(n)

    boneOf = IntArray(n) { -1 }
    muscleOf = IntArray(n) { -1 }

    // Жёсткими считаются кластеры от трёх клеток: две дают лишь жёсткий стержень,
    // то есть ровно то же, что обычная связь, и заводить ради них проекцию незачем.
    val rigid = body.boneClusters.filter { it.size >= 3 }
    degenerateBones = body.boneClusters.size - rigid.size
    rigidBones = rigid.toTypedArray()
    rigidBones.forEachIndexed { b, ids -> for (i in ids) boneOf[i] = b }
    boneClusterOf = boneOf.copyOf()
    buildBoneRestPose()

    body.muscleClusters.forEachIndexed { m, ids -> for (i in ids) muscleOf[i] = m }
    growMuscles()
    muscleActivation = FloatArray(body.muscleClusters.size)
    muscleScaleOf = FloatArray(body.muscleClusters.size) { 1.0f }
    muscleTarget = FloatArray(body.muscleClusters.size)

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
    conRest = FloatArray(lnkCount); conMuscle = IntArray(lnkCount)
    conEdge = IntArray(lnkCount)
    conCount = fillConstraints()

    triMuscle = IntArray(triCount) { t -> muscleOfTri(triA[t], triB[t], triC[t]) }
    triInverted = BooleanArray(triCount)
    cellScale = FloatArray(n) { 1f }
    cellScaleTarget = FloatArray(n) { 1f }
    dragScale = FloatArray(n) { 1.0f }
    conCellScale = FloatArray(conA.size) { 1f }; triCellScale = FloatArray(triA.size) { 1f }
    cellSoftness = FloatArray(n) { 1f }
    cellFiberX = FloatArray(n); cellFiberY = FloatArray(n) // волокна задаёт клиент (PhysicsWorld.setCellFiber)
    cellNoContact = BooleanArray(n)
    cellGroup = IntArray(n)
    cellGrip = FloatArray(n); gripsDirty = true
    cellHomeCompliance = FloatArray(n); cellHomeX = FloatArray(n); cellHomeY = FloatArray(n)
    conSoftness = FloatArray(conA.size) { 1f }; triSoftness = FloatArray(triA.size) { 1f }

    buildBoundary()
    buildBend(); buildFlaps()
    buildMasses()
    buildOrganisms()
    buildContacts()
    distanceSweepOrder = DistanceSweepOrder.create(this)
    initializeConstraintBlocks()
    // Временные связи роста (они бывают в описании только из снимка): какая клетка какие снимает (TempLinks.kt).
    rebuildTempRetire()
}

/** Возвращает рабочую топологию и численное состояние в исходную позу. */
internal fun BodyState.resetState() {
    // Ткань могла порваться — топология возвращается к целой вместе с позами.
    restoreTopology()
    for (i in 0 until n) {
        px[i] = body.x[i].toFloat(); py[i] = body.y[i].toFloat()
        vx[i] = 0.0f; vy[i] = 0.0f
        invMass[i] = restInvMass[i]; matchWeight[i] = restMatchWeight(i)
    }
    trapTicks.fill(0); foldTicks.fill(0)
    flowVX.fill(0.0f); flowVY.fill(0.0f)
    muscleActivation.fill(0.0f)
    muscleTarget.fill(0.0f)
    dragId = -1
    invertedPeak = 0
    boneCapHits = 0
    linkCapHits = 0
    linkTorn.fill(false)
    speedCapHits = 0
}
