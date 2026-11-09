package io.github.some_example_name.old.journal

import io.github.some_example_name.old.commands.GameCommand
import kotlinx.serialization.Serializable

/**
 * События потока симуляции, которые нужны для повтора, но игрок их не делал.
 * Пишутся в канал SIM с точным номером тика, источник — CommandSource.SYSTEM.
 */
@Serializable
sealed interface SimEvent : GameCommand

/**
 * Началась сессия симуляции (вход в мир, рестарт): новая эпоха журнала и сид для
 * случайностей команд игрока. При повторе сид берётся отсюда.
 */
@Serializable
data class SessionStarted(val seed: Long) : SimEvent {
    override val name get() = "SessionStarted"
    override val detail get() = seed.toString()
}

/** Клавиши контроллера на этот тик: маска по индексам controllerKeyTouched. Только при смене. */
@Serializable
data class ControllerKeys(val mask: Int) : SimEvent {
    override val name get() = "ControllerKeys"
    override val detail get() = Integer.toBinaryString(mask)
}

/** Поток симуляции обработал «Перезапустить» в этом тике. */
@Serializable
data object SimRestarted : SimEvent {
    override val name get() = "SimRestarted"
}

/**
 * Хэш состояния мира на этом тике (см. StateHash). При повторе мир считает свой хэш на
 * том же тике и сверяет: первое несовпадение — первый тик, где повтор разошёлся с игрой.
 *
 * @param version версия формулы (StateHash.VERSION); 0 — запись первых сборок, где был
 *   только общий хэш частиц, клеток и связей.
 * @param parts хэши частей мира по порядку StateHash.partNames — чтобы сказать, ЧТО
 *   разошлось.
 */
@Serializable
data class StateHashed(val hash: Long, val version: Int = 0, val parts: List<Long> = emptyList()) : SimEvent {
    override val name get() = "StateHash"
    override val detail get() = java.lang.Long.toHexString(hash)
}

/**
 * Снят мир для записи в слот [slot]. О последующей ошибке записи сообщает SaveWorldFailed.
 * Сохранение читает мир из потока
 * рендера, и при повторе симуляция держится на этом тике, пока сохранение не случится:
 * иначе в файл попал бы другой тик, и следующая загрузка этого слота разошлась бы.
 */
@Serializable
data class WorldSaveCommitted(val slot: String) : SimEvent {
    override val name get() = "WorldSaveCommitted"
    override val detail get() = slot
}

/** Последний завершённый тик перед удалением мира. Подготовка следующего экрана может
 * занять несколько тиков уже после нажатия кнопки перехода. */
@Serializable
data object WorldStopped : SimEvent {
    override val name get() = "WorldStopped"
}
