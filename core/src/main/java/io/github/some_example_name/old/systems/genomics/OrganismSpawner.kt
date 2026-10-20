package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager

/**
 * Новый организм: сначала сам организм с аренами, потом его зигота — первой клеткой, в
 * его же арене. Так сажает игрок (UserCommandManager) и так рождается потомок в мире —
 * зигота от продюсера или от деления по геному (WorldCommandsManager, SPAWN_ORGANISM).
 * Вызывать только из однопоточной фазы: выдача организмов и арен общая на весь мир.
 *
 * ПОЧЕМУ ОРГАНИЗМ РАНЬШЕ КЛЕТКИ
 * -----------------------------
 * На этом держатся арены: слот клетке выдаёт арена её организма, а у зиготы, созданной
 * раньше организма (organIndex = -1), арены нет. До арен потомок так и рождался: зигота-
 * сирота сама заказывала себе организм (ADD_ORGAN). С аренами этот заказ выключили —
 * переносить клетку в арену задним числом некому, — а продюсер рожать сирот продолжил, и
 * первый же обход клеток падал на organEntity.*[-1]. Здесь сироты не бывает вовсе.
 *
 * Свой экземпляр у каждого владельца, из его же сущностей: мир меню и мир игры — разные
 * миры, и посадка в одном не должна попадать в другой.
 */
class OrganismSpawner(
    private val organEntity: OrganEntity,
    private val cellEntity: CellEntity,
    private val genomeManager: GenomeManager,
    private val zygote: Zygote
) {

    /**
     * Посадить организм с геномом [genomeIndex], вернуть индекс его зиготы.
     *
     * Размеры арен — из генома, снятые при запекании, а не из констант: константа на все
     * геномы сразу — это либо перерасход на мелких телах, либо падение на крупных. У
     * незапечённого генома там нули, и тогда работают значения по умолчанию OrganEntity.
     */
    fun spawn(
        genomeIndex: Int,
        x: Float,
        y: Float,
        color: Int,
        radius: Float,
        angleCos: Float,
        angleSin: Float,
        angleDiffCos: Float = 1f,
        angleDiffSin: Float = 0f
    ): Int {
        val genome = genomeManager.genomes[genomeIndex]
        val organIndex = organEntity.addOrgan(
            genomeIndex = genomeIndex,
            genomeSize = genome.genomeStageInstruction.size,
            dividedTimes = genome.dividedTimes[0],
            mutatedTimes = genome.mutatedTimes[0]
        )
        // Строго до первой addCell: зигота уже должна брать слот из арены, иначе тело
        // начнётся вне своего диапазона.
        organEntity.allocateArenas(
            organIndex = organIndex,
            layout = genome.sortedGraph,
            maxNeuralLinks = genome.neuralLinksPeak ?: -1
        )
        return cellEntity.addCell(
            x = x,
            y = y,
            color = color,
            radius = radius,
            cellType = zygote.cellTypeId,
            organIndex = organIndex,
            angleCos = angleCos,
            angleSin = angleSin,
            angleDiffCos = angleDiffCos,
            angleDiffSin = angleDiffSin
        )
    }
}
