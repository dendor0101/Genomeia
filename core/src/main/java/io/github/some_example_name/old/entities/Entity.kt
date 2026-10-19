package io.github.some_example_name.old.entities

import it.unimi.dsi.fastutil.ints.IntArrayList

abstract class Entity(startMaxAmount: Int) {
    protected var maxAmount = startMaxAmount
    var lastId = -1

    var deadStack = IntArrayList(startMaxAmount)

    var isAlive = BooleanArray(maxAmount)
    private var generation = IntArray(maxAmount)
    fun getGeneration(index: Int) = generation[index]
    fun isAliveAndSameGen(index: Int, gen: Int) = isAlive[index] && generation[index] == gen

    var aliveList = IntArrayList(startMaxAmount)
    var positionInAlive = IntArray(maxAmount) { -1 }
    private var cellBoundBeforeClear = 0
    private var oldMaxBeforeResize = 0

    /**
     * Организм, из чьей арены выдан индекс, или -1 если индекс не из арены.
     *
     * Нужен на удалении: слот обязан вернуться в свободный список СВОЕЙ арены, а к моменту
     * delete() определить организм по содержимому сущности уже нельзя. У связи, например,
     * organIndex берётся через её клетку, а клетку к этому моменту могли удалить — она и
     * стала причиной удаления связи, — и её organIndex уже сброшен в -1.
     *
     * Поэтому владелец запоминается в момент выдачи, а не вычисляется в момент возврата.
     */
    private var arenaOwner = IntArray(maxAmount) { -1 }

    /** Организм-владелец слота, или -1 если слот не из арены. */
    fun arenaOwnerOf(index: Int) = arenaOwner[index]

    companion object {
        /**
         * Владелец «слот выдаёт кто-то снаружи»: сущность, индексируемая чужим индексом
         * (SpecialEntity — индексом клетки). Умерший слот в [deadStack] не идёт: add()
         * у такой сущности не зовётся, и стек только рос бы — в памяти и в сохранении.
         */
        const val EXTERNAL_SLOT = -2
    }

    protected fun add(): Int {
        val cellIndex = if (!deadStack.isEmpty()) {
            deadStack.removeInt(deadStack.size - 1)
        } else {
            ++lastId
        }

        isAlive[cellIndex] = true
        arenaOwner[cellIndex] = -1
        generation[cellIndex]++

        val pos = aliveList.size
        aliveList.add(cellIndex)
        positionInAlive[cellIndex] = pos

        if (maxAmount - 2 < lastId) {
            resize()
        }
        return cellIndex
    }

    /**
     * Занять КОНКРЕТНЫЙ индекс, минуя deadStack и lastId.
     *
     * Так сущности заполняются по аренам: организм получает непрерывный диапазон при
     * рождении и раздаёт из него слоты по мере роста, поэтому все его клетки, частицы и
     * связи лежат в массивах подряд, а не там, куда их положил общий стек переиспользования.
     *
     * [lastId] всё равно ведётся как максимальный использованный индекс — на него опираются
     * clear() и все обходы вида `0..lastId`.
     */
    protected fun addAt(index: Int, arenaOwner: Int = -1): Int {
        ensureCapacity(index)

        if (isAlive[index]) {
            throw IllegalStateException(
                "арена выдала занятый индекс $index — пересечение арен или сбой курсора"
            )
        }

        isAlive[index] = true
        this.arenaOwner[index] = arenaOwner
        generation[index]++

        val pos = aliveList.size
        aliveList.add(index)
        positionInAlive[index] = pos

        if (index > lastId) lastId = index
        return index
    }

    /**
     * Растит массивы так, чтобы [requiredIndex] стал валидным.
     *
     * Аренам это нужно потому, что они выдают индексы ВПЕРЁД: организм резервирует
     * диапазон целиком при рождении, задолго до того, как заполнит его клетками. Обычный
     * рост по lastId такого не покрывает — он реагирует на уже занятый индекс.
     *
     * Цикл, а не одна resize(): шаг роста задан в resize() (5/4), и повторять его до
     * покрытия дешевле, чем дублировать здесь логику пересоздания всех массивов подсущности.
     * Копирование геометрическое, то есть амортизированно линейное, а вызывается это только
     * при рождении организма.
     */
    fun ensureCapacity(requiredIndex: Int) {
        while (requiredIndex > maxAmount - 2) {
            resize()
        }
    }

