package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.PI

class ParticleEntity(
    particlesStartMaxAmount: Int,
    val gridManager: GridManager
): Entity(particlesStartMaxAmount) {
    var gridId = IntArray(maxAmount) { -1 }
    var x = FloatArray(maxAmount)
    var y = FloatArray(maxAmount)
    var vx = FloatArray(maxAmount)
    var vy = FloatArray(maxAmount)
    var radius = FloatArray(maxAmount) { PARTICLE_MAX_RADIUS }
    var mass = FloatArray(maxAmount)
    var color = IntArray(maxAmount)
    var dragCoefficient = FloatArray(maxAmount) { 0.003f }
    var effectOnContact = BooleanArray(maxAmount)
    var isCollidable = BooleanArray(maxAmount)
    var cellStiffness = FloatArray(maxAmount) { 0.5f }
    var isCell = BooleanArray(maxAmount) { false }
    var isSub = BooleanArray(maxAmount) { false }
    var holderEntityIndex = IntArray(maxAmount) { -1 }
    var isPheromoneEmitter = BooleanArray(maxAmount) { false }

    /**
     * Участвует ли частица в пространственной сетке, то есть может ли она столкнуться.
     *
     * false ставится ВНУТРЕННИМ клеткам организма (CellEntity.isOnEdge == false): они
     * закрыты со всех сторон соседями по решётке, поэтому снаружи до них не дотянуться,
     * и держать их в сетке — значит считать пары, ни одна из которых не даёт контакта
     * с внешним миром.
     *
     * Экономия здесь порядковая, а не процентная: у плотно упакованного диска граничных
     * клеток порядка sqrt(n). Для тела на 947 клеток это ~110 из 947, то есть в сетку
     * попадает восьмая часть, а число пар-кандидатов падает примерно как квадрат.
     *
     * Субстанции и всё остальное остаются в сетке: по умолчанию true, и снимается флаг
     * только через CellEntity.refreshOnEdge.
     *
     * ВАЖНО: это НЕ isCollidable. isCollidable решает, отталкиваются ли две частицы,
     * когда пара уже найдена; этот флаг решает, попадёт ли частица в перебор вообще.
     */
    var isInGrid = BooleanArray(maxAmount) { true }

    /**
     * Частицы, которые НЕ являются клетками (isCell == false): субстанции, террейн и всё
     * прочее без владельца в CellEntity.
     *
     * ЗАЧЕМ ОТДЕЛЬНЫЙ СПИСОК
     * ----------------------
     * Сборка буфера рендера шла одним циклом по aliveList с проверкой `if (isCell[i])`
     * внутри. Ветка непредсказуемая (клетки и субстанции перемешаны в порядке создания),
     * а тела у неё совершенно разные: у клетки читаются шесть полей из CellEntity, у
     * субстанции — ни одного. Разделение на два цикла убирает ветку целиком и позволяет
     * обходить клетки по аренам организмов, то есть подряд.
     *
     * Ведётся инкрементально, тем же приёмом, что и aliveList: добавление в конец,
     * удаление через swap-with-last по [positionInNonCellList]. isCell за время жизни
     * частицы не меняется (мутация меняет тип клетки, но не превращает клетку в
     * субстанцию), поэтому трогать список приходится только на создании и удалении.
     */
    var nonCellList = IntArrayList(particlesStartMaxAmount)

    /** Позиция частицы в [nonCellList], или -1 если её там нет (то есть это клетка). */
    var positionInNonCellList = IntArray(maxAmount) { -1 }

    fun addParticle(
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        vx: Float = 0f,
        vy: Float = 0f,
        dragCoefficient: Float = 0.03f,
        effectOnContact: Boolean = false,
        isCollidable: Boolean = true,
        cellStiffness: Float = 0.02f,
        isCell: Boolean,
        isSub: Boolean,
        isPheromoneEmitter: Boolean = false,
        holderEntityIndex: Int
    ): Int = initParticle(
        particleIndex = add(),
        x = x, y = y, radius = radius, color = color, vx = vx, vy = vy,
        dragCoefficient = dragCoefficient, effectOnContact = effectOnContact,
        isCollidable = isCollidable, cellStiffness = cellStiffness,
        isCell = isCell, isSub = isSub, isPheromoneEmitter = isPheromoneEmitter,
        holderEntityIndex = holderEntityIndex
    )

    /**
     * То же, но в ЗАРАНЕЕ ИЗВЕСТНЫЙ слот арены организма.
     *
     * Так частицы клеток остаются параллельны самим клеткам: клетка со смещением k в своей
     * арене владеет частицей с тем же смещением k, и переход клетка -> частица становится
     * арифметикой вместо чтения cellEntity.particleIndexes.
     * См. OrganEntity.particleIndexOfCell.
     *
     * [particleIndex] == -1 означает «арены нет, выдай слот общим аллокатором». Так у
     * вызывающего остаётся одна точка вызова вместо двух почти одинаковых веток с
     * пятнадцатью аргументами каждая — а расходятся эти ветки ровно в одном месте.
     */
    fun addParticleAt(
        particleIndex: Int,
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        vx: Float = 0f,
        vy: Float = 0f,
        dragCoefficient: Float = 0.03f,
        effectOnContact: Boolean = false,
        isCollidable: Boolean = true,
        cellStiffness: Float = 0.02f,
        isCell: Boolean,
        isSub: Boolean,
        isPheromoneEmitter: Boolean = false,
        holderEntityIndex: Int,
        /**
         * Организм, из чьей арены слот. Без владельца умершая частица ушла бы в общий
         * deadStack, и её взяла бы первая же субстанция — например та, что появляется
         * на месте умершей клетки, — а потом арену, выданную заново, addAt нашёл бы занятой.
         */
        arenaOwner: Int = -1
    ): Int = initParticle(
        particleIndex = if (particleIndex == -1) add() else addAt(particleIndex, arenaOwner),
        x = x, y = y, radius = radius, color = color, vx = vx, vy = vy,
        dragCoefficient = dragCoefficient, effectOnContact = effectOnContact,
        isCollidable = isCollidable, cellStiffness = cellStiffness,
        isCell = isCell, isSub = isSub, isPheromoneEmitter = isPheromoneEmitter,
        holderEntityIndex = holderEntityIndex
    )

    private fun initParticle(
        particleIndex: Int,
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        vx: Float,
        vy: Float,
        dragCoefficient: Float,
        effectOnContact: Boolean,
        isCollidable: Boolean,
        cellStiffness: Float,
        isCell: Boolean,
        isSub: Boolean,
        isPheromoneEmitter: Boolean,
        holderEntityIndex: Int
    ): Int {
        // Сетка не мутируется: запоминаем клетку частицы и регистрируем её как новую.
        // В самой сетке частица появится при ближайшей пересборке (в конце этого тика).
        gridId[particleIndex] = gridManager.cellIndexOf(x.toInt(), y.toInt())
        gridManager.registerParticle(particleIndex)

        this.x[particleIndex] = x
        this.y[particleIndex] = y
        this.vx[particleIndex] = vx
        this.vy[particleIndex] = vy
        this.radius[particleIndex] = radius
        this.mass[particleIndex] = radius * radius * PI.toFloat()
        this.color[particleIndex] = color
        this.dragCoefficient[particleIndex] = dragCoefficient
        this.effectOnContact[particleIndex] = effectOnContact
        this.isCollidable[particleIndex] = isCollidable
        this.cellStiffness[particleIndex] = cellStiffness
        this.isCell[particleIndex] = isCell
        this.isSub[particleIndex] = isSub
        this.holderEntityIndex[particleIndex] = holderEntityIndex
        this.isPheromoneEmitter[particleIndex] = isPheromoneEmitter
        // Слот мог достаться от внутренней клетки — сбрасываем, иначе новая частица
        // молча не попала бы в сетку.
        this.isInGrid[particleIndex] = true

        // Субстанции и прочие не-клетки собираются в свой список: буфер рендера обходит
        // их отдельным циклом, без ветки isCell внутри.
        if (!isCell) {
            positionInNonCellList[particleIndex] = nonCellList.size
            nonCellList.add(particleIndex)
        }
        return particleIndex
    }

    fun deleteParticle(particleIndex: Int) {
        delete(particleIndex)

        // Снимается ДО обнуления полей: позиция в списке не-клеток самодостаточна, но
        // порядок важен, если сюда когда-нибудь добавится чтение isCell.
        val nonCellPosition = positionInNonCellList[particleIndex]
        if (nonCellPosition >= 0) {
            val lastPosition = nonCellList.size - 1
            val lastParticle = nonCellList.getInt(lastPosition)
            nonCellList.set(nonCellPosition, lastParticle)
            positionInNonCellList[lastParticle] = nonCellPosition
            nonCellList.removeInt(lastPosition)
            positionInNonCellList[particleIndex] = -1
        }

        // Из сетки мёртвая частица уйдёт сама при пересборке: она пропускает всё,
        // что помечено как !isAlive. Отдельная операция удаления не нужна.
        gridId[particleIndex] = -1

        x[particleIndex] = 0f
        y[particleIndex] = 0f
        vx[particleIndex] = 0f
        vy[particleIndex] = 0f
        radius[particleIndex] = PARTICLE_MAX_RADIUS
        mass[particleIndex] = 0f
        color[particleIndex] = 0
        dragCoefficient[particleIndex] = 0.93f
        effectOnContact[particleIndex] = false
        isCollidable[particleIndex] = true
        cellStiffness[particleIndex] = 0.5f
        isCell[particleIndex] = false
        isSub[particleIndex] = false
        holderEntityIndex[particleIndex] = -1
        isPheromoneEmitter[particleIndex] = false
        isInGrid[particleIndex] = true
    }

    override fun persistFields(state: EntityState) {
        state.ints(::gridId)
        state.floats(::x)
        state.floats(::y)
        state.floats(::vx)
        state.floats(::vy)
        state.floats(::radius)
        state.floats(::mass)
        state.ints(::color)
        state.floats(::dragCoefficient)
        state.booleans(::effectOnContact)
        state.booleans(::isCollidable)
        state.floats(::cellStiffness)
        state.booleans(::isCell)
        state.booleans(::isSub)
        state.ints(::holderEntityIndex)
        state.booleans(::isPheromoneEmitter)
        state.booleans(::isInGrid)
        state.intList(::nonCellList)
        state.ints(::positionInNonCellList)
    }

    /**
     * Сетка не сохраняется — она целиком выводится из gridId и isInGrid. Частицы
     * регистрируются заново и раскладываются одной перестройкой, тем же путём, каким
     * в сетку попадает любая новая частица.
     */
    override fun restoreAfterLoad() {
        gridManager.clearAll()
        for (i in 0 until aliveList.size) {
            gridManager.registerParticle(aliveList.getInt(i))
        }
        gridManager.rebuild(isAlive, gridId, isInGrid)
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        gridId.clear(-1)
        x.clear()
        y.clear()
        vx.clear()
        vy.clear()
        radius.clear(PARTICLE_MAX_RADIUS)
        mass.clear()
        color.clear()
        dragCoefficient.clear(0.03f)
        effectOnContact.clear(false)
        isCollidable.clear(true)
        cellStiffness.clear()
        isCell.clear(false)
        isSub.clear(false)
        holderEntityIndex.clear(-1)
        isPheromoneEmitter.clear(false)
        isInGrid.clear(true)
        nonCellList.clear()
        positionInNonCellList.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        gridId = gridId.resize(-1)
        x = x.resize()
        y = y.resize()
        vx = vx.resize()
        vy = vy.resize()
        radius = radius.resize(PARTICLE_MAX_RADIUS)
        mass = mass.resize()
        color = color.resize()
        dragCoefficient = dragCoefficient.resize(0.03f)
        effectOnContact = effectOnContact.resize(false)
        isCollidable = isCollidable.resize(true)
        cellStiffness = cellStiffness.resize()
        isCell = isCell.resize(false)
        isSub = isSub.resize(false)
        holderEntityIndex = holderEntityIndex.resize(-1)
        isPheromoneEmitter = isPheromoneEmitter.resize(false)
        isInGrid = isInGrid.resize(true)
        positionInNonCellList = positionInNonCellList.resize(-1)
    }
}
