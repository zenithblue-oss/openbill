package app.openbill.ui

import app.openbill.ui.nav.Route
import app.openbill.ui.notify.InboxCategory
import app.openbill.ui.notify.InboxController
import app.openbill.ui.settings.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class InboxTest {
    @Test
    fun deepLinks() {
        assertEquals(Route.InvoiceDetail("01ABC"), Route.fromLink("openbill://invoice/01ABC"))
        assertEquals(Route.PartyLedger("p-1"), Route.fromLink("openbill://party/p-1/"))
        assertEquals(Route.ItemStock("i_2"), Route.fromLink("openbill://item/i_2?x=1"))
        assertEquals(Route.Inbox, Route.fromLink("openbill://inbox"))
        assertNull(Route.fromLink("https://invoice/1"))
        assertNull(Route.fromLink("openbill://invoice/"))
        assertNull(Route.fromLink("openbill://invoice/a%2Fb"))
        assertNull(Route.fromLink("openbill://settings/x"))
        assertEquals(Route.Inbox, Route.fromKey(Route.Inbox.key))
    }

    @Test
    fun invoiceMessageCarriesAmountDueDateAndPaymentLink() {
        val doc = app.openbill.ui.pdf.SampleInvoice.document()
        val link = app.openbill.ui.pdf.InvoiceLayoutEngine.upiUri("shop@upi", doc.seller.name, doc.grandTotalPaise, doc.number)
        val text = doc.copy(dueDate = "30-11-2026", paymentLink = link).messageText("₹1,000.00")
        assertTrue(doc.number in text && doc.grandTotal in text, text)
        assertTrue("Balance due: ₹1,000.00" in text && "Please pay by 30-11-2026." in text, text)
        assertTrue("Pay by UPI: upi://pay?pa=shop%40upi" in text || "Pay by UPI: upi://pay?pa=shop@upi" in text, text)
        assertTrue("Pay by UPI" !in doc.messageText(), "no link without a UPI ID")
        assertTrue("Pay by UPI: $link" in doc.copy(paymentLink = link).shareSummary())
    }

    @Test
    fun bundledHelpSplitsIntoTopics() {
        val topics = app.openbill.ui.more.helpTopics()
        assertEquals("Getting started", topics.first().first)
        assertTrue(topics.size >= 10 && topics.all { it.first.isNotBlank() && it.second.isNotBlank() }, topics.map { it.first }.toString())
        assertEquals(
            listOf("A" to "x y\n• one two\n• three\n\n1. a b", "B" to ""),
            app.openbill.ui.more.helpTopics("intro\n## A\nx\ny\n- one\n  two\n- three\n\n1. a\n   b\n\n## B"),
        )
    }

    @Test
    fun setupChecklist() {
        val at = Instant.fromEpochMilliseconds(0)
        val bare = app.openbill.core.domain.model.Firm(id = "f", name = "F", stateCode = "27", addressLine1 = "1 Road", city = "Pune", createdAt = at, updatedAt = at)
        val steps = app.openbill.ui.onboarding.setupSteps(bare, hasItem = true, hasParty = false, hasInvoice = false, backedUp = false)
        assertEquals(listOf(false, true, false, false, false), steps.map { it.done })
        assertTrue(app.openbill.ui.onboarding.setupSteps(bare.copy(phone = "98"), true, true, true, true).all { it.done })
        assertEquals(Route.BusinessEdit, steps[0].route)
    }

    @Test
    fun eventsReadStateAndPreferences() {
        val inbox = InboxController(InMemorySettingsStore())
        val at = Instant.fromEpochMilliseconds(1_790_000_000_000L)
        inbox.addEvent("f1", InboxCategory.BACKUP, at, "Backup failed", "Disk\u001Ffull\nagain", Route.Recurring)
        inbox.addEvent(null, InboxCategory.RECURRING, at, "2 made", "")
        val events = inbox.events()
        assertEquals(listOf("2 made", "Backup failed"), events.map { it.title })
        assertEquals("Disk full again", events[1].body)
        assertEquals(Route.Recurring.key, events[1].routeKey)
        assertEquals("f1", events[1].firmId)

        inbox.markRead("f1", listOf("a", "b"))
        assertEquals(setOf("a", "b"), inbox.readKeys("f1"))
        assertTrue(inbox.readKeys("f2").isEmpty())

        inbox.setEnabled(InboxCategory.LOW_STOCK, false)
        assertEquals(setOf(InboxCategory.LOW_STOCK), inbox.disabled())
        inbox.setEnabled(InboxCategory.LOW_STOCK, true)
        assertTrue(inbox.disabled().isEmpty())

        assertEquals(7, inbox.backupReminderDays)
        assertNull(inbox.lastBackup)
        inbox.lastBackup = at
        assertEquals(at, inbox.lastBackup)
        repeat(40) { inbox.recordError(at, "e$it") }
        assertEquals(30, inbox.errors().size)
        assertEquals("e39", inbox.errors().first().second)
    }
}
