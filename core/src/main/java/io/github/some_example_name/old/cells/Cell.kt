package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.specialDataFor
import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.features.editor.dialog.CellEditorSection
import io.github.some_example_name.old.features.editor.dialog.DirectedSection
import io.github.some_example_name.old.features.editor.dialog.NeuralSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import java.util.MissingResourceException

/**
 * Тип клетки: её поведение в симуляции и то, как она выглядит в редакторе.
 *
 * Класс открытый (не sealed), чтобы мод мог добавить свою клетку: наследник регистрируется
 * в [io.github.some_example_name.old.cells.base.CellTypes].
 *
 * КЛЮЧ И НОМЕР
 * ------------
 * [key] — постоянное имя типа. Им тип записан в геноме, в сохранённом мире и в журнале
 * .bug, поэтому однажды выпущенный ключ не меняется и не переиспользуется: это формат
 * файла. Встроенные клетки — коротко ("leaf"), клетки модов — с префиксом мода ("mod:name").
 *
 * [cellTypeId] — просто место типа в реестре, его считают при запуске. Им симуляция
 * индексирует CellEntity.cellType, текстуры и палитры: байт по номеру дешевле строки в
 * горячих циклах. Поэтому порядок в реестре можно менять, а клетки удалять — в файлах
 * номеров нет.
 */
