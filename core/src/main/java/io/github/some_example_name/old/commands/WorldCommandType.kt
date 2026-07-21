package io.github.some_example_name.old.commands

enum class WorldCommandType(val id: Int, val intParamsCount: Int, val floatParamsCount: Int, val booleanParamsCount: Int) {
    ADD_CELL(
        id = 1,
        intParamsCount = 9,
        floatParamsCount = 11,
        booleanParamsCount = 2
    ),
    ADD_LINK(
        id = 2,
        intParamsCount = 3,
        floatParamsCount = 2,
        booleanParamsCount = 3
    ),
    ADD_LINK_BY_ID(
        id = 3,
        intParamsCount = 4,
        floatParamsCount = 1,
        booleanParamsCount = 2
    ),
    ADD_SUBSTANCE(
        id = 4,
        intParamsCount = 2, //color, subType
        floatParamsCount = 3, //x, y, radius
        booleanParamsCount = 0
    ),
    DELETE_SUBSTANCE(
        id = 5,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PARTICLE(
        id = 6,
        intParamsCount = 1,
        floatParamsCount = 4,
        booleanParamsCount = 0
    ),
    ADD_ORGAN(
        id = 7,
        intParamsCount = 5,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_ORGAN(
        id = 8,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DECREMENT_DIVIDE_COUNTER( // organismIndex
        id = 9,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DECREMENT_MUTATION_COUNTER( // organismIndex
        id = 10,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_CELL( // cellIndex, entityGeneration
        id = 11,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_LINK( // linkIndex
        id = 12,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_PARTICLE( // particleIndex
        id = 13,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    /*
    * EN: A counter for all living cells of the organism that are supposed to divide/mutate...
    * */
    DIVIDE_ALIVE_CELL_ACTION_COUNTER( // organismIndex: Int
        id = 14,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    MUTATE_ALIVE_CELL_ACTION_COUNTER( // organismIndex: Int
        id = 15,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_NEURAL(
        id = 16,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_NEURAL(
        id = 17,
        intParamsCount = 3,
        floatParamsCount = 3,
        booleanParamsCount = 1
    ),
    DELETE_EYE(
        id = 18,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_EYE(
        id = 19,
        intParamsCount = 2,
        floatParamsCount = 1,
        booleanParamsCount = 0
    ),
    DELETE_TAIL(
        id = 20,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_TAIL(
        id = 21,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_PRODUCER(
        id = 22,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PRODUCER(
        id = 23,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PHEROMONE(
        id = 24,
        intParamsCount = 2,
        floatParamsCount = 2,
        booleanParamsCount = 0
    ),
    DELETE_PHEROMONE(
        id = 25,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    DELETE_PHEROMONE_EMITTER(
        id = 26,
        intParamsCount = 2,
        floatParamsCount = 0,
        booleanParamsCount = 0
    ),
    ADD_PHEROMONE_EMITTER(
        id = 27,
        intParamsCount = 1,
        floatParamsCount = 0,
        booleanParamsCount = 0
    );

    companion object {
        const val MAX_INT_PARAMS = 9
        const val MAX_FLOAT_PARAMS = 11
        const val MAX_BOOLEAN_PARAMS = 3

        private val mapById = values().associateBy { it.id }

        fun fromId(id: Int): WorldCommandType? = mapById[id]
    }
}
