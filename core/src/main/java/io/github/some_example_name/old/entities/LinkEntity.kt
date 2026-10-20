package io.github.some_example_name.old.entities

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer.linkMaxLength2
import io.github.some_example_name.old.systems.physics.GridManager

import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.sqrt

class LinkEntity(
    linksStartMaxAmount: Int,
    val cellEntity: CellEntity,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val diContext: DIContext,
    /** Источник арен: связи организма лежат в массивах подряд. См. OrganEntity. */
    val organEntity: OrganEntity
) : Entity(linksStartMaxAmount) {
    /**
     * Клетки на концах связи, по массиву на конец.
     *
     * ЗДЕСЬ БЫЛА УПАКОВКА, И ОНА ОКАЗАЛАСЬ ХУЖЕ — не повторять без нового замера.
     *
     * Пробовали сложить в один блок [cellA, cellB, particleA, particleB] со stride 4,
     * чтобы физика брала индексы частиц оттуда же и не ходила в cellEntity.particleIndexes.
     * Замер: фаза связей +6..+19%, то есть стало медленнее.
     *
     * Причина в том, что обход списка связей идёт ПО ВОЗРАСТАНИЮ ИНДЕКСА СВЯЗИ и потому
     * почти последователен (связи создаются пачками — организм заводит свои разом).
     * Значит links1/links2 читаются не как случайные линии, а как ПОТОК, который тянет
     * аппаратный префетчер: 16 связей на кэш-линию. Упаковка раздула блок с 8 байт до 16,
     * то есть вчетверо уменьшила число связей на линию в потоковой части — ради экономии
     * двух чтений из particleIndexes, а этот массив всего ~780 КБ на 195k клеток, живёт
     * в L2/L3 и стоит дёшево. Обмен дешёвых попаданий в кэш на вдвое больший поток
     * из памяти оказался невыгодным.
     *
     * Отсюда общее правило для этой фазы: считать кэш-линии имеет смысл только для
     * СЛУЧАЙНЫХ обращений (по индексам клеток и частиц). Для массивов, индексируемых
     * номером связи, доступ потоковый, и там важен объём байт, а не число массивов.
     */
    var links1 = IntArray(maxAmount) { -1 }
    var links2 = IntArray(maxAmount) { -1 }
    var linksGeneration1 = IntArray(maxAmount) { -1 }
    var linksGeneration2 = IntArray(maxAmount) { -1 }
    var linksNaturalLength = FloatArray(maxAmount) { -10f }


    /**
     * Отладочная проверка: у клетки есть живая частица с осмысленной позицией.
     *
     * Ловит связи, создаваемые к клетке, которой фактически нет. Проверяются три вещи:
     *  - particleIndexes != -1: deleteCell обнуляет его, значит клетка уже мертва, хотя
     *    isAlive могла успеть стать true заново при переиспользовании индекса;
     *  - частица жива: клетка и её частица должны умирать вместе, расхождение означает
     *    испорченную связку;
     *  - позиция не строго (0, 0): ParticleEntity.deleteParticle обнуляет x и y, а живая
     *    частица туда попасть не может — processWorldBorders зажимает координаты в
     *    [radius, gridSize - radius] при ненулевом радиусе. То есть ровный ноль означает
     *    именно удалённую частицу, это точный признак, а не эвристика.
     */
    private fun checkCellHasValidParticle(cellIndex: Int, side: String, otherCellIndex: Int) {
        val particleIndex = cellEntity.particleIndexes[cellIndex]
        if (particleIndex == -1) {
            throw IllegalStateException(
                "связь к клетке $side=$cellIndex без частицы (particleIndexes = -1), " +
                    "вторая клетка $otherCellIndex"
            )
        }
        if (!particleEntity.isAlive[particleIndex]) {
            throw IllegalStateException(
                "связь к клетке $side=$cellIndex с МЁРТВОЙ частицей $particleIndex, " +
                    "вторая клетка $otherCellIndex"
            )
        }
        val x = particleEntity.x[particleIndex]
        val y = particleEntity.y[particleIndex]
        if (x == 0f && y == 0f) {
            throw IllegalStateException(
                "связь к клетке $side=$cellIndex в нулевой координате " +
                    "(частица $particleIndex), вторая клетка $otherCellIndex"
            )
        }
    }

    /**
     * Пригодна ли клетка для создания связи: жива и того же поколения, что ожидал
     * поставщик команды.
     *
     * [expectedGeneration] == [NO_GENERATION_CHECK] означает, что поколение проверять
     * нечем. Так бывает ровно в одном случае: команда ссылается на клетку, которая
     * создаётся в этой же фазе применения (в команде она закодирована как -1 и
     * разворачивается в lastAddedCellIndexBuffer). Такой клетки на момент постановки
     * команды ещё не существовало, поколения у неё не было, а умереть между её созданием
     * и этой командой она не может — обе идут одна за другой в однопоточной фазе.
     */
    private fun isCellUsable(cellIndex: Int, expectedGeneration: Int): Boolean {
        if (!cellEntity.isAlive[cellIndex]) return false
        if (expectedGeneration == NO_GENERATION_CHECK) return true
        return cellEntity.getGeneration(cellIndex) == expectedGeneration
    }

    /**
     * Возвращает индекс новой связи или -1, если добавить её нельзя.
     *
     * -1 бывает по двум причинам:
     *  - у одной из клеток закончились слоты в cellLinks (см. CellEntity.MAX_LINKS_PER_CELL).
     *    Это осознанное ограничение симуляции — плата за то, что проверка «связаны ли клетки»
     *    в repulse стала сканом одной кэш-линии вместо похода в хэш-таблицу на полтора мегабайта;
     *  - одна из клеток уже мертва (см. ниже);
     *  - клетки слишком далеко друг от друга (см. ниже).
     * Все вызывающие обязаны проверять результат.
     */
    fun addLink(
        cellIndex: Int,
        otherCellIndex: Int,
        linksLength: Float,
        cellGeneration: Int = NO_GENERATION_CHECK,
        otherCellGeneration: Int = NO_GENERATION_CHECK,
    ): Int {
        // Обе клетки обязаны быть живы И быть теми же самыми, что и в момент постановки
        // команды.
        //
        // Команда ADD_LINK формируется в параллельной фазе, а применяется позже, и за это
        // время одна из клеток могла получить DELETE_CELL — причём её команда могла быть
        // применена РАНЬШЕ этой. Пока в processLink стояла проверка живости, такая связь
        // просто снималась на следующем тике сама. Теперь проверки нет, и связь с мёртвой
        // клеткой означала бы обращение по particleIndexes == -1 при первом же расчёте.
        //
        // Одного isAlive мало: индексы клеток переиспользуются через deadStack, поэтому
        // умершая и заново занятая ячейка снова "жива", но это уже другая клетка. Отличает
        // их поколение — оно инкрементируется при каждом add() и потому уникально для
        // каждой жизни индекса.
        if (!isCellUsable(cellIndex, cellGeneration) ||
            !isCellUsable(otherCellIndex, otherCellGeneration)
        ) {
            return -1
        }

        // Обе клетки обязаны иметь живую частицу с осмысленной позицией.
        //
        // Проверка стоит именно здесь, а не в processLink: связь ловится в момент рождения,
        // и стектрейс сразу показывает, какой путь её создал — обычное деление, морфогенез
        // или ADD_LINK_BY_ID. В processLink она всплыла бы тиком позже и уже без контекста.
        if (DEBUG_CHECKS) {
            checkCellHasValidParticle(cellIndex, "A", otherCellIndex)
            checkCellHasValidParticle(otherCellIndex, "B", cellIndex)
        }

        // Клетки обязаны принадлежать одному организму.
        //
        // Это не игровое правило, а условие корректности: слот связи выбирается по якорю
        // организма, поэтому связь между разными организмами попала бы в слот только одной
        // из них, и её вторая клетка оказалась бы чужой для этого воркера — гонка на vx/vy.
        //
        // Проверка расстояния такое НЕ ловит: два разных организма могут соприкасаться
        // физически, и тогда расстояние в норме.
        //
        // Все пути создания связей ищут вторую клетку внутри одного organIndex, так что
        // сюда такая пара приходить не должна — барьер стоит на случай, если какой-то путь
        // это обойдёт: связь просто не создаётся, вместо того чтобы молча портить физику.
        if (cellEntity.organIndex[cellIndex] != cellEntity.organIndex[otherCellIndex]) {
            return -1
        }

        // Клетки должны быть физически рядом.
        //
        // Связи по чертежу генома резолвятся через organToIdToIndex, то есть по геномному
        // id внутри организма. Оторванный от организма кусок сохраняет тот же organIndex,
        // поэтому делящаяся клетка находит «соседа по чертежу», который физически уже
        // улетел на другой конец мира. Связь при этом создавалась и умирала в том же тике
        // по linkMaxLength2 — но успевала попасть в список слота и посчитаться как
        // нормальная, с записью в vx/vy обеих частиц.
        //
        // Порог тот же, по которому связь рвётся в processLink: если она не переживёт
        // ближайшую же проверку, создавать её незачем. Заодно это восстанавливает
        // допущение, на котором держится приписка к чанкам, — что связанные клетки близки.
        val dx = cellEntity.getX(cellIndex) - cellEntity.getX(otherCellIndex)
        val dy = cellEntity.getY(cellIndex) - cellEntity.getY(otherCellIndex)
        if (dx * dx + dy * dy > linkMaxLength2) {
            return -1
        }

        // Проверка до add(): связь либо создаётся целиком, либо не создаётся вовсе,
        // иначе можно получить живую связь, которой нет в списке соседей одной из клеток.
        if (!cellEntity.canAddCellLink(cellIndex) || !cellEntity.canAddCellLink(otherCellIndex)) {
            return -1
        }

        // Слот из арены связей организма. Обе клетки заведомо принадлежат одному телу —
        // это уже обеспечено барьером по якорю выше, — поэтому организм берётся у любой.
        // У клеток без арены (редактор, где арены выключены) связь идёт общим путём.
        val organIndex = cellEntity.organIndex[cellIndex]
        val arenaLinkSlot = if (organEntity.hasArena(organIndex)) {
            val slot = organEntity.takeLinkSlot(
                organIndex,
                cellEntity.cellGenomeId[cellIndex],
                cellEntity.cellGenomeId[otherCellIndex]
            )
            if (slot == -1) {
                throw IllegalStateException(
                    "связи ${cellEntity.cellGenomeId[cellIndex]}–${cellEntity.cellGenomeId[otherCellIndex]} нет " +
                        "в запечённой раскладке организма $organIndex — пересохраните геном"
                )
            }
            slot
        } else -1

        val addLinkIndex =
            if (arenaLinkSlot == -1) add() else addAt(arenaLinkSlot, arenaOwner = organIndex)

        links1[addLinkIndex] = cellIndex
        links2[addLinkIndex] = otherCellIndex
        linksGeneration1[addLinkIndex] = cellEntity.getGeneration(cellIndex)
        linksGeneration2[addLinkIndex] = cellEntity.getGeneration(otherCellIndex)

        this.linksNaturalLength[addLinkIndex] = linksLength

        cellEntity.linkAmount[cellIndex] ++
        cellEntity.linkAmount[otherCellIndex] ++

        // Связь могла закрыть последний свободный сектор вокруг клетки — тогда она
        // становится внутренней и уходит из пространственной сетки.
        cellEntity.refreshOnEdge(cellIndex)
        cellEntity.refreshOnEdge(otherCellIndex)

        cellEntity.addCellLink(cellIndex, otherCellIndex, addLinkIndex)
        cellEntity.addCellLink(otherCellIndex, cellIndex, addLinkIndex)

        // Связь могла достроить треугольники тела — они формируются, и их вершины могут стать
        // внутренними (TriangleEntity). Только после addCellLink: refreshOnEdge вершин читает
        // уже полное состояние.
        if (arenaLinkSlot != -1) organEntity.triangles?.onLinkChanged(organIndex, addLinkIndex, organEntity, this, cellEntity)

        return addLinkIndex
    }

    /**
     * Снимает ВСЕ связи умирающей клетки. Вызывать до cellEntity.deleteCell, из
     * однопоточной фазы применения команд.
     *
     * ЗАЧЕМ
     * -----
     * Раньше это делалось лениво: processLink на каждой связи каждый тик проверял, живы ли
     * обе клетки, и при смерти отправлял DELETE_LINK. Проверка — это isAlive[a], isAlive[b],
     * getGeneration(a), getGeneration(b), linksGeneration1[i], linksGeneration2[i], то есть
     * шесть чтений из шести разных массивов на КАЖДУЮ связь. При 480 тысячах связей это
     * порядка трети всех обращений к памяти в методе, и вся эта работа пропорциональна
     * числу связей в мире, хотя событие пропорционально числу смертей.
     *
     * Теперь стоимость платится один раз на смерть и равна числу связей самой клетки
     * (не больше MAX_LINKS_PER_CELL), а из горячего цикла проверка убрана совсем.
     *
     * ПРО ОБХОД
     * ---------
     * Список соседей плотный, а deleteLink делает swap-with-last в обоих направлениях,
     * поэтому после каждого удаления в слоте 0 снова оказывается живой сосед. Значит
     * итератор не нужен и проблемы инвалидации тоже: просто разбираем слот 0, пока он занят.
     * Каждая итерация уменьшает список ровно на один элемент, так что цикл конечен.
     */
    fun detachAllLinks(cellIndex: Int) {
        val base = cellIndex shl CellEntity.LINKS_SHIFT
        val neighbours = cellEntity.cellLinks
        val linkIds = cellEntity.cellLinkIds

        while (neighbours[base] != -1) {
            val otherCellIndex = neighbours[base]
            val linkIndex = linkIds[base]

            if (linkIndex == -1) {
                // Рассинхронизации быть не должно, но зацикливаться на ней нельзя:
                // убираем соседа руками, сохраняя плотность списка.
                cellEntity.removeCellLink(cellIndex, otherCellIndex)
                continue
            }

            // Сосед помечается ДО удаления: deleteLink обнулит блок связи, и после него
            // связь уже не скажет, кто был на другом конце.
            cellEntity.isOnEdge[otherCellIndex] = true
            cellEntity.setColor(otherCellIndex, EDGE_COLOR)
            // Тот же смысл, что у reinitParentLink: у выжившего соседа не должно остаться
            // ссылки на индекс, который вот-вот переиспользует новая клетка.
            if (cellEntity.parentIndex[otherCellIndex] == cellIndex) {
                cellEntity.parentIndex[otherCellIndex] = -1
            }

            // Без проверки поколения: индекс взят из живого списка соседей прямо сейчас,
            // переиспользовать его между этими двумя строками некому.
            deleteLink(linkIndex)
        }
    }


    fun deleteLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null || getGeneration(linkIndex) == linkGeneration)) {
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]

            cellEntity.removeCellLink(cellA, cellB)
            cellEntity.removeCellLink(cellB, cellA)

            cellEntity.linkAmount[cellA] --
            cellEntity.linkAmount[cellB] --

            // Обратное: у клетки открылся сектор, она снова на границе и возвращается
            // в сетку. Иначе оторванный кусок тела перестал бы сталкиваться вообще.
            cellEntity.refreshOnEdge(cellA)
            cellEntity.refreshOnEdge(cellB)

            // И треугольники на ней распались: их вершины теперь у щели. Владелец — запомненный
            // при выдаче: клетки связи могли уже умирать (detachAllLinks).
            val owner = arenaOwnerOf(linkIndex)
            if (owner >= 0) organEntity.triangles?.onLinkChanged(owner, linkIndex, organEntity, this, cellEntity)

            links1[linkIndex] = -1
            links2[linkIndex] = -1
            linksGeneration1[linkIndex] = -1
            linksGeneration2[linkIndex] = -1

            linksNaturalLength[linkIndex] = -10f
        }
    }

    fun reinitParentLink(linkIndex: Int) {
        val cellA = links1[linkIndex]
        val cellB = links2[linkIndex]

        if (cellEntity.parentIndex[cellA] == cellB) {
            cellEntity.parentIndex[cellA] = -1
        }

        if (cellEntity.parentIndex[cellB] == cellA) {
            cellEntity.parentIndex[cellB] = -1
        }
    }

    fun reinitParentIndex(cellIndex: Int, newParentIndex: Int) = with(cellEntity) {
        parentIndex[cellIndex] = newParentIndex
        val otherCellIndex = parentIndex[cellIndex]

        val dx = getX(cellIndex) - getX(otherCellIndex)
        val dy = getY(cellIndex) - getY(otherCellIndex)
        val len = sqrt(dx * dx + dy * dy)
        val dirCos = dx / len
        val dirSin = dy / len

        parentIndex[cellIndex] = otherCellIndex

        angleCompensationCos[cellIndex] = angleCos[cellIndex] * dirCos + angleSin[cellIndex] * dirSin
        angleCompensationSin[cellIndex] = angleSin[cellIndex] * dirCos - angleCos[cellIndex] * dirSin
    }

    override fun persistFields(state: EntityState) {
        state.ints(::links1)
        state.ints(::links2)
        state.ints(::linksGeneration1)
        state.ints(::linksGeneration2)
        state.floats(::linksNaturalLength)
    }

    override fun onCopy() {
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        links1.clear(-1)
        links2.clear(-1)
        linksGeneration1.clear(-1)
        linksGeneration2.clear(-1)
        linksNaturalLength.clear(-10f)

    }

    override fun onResize(oldMax: Int) {
        links1 = links1.resize(-1)
        links2 = links2.resize(-1)
        linksGeneration1 = linksGeneration1.resize(-1)
        linksGeneration2 = linksGeneration2.resize(-1)
        linksNaturalLength = linksNaturalLength.resize(-10f)
    }

    companion object {
        /** Цвет клетки, оставшейся на краю после разрыва связи. Считается один раз. */
        private val EDGE_COLOR = Color.RED.toIntBits()

        /**
         * Поколение "не проверять". Настоящее поколение живой клетки всегда >= 1:
         * Entity.generation начинается с нуля и инкрементируется в add() ДО того, как
         * индекс станет живым. Поэтому -1 не может совпасть ни с одним реальным.
         */
        const val NO_GENERATION_CHECK = -1
    }
}
