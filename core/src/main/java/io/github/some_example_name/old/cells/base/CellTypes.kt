package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.*

/**
 * Реестр типов клеток: из чего собирается список клеток каждого контейнера (CellListBuilder).
 *
 * Номер типа — место в этом списке, его считают при запуске. В файлах (геном, мир, журнал)
 * тип записан ключом Cell.key, поэтому порядок здесь можно менять, а клетки удалять:
 * старые файлы переводятся из ключей в номера при чтении. Старые номера, которые успели
 * попасть в файлы до ключей, заморожены отдельно — LegacyTypeIds.
 *
 * Пока порядок встроенных клеток совпадает со старыми номерами, сохранения старой версии
 * читаются без перестановки — но это удобство, а не требование.
 *
 * Мод добавляет клетку через [register] до того, как игра соберёт первый список клеток
 * (первый DI-контейнер). После этого реестр заморожен: номера типов во всех контейнерах
 * обязаны совпадать — по ним рисуются текстуры и живут сохранения.
 */
object CellTypes {

    private val builtIn: List<(cellTypeId: Int) -> Cell> = listOf(
        ::Leaf, //Клетка, которая получает энергию из солнца
        ::Fat, //Накапливает много энергии
        ::Bone, //Твердая клетка, для создания скелета
        ::Tail, //Клетка, которая может плыть
        ::Controller, //Клетка через которую можно управлять организмом
        ::Muscle, // Сокращается и сжимается
        ::Sucker, // Поглощает еду
        ::Excreta, // Создает еду
        ::Punisher, // Убийца других клеток, кроме кости (нужно сделать что бы он впрыскивал типа яд или вещество которое не сразу убивает клетку)
        ::Pumper, // Выкачивает энергию до которой прикоснулся
        ::Zygote, // первая клетка организма
        ::Producer, // Создает зиготу из которой появляется новый организм
        ::Neuron, // Суммирует входные сигналы и преобразовывает их через формулу и выдает сигнал дальше
        ::SuctionCup, // Прилипает к субстрату
        ::Sticky, // Примагничивается к другим клеткам
        ::Chameleon, // Красится по сигналу 0..1 от чёрного до выбранных каналов R/G/B
        ::Eye, // Видит то что первое пересекло луч
        ::TouchTrigger, // Измеряет силу прикосновения
        ::Synapse, // Клетка для обучения нейронной сети через ассоциацию
        ::PheromoneEmitter, //Источает феромон
        ::PheromoneSensor, //Чувствует феромон
        //Уже не используемые клетки, но их необходимо оставить для обратной совместимости со старыми геномами
        ::Breakaway,
        ::Fin,
        ::SpeedSensor,
        ::FoodSensor,
        ::Stem,
        ::Compass,
    )

    private val modded = mutableListOf<(cellTypeId: Int) -> Cell>()

    private var frozen = false

    /** Клетка мода. Её ключ обязан быть с префиксом мода: "modid:name". */
    fun register(factory: (cellTypeId: Int) -> Cell) = synchronized(this) {
        check(!frozen) { "клетки регистрируются до создания первого списка клеток" }
        modded += factory
    }

    /** Новый список клеток для контейнера. Первый вызов замораживает реестр. */
    fun createAll(): CellTypeList {
        val factories = synchronized(this) {
            frozen = true
            builtIn + modded
        }
        val cells = factories.mapIndexed { index, create -> create(index) }
        cells.forEachIndexed { index, cell ->
            check(cell.cellTypeId == index) { "клетка ${cell.key} создана с номером ${cell.cellTypeId}, а стоит $index" }
            val pattern = if (index < builtIn.size) BUILT_IN_KEY else MOD_KEY
            check(pattern.matches(cell.key)) {
                if (index < builtIn.size) "ключ встроенной клетки '${cell.key}' — латиница в нижнем регистре, без ':'"
                else "ключ клетки мода '${cell.key}' обязан быть вида 'modid:name'"
            }
        }
        return CellTypeList(cells)
    }

    private val BUILT_IN_KEY = Regex("[a-z][a-z0-9_]*")

    /**
     * Ключ клетки, формулы или данных клетки из мода: "<id мода>:<имя>". id мода — тот же,
     * что "id" в fabric.mod.json, и по тем же правилам: с латинской буквы, строчные буквы,
     * цифры, '-' и '_', от 2 до 64 символов. Имя — как у встроенных ключей.
     */
    val MOD_KEY = Regex("[a-z][a-z0-9_-]{1,63}:[a-z][a-z0-9_]*")
}

/**
 * Список клеток контейнера: обычный List<Cell> по номеру типа плюс поиск по ключу.
 *
 * Поиск по ключу — HashMap по строке, то есть на событие (деление, мутация, открытие
 * диалога), а не на клетку за тик: горячие циклы ходят по номеру.
 */
class CellTypeList(private val cells: List<Cell>) : List<Cell> by cells {

    private val indexByKey = HashMap<String, Int>(cells.size * 2)

    init {
        // Номер типа живёт в CellEntity.cellType — байте со знаком.
        check(cells.size <= Byte.MAX_VALUE) { "типов клеток ${cells.size}, а в байт номера влезает ${Byte.MAX_VALUE}" }
        cells.forEachIndexed { index, cell ->
            check(indexByKey.put(cell.key, index) == null) { "два типа клеток с ключом '${cell.key}'" }
        }
    }

    /** Номер типа по ключу, -1 — такого типа нет (геном от мода, которого нет). */
    fun indexOfKey(key: String): Int = indexByKey[key] ?: -1

    fun byKey(key: String): Cell =
        indexByKey[key]?.let { cells[it] } ?: throw UnknownCellTypeException(key)

    fun containsKey(key: String): Boolean = key in indexByKey

    /** Индекс в списке; найденный ключ обязан быть — иначе геном не прошёл проверку при чтении. */
    fun requireIndex(key: String): Int = indexByKey[key] ?: throw UnknownCellTypeException(key)

    fun keys(): List<String> = cells.map { it.key }

    /**
     * Слой пустой текстуры (not_cell.png) в массиве текстур клеток: он идёт сразу после
     * текстур всех типов (DIGameGlobalContainer.particleTexturePaths). Им рисуется то, что
     * не клетка какого-то типа, — например, призрак будущей клетки в редакторе. Номер
     * зависит от числа типов, поэтому константой его не записать.
     */
    val emptyTextureLayer: Int get() = cells.size
}

class UnknownCellTypeException(val key: String) :
    IllegalStateException("неизвестный тип клетки '$key': геном или мир от мода, которого нет")
