package app.openbill.ui

import app.openbill.ui.settings.CHANGELOG
import app.openbill.ui.settings.InMemorySettingsStore
import app.openbill.ui.settings.UpdateController
import app.openbill.ui.settings.WhatsNewController
import app.openbill.ui.settings.compareVersions
import app.openbill.ui.settings.releaseNotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun semverOrder() {
        val ordered = listOf("0.1.0-alpha", "0.3.0-alpha", "1.0.0-alpha", "1.0.0-alpha.2", "1.0.0-alpha.10", "1.0.0-beta", "1.0.0", "1.0.9", "1.0.10", "1.1.0", "v2.0.0")
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j), compareVersions(ordered[i], ordered[j])?.coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
        }
        assertEquals(0, compareVersions("v1.0.0", "1.0.0+build.5"))
        assertNull(compareVersions("dev", "1.0.0"))
        assertNull(compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun whatsNewShowsOnceAfterAnUpdateOnly() {
        val fresh = InMemorySettingsStore()
        assertFalse(WhatsNewController(fresh).onLaunch("1.1.0", hasData = false), "fresh install saves silently")
        assertFalse(WhatsNewController(fresh).onLaunch("1.1.0", hasData = true), "same version")

        val legacy = InMemorySettingsStore()
        assertTrue(WhatsNewController(legacy).onLaunch("1.1.0", hasData = true), "pre-feature install with data")
        assertFalse(WhatsNewController(legacy).onLaunch("1.1.0", hasData = true), "only once")
        assertTrue(WhatsNewController(legacy).onLaunch("1.1.1", hasData = true), "next update")
        assertFalse(WhatsNewController(legacy).onLaunch("1.0.0", hasData = true), "downgrade")

        val dev = InMemorySettingsStore()
        assertFalse(WhatsNewController(dev).onLaunch("dev", hasData = true))
        assertNull(dev.getString(WhatsNewController.KEY_LAST_SEEN))
    }

    @Test
    fun dismissedVersionStaysHiddenUntilANewerOne() {
        val store = InMemorySettingsStore()
        val updates = UpdateController(store, defaultEnabled = true)
        assertTrue(updates.isDue(0))
        updates.markChecked(1_000)
        assertFalse(updates.isDue(1_000 + 3_600_000))
        assertTrue(updates.isDue(1_000 + 86_400_000))

        updates.saveLatest("v1.1.0", "https://github.com/zenithblue-oss/openbill/releases/tag/v1.1.0")
        assertEquals("1.1.0", updates.available("1.0.0")?.first)
        assertNull(updates.available("1.1.0"), "already current")
        updates.dismiss("1.1.0")
        assertNull(updates.available("1.0.0"))
        updates.saveLatest("v1.2.0", "https://github.com/zenithblue-oss/openbill/releases/tag/v1.2.0")
        assertEquals("1.2.0", updates.available("1.0.0")?.first)

        updates.enabled = false
        assertNull(updates.available("1.0.0"))
        assertFalse(updates.isDue(Long.MAX_VALUE))
        assertFalse(UpdateController(InMemorySettingsStore(), defaultEnabled = false).isDue(0), "off by default on Android")
    }

    @Test
    fun parsesReleaseAndBundledNotes() {
        val json = """{"url":"x","html_url":"https://github.com/zenithblue-oss/openbill/releases/tag/v1.1.0","author":{"html_url":"https://github.com/someone"},"tag_name":"v1.1.0"}"""
        assertEquals("v1.1.0" to "https://github.com/zenithblue-oss/openbill/releases/tag/v1.1.0", UpdateController.parseRelease(json))
        assertNull(UpdateController.parseRelease(json.replace("zenithblue-oss/openbill", "evil/openbill")))
        assertNull(UpdateController.parseRelease("""{"message":"rate limited"}"""))

        val notes = assertNotNull(releaseNotes(CHANGELOG, "1.0.0"))
        assertTrue(notes.startsWith("First stable release"), notes)
        assertFalse(notes.contains("## 0.3.0"))
        assertFalse(notes.contains("]("), "links reduced to text")
        assertNull(releaseNotes(CHANGELOG, "9.9.9"))
    }
}
