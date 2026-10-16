package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.ChameleonData
import io.github.some_example_name.old.cells.ControllerData
import io.github.some_example_name.old.cells.EyeData
import io.github.some_example_name.old.cells.PheromoneEmitterState
import io.github.some_example_name.old.cells.ProducerState
import io.github.some_example_name.old.cells.TailState
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.reflect.KClass

/**
 * Свои данные клетки — всё, что нужно одному типу клетки и чего нет в общих массивах
 * CellEntity: параметры глаза, скорость хвоста, клавиша контроллера, данные клеток модов.
 * Лежат в SpecialModDataEntity, слот на клетку (SpecialEntity.specialTypeIndexes).
 *
 * ДВА ВИДА
 * --------
 *  - Данные из генома — неизменяемые (только val): EyeData, ControllerData. Один объект
 *    может быть у многих клеток сразу и в самом геноме: деление ничего не копирует. Меняет
 *    их только мутация — новым объектом в слоте клетки (SpecialEntity.setSpecialData).
 *  - Состояние на время жизни — изменяемое (var): TailState, ProducerState,
 *    PheromoneEmitterState. Объект у каждой клетки СВОЙ (Cell.createSpecialData возвращает
 *    новый), и пишет в него только воркер этой клетки. Общий изменяемый объект был бы
 *    гонкой между воркерами — недетерминизмом.
 *
 * КАК ЗАВЕСТИ
 * -----------
 * 1. data class, у каждого поля свой @ProtoNumber:
 *
 *        @Serializable @SerialName("controller")
 *        data class ControllerData(@ProtoNumber(1) val key: String = "W") : SpecialModData
 *
 *    @SerialName обязателен и равен ключу клетки (у мода — "modid:name"): по нему тип
 *    данных узнаётся в файле, это формат. Номера полей — тоже формат, как в Genome.kt.
 * 2. Сериализатор — в [SpecialModDataTypes]: встроенные в списке там, моды через register.
 * 3. В клетке — Cell.createSpecialData (и mutateSpecialData, если мутация их меняет), а если
 *    данные задаются в геноме — Cell.defaultGenomeData.
 * 4. В симуляции: `specialEntity.getSpecialData(cellIndex) as? ControllerData`.
 *
 * Всё, что стоит считать один раз (номер клавиши по её имени и т.п.), — @Transient-полем
 * с инициализатором: оно считается при создании объекта, в равенство и в файл не входит.
 *
 * ЦЕНА
 * ----
 * Против прежних отдельных массивов на вид (EyeEntity, TailEntity…) — одно разыменование
 * объекта на клетку за тик и объект на клетку при её создании (у состояния). Таких клеток
 * десятки–тысячи, а не сотни тысяч; замер — в TODO.md, раздел 2.7.
 *
 * ГДЕ ЖИВУТ
 * ---------
 *  - в геноме: Action.special (protobuf, полиморфно: имя типа + тело) — только данные из
 *    генома; параметры глаза исторически лежат в общих полях Action;
 *  - в мире: SpecialModDataEntity; в сохранение и в хэш состояния — теми же protobuf-байтами,
 *    см. [SpecialModDataTypes.encode].
 */
interface SpecialModData

/**
 * Данные новой клетки: из генома, если они того же типа, что у клетки по умолчанию, иначе
 * значение по умолчанию. Другого типа (или null) они бывают у мутации, сменившей тип
 * клетки, и у генома, сохранённого до появления у клетки своих данных.
 */
fun specialDataFor(action: Action, default: SpecialModData): SpecialModData {
    val fromGenome = action.special ?: return default
    return if (fromGenome.javaClass == default.javaClass) fromGenome else default
}

/**
 * Сериализаторы [SpecialModData] и общий ProtoBuf, который их знает.
 *
 * Все чтения и записи, где может встретиться геном (файлы .genome, сохранения миров,
 * журнал .bug), обязаны идти через [proto]: обычный ProtoBuf не знает типов данных
 * клеток и упадёт на первом же Action.special.
 *
 * Список замораживается при первом обращении к [proto] — то есть моды регистрируются
 * до того, как игра прочтёт первый геном.
 */
@OptIn(ExperimentalSerializationApi::class)
object SpecialModDataTypes {

    private class Registration<T : SpecialModData>(val type: KClass<T>, val serializer: KSerializer<T>)

    private val types = mutableListOf<Registration<*>>(
        Registration(ControllerData::class, ControllerData.serializer()),
        Registration(ChameleonData::class, ChameleonData.serializer()),
        Registration(EyeData::class, EyeData.serializer()),
        Registration(TailState::class, TailState.serializer()),
        Registration(ProducerState::class, ProducerState.serializer()),
        Registration(PheromoneEmitterState::class, PheromoneEmitterState.serializer()),
    )

    private var frozen = false

    fun <T : SpecialModData> register(type: KClass<T>, serializer: KSerializer<T>) = synchronized(this) {
        check(!frozen) { "данные клеток регистрируются до первого чтения генома" }
        check(CellTypes.MOD_KEY.matches(serializer.descriptor.serialName)) {
            "@SerialName данных клетки мода '${serializer.descriptor.serialName}' обязан быть вида 'modid:name'"
        }
        check(types.none { it.serializer.descriptor.serialName == serializer.descriptor.serialName }) {
            "данные клетки ${serializer.descriptor.serialName} уже зарегистрированы"
        }
        types += Registration(type, serializer)
    }

    val proto: ProtoBuf by lazy {
        synchronized(this) { frozen = true }
        ProtoBuf {
            serializersModule = SerializersModule {
                polymorphic(SpecialModData::class) {
                    for (registration in types) registration.registerIn(this)
                }
            }
        }
    }

    private val polymorphic = PolymorphicSerializer(SpecialModData::class)

    /** Байты данных вместе с именем типа — для сохранения мира и хэша. */
    fun encode(data: SpecialModData): ByteArray = proto.encodeToByteArray(polymorphic, data)

    fun decode(bytes: ByteArray): SpecialModData = proto.decodeFromByteArray(polymorphic, bytes)

    private fun <T : SpecialModData> Registration<T>.registerIn(
        builder: kotlinx.serialization.modules.PolymorphicModuleBuilder<SpecialModData>
    ) = builder.subclass(type, serializer)
}
