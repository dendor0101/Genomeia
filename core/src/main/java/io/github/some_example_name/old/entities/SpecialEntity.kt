package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.base.SpecialModData

/**
 * Особая часть клетки — её свои данные (SpecialModData: глаз, хвост, продюсер, излучатель
 * феромонов, контроллер, клетки модов). Индексируется индексом клетки; [specialTypeIndexes]
 * ведёт в слот SpecialModDataEntity, -1 — своих данных у клетки нет.
 *
 * ОДНА СУЩНОСТЬ ДЛЯ ВСЕХ ВИДОВ
 * ----------------------------
 * Раньше у глаза, хвоста, продюсера и излучателя были свои сущности, и номер в
 * specialTypeIndexes вёл в одну из них — какую, код угадывал по ТЕКУЩЕМУ типу клетки.
 * Мутация меняет тип сразу, в параллельной фазе, а удаление старой части и заведение
 * новой ждут применения команд. Умри клетка или отдай она индекс новой клетке в этот
 * промежуток, удаление «хвоста» получало номер слота глаза и освобождало слот ЧУЖОЙ
 * клетки. Теперь номер всегда ведёт в одну сущность, а вид данных знает сам объект —
 * угадывать нечего.
 */
class SpecialEntity(
    cellsStartMaxAmount: Int,
    private val specialModDataEntity: SpecialModDataEntity
): Entity(cellsStartMaxAmount) {

    var specialTypeIndexes = IntArray(maxAmount) { -1 }

    /** Свои данные клетки или null, если их у неё нет. */
    fun getSpecialData(cellIndex: Int): SpecialModData? {
        val modDataIndex = specialTypeIndexes[cellIndex]
        return if (modDataIndex == -1) null else specialModDataEntity.specialModData[modDataIndex]
    }

    /**
     * Заменить свои данные клетки (мутация, или клетка сама меняет их по ходу жизни).
     * Пишет только в слот этой клетки — можно из параллельной фазы.
     */
    fun setSpecialData(cellIndex: Int, data: SpecialModData) {
        specialModDataEntity.specialModData[specialTypeIndexes[cellIndex]] = data
    }

    fun getSpecialModDataGeneration(cellIndex: Int) = specialModDataEntity.getGeneration(specialTypeIndexes[cellIndex])

    fun addSpecialModData(cellIndex: Int, data: SpecialModData) {
        specialTypeIndexes[cellIndex] = specialModDataEntity.addModData(data)
    }

    fun deleteSpecialModData(cellIndex: Int, modDataGeneration: Int? = null) {
        val modDataIndex = specialTypeIndexes[cellIndex]
        if (modDataIndex == -1) return
        if (specialModDataEntity.isAlive[modDataIndex] && (modDataGeneration == null
                || specialModDataEntity.getGeneration(modDataIndex) == modDataGeneration)) {
            specialModDataEntity.deleteModData(modDataIndex)
            specialTypeIndexes[cellIndex] = -1
        }
    }

    /**
     * Заводит специальную часть для клетки [cellIndex]; [specialModData] — её свои данные
     * (Cell.createSpecialData), null — их нет.
     *
     * ЭТА СУЩНОСТЬ ИНДЕКСИРУЕТСЯ ИНДЕКСОМ КЛЕТКИ. Не своим собственным — именно чужим:
     * весь класс состоит из методов вида `getSpecialData(index)`, куда снаружи
     * передаётся cellIndex, и из [deleteSpecial], который его же и принимает.
     *
     * Раньше индекс здесь выдавался своим add(), а совпадение с индексом клетки держалось
     * лишь на том, что обе сущности всегда растут и умирают в ногу, то есть их lastId и
     * deadStack ходят синхронно. Это молчаливое совпадение, а не инвариант, и арены его
     * ломают: клетка берёт слот из диапазона своего организма, а add() здесь по-прежнему
     * отдаёт следующий свободный. Дальше specialTypeIndexes[cellIndex] читается по индексу,
     * который никто не заполнял, там -1, и падает первый же обратившийся к глазу или хвосту.
     *
     * Поэтому индекс теперь передаётся явно и занимается через addAt. Побочно это делает
     * связь настоящим инвариантом: разъехаться она больше не может в принципе, независимо
     * от того, чем и как выдаются слоты клеткам.
     */
    fun addSpecial(cellIndex: Int, specialModData: SpecialModData?): Int {
        addAt(cellIndex, arenaOwner = EXTERNAL_SLOT)
        specialTypeIndexes[cellIndex] = -1
        specialModData?.let { addSpecialModData(cellIndex, it) }
        return cellIndex
    }

    /** Смерть клетки: её слот данных освобождается, какого бы вида они ни были. */
    fun deleteSpecial(cellIndex: Int) {
        deleteSpecialModData(cellIndex)
        delete(cellIndex)
    }

    override fun persistFields(state: EntityState) {
        state.ints(::specialTypeIndexes)
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        specialTypeIndexes.clear()
    }

    override fun onResize(oldMax: Int) {
        specialTypeIndexes = specialTypeIndexes.resize()
    }
}
