package io.github.some_example_name.render.pack

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Буфер инстансов клеток в том виде, в каком его читает GPU.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС
 * ---------------------
 * Раскладка этих 40 байт должна совпадать с тем, как CellGeometry объявляет инстансные
 * атрибуты, и с тем, что ждут вершинные шейдеры клеток (shaders/debug/circle_pc.vert,
 * shaders/debug_view/cell.vert, shaders/nostalgic/nostalgic.vert).
 * Раньше знание о ней жило в трёх местах сразу: побитовые формулы — в
 * RenderBufferManager.writeCell и в EditorRenderSystem.putBuffer, порядок полей — в
 * RenderSystem и снова в putBuffer, а обратные формулы — в самом шейдере. Три копии одних
 * и тех же магических чисел, которые обязаны сходиться, иначе картинка едет молча.
 *
 * Теперь копий на CPU одна, и она покрыта тестами (CellInstanceBufferTest).
 *
 * РАСКЛАДКА (40 байт, порядок байт нативный)
 * ------------------------------------------
 *   [0..7]   float x, float y      → a_center     (2 × GL_FLOAT)
 *   [8..11]  int   color           → a_color      (4 × GL_UNSIGNED_BYTE, normalized)
 *   [12..15] int   shape           → a_shape      (4 × GL_UNSIGNED_BYTE, normalized)
 *   [16..19] int   type            → a_type       (4 × GL_UNSIGNED_BYTE, normalized)
 *   [20..23] int   look            → a_look       (4 × GL_UNSIGNED_BYTE, normalized)
 *   [24..27] int   dir             → a_dir        (2 × GL_SHORT, normalized)
 *   [28..35] float prevX, prevY    → a_prevCenter (2 × GL_FLOAT)
 *   [36..39] int   prevDir         → a_prevDir    (2 × GL_SHORT, normalized)
 *
 *   shape = 0        | 0<<8       | radiusByte<<16 | energyByte<<24
 *   type  = cellType | seedLo<<8  | seedHi<<16    | flags<<24
 *   look  = pattern  | action<<8  | transparency<<16 | filter<<24   — см. CellLook
 *   dir   = cos16    | sin16<<16  — направление клетки, см. [dir]
 *
 *   flags — битовые признаки частицы, см. [FLAG_NOT_CELL]. Два младших байта shape
 *   свободны: раньше в них лежали cos и sin по байту.
 *
 * Поле look читает только отладочный режим (shaders/debug_view), остальные пишут ноль —
 * непрозрачная клетка без рисунка. Отдельным полем, а не свободными битами чужих полей:
 * энергия нужна ностальгическому режиму, тип — реалистичному, и байт, смысл которого
 * зависит от режима, разъехался бы при первой же правке.
 *
 * НАПРАВЛЕНИЕ — ПО 16 БИТ, А НЕ ПО БАЙТУ
 * --------------------------------------
 * Раньше cos и sin ехали байтом каждый: шаг 2/255, то есть угол квантовался на 0.2–0.45°
 * и, что хуже, cos и sin переходили на следующую ступеньку в разные моменты. Медленно
 * вращающаяся клетка поворачивала текстуру рывками, а у клетки радиусом в сотню пикселей
 * край текстуры прыгал на 1–2 пикселя. Теперь шаг 1/32767 — около 0.002°, на любом
 * приближении меньше пикселя. Угол из cos/sin не собирается обратно в радианы на CPU:
 * atan2 на каждую клетку каждый тик стоил бы миллисекунды на 100k клеток.
 *
 * ИНТЕРПОЛЯЦИЯ МЕЖДУ ТИКАМИ
 * -------------------------
 * Симуляция идёт с одной частотой (60, а у XPBD — 30 тиков в секунду), экран с другой
 * (до 144+ кадров). Чтобы движение было плавным, шейдер рисует клетку не в позиции
 * снимка, а между прошлым и нынешним тиком:
 *
 *     center = a_center + (a_prevCenter - a_center) * u_lag
 *     dir    = nlerp(a_dir, a_prevDir, u_lag)
 *
 * u_lag (RenderFrame.interpolationLag) — насколько кадр отстаёт от снимка в долях тика:
 * 1 — ровно прошлый тик, 0 — нынешний. Прошлая поза лежит в самом инстансе, а не вторым
 * снимком: прошлый снимок упорядочен иначе (рождения сдвигают арену), и сопоставлять
 * клетки по индексу GPU не умеет. Пока новый снимок не пришёл (экран чаще симуляции),
 * кадры отличаются только униформом — буфер не перекладывается и не перезаливается.
 *
 * ПОЧЕМУ ПРОШЛАЯ ПОЗА, А НЕ СМЕЩЕНИЕ
 * ----------------------------------
 * Первая версия везла смещение за тик нормализованными short в долях MOTION_RANGE и
 * синус поворота — 32 байта вместо 40. Замер 21.10 на 16 Мегалодонах (150k частиц): три
 * лишних округления float → short на клетку и два на субстанцию стоили снимку больше
 * всего остального, вместе взятого (~10 нс на частицу из ~16). Прошлая поза — это
 * просто копия чисел: позиция из истории как есть, направление — уже упакованный int из
 * прошлого снимка. Округлений на частицу ровно столько же, сколько до интерполяции
 * (два — на направление). Цена — 8 байт на инстанс, а заливается буфер теперь раз в
 * снимок, а не раз в кадр.
 *
 * Перенос дальше [MOTION_RANGE] за тик (мышью, загрузкой мира) — не движение: такой
 * клетке прошлая поза ставится равной нынешней, и она встаёт на место сразу, а не
 * пролетает полосой через полмира. Это решает CPU ([isMotion]), шейдер только смешивает.
 *
 * ПОЧЕМУ НОРМАЛИЗОВАННЫЕ БАЙТЫ, А НЕ РАСПАКОВКА В ШЕЙДЕРЕ
 * -------------------------------------------------------
 * Раньше эти же байты ехали одним uint и распаковывались вручную:
 *
 *     vec4 v1 = unpackRGBA8(packed1);   // сдвиги и маски
 *     float cosA = v1.x * 2.0 - 1.0;
 *
 * Ровно это железо выборки вершинных атрибутов делает бесплатно для типов
 * GL_UNSIGNED_BYTE и GL_SHORT с normalized = true. Заодно ушёл целый слой: текстура данных
 * RGBA32UI, её рост, загрузка glTexSubImage2D по строкам и остатку, и расхождение
 * float/uint между вебом и остальными платформами.
 *
 * ПРО ПОРЯДОК БАЙТ
 * ----------------
 * putInt пишет в нативном порядке, а GL читает компоненты атрибута в порядке адресов.
 * На little-endian (x86, ARM, WASM — то есть все целевые платформы) младший байт (и
 * младший short) числа оказывается компонентом .x, что здесь и предполагается. Прежний
 * путь через текстуру держался ровно на том же допущении.
 *
 * ПРО ПОТОКИ
 * ----------
 * Снимок мира собирает поток симуляции (RenderBufferManager) — int-массивом в этой же
 * раскладке ([write]); GL-поток переносит его в буфер одной копией ([putAll]) и заливает.
 * Поэтому у класса два уровня API:
 *   - [put], [putAll] — «положи уже готовые поля»;
 *   - [putCell] и функции из companion — упаковка из физических величин.
 */
