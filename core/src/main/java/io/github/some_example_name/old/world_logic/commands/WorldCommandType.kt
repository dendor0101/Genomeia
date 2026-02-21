package io.github.some_example_name.old.world_logic.commands

enum class WorldCommandType(val intParamsCount: Int, val floatParamsCount: Int, val booleanParamsCount: Int) {
    ADD_CELL( // AddCell: action (object, но сериализуем в примитивы), parentX/Y/Angle, parentId/OrganismId/Index
        intParamsCount = 4,    // parentId, parentOrganismId, parentIndex, +1 запас
        floatParamsCount = 3,  // parentX, parentY, parentAngle
        booleanParamsCount = 0
    ),
    ADD_LINK( // AddLink: cellIndex, otherCellIndex, linksLength, degreeOfShortening, isStickyLink, isNeuronLink, directedNeuronLink
        intParamsCount = 3,    // cellIndex, otherCellIndex, directedNeuronLink
        floatParamsCount = 2,  // linksLength, degreeOfShortening
        booleanParamsCount = 2 // isStickyLink, isNeuronLink
    ),
    ADD_SUBSTANCE( // SubstanceAdd: x, y, vx, vy
        intParamsCount = 0,
        floatParamsCount = 4,  // x, y, vx, vy
        booleanParamsCount = 0
    ),
    ADD_ORGANISM( // Organism: genomeIndex, genomeSize, stage, dividedTimes/mutatedTimes (массивы — сериализуем как int[] с фиксированным размером, скажем max 10)
        intParamsCount = 3 + 10 + 10, // stage, genomeIndex, genomeSize + dividedTimes[10] + mutatedTimes[10] (если больше — адаптируйте)
        floatParamsCount = 0,
        booleanParamsCount = 3 // alreadyGrownUp, justChangedStage, etc.
    ),
    DECREMENT_MUTATION_COUNTER( // Просто organismId
        intParamsCount = 1,    // organismId
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_CELL( // cellId
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_LINK( // linkId
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    /*
    * EN: A counter for all living cells of the organism that are supposed to divide/mutate
    * in the current stage. This is needed so the stage doesn’t stall if some cell
    * can’t divide/mutate because it’s already dead
    *
    * RU: Счетчик для всех живых клеток организма которые должны поделиться/мутировать
    * в текущую стадию. Нужно что бы не тормозить стадию если какая-то клетка
    * не может поделиться/мутировать из за того что она уже мерта
    * */
    DIVIDE_ALIVE_CELL_ACTION_COUNTER( // organismIndex: Int
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    MUTATE_ALIVE_CELL_ACTION_COUNTER( // organismIndex: Int
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    );

    companion object {
        const val MAX_INT_PARAMS = 20    // Максимум int на команду (покрывает все)
        const val MAX_FLOAT_PARAMS = 10  // Максимум float
        const val MAX_BOOLEAN_PARAMS = 5 // Максимум boolean
    }
}
