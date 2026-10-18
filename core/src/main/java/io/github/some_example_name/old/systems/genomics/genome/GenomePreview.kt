package io.github.some_example_name.old.systems.genomics.genome

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import kotlin.math.abs

/**
 * Снимок взрослого тела для превью: всё, что нужно рендеру, чтобы нарисовать организм без
 * выращивания.
 *
 * ЗАЧЕМ
 * -----
 * Список и карточка геномов показывают организм картинкой. Вырастить тело заново — это
 * прогнать все стадии в мире редактора (EditorSimulationSystem.simulate), на каждый геном
 * списка. Снять его один раз при запекании, когда тело и так выращено, ничего не стоит,
 * а дальше превью — это одна заливка инстансов на GPU.
 *
 * ПОЧЕМУ ДАННЫЕ, А НЕ КАРТИНКА
 * ----------------------------
 * Картинка застыла бы в одном режиме рендера и одном размере. Сырые данные рисуются
 * любым режимом (реалистичный, ностальгический, отладочный) и в любом разрешении, а по
 * объёму они того же порядка, что маленький PNG.
 *
 * ФОРМАТ
 * ------
 * Структура массивов: элемент k каждого массива — клетка k. Клетки в порядке, в котором
 * их рисует редактор (aliveList), — от него зависит, кто кого перекрывает.
 *  - координаты — относительно центра рамки тела с учётом радиусов: превью не нужно знать,
 *    в каком месте мира редактора росло тело;
 *  - тип клетки — номер в [cellTypeKeys], а не в реестре: номер в реестре это место типа в
 *    этом запуске, и с модами он другой (см. Action.cellType). Рендер переводит ключ в
 *    слой текстуры уже у себя;
 *  - угол — cos/sin как есть, без atan2: копия без арифметики даёт одинаковые байты на
 *    любой платформе, а рендер и так берёт cos/sin;
 *  - [cellGenomeId] — чтобы нарисовать связи из [BakedLayout] (они адресуются им же).
 *
 * Массивы упакованы (@ProtoPacked): иначе protobuf пишет тег перед каждым числом. Цвет —
 * байтами, а не IntArray: это ABGR с альфой в старшем байте, то есть почти всегда
 * отрицательное число, и varint тратит на него 10 байт. @ProtoType(FIXED) на элементы
 * упакованного массива не действует (замер в GenomePreviewTest: 33 байта на клетку вместо 27).
 *
 * ОБЪЁМ: на клетку 4·5 float + 4 цвет + ~1 тип + ~2 genomeId ≈ 27 байт, тело на 1000
 * клеток — около 27 КБ (замер 17.10 на встроенных: «Megalodon», 5120 клеток, — 135 КБ при
 * файле 1.1 МБ). Снимок едет вместе с геномом в сохранения миров и в .bug, поэтому
 * ничего сверх нужного рендеру сюда не кладётся (ни энергии, ни сигналов — в редакторе их
 * нет).
 *
 * Пустой снимок (тело без клеток) — тоже снимок: по null отличается геном, который ещё
 * не снимали (GenomeImport.withBaking), и пустое тело иначе пеклось бы на каждом запуске.
 *
 * Симуляция снимок не читает: в состояние мира и хэши он не входит.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class GenomePreview(
    /** Ключи типов клеток, встретившихся в теле (Cell.key). */
    @ProtoNumber(1) val cellTypeKeys: List<String> = emptyList(),
    /** Номер типа клетки в [cellTypeKeys]. */
    @ProtoNumber(2) @ProtoPacked val cellType: IntArray = IntArray(0),
    @ProtoNumber(3) @ProtoPacked val x: FloatArray = FloatArray(0),
    @ProtoNumber(4) @ProtoPacked val y: FloatArray = FloatArray(0),
    @ProtoNumber(5) @ProtoPacked val radius: FloatArray = FloatArray(0),
    @ProtoNumber(6) @ProtoPacked val angleCos: FloatArray = FloatArray(0),
    @ProtoNumber(7) @ProtoPacked val angleSin: FloatArray = FloatArray(0),
    /**
     * Цвет частицы по 4 байта на клетку: R, G, B, A — байты числа ParticleEntity.color
     * (Color.toIntBits) от младшего к старшему, тот же порядок, в каком их читает GPU
     * (CellInstanceBuffer). Число обратно — [color].
     */
    @ProtoNumber(8) val colorBytes: ByteArray = ByteArray(0),
    @ProtoNumber(9) @ProtoPacked val cellGenomeId: IntArray = IntArray(0)
) {

    val cellCount: Int get() = x.size

    /** Цвет клетки [cell] числом, как в ParticleEntity.color. */
    fun color(cell: Int): Int {
        val i = cell * 4
        return (colorBytes[i].toInt() and 0xFF) or
            ((colorBytes[i + 1].toInt() and 0xFF) shl 8) or
            ((colorBytes[i + 2].toInt() and 0xFF) shl 16) or
            ((colorBytes[i + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Половина стороны квадрата с центром в нуле, в который тело помещается целиком,
     * вместе с радиусами. По ней превью подбирает масштаб.
     */
    fun halfExtent(): Float {
        var extent = 0f
        for (i in 0 until cellCount) {
            extent = maxOf(extent, abs(x[i]) + radius[i], abs(y[i]) + radius[i])
        }
        return extent
    }

    // Сравнение по содержимому: Genome — data class, и без этого два одинаковых генома
    // (например, до записи и после чтения) были бы не равны из-за ссылок на массивы.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GenomePreview) return false
        return cellTypeKeys == other.cellTypeKeys &&
            cellType.contentEquals(other.cellType) &&
            x.contentEquals(other.x) &&
            y.contentEquals(other.y) &&
            radius.contentEquals(other.radius) &&
            angleCos.contentEquals(other.angleCos) &&
            angleSin.contentEquals(other.angleSin) &&
            colorBytes.contentEquals(other.colorBytes) &&
            cellGenomeId.contentEquals(other.cellGenomeId)
    }

    override fun hashCode(): Int {
        var h = cellTypeKeys.hashCode()
        h = 31 * h + cellType.contentHashCode()
        h = 31 * h + x.contentHashCode()
        h = 31 * h + y.contentHashCode()
        h = 31 * h + radius.contentHashCode()
        h = 31 * h + angleCos.contentHashCode()
        h = 31 * h + angleSin.contentHashCode()
        h = 31 * h + colorBytes.contentHashCode()
        h = 31 * h + cellGenomeId.contentHashCode()
        return h
    }

    override fun toString(): String = "GenomePreview(cells=$cellCount, types=$cellTypeKeys)"

    companion object {
        /** Цвета-числа (ParticleEntity.color) в байты для [colorBytes]. */
        fun colorBytesOf(colors: IntArray): ByteArray {
            val bytes = ByteArray(colors.size * 4)
            for (k in colors.indices) {
                val c = colors[k]
                bytes[k * 4] = c.toByte()
                bytes[k * 4 + 1] = (c ushr 8).toByte()
                bytes[k * 4 + 2] = (c ushr 16).toByte()
                bytes[k * 4 + 3] = (c ushr 24).toByte()
            }
            return bytes
        }
    }
}
