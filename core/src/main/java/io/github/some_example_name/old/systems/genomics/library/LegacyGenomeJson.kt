package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.LegacyTypeIds
import io.github.some_example_name.old.systems.genomics.genome.LinkData

/**
 * Чтение генома в старом формате JSON — том, в котором геномы хранились до .genome
 * (коммит 01de851, июль 2026). Файлы в нём остались у игроков и в ассетах прошлых версий.
 *
 * ЗАЧЕМ ОБХОД ДЕРЕВА, А НЕ Json.fromJson
 * --------------------------------------
 * Старый код читал файл отражением libGDX в классы CreatureJsonRead и соседей. Отражение
 * зависит от имён полей наших классов, а их на Android переименовывает минификатор; на вебе
 * (TeaVM) отражения почти нет. Обход JsonValue от этого свободен и работает без Gdx, то есть
 * проверяется обычными тестами :core.
 *
 * ЧТО ВСТРЕЧАЕТСЯ В ФАЙЛАХ
 * ------------------------
 * Писатель за время жизни формата менялся, и в файлах лежат оба варианта:
 *  - цвет строкой "colorHsv": "rrggbbaa" (последний писатель) или объектом "color": {r,g,b,a}
 *    (Json libGDX писал Color как есть; так сохранён, например, «Burdock leaf»);
 *  - "class": "java.util.LinkedHashMap" внутри словарей — метка типа от libGDX, не ключ;
 *  - явные null у необязательных полей;
 *  - id строкой ("1"), у мутаций "-1".
 *
 * ПЕРЕВОД — ровно как в старом чтении (GenomeJsonRead.kt до 01de851):
 *  - длины связей и lengthDirected в JSON в пикселях старой сетки, в геноме — в клетках: / 40;
 *  - связь нейронная, если у неё есть directedNeuronLink (isNeuronal писался не всегда и не читался);
 *  - порядок клеток в стадии и связей в клетке — порядок в файле: от него зависит, в каком
 *    порядке клетки делятся в тике, а значит и индексы.
 *
 * ОТЛИЧИЯ ОТ СТАРОГО ЧТЕНИЯ
 * -------------------------
 * Деление с номером корня (0) получает свободный номер — см. [renumberRootDivides].
 *
 * Кость → лист.
 * Решение 20.11.2026: кость (старый номер 2) из JSON становится листом (Leaf) — и в делении,
 * и в мутации. Цвет остаётся тем, что записан в действии: цвет клетки от её типа не зависит
 * (деление без цвета красит в белый что кость, что лист — DivideManager; мутация без цвета
 * цвет не трогает — MutateManager), поэтому меняется только тип.
 * Только JSON: .genome формата 1 с тем же номером 2 читается как кость (LegacyTypeIds не
 * трогается). Повторов это не касается: в .bug лежит уже переведённый .genome.
 * Цена — один if на действие при импорте, в симуляции её нет.
 *
 * ЧЕГО НЕТ В ГЕНОМЕ
 * -----------------
 *  - specialData.attachedKey (клавиша клетки управления): в .genome такого поля нет с момента
 *    перехода, клетка управления не работала уже тогда;
 *  - indexOfNew и startDirectionId: их не читал и старый код.
 *
 * Номера типов клеток и формул в JSON — те же старые номера, что и в .genome формата 1:
 * они переводятся в ключи по LegacyTypeIds. Типы 16 и 21 в JSON были неработающими
 * заглушками (NonWorkingCell1/2), теперь под ними Synapse и Fin.
 */
object LegacyGenomeJson {

    /** Версия, которой помечается геном из JSON. Её же ставил переходный код в 01de851. */
    const val VERSION = 23

    /** Сколько пикселей старой сетки в одной клетке: в JSON длины хранились в пикселях. */
    private const val PIXELS_PER_CELL = 40f

    /** Метка типа словаря, которую Json libGDX вписывал среди ключей. */
    private const val CLASS_TAG = "class"

