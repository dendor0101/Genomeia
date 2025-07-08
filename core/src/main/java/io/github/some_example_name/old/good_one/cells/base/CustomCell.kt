package io.github.some_example_name.old.good_one.cells.base

class CustomCell(private val delegates: Set<Cell>) : Cell() {

    override fun specificToThisType() {
        delegates.forEach { it.specificToThisType() }
    }
}
