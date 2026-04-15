package io.github.some_example_name.old.commands

enum class WorldCommandType(val intParamsCount: Int, val floatParamsCount: Int, val booleanParamsCount: Int) {
    ADD_CELL(
        intParamsCount = 7,
        floatParamsCount = 9,
        booleanParamsCount = 1
    ),
    ADD_LINK(
        intParamsCount = 2,
        floatParamsCount = 2,
        booleanParamsCount = 3
    ),
    ADD_LINK_BY_ID(
        intParamsCount = 3,
        floatParamsCount = 1,
        booleanParamsCount = 2
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
    ADD_ORGAN(
        intParamsCount = 5,
        floatParamsCount = 0,
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
    DELETE_LINK( // linkIndex
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
    DELETE_EYE(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_EYE(
        intParamsCount = 2,
        floatParamsCount = 1,
        booleanParamsCount = 0
    ),
    DELETE_TAIL(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_TAIL(
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_PRODUCER(
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PRODUCER(
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