class CellInstanceBuffer(initialCapacity: Int = INITIAL_CAPACITY) {

    private var buffer: ByteBuffer = allocate(initialCapacity)

    /** Инстансов записано с последнего [begin]. Отдельным счётчиком, а не из position. */
    private var written = 0

    /**
     * Буфер уже перевёрнут под чтение.
     *
     * Нужно, чтобы [end] можно было звать сколько угодно раз за кадр. Редактор генома так
     * и делает: между тиками он не перезаписывает буфер (данные те же), но кадр рисует
     * каждый раз и буфер запрашивает заново. Голый flip() во второй раз выставил бы
     * limit = position = 0, то есть отдал бы ПУСТОЙ буфер — и клетки пропали бы с экрана.
     */
    private var finished = false

    /** Сколько инстансов помещается без пересоздания. */
    val capacity: Int get() = buffer.capacity() / STRUCT_SIZE

    /** Сколько инстансов записано с последнего [begin]. */
    val count: Int get() = written

    /**
     * Начать кадр.
     *
     * [expectedInstances] — ёмкость, которая точно понадобится. Передавать её стоит, когда
     * размер известен заранее: тогда рост случится один раз здесь, а не в середине записи.
     * Если размер неизвестен, [put] дорастит сам.
     */
    fun begin(expectedInstances: Int = 0) {
        ensureCapacity(expectedInstances)
        (buffer as Buffer).clear()
        written = 0
        finished = false
    }