    /**
     * Забронировать непрерывный диапазон из [count] индексов и вернуть его начало.
     *
     * Ключевое здесь — что бронь СРАЗУ сдвигает [lastId] за конец диапазона. Иначе между
     * резервированием арены и её заполнением обычный [add] выдал бы индекс ВНУТРЬ неё:
     * организм резервирует тысячу слотов при рождении, а занимает их по одному в течение
     * тысяч тиков, и всё это время диапазон для общего аллокатора выглядел бы свободным.
     * Именно так первая версия и падала — субстанция получала слот 0, который арена уже
     * считала своим.
     *
     * Диапазон берётся строго ЗА текущим [lastId], а не с нуля: слоты ниже уже могли быть
     * розданы общим аллокатором (субстанции и террейн появляются раньше первого организма).
     * [deadStack] при этом не трогается — дырки ниже брони остаются доступны обычному
     * add(), и арены с ними не пересекаются, потому что бронь всегда выше.
     */
    fun reserveRange(count: Int): Int {
        val reused = takeFreeRange(count)
        if (reused != -1) return reused

        val base = lastId + 1
        lastId = base + count - 1
        ensureCapacity(lastId)
        return base
    }

    // ===================================================================================
    // СВОБОДНЫЕ ДИАПАЗОНЫ
    //
    // Арена умершего организма возвращается сюда и выдаётся следующему. Без этого бронь
    // только росла: каждое рождение навсегда съедало свой диапазон (тело на 1200 клеток —
    // ~0.6 МБ всех массивов), и за долгую сессию массивы раздувались до сотен мегабайт.
    //
    // Пары [начало, длина] подряд, по возрастанию начала, соседние всегда склеены. Это
    // состояние мира: в сохранении и в хэше (StateHash). Список
    // короткий (по куску на умерший и ещё не занятый организм), а трогается он только на
    // рождении и смерти организма, поэтому здесь простой перебор — дерево не окупится.
    // Особей одного генома много, и дыра умершего обычно в точности под следующего такого
    // же, поэтому выбирается НАИМЕНЬШИЙ подходящий кусок: так большие не крошатся на мелочь.
    // ===================================================================================

    private var freeRanges = IntArrayList()

    /** Начало подходящего свободного куска (откусывается его начало), или -1. */
    private fun takeFreeRange(count: Int): Int {
        var best = -1
        var bestLength = Int.MAX_VALUE
        var i = 0
        while (i < freeRanges.size) {
            val length = freeRanges.getInt(i + 1)
            // Строго меньше: при равной длине остаётся первый, то есть с меньшим адресом.
            // Выбор обязан зависеть только от состояния мира — иначе разойдётся повтор.
            if (length >= count && length < bestLength) {
                best = i
                bestLength = length
            }
            i += 2
        }
        if (best == -1) return -1

        val base = freeRanges.getInt(best)
        if (bestLength == count) {
            freeRanges.removeElements(best, best + 2)
        } else {
            freeRanges.set(best, base + count)
            freeRanges.set(best + 1, bestLength - count)
        }
        return base
    }

    /**
     * Вернуть диапазон, выданный [reserveRange]. Вызывать, когда в нём не осталось
     * живых сущностей его владельца.
     *
     * Освобождаются только МЁРТВЫЕ слоты, подряд идущими кусками. Живой слот внутри —
     * это чужой жилец: в сохранениях до починки слотов частиц субстанция могла занять
     * частицу клетки через общий [deadStack]. Отдать его под новую арену нельзя — addAt
     * упал бы на занятом индексе, — поэтому он просто остаётся вне свободного списка.
     */
    fun releaseRange(base: Int, count: Int) {
        val end = minOf(base + count, lastId + 1)
        var runStart = -1
        for (index in base until end) {
            if (!isAlive[index]) {
                if (runStart == -1) runStart = index
            } else if (runStart != -1) {
                addFreeRange(runStart, index - runStart)
                runStart = -1
            }
        }
        if (runStart != -1) addFreeRange(runStart, end - runStart)
    }

