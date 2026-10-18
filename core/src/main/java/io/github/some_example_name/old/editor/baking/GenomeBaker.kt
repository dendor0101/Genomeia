package io.github.some_example_name.old.editor.baking

import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.library.Baker

/**
 * Запекание генома — размеры взрослого тела, раскладка арен и снимок для превью (см.
 * Genome.cellsAmount, [RCMSort], [GenomePreviewCapture]). Снимается с тела, выращенного в
 * мире редактора.
 *
 * Один код на два пути, чтобы импортированный геном ничем не отличался от сохранённого:
 *  - [bakeGrown] — кнопка «Сохранить»: тело уже выращено на экране редактора;
 *  - [bake] — геном не из редактора (импорт, перенос старых JSON): тот же мир редактора
 *    выращивает его так же, как при открытии в редакторе, и печёт.
 *
 * [bake] занимает мир редактора. Открытие экрана редактора всё равно пересчитывает его
 * заново (GenomeEditorScreen.show), поэтому вызывать можно где угодно, кроме самого
 * открытого редактора.
 */
class GenomeBaker(
    private val editorSimulationSystem: EditorSimulationSystem,
    private val rcmSort: RCMSort,
    private val previewCapture: GenomePreviewCapture,
    private val cellEntity: CellEntity,
    private val linkEntity: LinkEntity,
    private val neuralLinkEntity: NeuralLinkEntity
) : Baker {

    override fun bake(genome: Genome, name: String): Genome {
        editorSimulationSystem.useGenome(genome)
        editorSimulationSystem.simulate()
        return bakeGrown(name)
    }

    /**
     * Тело в сущностях редактора уже выращено. Организм в редакторе всегда один, с
     * organIndex = 0.
     */
    fun bakeGrown(name: String): Genome {
        val layout = rcmSort.bake()
        val grown = editorSimulationSystem.getGenome()
        return grown.copy(
            name = name,
            // Копия: у генома редактора это его же рабочий список стадий, и следующая
            // правка или открытие другого генома изменили бы запечённый геном задним числом.
            stageInstruction = grown.stageInstruction.toList(),
            cellsAmount = cellEntity.aliveList.size,
            linksAmount = linkEntity.aliveList.size,
            neuralLinksAmount = neuralLinkEntity.aliveList.size,
            sortedGraph = layout,
            preview = previewCapture.capture()
        )
    }
}