    /**
     * Готовые поля — без арифметики. Прошлая поза по умолчанию равна нынешней: клетка
     * стоит, интерполировать нечего.
     */
    fun put(
        x: Float,
        y: Float,
        color: Int,
        shape: Int,
        type: Int,
        look: Int,
        dir: Int,
        prevX: Float = x,
        prevY: Float = y,
        prevDir: Int = dir
    ) {
        ensureRoomForOneMore()
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putInt(color)
        buffer.putInt(shape)
        buffer.putInt(type)
        buffer.putInt(look)
        buffer.putInt(dir)
        buffer.putFloat(prevX)
        buffer.putFloat(prevY)
        buffer.putInt(prevDir)
        written++
    }

    /**
     * Упаковать и положить за один вызов.
     *
     * [noiseSeed] — устойчивый ключ шума: шейдер доворачивает текстуру на случайный угол,
     * взятый из него. Ключ обязан быть закреплён за клеткой на всю жизнь, иначе доворот
     * пересчитывается каждый кадр и текстуры визуально крутятся. Индекс частицы годится,
     * позиция в буфере — нет. По нему же отладочный режим раскладывает рисунок клетки.
     *
     * [look] — вид в отладочном режиме, см. CellLook; ноль — прежняя плоская клетка.
     *
     * [prevX], [prevY], [prevCos], [prevSin] — поза в прошлом тике, см. «Интерполяция между
     * тиками» в шапке. По умолчанию — нынешняя: клетка стоит (редактор, превью, стенд).
     */
    fun putCell(
        x: Float,
        y: Float,
        color: Int,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        energy: Float,
        cellType: Int,
        noiseSeed: Int,
        isCell: Boolean = true,
        look: Int = 0,
        prevX: Float = x,
        prevY: Float = y,
        prevCos: Float = angleCos,
        prevSin: Float = angleSin
    ) {
        put(
            x = x,
            y = y,
            color = color,
            shape = shape(radiusByte = radiusByte(radius), energyByte = energyByte(energy)),
            type = type(cellType = cellType, noiseSeed = noiseSeed, isCell = isCell),
            look = look,
            dir = dir(angleCos, angleSin),
            prevX = prevX,
            prevY = prevY,
            prevDir = dir(prevCos, prevSin)
        )
    }

    /**
     * Дописать [instances] готовых инстансов из [source] — того, что собрано функцией
     * [write], — одной копией (memcpy, ~1–2 нс на инстанс).
     *
     * Так снимок мира попадает в буфер для GPU: поток симуляции пишет инстансы в обычный
     * int-массив (запись в массив дешевле десяти вызовов DirectByteBuffer.put, а на
     * Android — заметно дешевле), а GL-поток копирует его сюда один раз на новый снимок.
     */
    fun putAll(source: IntArray, instances: Int) {
        if (instances <= 0) return
        ensureCapacity(written + instances)
        val view = intView()
        (view as Buffer).position(written * STRUCT_INTS)
        view.put(source, 0, instances * STRUCT_INTS)
        written += instances
        (buffer as Buffer).position(written * STRUCT_SIZE)
    }

    /** int-вид на [buffer]; заводится заново, только когда буфер вырос. */
    private var intView: IntBuffer? = null
    private var intViewOf: ByteBuffer? = null

    private fun intView(): IntBuffer {
        var view = intView
        if (view == null || intViewOf !== buffer) {
            // Вид от позиции 0 и на всю ёмкость: duplicate(), чтобы не зависеть от того,
            // где сейчас позиция у самого буфера. Порядок байт duplicate() не наследует —
            // ставим нативный, как у буфера, иначе каждый int ляжет перевёрнутым.
            val bytes = buffer.duplicate().order(ByteOrder.nativeOrder())
            (bytes as Buffer).clear()
            view = bytes.asIntBuffer()
            intView = view
            intViewOf = buffer
        }
        return view
    }

    /** Завершить кадр и отдать буфер на загрузку в инстансный VBO. Идемпотентно. */
    fun end(): ByteBuffer {
        if (!finished) {
            (buffer as Buffer).flip()
            finished = true
        }
        return buffer
    }

    /**
     * Рост на ×1.5 с пересозданием.
     *
     * Direct-буфер нельзя расширить на месте, поэтому уже записанное копируется в новый.
     * На установившемся режиме этого не происходит вообще: ёмкость подбирается один раз
     * под размер мира и дальше держится.
     */
    fun ensureCapacity(instances: Int) {
        val current = capacity
        if (instances <= current) return

        var grown = current.toDouble()
        do {
            grown *= GROWTH_FACTOR
        } while (grown < instances)

        val bigger = allocate(grown.toInt().coerceAtLeast(instances))

        // Копируем ровно записанное, а не всю ёмкость.
        if (written > 0) {
            val old = buffer
            (old as Buffer).position(0)
            (old as Buffer).limit(written * STRUCT_SIZE)
            bigger.put(old)
        }
        buffer = bigger
    }

