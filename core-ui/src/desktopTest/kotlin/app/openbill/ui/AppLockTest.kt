package app.openbill.ui

import app.openbill.ui.settings.AppLockController
import app.openbill.ui.settings.InMemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertFalse(lock.unlock("1111", 0))
        assertTrue(lock.unlock("4821", 0))
        assertFalse(lock.locked.value)

        lock.onBackground(0)
        lock.onForeground(AppLockController.RELOCK_AFTER_MS - 1)
        assertFalse(lock.locked.value, "a short trip to a picker keeps it open")
        lock.onBackground(0)
        lock.onForeground(AppLockController.RELOCK_AFTER_MS)
        assertTrue(lock.locked.value)

        assertTrue(lock.unlock("4821", 0))
        lock.idleMinutes = 5
        lock.onActivity(0)
        lock.checkIdle(5 * 60_000L - 1)
        assertFalse(lock.locked.value)
        lock.checkIdle(5 * 60_000L)
        assertTrue(lock.locked.value, "idle desktop locks again")
        assertTrue(lock.unlock("4821", 0))
        lock.onMinimised()
        assertTrue(lock.locked.value, "minimising locks when the idle setting is on")

        lock.setPin(null)
        assertFalse(AppLockController(store).locked.value, "off after the PIN is removed")
    
    @Test
    fun wrongPinsBackOffExponentiallyAndSurviveARestart(): Unit = runBlocking {
        val store = InMemorySettingsStore()
        AppLockController(store).setPin("4821")
        val lock = AppLockController(store)
        repeat(AppLockController.FREE_TRIES - 1) { assertFalse(lock.unlock("0000", 0)) }
        assertEquals(0L, lock.retryInMs(0), "a typo or two costs nothing")
        assertFalse(lock.unlock("0000", 0))
        assertEquals(30_000L, lock.retryInMs(0))
        assertFalse(AppLockController(store).unlock("4821", 29_999), "the right PIN waits too, after a restart")
        assertFalse(lock.unlock("0000", 30_000))
        assertEquals(60_000L, lock.retryInMs(30_000))
        assertEquals(3_600_000L, AppLockController.backoffMs(50))
        assertTrue(lock.unlock("4821", 90_000))
        assertEquals(0L, lock.retryInMs(90_000))
        assertFalse(lock.unlock("0000", 90_000))
        assertEquals(0L, lock.retryInMs(90_000), "success resets the count")
    }
}

    @Test
    fun wrongPinsBackOffExponentiallyAndSurviveARestart(): Unit = runBlocking {
        val store = InMemorySettingsStore()
        AppLockController(store).setPin("4821")
        val lock = AppLockController(store)
        repeat(AppLockController.FREE_TRIES - 1) { assertFalse(lock.unlock("0000", 0)) }
        assertEquals(0L, lock.retryInMs(0), "a typo or two costs nothing")
        assertFalse(lock.unlock("0000", 0))
        assertEquals(30_000L, lock.retryInMs(0))
        assertFalse(AppLockController(store).unlock("4821", 29_999), "the right PIN waits too, after a restart")
        assertFalse(lock.unlock("0000", 30_000))
        assertEquals(60_000L, lock.retryInMs(30_000))
        assertEquals(3_600_000L, AppLockController.backoffMs(50))
        assertTrue(lock.unlock("4821", 90_000))
        assertEquals(0L, lock.retryInMs(90_000))
        assertFalse(lock.unlock("0000", 90_000))
        assertEquals(0L, lock.retryInMs(90_000), "success resets the count")
    }
}
