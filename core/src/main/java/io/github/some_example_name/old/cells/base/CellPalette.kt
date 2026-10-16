package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.systems.genomics.genome.LegacyTypeIds

/**
 * Что игрок может ВЫБРАТЬ в редакторе генома: типы клеток и формулы.
 *
 * Реестры (CellTypes, Formulas) — всё, что игра умеет читать и растить, и убрать оттуда
 * ничего нельзя: старые геномы, миры и .bug перестанут открываться. Палитра решает другое —
 * что предлагать в списках диалога клетки. Скрытая клетка в старом геноме растёт и живёт
 * как раньше, её просто нельзя выбрать заново.
 *
 * ДВА УРОВНЯ
 * ----------
 *  - [RETIRED_CELLS] / [RETIRED_FORMULAS] — выведено из игры насовсем, скрыто в любой палитре.
 *    Решили, что клетка лишняя, — её ключ сюда, а не удаление из реестра.
 *  - палитра уровня — белый список ([only]): обучающий уровень показывает только то, что
 *    в нём проходят. [FULL] — без ограничений.
 *
 * Текущая палитра редактора — DIGenomeEditorContainer.cellPalette. Когда появятся уровни,
 * её будет выставлять старт уровня — командой, чтобы повтор .bug видел те же списки.
 * На результат симуляции палитра не влияет: в геном и в команды редактора попадают ключи.
 */
class CellPalette private constructor(
    /** Разрешённые ключи клеток; null — все, кроме выведенных. */
    private val cells: Set<String>?,
    /** Разрешённые ключи формул; null — все, кроме выведенных. */
    private val formulas: Set<String>?
) {

    fun allowsCell(key: String): Boolean = key !in RETIRED_CELLS && (cells == null || key in cells)

    fun allowsFormula(key: String): Boolean = key !in RETIRED_FORMULAS && (formulas == null || key in formulas)

    /**
     * Клетки для списка выбора — в порядке реестра. [current] (тип клетки, которую сейчас
     * редактируют) остаётся в списке, даже если скрыт: иначе диалог клетки старого генома
     * показал бы чужой тип, а сохранение его молча подменило бы.
     */
    fun cellChoices(all: List<Cell>, current: String?): List<Cell> =
        all.filter { allowsCell(it.key) || it.key == current }

    /** Формулы для списка выбора, по тем же правилам, что [cellChoices]. */
    fun formulaChoices(current: String?): List<Formula> =
        Formulas.all().filter { allowsFormula(it.key) || it.key == current }

    /**
     * Формула нового нейрона: обычная по умолчанию, а если она скрыта — первая разрешённая.
     * Не разрешена ни одна (уровень без нейронов) — всё равно обычная: поля нейрона в
     * геноме должны быть заполнены.
     */
    fun defaultFormula(): Formula =
        Formulas.DEFAULT.takeIf { allowsFormula(it.key) }
            ?: Formulas.all().firstOrNull { allowsFormula(it.key) }
            ?: Formulas.DEFAULT

    /** Ключи, которых нет в реестрах: опечатка в палитре уровня. */
    fun unknownKeys(cellList: CellTypeList): List<String> =
        cells.orEmpty().filterNot(cellList::containsKey) + formulas.orEmpty().filter { Formulas.indexOfKey(it) == -1 }

    companion object {
        /** Клетки, выведенные из игры. Старые геномы с ними растут как раньше. */
        val RETIRED_CELLS: Set<String> = setOf("breakaway", "fin", "stem", "compass", "food_sensor", "speed_sensor")

        /**
         * Формулы, выведенные из игры. "identity" нужна только старым геномам с номером
         * формулы вне списка (см. LegacyTypeIds) — предлагать её незачем, есть y = ax + b.
         * "red", "green", "blue" (r(x), g(x), b(x)) кодировали цвет числом для хамелеона;
         * теперь он сам берёт яркость выбранных каналов из сигнала 0..1 (ChameleonData).
         */
        val RETIRED_FORMULAS: Set<String> = setOf(LegacyTypeIds.IDENTITY_FORMULA, "red", "green", "blue")

        /** Всё, кроме выведенного из игры. */
        val FULL = CellPalette(cells = null, formulas = null)

        /** Только перечисленное — например, для обучающего уровня. */
        fun only(cells: Set<String>, formulas: Set<String>) = CellPalette(cells, formulas)
    }
}
