package io.github.some_example_name.old.systems.genomics.genome

import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.SpecialModDataTypes
import io.github.some_example_name.old.core.GameFiles
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Номера полей (@ProtoNumber) фиксируйте раз и навсегда. Никогда не меняйте существующие номера и не переиспользуйте удалённые */


@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Genome(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val stageInstruction: List<GenomeStage> = emptyList(),
    @ProtoNumber(4) val subGenomes: Map<Int, SubGenome> = emptyMap(),
    /**
     * Размеры взрослого тела, снятые при запекании в редакторе.
     *
     * По ним считаются ёмкости арен организма. Раньше там стояли константы на все геномы
     * сразу — то есть либо перерасход памяти на мелких телах, либо падение на крупных.
     *
     * Ноль означает «геном сохранён до появления запекания»: тогда арены берут размеры
     * из констант OrganEntity, как и раньше.
     */
    @ProtoNumber(5) val cellsAmount: Int = 0,
    @ProtoNumber(6) val linksAmount: Int = 0,
    @ProtoNumber(7) val neuralLinksAmount: Int = 0,

    /**
     * Порядок укладки сущностей в арены. null — геном не запечён, порядок будет обычный
     * (по мере роста). См. [BakedLayout].
     */
    @ProtoNumber(8) val sortedGraph: BakedLayout? = null,

    /**
     * Снимок взрослого тела для превью в списке и карточке геномов. null — геном ещё не
     * снимали: его допекает GenomeImport.withBaking. См. [GenomePreview].
     */
    @ProtoNumber(9) val preview: GenomePreview? = null,

    /**
     * Сколько нейросвязей может быть у организма ОДНОВРЕМЕННО за весь рост — ёмкость его
     * арены нейросвязей (OrganEntity.allocateArenas). Не [neuralLinksAmount] (столько их у
     * взрослого) и не число всех созданных: геном перевязывает нейросвязи по ходу стадий,
     * удалённая уступает слот следующей.
     *
     * Снимается в редакторе (EditorSimulationSystem.simulate) как наибольшее за стадии
     * «живых на начало стадии + созданных за стадию» — с запасом на любой порядок создания
     * и удаления внутри стадии, который в мире зависит от энергии клеток.
     *
     * null — геном старше этого поля: его допекает GenomeImport.withBaking, а до того
     * нейросвязи такого организма живут в общем пуле.
     */
    @ProtoNumber(10) val neuralLinksPeak: Int? = null
) {
    @Transient
    var genomeStageInstruction: List<GenomeStage> = stageInstruction

    @Transient
    var dividedTimes = IntArray(0)

    @Transient
    var mutatedTimes = IntArray(0)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SubGenome(
    @ProtoNumber(1) val genomeStageInstruction: List<GenomeStage> = emptyList()
) {
    @Transient
    var dividedTimes = IntArray(0)

    @Transient
    var mutatedTimes = IntArray(0)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GenomeStage(
    @ProtoNumber(1) val cellActions: Map<Int, CellAction> = emptyMap()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CellAction(
    @ProtoNumber(1) val divide: Action? = null,
    @ProtoNumber(2) val mutate: Action? = null
)

/**
 * Что сделать с клеткой на стадии: поделиться (divide) или измениться (mutate).
 *
 * У мутации null значит «не менять»; у деления — «по умолчанию».
 *
 * Тип клетки и формула — КЛЮЧИ (Cell.key, Formula.key), а не номера: номер — это место в
 * реестре текущего запуска, и с модами или после перестановки он другой. В файл Action
 * пишется через [ActionProto], там же перевод старых номеров в ключи.
 */
@Serializable(with = ActionSerializer::class)
data class Action(
    val id: Int = -1,
    val angle: Float? = null,
    /** Ключ типа клетки ("leaf", "mod:name"). У мутации null — тип не меняется. */
    val cellType: String? = null,
    val physicalLink: Map<Int, LinkData?> = emptyMap(),
    val color: Color? = null,
    val radius: Float? = null,
    val angleDirected: Float? = null,
    /** Ключ формулы активации нейрона ("linear", "sigmoid"…). */
    val funActivation: String? = null,
    val a: Float? = null,
    val b: Float? = null,
    val c: Float? = null,
    val isSum: Boolean? = null,
    val colorRecognition: Int? = null,
    val lengthDirected: Float? = null,
    val pheromoneType: Int? = null,
    /** Свои данные клетки (клавиша контроллера и т.п.), см. SpecialModData. */
    val special: SpecialModData? = null
) {

    /** Досчитывается GenomeManager.addInvertedDivideLinks; в файл не пишется. */
    val physicalLinkMirroredForCell: MutableMap<Int, LinkData?> = physicalLink.toMutableMap()

}

/**
 * Action, как он лежит в protobuf. Номера полей — формат файла (см. начало файла).
 *
 * Поля 3 и 8 — старые НОМЕРА типа клетки и формулы. Они только читаются: при чтении
 * переводятся в ключи по замороженной таблице LegacyTypeIds, а пишутся всегда ключи
 * (16, 17). Так любой путь чтения — файл .genome, мир, журнал .bug, встроенные геномы —
 * переводит старые данные сам, без отдельного прохода.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ActionProto(
    @ProtoNumber(1) val id: Int = -1,
    @ProtoNumber(2) val angle: Float? = null,
    @ProtoNumber(3) val legacyCellType: Int? = null,
    @ProtoNumber(4) val physicalLink: Map<Int, LinkData?> = emptyMap(),
    @ProtoNumber(5) @Serializable(with = ColorSerializer::class) val color: Color? = null,
    @ProtoNumber(6) val radius: Float? = null,
    @ProtoNumber(7) val angleDirected: Float? = null,
    @ProtoNumber(8) val legacyFunActivation: Int? = null,
    @ProtoNumber(9) val a: Float? = null,
    @ProtoNumber(10) val b: Float? = null,
    @ProtoNumber(11) val c: Float? = null,
    @ProtoNumber(12) val isSum: Boolean? = null,
    @ProtoNumber(13) val colorRecognition: Int? = null,
    @ProtoNumber(14) val lengthDirected: Float? = null,
    @ProtoNumber(15) val pheromoneType: Int? = null,
    @ProtoNumber(16) val cellType: String? = null,
    @ProtoNumber(17) val funActivation: String? = null,
    @ProtoNumber(18) val special: SpecialModData? = null
)

object ActionSerializer : KSerializer<Action> {
    private val proto = ActionProto.serializer()

    override val descriptor: SerialDescriptor = proto.descriptor

    override fun serialize(encoder: Encoder, value: Action) = encoder.encodeSerializableValue(
        proto,
        ActionProto(
            id = value.id,
            angle = value.angle,
            physicalLink = value.physicalLink,
            color = value.color,
            radius = value.radius,
            angleDirected = value.angleDirected,
            a = value.a,
            b = value.b,
            c = value.c,
            isSum = value.isSum,
            colorRecognition = value.colorRecognition,
            lengthDirected = value.lengthDirected,
            pheromoneType = value.pheromoneType,
            cellType = value.cellType,
            funActivation = value.funActivation,
            special = value.special
        )
    )

    override fun deserialize(decoder: Decoder): Action {
        val p = decoder.decodeSerializableValue(proto)
        return Action(
            id = p.id,
            angle = p.angle,
            cellType = p.cellType ?: p.legacyCellType?.let(LegacyTypeIds::cellType),
            physicalLink = p.physicalLink,
            color = p.color,
            radius = p.radius,
            angleDirected = p.angleDirected,
            funActivation = p.funActivation ?: p.legacyFunActivation?.let(LegacyTypeIds::formula),
            a = p.a,
            b = p.b,
            c = p.c,
            isSum = p.isSum,
            colorRecognition = p.colorRecognition,
            lengthDirected = p.lengthDirected,
            pheromoneType = p.pheromoneType,
            special = p.special
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LinkData(
    @ProtoNumber(1) val length: Float? = null,
    @ProtoNumber(2) val isNeuronal: Boolean = false,
    @ProtoNumber(3) @Serializable(with = ColorSerializer::class) val color: Color? = null,
    @ProtoNumber(4) val weight: Float? = null,
    @ProtoNumber(5) val directedNeuronLink: Int? = null,
    @ProtoNumber(6) val isExtra: Boolean = false
)


object ColorSerializer : KSerializer<Color> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("gdx.Color", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Color) {
        encoder.encodeInt(value.toIntBits())
    }

    override fun deserialize(decoder: Decoder): Color {
        val intBits = decoder.decodeInt()
        val color = Color()
        Color.abgr8888ToColor(color, intBits)
        return color
    }
}


private const val MAGIC = "genomeia"

/**
 * 2 — тип клетки и формула записаны ключами (Action.cellType / funActivation), есть данные
 * клеток (Action.special). Игра версии 1 такой файл не поймёт и честно скажет, что он из
 * более новой версии, вместо падения в симуляции на «Forgot cellType».
 */
private const val CURRENT_FORMAT_VERSION = 2

/**
 * ProtoBuf для всего, где может встретиться геном: файлы .genome, сохранения миров,
 * журнал .bug. Обычный ProtoBuf не знает типов данных клеток (Action.special).
 */
val GenomeProto: ProtoBuf get() = SpecialModDataTypes.proto

// ==================== СОХРАНЕНИЕ ====================
fun saveGenome(genome: Genome, fileName: String) {
    // Сохраняем файл с расширением .genome
    GameFiles.genomesDir().child("$fileName.genome").writeBytes(encodeGenome(genome), false)
}

/**
 * Файл .genome целиком, в памяти. Отдельно от [saveGenome]: импорт сравнивает байты с уже
 * лежащим файлом, прежде чем что-то писать (см. GenomeImport).
 */
@OptIn(ExperimentalSerializationApi::class)
fun encodeGenome(genome: Genome): ByteArray {
    val protobufBytes = GenomeProto.encodeToByteArray(Genome.serializer(), genome)

    val byteArrayOutput = ByteArrayOutputStream()
    val dataOutput = DataOutputStream(byteArrayOutput)

    try {
        // 1. Magic Number (8 байт)
        dataOutput.writeBytes(MAGIC)

        // 2. Версия формата (4 байта)
        dataOutput.writeInt(CURRENT_FORMAT_VERSION)

        // 3. Данные protobuf
        dataOutput.write(protobufBytes)

        return byteArrayOutput.toByteArray()

    } finally {
        dataOutput.close()
    }
}

// ==================== ЗАГРУЗКА ====================
fun loadGenome(fileName: String): Genome {
    val file = GameFiles.genomesDir().child("$fileName.genome")

    if (!file.exists()) {
        throw IllegalStateException("Файл генома не найден: $fileName.genome")
    }

    return decodeGenome(file.readBytes())
}

/**
 * Начинается ли содержимое с заголовка .genome. Импорт узнаёт формат по байтам, а не по
 * расширению: на Android выбранный файл приходит временной копией, и имени верить нельзя.
 */
fun isGenomeFile(bytes: ByteArray): Boolean =
    bytes.size >= MAGIC.length && String(bytes, 0, MAGIC.length, Charsets.UTF_8) == MAGIC

@OptIn(ExperimentalSerializationApi::class)
fun decodeGenome(allBytes: ByteArray): Genome {
    val byteArrayInput = ByteArrayInputStream(allBytes)
    val dataInput = DataInputStream(byteArrayInput)

    try {
        // 1. Проверяем Magic Number
        val magicBytes = ByteArray(8)
        dataInput.readFully(magicBytes)
        val magic = String(magicBytes, Charsets.UTF_8)

        if (magic != MAGIC) {
            throw IllegalStateException("Неверный формат файла (Magic Number не совпадает)")
        }

        // 2. Читаем версию формата
        val fileVersion = dataInput.readInt()

        // Здесь можно добавить логику миграции, если версия старая
        if (fileVersion > CURRENT_FORMAT_VERSION) {
            throw IllegalStateException("Файл сохранён в более новой версии игры")
        }

        // 3. Читаем оставшиеся байты как protobuf
        val remainingBytes = ByteArray(dataInput.available())
        dataInput.readFully(remainingBytes)

        return GenomeProto.decodeFromByteArray(Genome.serializer(), remainingBytes)

    } finally {
        dataInput.close()
    }
}
