package app.openbill.ui

import app.openbill.ui.settings.AppLockController
import app.openbill.ui.settings.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AppLockTest {
    @Test
    fun pinIsHashedCheckedAndRelocksAfterAMinuteAway(): Unit = runBlocking {
        val store = InMemorySettingsStore()
        AppLockController(store).setPin("4821")
        assertFalse(store.getString(AppLockController.KEY_PIN)!!.contains("4821"))

        val lock = AppLockController(store)
        assertTrue(lock.locked.value, "starts locked")
        assertFalse(lock.unlock("1111"))
        assertTrue(lock.unlock("4821"))
        assertFalse(lock.locked.value)

        lock.onBackground(0)
        lock.onForeground(AppLockController.RELOCK_AFTER_MS - 1)
        assertFalse(lock.locked.value, "a short trip to a picker keeps it open")
        lock.onBackground(0)
        lock.onForeground(AppLockController.RELOCK_AFTER_MS)
        assertTrue(lock.locked.value)

        lock.setPin(null)
        assertFalse(AppLockController(store).locked.value, "off after the PIN is removed")
    }
}