abstract class Cell(
    val key: String,
    val defaultColor: Color,
    val cellTypeId: Int,
    val textureName: String = "not_cell.png",
    val isDirected: Boolean = false,
    val isNeural: Boolean = false,
    val maxEnergy: Float = 5f,
    val isNeuronTransportable: Boolean = true,
    val effectOnContact: Boolean = false,
    val isCollidable: Boolean = true,
    val descriptionBundle: String? = null,
    val defaultCellSettings: CellSettings = CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0.0005f,
    )
) {
    /**
     * Имя типа для людей: список типов в редакторе и ключ в файле настроек клеток.
     *
     * Выводится из [key], а не из имени класса: на Android имена классов переименовывает
     * минификатор, и там вместо "PheromoneEmitter" было бы "a". Для встроенных клеток
     * совпадает с прежним именем класса ("pheromone_emitter" -> "PheromoneEmitter"),
     * поэтому старые файлы настроек читаются как раньше.
     */
    val name: String = displayName(key)

    /**
     * Что делает клетка — для гайда. Ключ в I18NBundle — [descriptionBundle], по умолчанию
     * `cell.<key>.description`; у клетки мода без описания — пустая строка.
     *
     * Лениво: так клетку можно создать без Gdx (в тестах :core), бандл нужен только UI.
     */
    val description: String by lazy {
        val bundleKey = descriptionBundle ?: "cell.$key.description"
        try {
            bundle.get(bundleKey)
        } catch (e: MissingResourceException) {
            ""
        }
    }

    /**
     * Свои данные клетки, которые игрок настраивает в ГЕНОМЕ (Action.special), — значение по
     * умолчанию; null — в геноме у клетки своих данных нет. Образец — клавиша Controller.
     * Редактор ставит его в геном, когда клетке выбирают этот тип.
     */
    open val defaultGenomeData: SpecialModData? get() = null

    /**
     * Свои данные новой клетки этого типа (слот в SpecialModDataEntity) или null — их нет.
     * [action] — действие генома, из которого клетка появилась: деление или мутация в этот
     * тип (для клетки без генома — [NO_GENOME_ACTION]).
     *
     * По умолчанию — данные из генома ([defaultGenomeData], Action.special). Клетка, которой
     * нужно своё состояние на время жизни (скорость хвоста, счётчик продюсера), возвращает
     * НОВЫЙ объект: изменяемый объект, общий для нескольких клеток, — это гонка между
     * воркерами. Вызывается из параллельной фазы, поэтому только из [action] и констант.
     */
    open fun createSpecialData(action: Action): SpecialModData? =
        defaultGenomeData?.let { specialDataFor(action, it) }

    /**
     * Мутация клетки, которая этого типа и остаётся: новые данные или null — оставить как
     * есть. [current] — данные клетки сейчас. Смену типа обрабатывает не это, а
     * [createSpecialData] нового типа.
     */
    open fun mutateSpecialData(current: SpecialModData, action: Action): SpecialModData? =
        defaultGenomeData?.let { default -> action.special?.takeIf { it.javaClass == default.javaClass } }

    /**
     * Из чего собран диалог клетки в редакторе генома, сверху вниз.
     *
     * Своя верстка клетки живёт рядом с её логикой (в файле клетки, см. Synapse) и
     * подключается здесь. По этому же списку диалог сбрасывает поля генома при смене типа:
     * секции, которых у нового типа нет, чистят свои поля, новые — ставят значения по
     * умолчанию. Поэтому порядок и состав секций — это всё, что редактору надо знать о
     * клетке; проверок вида `is Synapse` в нём нет.
     */
    open fun editorSections(): List<CellEditorSection> = buildList {
        if (isNeural) add(NeuralSection)
        if (isDirected) add(DirectedSection)
    }

    lateinit var context: DIContext

    //TODO передавать Di конеткст в методы, onStart, doOnTick, onContact и тд
    val particleEntity get() = context.particleEntity
    val cellEntity get() = context.cellEntity
    val linkEntity get() = context.linkEntity
    val neuralLinkEntity get() = context.neuralLinkEntity
    val substancesEntity get() = context.substancesEntity
    val specialEntity get() = context.specialEntity
    val worldCommandsManager get() = context.worldCommandsManager
    val organEntity get() = context.organEntity
    val genomeManager get() = context.genomeManager
    val pheromoneEntity get() = context.pheromoneEntity

    val gridManager get() = context.gridManager
    val organManager get() = context.organManager
    val pheromonesManager get() = context.pheromonesManager

    open fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int = -1) {

    }

    open fun doOnTick(cellIndex: Int, threadId: Int) {

    }

    /**
     * Вызывается из фазы коллизий (CollisionManager.repulse), т.е. изнутри обхода сетки.
     *
     * ЗАПРЕЩЕНО менять содержимое сетки: никаких gridManager.addParticle / addCell /
     * removeParticle и ничего, что меняет particleCounts, grid или mapMoreThenMax.
     * ParticlePhysicsSystem обходит слоты сетки напрямую, без копирования в промежуточный
     * массив, поэтому мутация сетки во время обхода приведёт к пропуску/дублированию
     * частиц, а при переполнении клетки — к рассинхрону particleCounts со списком-хвостом.
     *
     * Можно: писать в vx/vy/energy/radius/цвет и складывать отложенные команды в
     * worldCommandsManager.worldCommandBuffer[threadId] — они исполнятся после фаз физики.
     *
     * Также помни, что метод вызывается из нескольких потоков: писать можно только по
     * индексам участников контакта (cellIndex / particleIndexCollided) и в буфер своего
     * threadId, без общего изменяемого состояния.
     */
    open fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) {

    }


    open fun onDie(cellIndex: Int) {

    }

    open fun onLinkDeleted(cellIndex: Int, linkIndex: Int, threadId: Int) {

    }

    companion object {
        /**
         * Действие «без генома» для [createSpecialData]: клетку создали напрямую (зигота игрока).
         * Общий экземпляр, чтобы не создавать Action на каждую клетку; менять его нельзя.
         */
        val NO_GENOME_ACTION = Action()

        /** "pheromone_emitter" -> "PheromoneEmitter", "mod:my_cell" -> "mod:MyCell". */
        fun displayName(key: String): String {
            val namespace = key.substringBefore(':', missingDelimiterValue = "")
            val local = key.substringAfter(':')
            val pascal = local.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            return if (namespace.isEmpty()) pascal else "$namespace:$pascal"
        }
    }
}