    private fun addFreeRange(start: Int, length: Int) {
        // Место вставки по началу — список отсортирован.
        var i = 0
        while (i < freeRanges.size && freeRanges.getInt(i) < start) i += 2

        var newStart = start
        var newEnd = start + length

        // Склейка с левым соседом.
        if (i >= 2 && freeRanges.getInt(i - 2) + freeRanges.getInt(i - 1) == newStart) {
            i -= 2
            newStart = freeRanges.getInt(i)
            freeRanges.removeElements(i, i + 2)
        }
        // Склейка с правым.
        if (i < freeRanges.size && freeRanges.getInt(i) == newEnd) {
            newEnd += freeRanges.getInt(i + 1)
            freeRanges.removeElements(i, i + 2)
        }

        // Кусок на самом верху lastId НЕ опускает, хотя мог бы. Строки выше lastId не
        // сохраняются, и мир после загрузки получил бы там значения по умолчанию, а мир без
        // загрузки — остатки умерших (поколения, поля, которые addCell не сбрасывает).
        // Следующая арена в этих слотах разошлась бы между ними, и saveRoundTrip с повтором
        // сохранённого мира это поймали бы. Опускать — только вместе со сбросом строк.
        freeRanges.add(i, newStart)
        freeRanges.add(i + 1, newEnd - newStart)
    }

    /** Сколько слотов ниже lastId лежит в свободных кусках — для замеров дробления. */
    fun freeRangeSlots(): Int {
        var total = 0
        var i = 1
        while (i < freeRanges.size) {
            total += freeRanges.getInt(i)
            i += 2
        }
        return total
    }

    /**
     * Отдать арене [owner] слоты её диапазона, которые выданы без владельца. Только для
     * загрузки старых сохранений; [inDeadStack] — из [deadStackMask].
     *
     * До починки частицы клеток выдавались без владельца, и умершая частица уходила в
     * общий стек — откуда её брала субстанция. Здесь такие слоты (живые без владельца и
     * мёртвые из стека) получают владельца, а [dropOwnedFromDeadStack] уберёт их из стека.
     * Живой чужой жилец остаётся жить, но после смерти в стек уже не вернётся.
     *
     * Ни разу не занятые слоты не трогаются: в мире, сохранённом уже с починкой, у них
     * владельца нет, и загрузка обязана дать ровно то же состояние (saveRoundTrip).
     */
    fun adoptRange(base: Int, count: Int, owner: Int, inDeadStack: BooleanArray) {
        val end = minOf(base + count, lastId + 1)
        for (index in base until end) {
            if (arenaOwner[index] != -1) continue
            if (isAlive[index] || inDeadStack[index]) arenaOwner[index] = owner
        }
    }

    /** Какие слоты лежат в [deadStack] — разовая маска для [adoptRange]. */
    fun deadStackMask(): BooleanArray {
        val mask = BooleanArray(lastId + 1)
        for (i in 0 until deadStack.size) mask[deadStack.getInt(i)] = true
        return mask
    }

    /** Убрать из [deadStack] слоты, у которых есть владелец-арена. Пара к [adoptRange]. */
    fun dropOwnedFromDeadStack() {
        var write = 0
        for (read in 0 until deadStack.size) {
            val index = deadStack.getInt(read)
            if (arenaOwner[index] == -1) deadStack.set(write++, index)
        }
        deadStack.size(write)
    }

    protected fun delete(index: Int) {
        if (!isAlive[index]) throw IllegalStateException("Entity $index is already dead")

        isAlive[index] = false
        // Слот из арены в общий стек не возвращается — иначе его получит чужой организм.
        // Он вернётся в свободный список СВОЕЙ арены, см. [arenaOwner] и
        // OrganEntity.releaseCellSlot.
        if (arenaOwner[index] == -1) deadStack.add(index)

        val pos = positionInAlive[index]
        if (pos >= 0) {
            val lastPos = aliveList.size - 1
            val lastEntity = aliveList.getInt(lastPos)

            aliveList.set(pos, lastEntity)
            positionInAlive[lastEntity] = pos

            aliveList.removeInt(lastPos)

            positionInAlive[index] = -1
        }
    }