    private fun ensureRoomForOneMore() {
        if (buffer.remaining() < STRUCT_SIZE) ensureCapacity(written + 1)
    }

    private fun allocate(instances: Int): ByteBuffer =
        ByteBuffer
            .allocateDirect(instances.coerceAtLeast(1) * STRUCT_SIZE)
            .order(ByteOrder.nativeOrder())

    companion object {
        /**
         * 40 байт на инстанс: 4 float, 4 упакованных четвёрки байт и две пары short.
         * Менять только вместе с раскладкой атрибутов в CellGeometry, с шейдерами клеток
         * (circle_pc.vert, debug_view/cell.vert, nostalgic.vert) и с версией RenderSceneDump.
         */
        const val STRUCT_SIZE = 40

        /** Смещения полей внутри инстанса — их же объявляет CellGeometry. */
        const val OFFSET_CENTER = 0
        const val OFFSET_COLOR = 8
        const val OFFSET_SHAPE = 12
        const val OFFSET_TYPE = 16
        const val OFFSET_LOOK = 20
        const val OFFSET_DIR = 24
        const val OFFSET_PREV_CENTER = 28
        const val OFFSET_PREV_DIR = 36

        /** Инстанс в int: все поля — по 4 байта, так что раскладка та же, что в байтах. */
        const val STRUCT_INTS = STRUCT_SIZE / 4

        /**
         * Инстанс номер [instance] — в int-массив, ровно в раскладке буфера (float — своими
         * битами, порядок полей тот же). Обратно в буфер — [putAll]. Маленькая, чтобы
         * встраивалась в цикл снимка: десять записей в массив и ничего больше.
         */
        fun write(
            target: IntArray,
            instance: Int,
            x: Float,
            y: Float,
            color: Int,
            shape: Int,
            type: Int,
            look: Int,
            dir: Int,
            prevX: Float,
            prevY: Float,
            prevDir: Int
        ) {
            val o = instance * STRUCT_INTS
            target[o] = x.toRawBits()
            target[o + 1] = y.toRawBits()
            target[o + 2] = color
            target[o + 3] = shape
            target[o + 4] = type
            target[o + 5] = look
            target[o + 6] = dir
            target[o + 7] = prevX.toRawBits()
            target[o + 8] = prevY.toRawBits()
            target[o + 9] = prevDir
        }

        const val INITIAL_CAPACITY = 30_000

        private const val GROWTH_FACTOR = 1.5

        /**
         * Диапазон радиуса, который влезает в один байт.
         * Обратное преобразование в шейдерах клеток: `radius = 0.05 + a_shape.z * 0.7`.
         */
        const val RADIUS_MIN = 0.05f
        const val RADIUS_SPAN = 0.7f

        /**
         * Делитель энергии: при ней байт насыщается, и ядро клетки достигает максимума.
         * Это не физическая энергия, а только её показ.
         *
         * Читает её только ностальгический шейдер (nostalgic.vert): линейно, в долях
         * радиуса клетки — см. NostalgicRenderer.NUCLEUS_AT_FULL_ENERGY. Реалистичный и
         * отладочный энергию не показывают: у отладочного ядра — часть рисунков клеток.
         */
        const val ENERGY_SPAN = 10f

        /**
         * Сколько единиц мира за тик ещё считается движением, а не переносом.
         *
         * Порог обязан быть заметно выше предела скорости физики: в нынешней симуляции
         * частица смещается за тик не больше чем на 4 единицы (HALF_CHUNK_HEIGHT в :core,
         * MovementManager.seedBump). Первая версия ставила порог ровно 4, и частица,
         * упёршаяся в предел, из-за округления float то интерполировалась бы, то нет —
         * тело дёргалось бы вперёд на целый тик. (Рывки при тяге 21.10 оказались не отсюда:
         * замер показал ноль переносов и шаг до 1.3 единицы, а виноват был цикл тиков, см.
         * ThreadManager.runUpdateLoop. Но совпадать с пределом порог не должен.) 16 — вчетверо выше
         * предела; настоящие переносы (загрузка мира, появление организма) много дальше.
         * Здесь, а не в :core, потому что решение «рисовать ли полёт» — рендеру; если
         * физика когда-нибудь разрешит больше 16 за тик, этот порог поднимать вместе с ней.
         */
        const val MOTION_RANGE = 16f

        /** Смещение за тик укладывается в [MOTION_RANGE] — иначе это перенос. NaN — тоже. */
        fun isMotion(dx: Float, dy: Float): Boolean =
            dx >= -MOTION_RANGE && dx <= MOTION_RANGE && dy >= -MOTION_RANGE && dy <= MOTION_RANGE

        private const val SHORT_SCALE = 32767f

        /**
         * Число из [-1..1] в нормализованный short (16 младших бит). Обратно — то, что делает
         * GL для GL_SHORT + normalized: `max(c / 32767, -1)`.
         *
         * Округление без ветки по знаку: число сдвигается в положительную область, и toInt()
         * там — это floor. Знак у направлений соседних клеток случайный, и ветка по нему
         * ошибалась бы у предсказателя на половине клеток. Зажим — сравнениями: значения
         * почти всегда внутри, эти ветки предсказываются. Math.round здесь не годится: в
         * JBR 21 он не встроен и на замере был в полтора раза дороже.
         */
        fun unitShort(value: Float): Int {
            if (value != value) return 0 // NaN — ноль, а не мусор в соседнем short
            val clamped = if (value > 1f) 1f else if (value < -1f) -1f else value
            return ((clamped * SHORT_SCALE + ROUND_OFFSET).toInt() - SHORT_OFFSET) and 0xFFFF
        }

        private const val ROUND_OFFSET = 32768.5f
        private const val SHORT_OFFSET = 32768

        /** Направление клетки: cos и sin по 16 бит. Обратно — `a_dir` в шейдерах клеток. */
        fun dir(angleCos: Float, angleSin: Float): Int =
            unitShort(angleCos) or (unitShort(angleSin) shl 16)

        /** cos из упакованного [dir] — как его увидит шейдер. */
        fun dirCos(dir: Int): Float = maxOf(dir.toShort() / SHORT_SCALE, -1f)

        /** sin из упакованного [dir] — как его увидит шейдер. */
        fun dirSin(dir: Int): Float = maxOf((dir shr 16).toShort() / SHORT_SCALE, -1f)

        /**
         * Направление не-клетки (субстанции): у неё его нет. (-1, -1), а не (1, 0), ради
         * прежней картинки: раньше её нулевые байты cos/sin распаковывались в -1, и
         * реалистичный шейдер так и рисовал её текстуру — с этим доворотом и масштабом.
         */
        val DIR_NONE: Int = dir(-1f, -1f)

        fun radiusByte(radius: Float): Int =
            (((radius - RADIUS_MIN) / RADIUS_SPAN) * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun energyByte(energy: Float): Int =
            ((energy / ENERGY_SPAN) * 255f + 0.5f).toInt().coerceIn(0, 255)

        /** Радиус и энергия — по байту; два младших байта свободны. */
        fun shape(radiusByte: Int, energyByte: Int): Int =
            ((radiusByte and 0xFF) shl 16) or ((energyByte and 0xFF) shl 24)

        /**
         * Частица не клетка (субстанция и прочее): у неё нет ядра.
         *
         * Отдельным битом, а не по номеру типа. Номер «не клетки» задаёт :core (последний
         * слой TextureArray, и тот достаётся через зажим индекса слоя), и сравнивать с ним
         * в шейдере значило бы протащить в рендер чужую договорённость о порядке слоёв.
         *
         * Бит именно «не клетка», а не «клетка»: тогда ноль, который лежит в этом байте у
         * всех, кто про флаги не знает (редактор генома, старые дампы), означает обычную
         * клетку, а поменять пришлось ровно одно место — запись не-клеток в :core.
         */
        const val FLAG_NOT_CELL = 1

        /**
         * Тип клетки (индекс слоя в TextureArray), 16-битный ключ шума двумя байтами и
         * байт флагов.
         *
         * Ключ раскладывается на два байта, потому что нормализованный атрибут отдаёт в
         * шейдер четыре независимых компоненты, а не одно число. Собирается обратно как
         * `lo + hi*256` с округлением — точно, без потери бита.
         *
         * Второй бит флагов (2) раньше означал «спираль хамелеона» в отладке; теперь у
         * хамелеона свой рисунок в look (CellLook.PIGMENTS), бит не пишется и не читается.
         */
        fun type(cellType: Int, noiseSeed: Int, isCell: Boolean = true): Int {
            val seed = noiseSeed and 0xFFFF
            val flags = if (isCell) 0 else FLAG_NOT_CELL
            return cellType.coerceIn(0, 255) or
                ((seed and 0xFF) shl 8) or
                (((seed ushr 8) and 0xFF) shl 16) or
                (flags shl 24)
        }
    }
}
