package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.whiteColors

class Bone(cellTypeId: Int): Cell(
    defaultColor = whiteColors.first(),
    cellTypeId = cellTypeId
)
