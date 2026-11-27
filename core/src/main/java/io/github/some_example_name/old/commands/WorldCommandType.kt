package io.github.some_example_name.old.commands

enum class WorldCommandType(
    val intParamsCount: Int,
    val floatParamsCount: Int,
    val booleanParamsCount: Int
) {
    ADD_CELL( // color, cellGenomeId, cellType, parentOrganIndex, parentIndex(4), activationFuncType, pheromoneType, specialModDataIndex(7), parentGeneration(8)
        intParamsCount = 9,
        floatParamsCount = 10,
        booleanParamsCount = 2
    ),
    ADD_LINK( // cellIndex, otherCellIndex, cellGeneration, otherCellGeneration
        intParamsCount = 4,
        floatParamsCount = 1,
        booleanParamsCount = 0
    ),
    ADD_LINK_BY_ID(
        intParamsCount = 3,
        floatParamsCount = 1,
        booleanParamsCount = 0
    ),
    DELETE_LINK( // linkIndex
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_NEURAL_LINK( // cellIndex, otherCellIndex, color, cellGeneration, otherCellGeneration
        intParamsCount = 5,
        floatParamsCount = 0,
        booleanParamsCount = 1
    ),
    ADD_NEURAL_LINK_BY_ID(
        intParamsCount = 4,
        floatParamsCount = 0,
        booleanParamsCount = 1
    ),
    DELETE_NEURAL_LINK(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_SUBSTANCE(
        intParamsCount = 2, //color, subType
        floatParamsCount = 3, //x, y, radius
        booleanParamsCount = 0
    ),
    DELETE_SUBSTANCE(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PARTICLE(
        intParamsCount = 1,
        floatParamsCount = 4,
        booleanParamsCount = 0
    ),
    // Новый организм от зиготы, родившейся в мире (OrganismSpawner).
    SPAWN_ORGANISM( // genomeIndex, color, scripted; x, y, radius, angleCos, angleSin, angleDiffCos, angleDiffSin
        intParamsCount = 3,
        floatParamsCount = 7,
        booleanParamsCount = 0
    ),
    DELETE_ORGAN(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DECREMENT_DIVIDE_COUNTER( // organismIndex
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DECREMENT_MUTATION_COUNTER( // organismIndex
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_CELL( // cellIndex, entityGeneration
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_PARTICLE( // particleIndex
        intParamsCount = 2,
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
    * не может поделиться/мутировать из за того что она уже мертва
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
    ),
    DELETE_NEURAL(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_NEURAL(
        intParamsCount = 3,
        floatParamsCount = 3,
        booleanParamsCount = 1
    ),
    ADD_PHEROMONE(
        intParamsCount = 2,
        floatParamsCount = 2,
        booleanParamsCount = 0
    ),
    DELETE_PHEROMONE(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    MUTATE_ON_START(
        intParamsCount = 3,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    // Свои данные клетки (SpecialModData) при мутации в клетку с данными и обратно.
    // Сам объект — в буфере команды (WorldCommandBuffer.pushObject).
    DELETE_SPECIAL_MOD_DATA( // cellIndex, поколение слота данных, поколение клетки
        intParamsCount = 3,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_SPECIAL_MOD_DATA( // cellIndex, номер объекта в буфере, поколение клетки
        intParamsCount = 3,
        floatParamsCount = 0,
        booleanParamsCount = 0
    );

    companion object {
        // Максимум int на команду: задаёт шаг в intParams всех буферов, поэтому увеличение
        // стоит памяти во всех буферах сразу, а не только у самой длинной команды.
        // Держать равным максимальному intParamsCount среди всех типов (сейчас ADD_CELL).
        const val MAX_INT_PARAMS = 9
        const val MAX_FLOAT_PARAMS = 10 // Максимум float
        const val MAX_BOOLEAN_PARAMS = 3 // Максимум boolean
    }
}
