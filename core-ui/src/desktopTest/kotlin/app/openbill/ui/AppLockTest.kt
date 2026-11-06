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

    @Test
    fun staffPinsAreSaltedHashesAndShareTheOwnersBackoff(): Unit = runBlocking {
        val a = AppLockController.hashPin("2580")
        val b = AppLockController.hashPin("2580")
        assertFalse("2580" in a)
        assertTrue(a != b, "a fresh salt per PIN")
        assertTrue(AppLockController.verifyPin("2580", a))
        assertFalse(AppLockController.verifyPin("2581", a))
        for (bad in listOf(null, "", "x", "1:zz:00", "0:00:00", "120000:00")) assertFalse(AppLockController.verifyPin("2580", bad), "$bad")

        val store = InMemorySettingsStore()
        AppLockController(store).setPin("4821")
        val lock = AppLockController(store)
        val staff: suspend (String) -> Boolean = { AppLockController.verifyPin(it, a) }
        var signedIn = false
        assertFalse(lock.unlock("4821", 0, staff), "the owner's PIN does not open a staff account")
        repeat(AppLockController.FREE_TRIES - 1) { assertFalse(lock.unlock("0000", 0, staff)) }
        assertTrue(lock.retryInMs(0) > 0, "staff failures count toward the same wait")
        assertFalse(lock.unlock("4821", 0), "switching to the owner does not skip it")
        assertTrue(lock.unlock("2580", lock.retryInMs(0), staff) { signedIn = true })
        assertTrue(signedIn)
        assertFalse(lock.locked.value)
        lock.lockNow()
        assertTrue(lock.locked.value, "switch user locks at once")
    }
}
