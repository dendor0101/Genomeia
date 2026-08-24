package io.github.some_example_name.old.core.log

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.DIGameGlobalContainer

/**
 * Собирает текст краш-репорта. Только собирает — записью занимается платформа,
 * потому что у desktop, Android и web для этого совсем разные средства.
 *
 * Что кладём кроме стектрейса и почему:
 *  - маршрут по экранам: стектрейс говорит, какой обработчик упал, но не говорит,
 *    как игрок до этого экрана добрался;
 *  - размер окна и локаль: половина багов вёрстки воспроизводится только на них;
 *  - хвост журнала действий: собственно сценарий.
 */
object CrashReport {

    fun build(throwable: Throwable): String = buildString {
        append("=== Genomeia crash report ===\n\n")

        append("Экран: ").append(environmentLine()).append('\n')
        append("Маршрут: ").append(navigationPath()).append('\n')
        append('\n')

        append("--- Действия игрока (последние) ---\n")
        append(ActionLog.dump())
        append('\n')

        append("--- Исключение ---\n")
        append(throwable.stackTraceToString())
    }

    /**
     * Всё через runCatching: репорт собирается уже в аварийном состоянии, и падение
     * внутри самого сборщика съело бы то единственное, ради чего он и нужен.
     */
    private fun environmentLine(): String = runCatching {
        "${Gdx.graphics.width}x${Gdx.graphics.height}, локаль ${DIGameGlobalContainer.currentLocale}"
    }.getOrElse { "недоступен" }

    private fun navigationPath(): String = runCatching {
        DIGameGlobalContainer.navigationCommandsManager.currentPath()
            .ifEmpty { listOf("(стартовый экран)") }
            .joinToString(" -> ")
    }.getOrElse { "недоступен" }
}
