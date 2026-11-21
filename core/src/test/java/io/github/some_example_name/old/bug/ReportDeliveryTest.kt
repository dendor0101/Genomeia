package io.github.some_example_name.old.bug

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportDeliveryTest {
    private val uploads = mutableListOf<ReportDraft>()
    private val results = mutableListOf<Pair<Boolean, Boolean>>()
    private var receipts = 0
    private lateinit var finish: (Boolean, String) -> Unit
    private val delivery = ReportDelivery({ draft, reply ->
        uploads += draft
        finish = reply
    }, { receipts++ }, { success, manual, _ -> results += success to manual })
    private val base = ReportDraft()

    @Test fun unchangedManualRequestSharesTheAutomaticReceipt() {
        delivery.send(base, manual = false)
        delivery.send(base)
        delivery.send(base)
        finish(true, "")
        delivery.send(base)
        assertEquals(listOf(base), uploads)
        assertEquals(1, receipts)
        assertEquals(listOf(true to true, true to true), results)
    }

    @Test fun editsDuringAutomaticUploadAreSentOnce() {
        for (draft in listOf(ReportDraft("details"), ReportDraft(images = listOf(BugImage("screen.png", byteArrayOf(1)))))) {
            val sent = mutableListOf<ReportDraft>()
            val flow = ReportDelivery({ d, reply -> sent += d; finish = reply }, {}, { _, _, _ -> })
            flow.send(base, manual = false)
            flow.send(draft)
            assertEquals(listOf(base), sent)
            finish(true, "")
            finish(true, "")
            flow.send(draft)
            flow.send(base)
            assertEquals(listOf(base, draft), sent)
        }
    }

    @Test fun failureAllowsRetryAndDoesNotCountAsDelivery() {
        delivery.send(base, manual = false)
        finish(false, "offline")
        assertEquals(0, receipts)
        delivery.send(base)
        finish(true, "")
        assertEquals(listOf(base, base), uploads)
        assertEquals(listOf(false to false, true to true), results)
        assertEquals(1, receipts)
    }

    @Test fun manualRequestStillRunsAfterAutomaticFailure() {
        delivery.send(base, manual = false)
        delivery.send(base)
        finish(false, "offline")
        assertEquals(listOf(base, base), uploads)
        finish(true, "")
        assertEquals(listOf(true to true), results)
    }
}
