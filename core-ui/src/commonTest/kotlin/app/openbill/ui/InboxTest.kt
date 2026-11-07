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
