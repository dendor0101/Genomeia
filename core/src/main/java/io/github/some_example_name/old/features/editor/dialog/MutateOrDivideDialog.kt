package io.github.some_example_name.old.features.editor.dialog

import io.github.some_example_name.old.core.ui.hint
import com.kotcrab.vis.ui.widget.VisDialog
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.setupTitleSize

class MutateOrDivideDialog(
    val clickedCell: EditorCell,
    val onDivide: () -> Unit,
    val onMutate: () -> Unit,
) : VisDialog("${bundle.get("button.cellId")} ${clickedCell.id}") {

    init {
        setupTitleSize(game)
        isModal = true
        isMovable = true

        val rootTable = globalVisTable {
            visTextButton(bundle.get("button.divide"), onClick = {
                onDivide.invoke()
                fadeOut()
            }) { pad(8.dp()) }.hint(bundle.get("editor.hint.chooseDivide"))

            row()

            visTextButton(bundle.get("button.mutate"), onClick = {
                onMutate.invoke()
                fadeOut()
            }) { pad(8.dp()) }.hint(bundle.get("editor.hint.chooseMutate"))
        }

        contentTable.add(rootTable)

        closeOnEscape()
        pack()
        centerWindow()
    }

}
