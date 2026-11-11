package io.github.some_example_name.old.bug

/** Снимок полей в момент нажатия. Картинки уже принадлежат форме и больше не меняются. */
internal data class ReportDraft(val description: String = "", val images: List<BugImage> = emptyList())

/** Все вызовы и ответы приходят в потоке интерфейса. */
internal class ReportDelivery(
    private val upload: (ReportDraft, (Boolean, String) -> Unit) -> Unit,
    private val confirmed: () -> Unit,
    private val result: (success: Boolean, manual: Boolean, message: String) -> Unit
) {
    var sending = false
        private set
    private var automatic = false
    private var waiting: ReportDraft? = null
    private var delivered: ReportDraft? = null
    private var baseDelivered = false

    fun send(draft: ReportDraft, manual: Boolean = true) {
        if (sending) {
            if (automatic && manual && waiting == null) waiting = draft
            return
        }
        if (draft == delivered || (baseDelivered && draft == ReportDraft())) {
            result(true, manual, "")
            return
        }
        sending = true
        automatic = !manual
        upload(draft) { success, message ->
            sending = false
            if (success) {
                delivered = draft
                if (draft == ReportDraft()) baseDelivered = true
                confirmed()
            }
            val next = waiting
            waiting = null
            if (next != null) send(next) else result(success, manual, message)
        }
    }
}
