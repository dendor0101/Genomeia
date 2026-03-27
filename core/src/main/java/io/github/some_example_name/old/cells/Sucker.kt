package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.orangeColors

class Sucker : Cell(
    defaultColor = orangeColors.first(),
    cellTypeId = 7
) {

    override fun doOnTick(index: Int, threadId: Int) {

    }

    override fun onContact(index: Int, indexCollided: Int, threadId: Int) {
        //TODO delete sub particle
    }

}
