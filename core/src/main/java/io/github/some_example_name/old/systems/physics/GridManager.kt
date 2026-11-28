package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.PROFILE_LOG
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.journal.Fnv

/**
 * Пространственная сетка в формате CSR (compressed sparse row), которая перестраивается
 * counting sort'ом один раз за тик.
 *
 * ЧТО БЫЛО
 * --------
 * grid = IntArray(gridSize * maxAmountOfParticles) — по 4 фиксированных слота на клетку,
 * particleCounts на счётчики и Int2ObjectOpenHashMap<IntArrayList> на переполнение.
 * Проблемы:
 *  - Размер: 16384 клетки * 4 слота * 4 байта = 256 КБ, из которых полезны ~34 КБ
 *    (8500 частиц). Фаза коллизий проходит всю сетку, то есть тянет через кэш 256 КБ
 *    ради 34 КБ данных. L2 на ядро — типично 512 КБ-2 МБ, и туда же должны влезать
 *    x/y/vx/vy/radius; в итоге всё выпадает в L3, а L3 общий на все ядра, и потоки
 *    начинают конкурировать за него.
 *  - Лимит в 4 частицы на клетку и хэш-мапа на хвосты: лишняя ветка на каждой клетке,
 *    а на переполненных — поход в хэш-таблицу (промах + разыменование объекта-списка).
 *  - Мутация сетки в горячей фазе: removeParticle делал линейный поиск по слотам,
 *    addParticle читал-писал счётчики. Обе операции — случайный доступ, и обе вызывались
 *    для каждой частицы, сменившей клетку.
 *
 * ЧТО СТАЛО
 * ---------
 *  - cellStart[gridSize + 1] — границы клеток, particleIdx[particleCount] — индексы частиц,
 *    сгруппированные по клеткам. Частицы клетки c лежат в particleIdx[cellStart[c] until
 *    cellStart[c + 1]] строго подряд. Никаких лимитов, хэшей, списков и аллокаций.
 *  - Фаза коллизий читает 64 КБ (cellStart) + 34 КБ (particleIdx) вместо 64 + 256 КБ,
 *    и читает их ЛИНЕЙНО, то есть аппаратный префетчер успевает подтянуть следующие линии.
 *  - Сетка не мутируется вообще. moveParticle только пишет новый номер клетки в gridId
 *    своей частицы (одна запись вместо remove + add), а addParticle/deleteParticle
 *    вообще не трогают сетку.
 *
 * ПОЧЕМУ ЭТО НЕ "СОРТИРОВКА С НУЛЯ КАЖДЫЙ ТИК"
 * --------------------------------------------
 * Counting sort стабильна, и оба её прохода идут по particleIdx С ПРЕДЫДУЩЕГО ТИКА,
 * который уже отсортирован по клеткам. Частицы за тик почти не меняют клетку, поэтому
 * последовательность номеров клеток, которую видит проход, почти монотонно возрастает.
 * Отсюда:
 *  - инкременты cursor[c] попадают в те же несколько кэш-линий (почти L1-локально),
 *  - запись в particleIdxNext идёт почти строго по возрастанию адреса, то есть это
 *    линейная запись, а не разбросанный scatter,
 *  - порядок частиц внутри клетки сохраняется между тиками, значит фаза коллизий
 *    каждый тик обходит их в одном и том же порядке.
 * То есть это ровно "досортировка предыдущего массива", просто выраженная через counting
 * sort: алгоритм O(n) с линейными обращениями вместо инкрементального обновления
 * структуры со сдвигами и дырами.
 *
 * ПРО ПАРАЛЛЕЛЬНОСТЬ
 * ------------------
 * Перестройка сознательно оставлена однопоточной. Причина арифметическая: вся работа —
 * это memset 64 КБ, два линейных прохода по n = 8500 элементов и префиксная сумма по
 * gridSize = 16384, суммарно порядка 40-60 мкс при тике ~1000 мкс. Корректная
 * параллельная схема требует трёх барьеров (гистограммы по полосам -> обмен "эмигрантами"
 * между полосами -> скан сумм полос -> scatter), а один барьер на нынешнем
 * executor.submit + Future.get стоит 10-30 мкс. То есть параллельная версия при текущем
 * n съест ровно столько, сколько сэкономит.
 *
 * Схема на будущее (когда появится дешёвый spin-барьер или n вырастет в разы):
 * полоса t — это непрерывный диапазон клеток [t * gridSize / T, (t + 1) * gridSize / T),
 * а частицы полосы за прошлый тик — непрерывный срез particleIdx[cellStart[bandFirst]
 * until cellStart[bandLast + 1]], потому что particleIdx отсортирован по клеткам. Значит
 * каждый поток читает свой срез без пересечений; частицы, ушедшие в чужую полосу
 * (их единицы — только те, что пересекли границу полосы), складываются в свой буфер
 * эмигрантов, а после барьера каждый поток забирает адресованных ему. Дальше локальная
 * префиксная сумма по своей полосе, скан сумм полос (T чисел) и scatter — каждый поток
 * пишет в свой непрерывный отрезок particleIdxNext, поэтому гонок нет и синхронизация
 * не нужна. Полосы под это нарезаются свои, независимо от чётных/нечётных чанков физики.
 *
 * КОГДА ВЫЗЫВАТЬ
 * --------------
 * Один раз за тик, после всех команд (создание/удаление частиц) и после фазы движения,
 * то есть в самом конце тика. Тогда фазы 1-6 следующего тика видят полностью
 * согласованную сетку. В окне между движением и перестройкой сетка отражает позиции
 * до движения — этим пользуется только редактор (поиск клетки под курсором), которому
 * промах на 1-2 клетки безразличен.
 */