    fun clear() {
        val cellBound = (lastId + 1).coerceAtLeast(0)
        cellBoundBeforeClear = cellBound
        lastId = -1
        deadStack.clear()
        freeRanges.clear()
        generation.fill(0, 0, cellBound)
        isAlive.fill(false, 0, cellBound)
        arenaOwner.fill(-1, 0, cellBound)

        aliveList.clear()
        positionInAlive.fill(-1, 0, cellBound)

        onClear(cellBound)
    }

    fun resize() {
        val oldMax = maxAmount
        oldMaxBeforeResize = oldMax
        maxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)
        run {
            val old = generation
            generation = IntArray(maxAmount)
            System.arraycopy(old, 0, generation, 0, oldMax)
        }
        run {
            val old = isAlive
            isAlive = BooleanArray(maxAmount)
            System.arraycopy(old, 0, isAlive, 0, oldMax)
        }
        run {
            val old = arenaOwner
            arenaOwner = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, arenaOwner, 0, oldMax)
        }
        run {
            val old = positionInAlive
            positionInAlive = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, positionInAlive, 0, oldMax)
        }

        aliveList.ensureCapacity(maxAmount)

        onResize(oldMax)
    }

    // ===================================================================================
    // СОХРАНЕНИЕ МИРА
    // ===================================================================================

    fun saveState(state: EntityState) {
        persist(state)
    }

    /**
     * Заменить содержимое сущности сохранённым.
     *
     * Массивы не подменяются, а растятся штатным [ensureCapacity] и заполняются на месте:
     * так растут и те массивы, которых нет в сохранении, и ссылки на сущность у систем
     * остаются верными.
     */
    fun loadState(state: EntityState) {
        clear()
        persist(state)
    }

    private fun persist(state: EntityState) {
        // lastId читается первым: от него зависит, сколько места нужно под массивы.
        val savedLastId = state.int("lastId", lastId)
        if (state.isReading) {
            if (savedLastId >= 0) ensureCapacity(savedLastId)
            lastId = savedLastId
        }

        state.ints(::generation)
        state.booleans(::isAlive)
        state.ints(::positionInAlive)
        state.ints(::arenaOwner)
        state.intList(::aliveList)
        state.intList(::deadStack)
        state.intList(::freeRanges)

        persistFields(state)
    }

    /** Свои поля сущности, см. [EntityState]. */
    protected open fun persistFields(state: EntityState) {}

    /**
     * Пересобрать после загрузки то, что выводится из сохранённых данных.
     *
     * Зовётся, когда загружены ВСЕ сущности мира: часть индексов собирается по соседним
     * сущностям (у клетки — по её организму).
     */
    open fun restoreAfterLoad() {}

    protected abstract fun onCopy()
    protected abstract fun onPaste()
    protected abstract fun onClear(bound: Int)
    protected abstract fun onResize(oldMax: Int)

    protected fun FloatArray.clear(defaultValue: Float = 0f) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun IntArray.clear(defaultValue: Int = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun BooleanArray.clear(defaultValue: Boolean) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun ByteArray.clear(defaultValue: Byte = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }


    protected fun FloatArray.resize(defaultValue: Float = 0f): FloatArray {
        val old = this
        val newArray = if (defaultValue == 0f)
            FloatArray(maxAmount)
        else
            FloatArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun IntArray.resize(defaultValue: Int = 0): IntArray {
        val old = this
        val newArray = if (defaultValue == 0)
            IntArray(maxAmount)
        else
            IntArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun BooleanArray.resize(defaultValue: Boolean): BooleanArray {
        val old = this
        val newArray = BooleanArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun ByteArray.resize(defaultValue: Byte = 0): ByteArray {
        val old = this
        val newArray = if (defaultValue == 0.toByte())
            ByteArray(maxAmount)
        else
            ByteArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }
}
