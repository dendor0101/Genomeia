package io.github.some_example_name.old.systems.physics

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.PROFILE_COUNTERS
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer.linkMaxLength2
import io.github.some_example_name.old.core.DISimulationContainer.threadManager
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.SimCounters
import io.github.some_example_name.old.systems.simulation.SimulationData

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager,
    val diContext: DIContext,
    val simulationData: SimulationData
) {

    companion object {
        /**
         * Какую ДОЛЮ ошибки площади ограничение исправляет за тик.
         *
         * Благодаря нормировке на |grad|^2 (см. processTriangles) величина безразмерна и
         * не зависит ни от размера треугольника, ни от размера клеток: 0.2 означает
         * «убрать пятую часть расхождения за тик», и это читается напрямую.
         *
         * Осмысленный диапазон — (0, 1). Ближе к единице тело становится почти
         * нерастяжимым; выше единицы поправка перелетает цель и начинается раскачка.
         */
        const val TRIANGLE_STIFFNESS = 0.5f

        /**
         * Демпфирование: сколько скорости изменения площади гасится за тик.
         *
         * Без него ограничение чисто консервативно — возвращает вершину на место, но не
         * забирает набранную по дороге энергию, и тело раскачивается до разлёта. У пружин
         * рядом ровно та же конструкция (dampeningConstant = 0.3).
         *
         * Единица — «погасить всё изменение площади за тик»; больше единицы это
         * переторможение с отдачей в обратную сторону.
         */
        const val TRIANGLE_DAMPING = 0.7f

        /**
         * Относительная ошибка площади, ниже которой треугольник не трогается.
         *
         * Зона покоя нужна не столько ради экономии деления, сколько ради устойчивости:
         * без неё ограничение постоянно поправляет вершины на уровне численного шума,
         * а поправки складываются — каждая клетка входит примерно в шесть треугольников.
         */
        const val TRIANGLE_TOLERANCE = 0.002f

        /**
         * Ниже этой суммы квадратов градиента треугольник считается вырожденным.
         *
         * У схлопнувшегося в отрезок градиент стремится к нулю, а деление на него — к
         * бесконечности. Такую тройку правильнее пропустить: направление восстановления
         * у неё не определено, и вытащить её ограничением площади всё равно нельзя.
         */
        const val MIN_GRAD_SQUARED = 1e-8f

        /** Номер куска «организм целиком» в работах фазы связей. */
        private const val WHOLE = -1
    }

    /**
     * ОБХОД ИДЁТ ПО АРЕНАМ, а не по спискам чётности.
     *
     * Списки хранили индексы связей в порядке регистрации, то есть обход прыгал по массивам
     * LinkEntity как попало — и запечённый RCM-порядок не давал ровно ничего: он определял,
     * ГДЕ связь лежит, но не в каком порядке её читают. Замер это и показал: локальность
     * улучшили, время фазы не изменилось.
     *
     * Диапазон арены обходится подряд, а внутри него связи уложены по возрастанию
     * min(слот концов) — значит подряд идущие связи трогают соседние клетки, и окно
     * горячих данных сжимается до ширины ленты графа вместо всего тела.
     *
     * ПРО ГОНКИ
     * ---------
     * Гонка в этой фазе возможна только между связями с ОБЩЕЙ КЛЕТКОЙ, а общие клетки
     * бывают исключительно внутри одного организма. Организм целиком достаётся одному
     * воркеру, значит гонок нет — и это более прямое обоснование, чем прежняя приписка
     * по якорю: она давала то же свойство окольным путём, через геометрию чанков.
     *
     * КРУПНОЕ ТЕЛО РЕЖЕТСЯ НА КУСКИ
     * ----------------------------
     * Организм целиком — одна работа, пока он мелкий. Крупный режется по слотам клеток на
     * куски (LayoutChunks), и фаза идёт двумя стадиями: сначала чётные куски всех крупных
     * тел вместе с мелкими телами целиком, потом нечётные. Почему чётные куски одного тела
     * не гоняются между собой и почему результат не зависит от воркеров — см. LayoutChunks.
     * Цена — один лишний барьер за тик, и только когда в мире есть крупное тело.
     *
     * ГРАНИЦА ПРИМЕНИМОСТИ
     * --------------------
     * Обходятся ТОЛЬКО связи организмов с аренами. Связь между клетками без организма
     * (organIndex == -1) в арену не попадёт и обсчитана не будет. Сейчас таких клеток в
     * мире нет: организм с ареной заводится раньше зиготы, см. OrganismSpawner. Появятся
     * бесхозные клетки со связями — этот путь придётся вернуть.
     */
    fun iterateLinksInParallel() {
        planWorks()

        // Работ больше, чем воркеров, и это нормально: раздача динамическая, а организмы
        // разного размера. Номер работы и номер воркера здесь РАЗНЫЕ вещи — в буферы
        // команд индексируемся вторым, иначе слот №9 писал бы за границу массива.
        threadManager.runWorkStage(evenCount, Phase.LINKS) { work, workerId ->
            processWork(evenOrgan[work], evenChunk[work], workerId)
        }
        // Вторая стадия — свой номер в WorkOrder, поэтому разрывы связей из неё применятся
        // после разрывов первой, кто бы что ни считал.
        if (oddCount > 0) {
            threadManager.runWorkStage(oddCount, Phase.LINKS) { work, workerId ->
                processWork(oddOrgan[work], oddChunk[work], workerId)
            }
        }
    }

    // Работы двух стадий: организм и номер куска, WHOLE — организм целиком. Пересобираются
    // каждый тик в однопоточной части (организмы рождаются и умирают), массивы растут только
    // когда работ стало больше, чем было когда-либо.
    private var evenOrgan = IntArray(64)
    private var evenChunk = IntArray(64)
    private var evenCount = 0
    private var oddOrgan = IntArray(64)
    private var oddChunk = IntArray(64)
    private var oddCount = 0

    private fun planWorks() {
        val organEntity = linkEntity.organEntity
        val organs = organEntity.aliveList
        evenCount = 0
        oddCount = 0
        for (i in 0 until organs.size) {
            val organIndex = organs.getInt(i)
            if (!organEntity.hasArena(organIndex)) continue
            val chunks = splittableChunks(organIndex)
            if (chunks == null) {
                addEven(organIndex, WHOLE)
                continue
            }
            for (chunk in 0 until chunks.count) {
                if (chunk % 2 == 0) addEven(organIndex, chunk) else addOdd(organIndex, chunk)
            }
        }
    }

    /**
     * Нарезка организма, если его можно резать, иначе null.
     *
     * Резать можно, только пока в арене нет ничего сверх раскладки: ширина ленты гарантирована
     * лишь для запечённых клеток и связей. Клетка или связь из хвоста арены (bump-курсор ушёл
     * за раскладку) могла лечь где угодно — тогда организм считается целиком, как раньше.
     */
    private fun splittableChunks(organIndex: Int): LayoutChunks? {
        val organEntity = linkEntity.organEntity
        val layout = organEntity.arenaLayout[organIndex] ?: return null
        val chunks = layout.chunks
        if (chunks.count < 2) return null
        if (organEntity.cellArenaUsed[organIndex] != layout.cellsInLayout) return null
        if (organEntity.linkArenaUsed[organIndex] != layout.linksInLayout) return null
        return chunks
    }

    private fun addEven(organIndex: Int, chunk: Int) {
        if (evenCount == evenOrgan.size) {
            evenOrgan = evenOrgan.copyOf(evenCount * 2)
            evenChunk = evenChunk.copyOf(evenCount * 2)
        }
        evenOrgan[evenCount] = organIndex
        evenChunk[evenCount] = chunk
        evenCount++
    }

    private fun addOdd(organIndex: Int, chunk: Int) {
        if (oddCount == oddOrgan.size) {
            oddOrgan = oddOrgan.copyOf(oddCount * 2)
            oddChunk = oddChunk.copyOf(oddCount * 2)
        }
        oddOrgan[oddCount] = organIndex
        oddChunk[oddCount] = chunk
        oddCount++
    }

    private fun processWork(organIndex: Int, chunk: Int, workerId: Int) {
        val organEntity = linkEntity.organEntity
        val base = organEntity.linkArenaBase[organIndex]
        val from: Int
        val to: Int
        val triangleFrom: Int
        val triangleTo: Int
        if (chunk == WHOLE) {
            from = base
            to = organEntity.linkArenaEnd(organIndex)
            triangleFrom = 0
            triangleTo = Int.MAX_VALUE
        } else {
            val chunks = organEntity.arenaLayout[organIndex]!!.chunks
            from = base + chunks.linkStart[chunk]
            to = base + chunks.linkStart[chunk + 1]
            triangleFrom = chunks.triangleStart[chunk]
            triangleTo = chunks.triangleStart[chunk + 1]
            if (DEBUG_CHECKS) verifyChunkWindow(organIndex, chunk, chunks, from, to)
        }

        val alive = linkEntity.isAlive
        for (linkIndex in from until to) {
            // Дырки в арене: связь умерла, а слот ещё не переиспользован.
            if (!alive[linkIndex]) continue
            processLink(linkIndex, workerId)
        }

        // Треугольники того же куска — здесь же, в той же работе.
        //
        // Не отдельной стадией: это ещё один барьер и, главное, второй проход по
        // тем же клеткам. Здесь они только что прочитаны обходом связей и лежат
        // в кэше — треугольники отсортированы по тому же минимальному слоту, то
        // есть идут вдоль той же ленты RCM.
        processTriangles(organIndex, triangleFrom, triangleTo)
    }

    /**
     * Связи куска трогают только клетки своего окна [k·W, (k+1)·W + band) — на этом держится
     * отсутствие гонок. Нарушиться может, если связь легла в слот не по раскладке.
     */
    private fun verifyChunkWindow(organIndex: Int, chunk: Int, chunks: LayoutChunks, from: Int, to: Int) {
        val organEntity = linkEntity.organEntity
        val cellBase = organEntity.cellArenaBase[organIndex]
        val low = chunk * chunks.slotsPerChunk
        val high = low + chunks.slotsPerChunk + chunks.band
        for (linkIndex in from until to) {
            if (!linkEntity.isAlive[linkIndex]) continue
            val a = linkEntity.links1[linkIndex] - cellBase
            val b = linkEntity.links2[linkIndex] - cellBase
            if (a < low || b < low || a >= high || b >= high) {
                throw IllegalStateException(
                    "связь $linkIndex организма $organIndex куска $chunk касается слотов $a и $b " +
                        "вне окна [$low, $high) — чётные куски могут гоняться"
                )
            }
        }
    }

    /**
     * Ограничение знаковой площади треугольников тела — то, что держит форму.
     *
     * ЧТО ИМЕННО ЛОВИТСЯ
     * ------------------
     * Сеть пружин в 2D жёсткая на растяжение и сдвиг, но не мешает треугольнику
     * ВЫВЕРНУТЬСЯ: вершина проходит сквозь противоположное ребро, все три длины остаются
     * в норме, и тело складывается наизнанку. Именно это и происходило после того, как
     * внутренние клетки убрали из сетки коллизий: раньше складываться им не давал
     * внутренний repulse.
     *
     * Знаковая площадь при выворачивании меняет знак, поэтому ошибка становится не просто
     * большой, а превышающей площадь покоя вдвое — сила гарантированно выталкивает вершину
     * обратно. Ограничение на длины или на угол этот случай не различает.
     *
     * ПОЧЕМУ НЕ shape matching
     * ------------------------
     * Здесь на треугольник приходятся три вершины и одно число покоя, а вся математика —
     * два вычитания на координату и одно умножение крест-накрест. Shape matching потребовал
     * бы rest-позиций кольца, центроида, оптимального поворота и обратного корня на каждую
     * клетку, причём кольца перекрываются и каждая клетка попадала бы в семь из них.
     *
     * ЛОКАЛЬНОСТЬ И ПАРАЛЛЕЛЬНОСТЬ
     * ----------------------------
     * Слоты берутся прямо из запечённой раскладки, а параллельность арен клеток и частиц
     * превращает переход в арифметику: `particleIndex = particleArenaBase + slot`. Ни
     * одного поиска, ни одного чтения particleIndexes. Организм целиком принадлежит одному
     * воркеру, поэтому записи в vx/vy никем не разделяются — синхронизация не нужна.
     */
    private fun processTriangles(organIndex: Int, from: Int, to: Int) {
        val organEntity = linkEntity.organEntity

        // Только для ВЗРОСЛОГО тела.
        //
        // Площади покоя сняты в редакторе с выросшего организма. Пока тело растёт, его
        // треугольники ещё не в позе покоя: клетка появляется вплотную к родителю и лишь
        // потом расталкивается пружинами, поэтому свежий треугольник во много раз меньше
        // своей взрослой площади. Ограничение видит огромную ошибку и непрерывно тянет
        // вершины — тело шевелится всё время роста.
        //
        // Наружу это вылезает неожиданным образом: angleCos/angleSin считаются из
        // направления на родителя (CellSystem.processCellAngle), поэтому непрерывно
        // движущиеся вершины дают непрерывно вращающиеся текстуры клеток. Как только
        // организм дорастал, ошибка уходила в зону допуска и вращение прекращалось —
        // отсюда и «пока растёт, углы скачут, вырос — всё нормально».
        //
        // Чинить подгонкой жёсткости бессмысленно: проблема не в силе, а в том, что
        // цель заведомо неверна для растущего тела. Правильная цель для промежуточных
        // стадий потребовала бы запекать площади для каждой стадии генома отдельно.
        if (!organEntity.alreadyGrownUp[organIndex]) return

        val layout = organEntity.arenaLayout[organIndex] ?: return

        val slots = layout.triangleSlotsArray
        val restArea2 = layout.triangleRestArea2Array
        val invRestArea2 = layout.triangleInvRestArea2
        if (restArea2.isEmpty()) return

        val cellBase = organEntity.cellArenaBase[organIndex]
        val particleBase = organEntity.particleArenaBase[organIndex]

        val cellAlive = cellEntity.isAlive
        val x = particleEntity.x
        val y = particleEntity.y
        val vx = particleEntity.vx
        val vy = particleEntity.vy

        // Диапазон треугольников куска (или все, если организм считается целиком).
        val end = minOf(to, restArea2.size)
        var t = from
        var base = 3 * from
        while (t < end) {
            val s0 = slots[base]
            val s1 = slots[base + 1]
            val s2 = slots[base + 2]
            base += 3
            val triangle = t
            t++

            // Клетка могла умереть — треугольника больше нет. Топология при этом не
            // перепекается, поэтому мёртвые тройки просто пропускаются.
            if (!cellAlive[cellBase + s0] ||
                !cellAlive[cellBase + s1] ||
                !cellAlive[cellBase + s2]
            ) continue

            val p0 = particleBase + s0
            val p1 = particleBase + s1
            val p2 = particleBase + s2

            val x0 = x[p0]; val y0 = y[p0]
            val x1 = x[p1]; val y1 = y[p1]
            val x2 = x[p2]; val y2 = y[p2]

            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val error = area2 - restArea2[triangle]

            // Мёртвая зона: треугольник в пределах допуска не трогаем вовсе.
            //
            // Это не только экономия деления ниже — это ещё и стабильность: без зоны
            // покоя ограничение постоянно дёргает вершины на уровне численного шума.
            if (error * invRestArea2[triangle] < TRIANGLE_TOLERANCE &&
                error * invRestArea2[triangle] > -TRIANGLE_TOLERANCE
            ) continue

            // Градиент удвоенной площади по каждой вершине.
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0

            val gradSquared =
                g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (gradSquared < MIN_GRAD_SQUARED) continue

            // Скорость изменения ограничения — проекция скоростей вершин на градиент.
            // Это то, что гасит демпфирование: у пружины такой член есть (dampeningForce),
            // а у площади его не было вообще, и любое колебание накачивало энергию.
            val cDot =
                g0x * vx[p0] + g0y * vy[p0] +
                    g1x * vx[p1] + g1y * vy[p1] +
                    g2x * vx[p2] + g2y * vy[p2]

            // Нормировка на |grad|^2 делает поправку САМОМАСШТАБИРУЕМОЙ: lambda получается
            // безразмерной, а lambda * grad — сразу нужное смещение вершины. Поэтому
            // TRIANGLE_STIFFNESS читается как «какую долю ошибки площади исправить за тик»
            // и не зависит ни от размера треугольника, ни от размера клеток.
            //
            // Деление на SIM_STEP переводит смещение в скорость: позиция интегрируется как
            // pos += v * SIM_STEP, и без этого поправка прикладывалась бы в 7.5 раз
            // сильнее нужного — именно это и разносило тело.
            val lambda =
                -(TRIANGLE_STIFFNESS * error + TRIANGLE_DAMPING * cDot) /
                    (gradSquared * MovementManager.SIM_STEP)

            vx[p0] += lambda * g0x; vy[p0] += lambda * g0y
            vx[p1] += lambda * g1x; vy[p1] += lambda * g1y
            vx[p2] += lambda * g2x; vy[p2] += lambda * g2y
        }
    }

    /**
     * Отчёт о локальности обхода связей. Только под DEBUG_CHECKS, вызывать раз в окно
     * профиля — стоит O(связей).
     *
     * ЧТО МЕРЯЕТ И ЗАЧЕМ
     * ------------------
     * Ширина ленты — max |слотA - слотB| по связям организма, где слот это смещение клетки
     * внутри её арены. Это и есть РАЗМЕР ГОРЯЧЕГО ОКНА: обходя связи по порядку, поток
     * держит в кэше клетки в пределах ленты. Средняя лента показывает типичный случай,
     * максимальная — худший.
     *
     * Ради чего это нужно видеть: «RCM применился» и «RCM дал эффект» — разные утверждения.
     * Раскладка может быть запечена, но если обход идёт не по ней (как было со списками
     * чётности) или геном сохранён до появления запекания, лента окажется порядка размера
     * тела, и никакого выигрыша не будет. По одному лишь времени фазы это неразличимо,
     * а по ленте видно сразу.
     *
     * Ориентир: для плоского тела RCM даёт ленту порядка sqrt(числа клеток). При 947
     * клетках это ~30-60. Лента в сотни — раскладка не применилась.
     */
    fun describeLinkLocality(): String {
        val organEntity = linkEntity.organEntity
        val organs = organEntity.aliveList
        val sb = StringBuilder("=== LINK LOCALITY ===\n")

        for (i in 0 until organs.size) {
            val organIndex = organs.getInt(i)
            if (!organEntity.hasArena(organIndex)) {
                sb.append("organ ").append(organIndex).append(": арены нет\n")
                continue
            }

            val cellBase = organEntity.cellArenaBase[organIndex]
            val from = organEntity.linkArenaBase[organIndex]
            val to = organEntity.linkArenaEnd(organIndex)

            // Считаем ЖИВЫЕ клетки, а не cellArenaUsed.
            //
            // cellArenaUsed — это bump-курсор, и при запечённой раскладке он равен размеру
            // раскладки с самого первого тика: запечённые слоты выдаются по cellGenomeId
            // напрямую, курсор через них не проходит. Печатать его как «клеток в арене»
            // было прямой дезинформацией — тело выглядело выросшим целиком с первого тика,
            // даже когда рост застревал на середине.
            var aliveCells = 0
            for (cellIndex in cellBase until organEntity.cellArenaEnd(organIndex)) {
                if (cellEntity.isAlive[cellIndex]) aliveCells++
            }

            var count = 0
            var maxBand = 0
            var sumBand = 0L
            for (linkIndex in from until to) {
                if (!linkEntity.isAlive[linkIndex]) continue
                val cellA = linkEntity.links1[linkIndex]
                val cellB = linkEntity.links2[linkIndex]
                if (cellA == -1 || cellB == -1) continue

                val band = kotlin.math.abs((cellA - cellBase) - (cellB - cellBase))
                if (band > maxBand) maxBand = band
                sumBand += band
                count++
            }

            val layout = organEntity.arenaLayout[organIndex]
            sb.append("organ ").append(organIndex)
                .append(": RCM=").append(if (layout == null) "НЕТ" else "да")
            if (layout != null) {
                sb.append(" (клеток в раскладке ").append(layout.cellsInLayout)
                    .append(", связей ").append(layout.linksInLayout).append(')')
            }
            sb.append(", живых связей ").append(count)
                .append(", лента avg ").append(if (count == 0) 0 else (sumBand / count))
                .append(" max ").append(maxBand)
                .append(", живых клеток ").append(aliveCells)
                .append(" из ").append(layout?.cellsInLayout ?: organEntity.cellArenaUsed[organIndex])
                .append(", стадия ").append(organEntity.stage[organIndex])
                .append('/').append(organEntity.genomeSize[organIndex])
                .append(if (organEntity.alreadyGrownUp[organIndex]) " (вырос)" else " (растёт)")
                .append(", ждём делений ").append(organEntity.divideCounterThisStage[organIndex])
                .append(" мутаций ").append(organEntity.mutateCounterThisStage[organIndex])
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * Обработка одной связи. Вызывается для каждой связи каждый тик, из нескольких потоков.
     *
     * Раньше тело метода жило в трёх вложенных with (particleEntity, cellEntity, linkEntity),
     * из-за чего каждое обращение вида x[i] или links1[i] превращалось в чтение поля
     * соответствующего объекта плюс чтение элемента. Поля сущностей — var (их
     * переоткрывают при росте), а в середине метода стоят вызовы transportEnergy,
     * processCellAngle и reinitParentIndex, которые для JIT могут записать что угодно
     * в любое поле, поэтому после каждого вызова все ссылки на массивы приходилось
     * перечитывать. Теперь массивы поднимаются в локальные переменные (регистры) один раз.
     *
     * Инвариант тот же, что и в repulse: в параллельной фазе массивы не пересоздаются,
     * меняются только элементы, а рост сущностей идёт в однопоточной фазе применения команд.
     */
    fun processLink(linkIndex: Int, threadId: Int = 0) {
        val linkEntity = linkEntity
        val cellEntity = cellEntity
        val particleEntity = particleEntity

        val linkCellA = linkEntity.links1[linkIndex]
        val linkCellB = linkEntity.links2[linkIndex]


        // Проверки "жива ли клетка на конце связи" здесь больше нет.
        //
        // Она стоила шесть чтений из шести разных массивов (isAlive дважды, generation
        // клетки дважды, linksGeneration1/2 связи) на КАЖДУЮ связь КАЖДЫЙ тик — то есть
        // примерно треть всех обращений к памяти в этом методе, — и вся эта работа была
        // пропорциональна числу связей в мире, хотя само событие пропорционально числу
        // смертей. Теперь связи умирающей клетки снимаются сразу, в LinkEntity.detachAllLinks
        // из обработчика DELETE_CELL, и до этого метода мёртвая связь просто не доходит:
        // detachAllLinks убирает её из списков слотов до начала следующего тика.
        //
        // Инвариант, на котором это держится: единственный путь смерти клетки — команда
        // DELETE_CELL, а она однопоточная и идёт до фазы связей следующего тика.
        //
        // Если инвариант когда-нибудь нарушат (появится ещё один путь смерти, который не
        // зовёт detachAllLinks), симуляция начнёт молча считать физику по мёртвым индексам.
        // Поэтому под DEBUG_CHECKS он проверяется явно — включать при любой правке путей
        // жизненного цикла клетки.
        if (DEBUG_CHECKS) {
            val isAlive = cellEntity.isAlive
            if (!isAlive[linkCellA] || !isAlive[linkCellB] ||
                cellEntity.getGeneration(linkCellA) != linkEntity.linksGeneration1[linkIndex] ||
                cellEntity.getGeneration(linkCellB) != linkEntity.linksGeneration2[linkIndex]
            ) {
                throw IllegalStateException(
                    "живая связь $linkIndex ссылается на мёртвую клетку: " +
                        "A=$linkCellA B=$linkCellB — detachAllLinks не был вызван"
                )
            }

            // Обе клетки связи обязаны принадлежать ОДНОМУ организму.
            //
            // Точное условие отсутствия гонки, без допущений о геометрии.
            //
            // Организм целиком достаётся одному воркеру (обход идёт по его арене),
            // поэтому конфликтовать могут только линки с общей клеткой, а общие клетки
            // бывают исключительно внутри организма. Единственный способ это сломать —
            // связать клетки разных организмов.
            //
            // Все пути создания связей резолвят вторую клетку внутри одного organIndex,
            // а морфогенез вдобавок фильтрует по нему явно; проверка ловит любой путь,
            // который это обошёл.
            if (cellEntity.organIndex[linkCellA] != cellEntity.organIndex[linkCellB]) {
                throw IllegalStateException(
                    "связь $linkIndex соединяет клетки разных организмов: " +
                        "A=$linkCellA organIndex=${cellEntity.organIndex[linkCellA]}, " +
                        "B=$linkCellB organIndex=${cellEntity.organIndex[linkCellB]} " +
                        "— их связи обошёл бы не тот воркер, возможна гонка на vx/vy"
                )
            }
        }

        // Денормализовать индексы частиц в саму связь пробовали — стало медленнее,
        // подробности в комментарии к LinkEntity.links1. Эти два обращения случайные,
        // но particleIndexes всего ~780 КБ и живёт в L2/L3, так что они дёшевы.
        val linkParticleA = cellEntity.getParticleIndex(linkCellA)
        val linkParticleB = cellEntity.getParticleIndex(linkCellB)
//        println("linkIndex: $linkIndex: cell1: $linkCellA cell2: $linkCellB particle1: $linkParticleA particle2: $linkParticleB")

        // Дубль проверки из LinkEntity.addLink, но уже по факту расчёта: ловит связи,
        // испортившиеся ПОСЛЕ создания.
        //
        // Ровно (0, 0) — точный признак удалённой частицы: ParticleEntity.deleteParticle
        // обнуляет x и y, а живая частица туда попасть не может, processWorldBorders
        // зажимает координаты в [radius, gridSize - radius] при ненулевом радиусе.
        if (DEBUG_CHECKS) {
            val particles = particleEntity
            if (linkParticleA == -1 || linkParticleB == -1) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на клетку без частицы: " +
                        "A=$linkCellA particle=$linkParticleA, B=$linkCellB particle=$linkParticleB"
                )
            }
            if (!particles.isAlive[linkParticleA] || !particles.isAlive[linkParticleB]) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на мёртвую частицу: " +
                        "A=$linkCellA particle=$linkParticleA alive=${particles.isAlive[linkParticleA]}, " +
                        "B=$linkCellB particle=$linkParticleB alive=${particles.isAlive[linkParticleB]}"
                )
            }
            if ((particles.x[linkParticleA] == 0f && particles.y[linkParticleA] == 0f) ||
                (particles.x[linkParticleB] == 0f && particles.y[linkParticleB] == 0f)
            ) {
                throw IllegalStateException(
                    "связь $linkIndex ссылается на частицу в нулевой координате: " +
                        "A=$linkCellA particle=$linkParticleA " +
                        "pos=(${particles.x[linkParticleA]}, ${particles.y[linkParticleA]}), " +
                        "B=$linkCellB particle=$linkParticleB " +
                        "pos=(${particles.x[linkParticleB]}, ${particles.y[linkParticleB]})"
                )
            }
        }

        val positionsX = particleEntity.x
        val positionsY = particleEntity.y

        val dx = positionsX[linkParticleA] - positionsX[linkParticleB]
        val dy = positionsY[linkParticleA] - positionsY[linkParticleB]
        val distanceSquared = dx * dx + dy * dy

        cellSystem.transportEnergy(linkCellA, linkCellB)

        //TODO можно попробовать сделать отдельную сущность под углы, там будут только пары родитель - потомок
        val parentIndices = cellEntity.parentIndex
        val parentCellA = parentIndices[linkCellA]
        val parentCellB = parentIndices[linkCellB]
        if (linkCellA == parentCellB) {
            if (PROFILE_COUNTERS) SimCounters.increment(threadId, SimCounters.LINK_ANGLES)
            cellSystem.processCellAngle(linkCellB, linkCellA)
        }
        if (linkCellB == parentCellA) {
            if (PROFILE_COUNTERS) SimCounters.increment(threadId, SimCounters.LINK_ANGLES)
            cellSystem.processCellAngle(linkCellA, linkCellB)
        }

        if (distanceSquared > linkMaxLength2) {
            if (PROFILE_COUNTERS) SimCounters.increment(threadId, SimCounters.LINK_BREAKS)
            linkEntity.reinitParentLink(linkIndex)
            worldCommandsManager.worldCommandBuffer[threadId].push(
                WorldCommandType.DELETE_LINK,
                linkIndex,
                linkEntity.getGeneration(linkIndex)
            )
            cellEntity.isOnEdge[linkCellB] = true
            cellEntity.setColor(linkCellB, Color.RED.toIntBits())
            cellEntity.isOnEdge[linkCellA] = true
            cellEntity.setColor(linkCellA, Color.RED.toIntBits())
            return
        }

        val cellStiffness = particleEntity.cellStiffness
        val stiffnessA = cellStiffness[linkParticleA]
        val stiffnessB = cellStiffness[linkParticleB]
        // Гармоническое среднее. У клеток одного типа значения совпадают — самый частый
        // случай, — поэтому равенство проверяется отдельно и деление пропускается по
        // хорошо предсказываемой ветке.
        val stiffness = if (stiffnessA == stiffnessB) stiffnessA
        else 2f * stiffnessA * stiffnessB / (stiffnessA + stiffnessB)

        // Отладочная проверка: distanceSquared это сумма двух квадратов, отрицательной
        // она может стать только при NaN/inf в координатах. При DEBUG_CHECKS = false
        // конструкция вырезается компилятором, в байткоде не остаётся ни сравнения,
        // ни конкатенации строки, ни throw внутри горячего метода.
        if (DEBUG_CHECKS && distanceSquared < 0) {
            throw Exception("distanceSquared < 0, distanceSquared = $distanceSquared")
        }

        val invDist = invSqrt(distanceSquared)
        val dist = distanceSquared * invDist

        val dirX = dx * invDist
        val dirY = dy * invDist

        val degreeOfShorteningArray = cellEntity.degreeOfShortening
        val degreeOfShorteningA = degreeOfShorteningArray[linkCellA]
        val degreeOfShorteningB = degreeOfShorteningArray[linkCellB]
        val degreeOfShortening = if (degreeOfShorteningA == degreeOfShorteningB) degreeOfShorteningA
        else 2f * degreeOfShorteningA * degreeOfShorteningB / (degreeOfShorteningA + degreeOfShorteningB)

        val force = (dist - linkEntity.linksNaturalLength[linkIndex] * degreeOfShortening) * stiffness

        // Spring dampening
        val velocitiesX = particleEntity.vx
        val velocitiesY = particleEntity.vy

        val dvx = velocitiesX[linkParticleA] - velocitiesX[linkParticleB]
        val dvy = velocitiesY[linkParticleA] - velocitiesY[linkParticleB]

        val dampeningConstant = 0.3f
        val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

        val totalForce = force + dampeningForce
        val fx = totalForce * dirX
        val fy = totalForce * dirY

        velocitiesX[linkParticleB] += fx
        velocitiesY[linkParticleB] += fy
        velocitiesX[linkParticleA] -= fx
        velocitiesY[linkParticleA] -= fy

        //TODO сделать инкрементально
        //Элементы читаются заново (а не берутся из parentCellA/B): reinitParentIndex
        //мог только что записать сюда значение.
        if (parentIndices[linkCellA] == -1) linkEntity.reinitParentIndex(linkCellA, linkCellB)
        if (parentIndices[linkCellB] == -1) linkEntity.reinitParentIndex(linkCellB, linkCellA)

    }
}