class GridManager (
    var gridWidth: Int,
    var gridHeight: Int,
    val diContext: DIContext,
    /**
     * Осталось только как подсказка ёмкости для утилит, собирающих частицы в список
     * (см. collectParticles). Жёсткого лимита частиц на клетку больше нет.
     */
    val maxAmountOfParticles: Int
): WorldResizable {
    var gridSize = gridWidth * gridHeight

    /**
     * Границы клеток: частицы клетки c — это particleIdx[cellStart[c] until cellStart[c + 1]].
     * Размер gridSize + 1, чтобы у последней клетки была правая граница без ветки.
     */
    var cellStart = IntArray(gridSize + 1)

    /** Индексы частиц, сгруппированные по клеткам. Длина массива >= particleCount. */
    var particleIdx = IntArray(INITIAL_PARTICLE_CAPACITY)

    /** Сколько элементов particleIdx реально занято. */
    var particleCount = 0

    /**
     * Второй буфер под particleIdx: counting sort читает предыдущий порядок и пишет новый,
     * поэтому на месте это делать нельзя. Буферы меняются местами, аллокаций нет.
     */
    private var particleIdxNext = IntArray(INITIAL_PARTICLE_CAPACITY)

    /**
     * Сначала гистограмма (сколько частиц в клетке), потом — курсор записи для scatter.
     * Одно и то же место переиспользуется, потому что после того как значение счётчика
     * учтено в префиксной сумме, оно больше не нужно: вместо него ложится смещение.
     * Так мы обходимся двумя массивами по 64 КБ вместо трёх.
     */
    private var cursor = IntArray(gridSize)

    /** Частицы, созданные с момента прошлой перестройки: их ещё нет в particleIdx. */
    private var pending = IntArray(256)
    private var pendingCount = 0

    /**
     * Статистика занятости сетки, снимается в проходе префиксной суммы (см. [rebuild]).
     * Нужна профайлеру: по ней считается реальная плотность и число пар-кандидатов,
     * а плотность — главный аргумент в вопросе "упёрлись ли мы в кэш". Собирается только
     * при PROFILE_LOG, иначе конструкция вырезается компилятором и цикл остаётся прежним.
     */
    var occupiedCells = 0
        private set

    var maxParticlesInCell = 0
        private set

    /**
     * Отметка "частица лежит в pending". Нужна на один вырожденный случай: индекс мёртвой
     * частицы может быть переиспользован новой в том же тике (deadStack — LIFO), и тогда
     * один и тот же индекс окажется и в старом particleIdx, и в pending — то есть попал бы
     * в сетку дважды. Проверка стоит дёшево: BooleanArray на ~10k частиц это ~10 КБ,
     * он целиком лежит в L1, обращение к нему 1-2 такта.
     */
    private var pendingFlag = BooleanArray(INITIAL_PARTICLE_CAPACITY)

    // ===================================================================================
    // РЕГИСТРАЦИЯ ЧАСТИЦ
    // ===================================================================================

    /** Индекс клетки сетки. Бросает исключение за границами — как старый addParticle. */
    fun cellIndexOf(x: Int, y: Int): Int {
        if (x !in 0..<gridWidth || y < 0 || y >= gridHeight) {
            //TODO Запретить спавн клетки за границей сетки
            throw Exception("Out of grid bounds")
        }
        return y * gridWidth + x
    }

    /** Индекс клетки с зажимом в границы сетки: для движения, где координата уже клампится. */
    fun cellIndexOfClamped(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= gridWidth) gridWidth - 1 else x
        val cy = if (y < 0) 0 else if (y >= gridHeight) gridHeight - 1 else y
        return cy * gridWidth + cx
    }

    /**
     * Сообщить сетке о новой частице. В сетке она появится при следующей перестройке
     * (то есть в конце этого же тика) — до тех пор её просто нет в particleIdx.
     */
    /**
     * Порядок частиц в сетке — для хэша мира (StateHash). Это не производное от позиций:
     * перестройка идёт от прошлого порядка, а по нему перебираются пары столкновений.
     */
    fun hashOrder(hash: Long): Long {
        var h = Fnv.word(hash, particleCount)
        for (i in 0 until particleCount) h = Fnv.word(h, particleIdx[i])
        return h
    }

    fun registerParticle(particleIndex: Int) {
        if (particleIndex >= pendingFlag.size) {
            pendingFlag = pendingFlag.copyOf(particleIndex + (particleIndex shr 1) + 1)
        }
        if (pendingFlag[particleIndex]) return

        pendingFlag[particleIndex] = true
        if (pendingCount >= pending.size) {
            pending = pending.copyOf(pending.size * 2)
        }
        pending[pendingCount] = particleIndex
        pendingCount++
    }

    // ===================================================================================
    // ПЕРЕСТРОЙКА
    // ===================================================================================

    /**
     * Counting sort по номеру клетки. Три линейных прохода:
     *  1) гистограмма по предыдущему порядку (+ новые частицы),
     *  2) префиксная сумма по клеткам — она же превращает гистограмму в курсоры,
     *  3) раскладка частиц по местам.
     *
     * [isAlive] и [particleCell] — это particleEntity.isAlive и particleEntity.gridId.
     * Массивы передаются параметрами, а не через ссылку на сущность, чтобы не заводить
     * циклическую зависимость (ParticleEntity уже держит GridManager) и чтобы ссылки
     * лежали в локальных переменных, а не перечитывались из полей на каждом обращении.
     */
    fun rebuild(isAlive: BooleanArray, particleCell: IntArray, inGrid: BooleanArray) {
        val counts = cursor
        // memset 64 КБ — это ~2 мкс, и это единственная "лишняя" работа, зависящая от
        // размера сетки, а не от числа частиц.
        counts.fill(0)

        val prev = particleIdx
        val prevCount = particleCount
        val flags = pendingFlag
        val pendingList = pending
        val pendingSize = pendingCount

        // --- Проход 1: гистограмма ---
        // Идём по ПРОШЛОМУ порядку, поэтому номера клеток почти монотонно возрастают,
        // и инкременты counts[c] держатся в нескольких кэш-линиях.
        var total = 0
        for (i in 0 until prevCount) {
            val particleIndex = prev[i]
            if (!isAlive[particleIndex]) continue
            // Внутренние клетки организма в сетку не кладутся: снаружи до них не дотянуться,
            // см. ParticleEntity.isInGrid.
            if (!inGrid[particleIndex]) continue
            // Индекс мог быть переиспользован новой частицей в этом же тике: тогда за него
            // отвечает запись в pending, а эту нужно пропустить, иначе будет дубль.
            if (particleIndex < flags.size && flags[particleIndex]) continue
            val cellIndex = particleCell[particleIndex]
            if (cellIndex < 0) continue
            counts[cellIndex]++
            total++
        }
        for (i in 0 until pendingSize) {
            val particleIndex = pendingList[i]
            if (!isAlive[particleIndex]) continue
            // Внутренние клетки организма в сетку не кладутся, см. ParticleEntity.isInGrid.
            if (!inGrid[particleIndex]) continue
            val cellIndex = particleCell[particleIndex]
            if (cellIndex < 0) continue
            counts[cellIndex]++
            total++
        }

        if (particleIdxNext.size < total) {
            val newCapacity = total + (total shr 2)
            particleIdxNext = IntArray(newCapacity)
        }

        // --- Проход 2: префиксная сумма ---
        // За один обход и заполняем границы клеток, и превращаем гистограмму в курсоры.
        val starts = cellStart
        val size = gridSize
        var running = 0
        var occupied = 0
        var maxInCell = 0
        for (cellIndex in 0 until size) {
            val count = counts[cellIndex]
            starts[cellIndex] = running
            counts[cellIndex] = running
            running += count

            if (PROFILE_LOG) {
                // Без if/else, чтобы не добавлять в цикл ветку с плохим предсказанием:
                // при плотности порядка единицы "клетка пустая" — это ~50/50, то есть
                // худший случай для предсказателя. Обе конструкции ложатся в cmov.
                occupied += if (count > 0) 1 else 0
                maxInCell = if (count > maxInCell) count else maxInCell
            }
        }
        starts[size] = running

        if (PROFILE_LOG) {
            occupiedCells = occupied
            maxParticlesInCell = maxInCell
        }

        if (DEBUG_CHECKS && running != total) {
            throw IllegalStateException("prefix sum mismatch: $running != $total")
        }

        // --- Проход 3: раскладка ---
        // Запись в next почти строго по возрастанию адреса — это линейный поток записей,
        // а не разбросанный scatter, потому что источник уже отсортирован по клеткам.
        val next = particleIdxNext
        for (i in 0 until prevCount) {
            val particleIndex = prev[i]
            if (!isAlive[particleIndex]) continue
            // Внутренние клетки организма в сетку не кладутся: снаружи до них не дотянуться,
            // см. ParticleEntity.isInGrid.
            if (!inGrid[particleIndex]) continue
            if (particleIndex < flags.size && flags[particleIndex]) continue
            val cellIndex = particleCell[particleIndex]
            if (cellIndex < 0) continue
            next[counts[cellIndex]] = particleIndex
            counts[cellIndex]++
        }
        for (i in 0 until pendingSize) {
            val particleIndex = pendingList[i]
            if (!isAlive[particleIndex]) continue
            // Внутренние клетки организма в сетку не кладутся, см. ParticleEntity.isInGrid.
            if (!inGrid[particleIndex]) continue
            val cellIndex = particleCell[particleIndex]
            if (cellIndex < 0) continue
            next[counts[cellIndex]] = particleIndex
            counts[cellIndex]++
        }

        // Буферы меняются местами: то, что было прошлым порядком, станет next в следующий раз.
        particleIdxNext = prev
        particleIdx = next
        particleCount = total

        // Флаги гасим только по списку pending, а не fill'ом всего массива.
        for (i in 0 until pendingSize) {
            flags[pendingList[i]] = false
        }
        pendingCount = 0
    }

    /**
     * Проверка инвариантов сетки. Вызывать сразу после [rebuild], под DEBUG_CHECKS.
     *
     * ЗАЧЕМ
     * -----
     * «Частицу видно, но она ни с чем не сталкивается и не берётся курсором» — это ровно
     * то, как выглядит частица, которой нет в particleIdx либо которая лежит в сетке не в
     * той клетке. Рисуется она из aliveList по x/y, а вот столкновения и попадание курсором
     * идут через сетку, поэтому расхождение между этими двумя источниками невидимо до тех
     * пор, пока его не проверить явно.
     *
     * После rebuild инвариант должен выполняться ТОЧНО: gridId обновляется в moveParticle
     * при каждой смене целочисленной клетки, а частицы, созданные позже фазы движения,
     * получают gridId прямо в addParticle и до конца тика не двигаются.
     *
     * Проверка однопоточная и стоит O(maxAmount + particleCount), то есть в релизе её нет
     * вообще (DEBUG_CHECKS — const val), а в отладке это доли миллисекунды на тик.
     *
     * ЧТО ОЗНАЧАЕТ КАЖДОЕ ПАДЕНИЕ
     * ---------------------------
     *  - "нет в сетке" — частица жива, но не попала ни в particleIdx, ни в pending. Значит
     *    её потеряли при регистрации или при перестройке: искать в registerParticle и в
     *    том, кто её создал.
     *  - "лежит в чужой клетке" — gridId разошёлся с позицией. Значит для неё не отработал
     *    moveParticle: искать в раскладке по стекам чанков (кто-то не попал в стек) либо
     *    в самой фазе движения.
     *  - "дубль" / "не та клетка в CSR" — испорчен сам counting sort.
     */
    fun verifyIntegrity(
        isAlive: BooleanArray,
        particleCell: IntArray,
        inGrid: BooleanArray,
        particles: io.github.some_example_name.old.entities.ParticleEntity
    ) {
        val seen = BooleanArray(isAlive.size)

        for (slot in 0 until particleCount) {
            val particleIndex = particleIdx[slot]

            if (particleIndex !in isAlive.indices) {
                throw IllegalStateException("сетка: мусорный индекс $particleIndex в позиции $slot")
            }
            if (!isAlive[particleIndex]) {
                throw IllegalStateException("сетка: мёртвая частица $particleIndex в позиции $slot")
            }
            if (seen[particleIndex]) {
                throw IllegalStateException("сетка: дубль частицы $particleIndex в позиции $slot")
            }
            seen[particleIndex] = true

            // Клетка, в чей диапазон CSR попала эта позиция, обязана совпасть с gridId.
            val declared = particleCell[particleIndex]
            if (declared < 0 || declared >= gridSize ||
                slot < cellStart[declared] || slot >= cellStart[declared + 1]
            ) {
                throw IllegalStateException(
                    "сетка: частица $particleIndex лежит в позиции $slot, а её gridId=$declared " +
                        "занимает [${if (declared in 0 until gridSize) cellStart[declared] else -1}, " +
                        "${if (declared in 0 until gridSize) cellStart[declared + 1] else -1})"
                )
            }
        }

        for (particleIndex in isAlive.indices) {
            if (!isAlive[particleIndex]) continue
            // Внутренней клетки в сетке нет по построению — это не расхождение.
            if (!inGrid[particleIndex]) continue

            if (!seen[particleIndex]) {
                throw IllegalStateException(
                    "сетка: живой частицы $particleIndex нет в сетке; " +
                        "x=${particles.x(particleIndex)} y=${particles.y(particleIndex)} " +
                        "gridId=${particleCell[particleIndex]} (строка ${particles.y(particleIndex).toInt()})"
                )
            }

            val actual = cellIndexOfClamped(particles.x(particleIndex).toInt(), particles.y(particleIndex).toInt())
            if (particleCell[particleIndex] != actual) {
                throw IllegalStateException(
                    "сетка: частица $particleIndex лежит в чужой клетке; " +
                        "gridId=${particleCell[particleIndex]} а по позиции должна быть $actual; " +
                        "x=${particles.x(particleIndex)} y=${particles.y(particleIndex)} (строка ${particles.y(particleIndex).toInt()})"
                )
            }
        }
    }

    // ===================================================================================
    // ЧТЕНИЕ СЕТКИ (без аллокаций)
    //
    // ВАЖНО: обход валиден, пока сетку не перестраивают. Фазы коллизий, связей, клеток
    // и движения этому условию удовлетворяют: они не вызывают rebuild и не меняют
    // cellStart/particleIdx.
    // ===================================================================================

    /** Индекс клетки сетки или -1, если координаты вне сетки. */
    fun cellIndexAt(x: Int, y: Int): Int =
        if (x !in 0..<gridWidth || y < 0 || y >= gridHeight) -1 else y * gridWidth + x

    /** Первый индекс в [particleIdx], принадлежащий клетке. */
    fun cellFrom(cellIndex: Int): Int = cellStart[cellIndex]

    /** Граница за последним индексом клетки. */
    fun cellTo(cellIndex: Int): Int = cellStart[cellIndex + 1]

    /** Сколько частиц в клетке. */
    fun countInCell(cellIndex: Int): Int = cellStart[cellIndex + 1] - cellStart[cellIndex]

    /** Обход всех частиц клетки. Ветки на переполнение больше нет — путь всегда один. */
    inline fun forEachParticleInCellIndex(cellIndex: Int, action: (Int) -> Unit) {
        val starts = cellStart
        val indices = particleIdx
        val to = starts[cellIndex + 1]
        for (i in starts[cellIndex] until to) action(indices[i])
    }

    /** Обход всех частиц клетки по координатам. Вне сетки — просто ничего не делает. */
    inline fun forEachParticleAt(x: Int, y: Int, action: (Int) -> Unit) {
        val cellIndex = cellIndexAt(x, y)
        if (cellIndex < 0) return
        forEachParticleInCellIndex(cellIndex, action)
    }

    /**
     * Обход частиц горизонтального отрезка одного ряда [xFrom-xTo] (границы клампятся).
     *
     * У соседних клеток одного ряда индексы идут подряд, а в CSR это значит, что и их
     * частицы лежат в particleIdx одним непрерывным куском: cellStart[from] .. cellStart[to+1].
     * Поэтому весь отрезок читается одним линейным проходом, без обращения к cellStart
     * на каждую клетку.
     */
    inline fun forEachParticleInRowSegment(y: Int, xFrom: Int, xTo: Int, action: (Int) -> Unit) {
        if (y !in 0..<gridHeight) return
        val from = if (xFrom < 0) 0 else xFrom
        val to = if (xTo >= gridWidth) gridWidth - 1 else xTo
        if (from > to) return

        val rowBase = y * gridWidth
        val starts = cellStart
        val indices = particleIdx
        val last = starts[rowBase + to + 1]
        for (i in starts[rowBase + from] until last) action(indices[i])
    }

    /**
     * Копирует частицы клетки в переиспользуемый буфер [dst], возвращает количество.
     * Нужна там, где требуется случайный доступ к списку.
     */
    fun copyParticlesInto(cellIndex: Int, dst: IntArray): Int {
        val from = cellStart[cellIndex]
        val count = cellStart[cellIndex + 1] - from
        if (count <= 0) return 0
        if (dst.size < count) throw IllegalArgumentException("dst is too small: ${dst.size} < $count")
        System.arraycopy(particleIdx, from, dst, 0, count)
        return count
    }

    /**
     * АЛЛОЦИРУЕТ новый массив. Не использовать в горячем цикле физики —
     * для обхода есть [forEachParticleAt] / [forEachParticleInCellIndex] / [copyParticlesInto].
     */
    fun getParticles(x: Int, y: Int): IntArray {
        val cellIndex = cellIndexAt(x, y)
        if (cellIndex < 0) return EMPTY_PARTICLES
        return getParticlesIndex(cellIndex)
    }

    /** АЛЛОЦИРУЕТ новый массив. См. комментарий к [getParticles]. */
    fun getParticlesIndex(cellIndex: Int): IntArray {
        val from = cellStart[cellIndex]
        val to = cellStart[cellIndex + 1]
        if (to <= from) return EMPTY_PARTICLES
        return particleIdx.copyOfRange(from, to)
    }

    fun clearAll() {
        cellStart.fill(0)
        cursor.fill(0)
        particleCount = 0
        for (i in 0 until pendingCount) pendingFlag[pending[i]] = false
        pendingCount = 0
        clearStatic()
    }

    override fun resize() {
        gridWidth = diContext.gridWidth
        gridHeight = diContext.gridHeight
        gridSize = gridWidth * gridHeight
        cellStart = IntArray(gridSize + 1)
        cursor = IntArray(gridSize)
        particleCount = 0
        pendingCount = 0
        pendingFlag.fill(false)
        staticStart = IntArray(0)
        staticCount = 0
    }

    // ===================================================================================
    // НЕПОДВИЖНЫЙ СЛОЙ (27.11)
    // ===================================================================================
    //
    // Террейн и стенка у рамки за сессию не двигаются и не умирают, а до 27.11 лежали в
    // перестраиваемой сетке: каждый тик пересборка раскладывала их заново, а столкновения
    // перебирали пары «террейн — террейн», у которых ничего не бывает. Замер на телефонах
    // (стенд PhonePerfLauncher, мир 256 с 66 тысячами частиц террейна): COLLIDE + REBUILD —
    // 25 мс тика A21s (13%) и 5.8 мс S24 (16%). Теперь они в своём слое: раскладка один раз
    // на мир (ParticleEntity.bakeStaticGrid — после постройки мира и после загрузки), в
    // перестраиваемую сетку не идут (isInGrid = false), столкновения смотрят их только со
    // стороны подвижной частицы, глаза и выбор касанием — оба слоя.
    //
    // Объём: (gridSize + 1) int на границы плюс int на частицу — мир 256×256 и 66 тысяч
    // частиц: 256 КБ + 264 КБ, один раз на мир.

    /** Границы клеток неподвижного слоя, как [cellStart]; пустой — слоя нет. */
    var staticStart = IntArray(0)
        private set

    /** Частицы неподвижного слоя по клеткам, [staticCount] штук. */
    var staticIdx = IntArray(0)
        private set
    var staticCount = 0
        private set

    /**
     * Разложить неподвижные частицы [particles] (первые [count]) по клеткам [particleCell] —
     * counting sort, внутри клетки в порядке [particles]. Однопоточно, между тиками.
     */
    fun buildStatic(particles: IntArray, count: Int, particleCell: IntArray) {
        if (staticStart.size != gridSize + 1) staticStart = IntArray(gridSize + 1) else staticStart.fill(0)
        if (staticIdx.size < count) staticIdx = IntArray(count)
        val starts = staticStart
        for (k in 0 until count) starts[particleCell[particles[k]] + 1]++
        for (c in 0 until gridSize) starts[c + 1] += starts[c]
        val write = starts.copyOf(gridSize)
        for (k in 0 until count) {
            val p = particles[k]
            staticIdx[write[particleCell[p]]++] = p
        }
        staticCount = count
    }

    fun clearStatic() {
        if (staticStart.isNotEmpty()) staticStart.fill(0)
        staticCount = 0
    }

    /** Неподвижные частицы клетки [x], [y]; за сеткой — ничего. */
    inline fun forEachStaticAt(x: Int, y: Int, action: (Int) -> Unit) {
        if (staticCount == 0 || x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return
        val cellIndex = y * gridWidth + x
        val starts = staticStart
        val indices = staticIdx
        for (i in starts[cellIndex] until starts[cellIndex + 1]) action(indices[i])
    }

    /** Частицы клетки из обоих слоёв: перестраиваемого и неподвижного (глаз видит и террейн). */
    inline fun forEachParticleOrStaticAt(x: Int, y: Int, action: (Int) -> Unit) {
        forEachParticleAt(x, y, action)
        forEachStaticAt(x, y, action)
    }

    /** Неподвижные частицы клеток [xFrom]..[xTo] ряда [y] — одним отрезком, как [forEachParticleInRowSegment]. */
    inline fun forEachStaticInRowSegment(y: Int, xFrom: Int, xTo: Int, action: (Int) -> Unit) {
        if (staticCount == 0 || y < 0 || y >= gridHeight) return
        val from = if (xFrom < 0) 0 else xFrom
        val to = if (xTo >= gridWidth) gridWidth - 1 else xTo
        if (from > to) return
        val rowBase = y * gridWidth
        val starts = staticStart
        val indices = staticIdx
        for (i in starts[rowBase + from] until starts[rowBase + to + 1]) action(indices[i])
    }

    companion object {
        /**
         * Один общий пустой массив вместо IntArray(0) на каждый промах границы сетки.
         * Иммутабелен по контракту: возвращается только для чтения.
         */
        @JvmField
        val EMPTY_PARTICLES = IntArray(0)

        private const val INITIAL_PARTICLE_CAPACITY = 4096
    }
}