    /**
     * Геном из текста JSON. [fallbackName] — если в файле нет имени.
     *
     * @throws IllegalArgumentException это не геном в старом формате.
     */
    fun parse(text: String, fallbackName: String): Genome {
        val root = try {
            JsonReader().parse(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("не JSON: ${e.message}", e)
        }
        require(root != null && root.isObject) { "не JSON-объект" }

        val stages = root.get("genomeStageInstruction")
        require(stages != null && stages.isArray) { "нет genomeStageInstruction — это не геном" }
        require(stages.size > 0) { "в геноме нет ни одной стадии" }

        val stageInstruction = ArrayList<GenomeStage>(stages.size)
        for (stage in stages) {
            stageInstruction += GenomeStage(cellActions = readCellActions(stage.opt("cellActions")))
        }

        return Genome(
            version = VERSION,
            name = root.opt("name")?.asString()?.takeIf { it.isNotBlank() } ?: fallbackName,
            stageInstruction = renumberRootDivides(stageInstruction)
        )
    }

    /**
     * Деление с номером корня (0) получает свободный номер: следующий после самого большого
     * номера клетки в геноме. Остальное не трогается, ссылки на 0 по-прежнему значат корень.
     *
     * Номер 0 у делящейся клетки — это старое размножение: так делили зиготу на зиготу-
     * потомка («gen4.json» среди 90 геномов игрока — единственный, в последней стадии).
     * Старая игра в мире делала такую зиготу корнем нового организма, а в редакторе молча
     * перетирала ею ключ корня. Сейчас ключ (организм, номер) обязан быть единственным
     * (CellEntity.addCell), и рост такого генома падал «organToIdToIndex: genomeId=0 уже
     * занят». Зигота от деления теперь пустышка и узнаётся как раз по номеру не 0
     * (CellEntity.isDormantZygote) — свободный номер ставит её туда же, куда поставил бы
     * нынешний редактор (maxCellId + 1).
     */
    private fun renumberRootDivides(stages: List<GenomeStage>): List<GenomeStage> {
        if (stages.none { stage -> stage.cellActions.values.any { it.divide?.id == Zygote.ROOT_CELL_GENOME_ID } }) {
            return stages
        }
        // Самый большой номер, который встречается где угодно: у делений, у клеток с
        // действиями и в ссылках связей — чтобы новый номер не совпал ни с чем в файле.
        var maxId = Zygote.ROOT_CELL_GENOME_ID
        for (stage in stages) {
            for ((id, action) in stage.cellActions) {
                maxId = maxOf(maxId, id)
                action.divide?.let { divide ->
                    maxId = maxOf(maxId, divide.id)
                    for (linked in divide.physicalLink.keys) maxId = maxOf(maxId, linked)
                }
                action.mutate?.let { mutate ->
                    for (linked in mutate.physicalLink.keys) maxId = maxOf(maxId, linked)
                }
            }
        }
        // Порядок — как в файле: от него зависит порядок деления в тике (см. описание объекта).
        return stages.map { stage ->
            if (stage.cellActions.values.none { it.divide?.id == Zygote.ROOT_CELL_GENOME_ID }) return@map stage
            val renumbered = LinkedHashMap<Int, CellAction>(stage.cellActions.size * 2)
            for ((id, action) in stage.cellActions) {
                val divide = action.divide
                renumbered[id] = if (divide?.id == Zygote.ROOT_CELL_GENOME_ID) {
                    action.copy(divide = divide.copy(id = ++maxId))
                } else action
            }
            stage.copy(cellActions = renumbered)
        }
    }

    /**
     * Похоже ли содержимое на JSON — для понятной ошибки, когда игрок выбрал не тот файл:
     * первым значащим символом идёт '{'. Пропускается BOM, его пишут редакторы Windows.
     */
    fun looksLikeJson(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c == '{'.code) return true
            if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) continue
            if (c == 0xEF || c == 0xBB || c == 0xBF) continue
            return false
        }
        return false
    }

    private fun readCellActions(map: JsonValue?): Map<Int, CellAction> {
        val result = LinkedHashMap<Int, CellAction>()
        if (map == null) return result
        for (entry in map) {
            if (entry.name == CLASS_TAG) continue
            if (entry.isNull) continue
            result[key(entry)] = CellAction(
                divide = readAction(entry.opt("divide")),
                mutate = readAction(entry.opt("mutate"))
            )
        }
        return result
    }

    private fun readAction(action: JsonValue?): Action? {
        if (action == null) return null
        return Action(
            id = action.opt("id")?.let(::readId) ?: -1,
            angle = action.float("angle"),
            cellType = action.int("cellType")?.let(::cellType),
            physicalLink = readLinks(action.opt("physicalLink")),
            // Объект color у старого чтения шёл первым, colorHsv — запасным.
            color = readColor(action.opt("color")) ?: readColor(action.opt("colorHsv")),
            radius = action.float("radius"),
            angleDirected = action.float("angleDirected"),
            funActivation = action.int("funActivation")?.let(LegacyTypeIds::formula),
            a = action.float("a"),
            b = action.float("b"),
            c = action.float("c"),
            isSum = action.opt("isSum")?.asBoolean(),
            colorRecognition = action.int("colorRecognition"),
            lengthDirected = action.float("lengthDirected")?.div(PIXELS_PER_CELL),
            pheromoneType = action.int("pheromoneType")
        )
    }

    /** Ключ типа по старому номеру, с заменой кости на лист (см. описание объекта). */
    private fun cellType(number: Int): String {
        val key = LegacyTypeIds.cellType(number)
        return if (key == BONE) BONE_REPLACEMENT else key
    }

    private const val BONE = "bone"
    private const val BONE_REPLACEMENT = "leaf"

    private fun readLinks(map: JsonValue?): Map<Int, LinkData?> {
        val result = LinkedHashMap<Int, LinkData?>()
        if (map == null) return result
        for (entry in map) {
            if (entry.name == CLASS_TAG) continue
            result[key(entry)] = if (entry.isNull) null else readLink(entry)
        }
        return result
    }

    private fun readLink(link: JsonValue): LinkData {
        val directedNeuronLink = link.int("directedNeuronLink")
        return LinkData(
            length = link.float("length")?.div(PIXELS_PER_CELL),
            isNeuronal = directedNeuronLink != null,
            color = readColor(link.opt("color")),
            weight = link.float("weight"),
            directedNeuronLink = directedNeuronLink
        )
    }

    /** Строка "rrggbbaa" (как писал Color.toString) или объект {r, g, b, a} (как писал Json libGDX). */
    private fun readColor(value: JsonValue?): Color? = when {
        value == null -> null
        value.isString -> Color.valueOf(value.asString())
        value.isObject -> Color(
            value.getFloat("r", 0f),
            value.getFloat("g", 0f),
            value.getFloat("b", 0f),
            value.getFloat("a", 1f)
        )
        else -> throw IllegalArgumentException("цвет ни строкой, ни объектом: $value")
    }

    /** id писался строкой, но руками правленные файлы бывают и с числом. */
    private fun readId(value: JsonValue): Int? =
        if (value.isNumber) value.asInt() else value.asString().trim().toIntOrNull()

    private fun key(entry: JsonValue): Int =
        entry.name?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("ключ '${entry.name}' — не номер клетки")

    private fun JsonValue.opt(name: String): JsonValue? = get(name)?.takeUnless { it.isNull }

    private fun JsonValue.float(name: String): Float? = opt(name)?.asFloat()

    private fun JsonValue.int(name: String): Int? = opt(name)?.asInt()
}
