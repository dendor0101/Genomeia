package io.github.some_example_name.old.entities

/**
 * Треугольники тел: сформирован ли каждый треугольник раскладки у каждого организма.
 *
 * ЗАЧЕМ
 * -----
 * Треугольник (три попарно связанные клетки, BakedLayout.triangleSlots) держит форму тела
 * ограничением площади (LinkPhysicsSystem.processTriangles) и закрывает щели для границы
 * (CellEntity.refreshOnEdge). Раньше он считался живым, если живы три его КЛЕТКИ, и включался
 * только у взрослого тела: у растущего три клетки уже есть, а связей между ними ещё нет, и
 * ограничение тянуло вершины без пружин — тело шевелилось весь рост. Здесь треугольник
 * СФОРМИРОВАН, только когда живы все три его СВЯЗИ, — на любой стадии, после разрыва и
 * после смерти клеток одинаково. Тогда цель площади согласована с пружинами, и включать
 * его можно сразу.
 *
 * КАК
 * ---
 * Арена организма: слот треугольника = смещение в раскладке, как у связей. Флаг меняется
 * по месту, когда рождается или умирает связь (LinkEntity.addLink/deleteLink, однопоточно):
 * трогаются только треугольники на этой связи (BakedLayout.trianglesOfLink, в плоском теле
 * их не больше двух) и их вершины. Фаза связей читает флаг подряд по арене — это один байт
 * на треугольник вместо трёх случайных чтений isAlive клеток.
 *
 * Всё здесь выводится из живости связей, но хранится и попадает в хэш: так сохранение и
 * повтор не зависят от того, как пересчитывать.
 */
class TriangleEntity(startMaxAmount: Int) : Entity(startMaxAmount) {

    var formed = BooleanArray(maxAmount)

    /** Новая арена: треугольники умершего организма в этих слотах не наследуются. */
    fun resetRange(base: Int, count: Int) {
        formed.fill(false, base, base + count)
    }

    /**
     * Связь [linkIndex] из арены организма [organIndex] родилась или умерла — уже
     * (isAlive у неё актуален). Пересчитать треугольники на ней.
     */
    fun onLinkChanged(organIndex: Int, linkIndex: Int, organs: OrganEntity, links: LinkEntity, cells: CellEntity) {
        val triangleBase = organs.triangleArenaBase[organIndex]
        if (triangleBase == -1) return
        val layout = organs.arenaLayout[organIndex] ?: return
        val linkBase = organs.linkArenaBase[organIndex]
        val linkSlot = linkIndex - linkBase
        val start = layout.trianglesOfLinkStart
        if (linkSlot < 0 || linkSlot + 1 >= start.size) return

        val list = layout.trianglesOfLink
        val edges = layout.triangleLinkSlots
        val vertices = layout.triangleSlotsArray
        val cellBase = organs.cellArenaBase[organIndex]
        val alive = links.isAlive
        for (k in start[linkSlot] until start[linkSlot + 1]) {
            val t = list[k]
            val now = alive[linkBase + edges[3 * t]] &&
                alive[linkBase + edges[3 * t + 1]] &&
                alive[linkBase + edges[3 * t + 2]]
            if (formed[triangleBase + t] == now) continue
            formed[triangleBase + t] = now
            val delta = if (now) 1 else -1
            for (v in 0..2) {
                val cell = cellBase + vertices[3 * t + v]
                cells.formedTriangles[cell] += delta
                cells.refreshOnEdge(cell)
            }
        }
    }

    /**
     * Под DEBUG_CHECKS: флаги и счётчики клеток совпадают с пересчётом с нуля. Расхождение
     * значит, что какой-то путь рождения или смерти связи прошёл мимо [onLinkChanged].
     */
    fun verify(organs: OrganEntity, links: LinkEntity, cells: CellEntity) {
        val organList = organs.aliveList
        for (i in 0 until organList.size) {
            val organIndex = organList.getInt(i)
            val triangleBase = organs.triangleArenaBase[organIndex]
            if (triangleBase == -1) continue
            val layout = organs.arenaLayout[organIndex] ?: continue
            val linkBase = organs.linkArenaBase[organIndex]
            val cellBase = organs.cellArenaBase[organIndex]
            val edges = layout.triangleLinkSlots
            val vertices = layout.triangleSlotsArray
            val around = IntArray(layout.cellsInLayout)
            for (t in 0 until layout.trianglesInLayout) {
                val expected = edges[3 * t] != -1 &&
                    links.isAlive[linkBase + edges[3 * t]] &&
                    links.isAlive[linkBase + edges[3 * t + 1]] &&
                    links.isAlive[linkBase + edges[3 * t + 2]]
                if (formed[triangleBase + t] != expected) {
                    throw IllegalStateException(
                        "треугольник $t организма $organIndex: сформирован=${formed[triangleBase + t]}, " +
                            "а по связям $expected — рождение или смерть связи прошли мимо onLinkChanged"
                    )
                }
                if (expected) for (v in 0..2) around[vertices[3 * t + v]]++
            }
            for (slot in 0 until layout.cellsInLayout) {
                val cell = cellBase + slot
                if (!cells.isAlive[cell]) continue
                if (cells.formedTriangles[cell] != around[slot]) {
                    throw IllegalStateException(
                        "клетка $cell организма $organIndex: сформированных треугольников " +
                            "${cells.formedTriangles[cell]}, а по связям ${around[slot]}"
                    )
                }
            }
        }
    }

    override fun persistFields(state: EntityState) {
        state.booleans(::formed)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        formed.clear(false)
    }

    override fun onResize(oldMax: Int) {
        formed = formed.resize(false)
    }
}
