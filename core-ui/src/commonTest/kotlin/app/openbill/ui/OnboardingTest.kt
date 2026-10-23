package app.openbill.ui

import app.openbill.ui.nav.Route
import app.openbill.ui.onboarding.LaunchStage
import app.openbill.ui.onboarding.OnboardingAction
import app.openbill.ui.onboarding.OnboardingKey
import app.openbill.ui.onboarding.OnboardingMode
import app.openbill.ui.onboarding.OnboardingPaging
import app.openbill.ui.onboarding.launchStage
import app.openbill.ui.settings.AppearanceController
import app.openbill.ui.settings.AppearanceSettings
import app.openbill.ui.settings.InMemorySettingsStore
import app.openbill.ui.settings.OnboardingController
import app.openbill.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingTest {
    // --- Routing --------------------------------------------------------------------------

    @Test
    fun loadingWinsUntilFirmsAreKnown() {
        assertEquals(LaunchStage.LOADING, launchStage(firmsLoaded = false, hasFirm = false, onboardingCompleted = false))
        assertEquals(LaunchStage.LOADING, launchStage(firmsLoaded = false, hasFirm = false, onboardingCompleted = true))
    }

    @Test
    fun freshInstallShowsIntroductionThenSetup() {
        assertEquals(LaunchStage.ONBOARDING, launchStage(firmsLoaded = true, hasFirm = false, onboardingCompleted = false))
        assertEquals(LaunchStage.SETUP, launchStage(firmsLoaded = true, hasFirm = false, onboardingCompleted = true))
    }

    @Test
    fun existingBusinessSkipsIntroductionEvenIfNeverSeen() {
        assertEquals(LaunchStage.APP, launchStage(firmsLoaded = true, hasFirm = true, onboardingCompleted = false))
        assertEquals(LaunchStage.APP, launchStage(firmsLoaded = true, hasFirm = true, onboardingCompleted = true))
    }

    @Test
    fun finishLabelsDependOnWhereTheIntroductionWasOpened() {
        assertEquals("Set up my business", OnboardingMode.FIRST_RUN.finishLabel)
        assertEquals("Done", OnboardingMode.REPLAY.finishLabel)
    }

    @Test
    fun introductionRouteSurvivesSaveAndRestore() {
        assertEquals(Route.Introduction, Route.fromKey(Route.Introduction.key))
    }

    // --- Persistence ----------------------------------------------------------------------

    @Test
    fun onboardingIsNotCompletedOnAFreshDevice() {
        assertFalse(OnboardingController(InMemorySettingsStore()).completed.value)
    }

    @Test
    fun markCompletedPersistsAcrossRestarts() {
        val store = InMemorySettingsStore()
        val controller = OnboardingController(store)
        controller.markCompleted()
        assertTrue(controller.completed.value)
        assertEquals("true", store.getString(OnboardingController.KEY_COMPLETED))
        assertTrue(OnboardingController(store).completed.value)
    }

    @Test
    fun completingTheIntroductionMovesAFreshInstallToSetup() {
        val controller = OnboardingController(InMemorySettingsStore())
        assertEquals(LaunchStage.ONBOARDING, launchStage(true, false, controller.completed.value))
        controller.markCompleted()
        assertEquals(LaunchStage.SETUP, launchStage(true, false, controller.completed.value))
    }

    @Test
    fun brokenStoreFallsBackToShowingTheIntroductionAndDoesNotCrash() {
        val broken = object : SettingsStore {
            override fun getString(key: String): String? = error("unreadable")
            override fun putString(key: String, value: String) = error("read-only")
        }
        val controller = OnboardingController(broken)
        assertFalse(controller.completed.value)
        controller.markCompleted()
        assertTrue(controller.completed.value)
    }

    @Test
    fun reduceMotionSettingRoundTrips() {
        val store = InMemorySettingsStore()
        assertFalse(AppearanceSettings.read(store).reduceMotion)
        AppearanceController(store).setReduceMotion(true)
        assertTrue(AppearanceSettings.read(store).reduceMotion)
        assertEquals("true", store.getString(AppearanceSettings.KEY_REDUCE_MOTION))
    }

    // --- Paging ---------------------------------------------------------------------------

    @Test
    fun nextStepsThroughPagesThenFinishes() {
        assertEquals(OnboardingAction.GoTo(1), OnboardingPaging.next(0, 6))
        assertEquals(OnboardingAction.GoTo(5), OnboardingPaging.next(4, 6))
        assertEquals(OnboardingAction.Finish, OnboardingPaging.next(5, 6))
    }

    @Test
    fun previousStopsAtTheFirstPage() {
        assertEquals(OnboardingAction.None, OnboardingPaging.previous(0))
        assertEquals(OnboardingAction.GoTo(2), OnboardingPaging.previous(3))
    }

    @Test
    fun keysPageWithArrowsFinishWithEnterOnLastAndEscapeSkips() {
        assertEquals(OnboardingAction.GoTo(1), OnboardingPaging.forKey(OnboardingKey.RIGHT, 0, 6))
        assertEquals(OnboardingAction.None, OnboardingPaging.forKey(OnboardingKey.RIGHT, 5, 6))
        assertEquals(OnboardingAction.None, OnboardingPaging.forKey(OnboardingKey.LEFT, 0, 6))
        assertEquals(OnboardingAction.GoTo(3), OnboardingPaging.forKey(OnboardingKey.LEFT, 4, 6))
        assertEquals(OnboardingAction.GoTo(3), OnboardingPaging.forKey(OnboardingKey.ENTER, 2, 6))
        assertEquals(OnboardingAction.Finish, OnboardingPaging.forKey(OnboardingKey.ENTER, 5, 6))
        assertEquals(OnboardingAction.Finish, OnboardingPaging.forKey(OnboardingKey.ESCAPE, 2, 6))
    }

    @Test
    fun touchBackStepsBackAndLetsTheSystemExitFromTheFirstPageOfFirstRun() {
        assertEquals(OnboardingAction.GoTo(1), OnboardingPaging.forBack(2, OnboardingMode.FIRST_RUN, keyboard = false))
        assertEquals(OnboardingAction.None, OnboardingPaging.forBack(0, OnboardingMode.FIRST_RUN, keyboard = false))
        assertFalse(OnboardingPaging.interceptsBack(0, OnboardingMode.FIRST_RUN, keyboard = false))
        assertTrue(OnboardingPaging.interceptsBack(1, OnboardingMode.FIRST_RUN, keyboard = false))
    }

    @Test
    fun touchBackFromTheFirstPageOfAReplayReturnsToMore() {
        assertEquals(OnboardingAction.Finish, OnboardingPaging.forBack(0, OnboardingMode.REPLAY, keyboard = false))
        assertEquals(OnboardingAction.GoTo(0), OnboardingPaging.forBack(1, OnboardingMode.REPLAY, keyboard = false))
    }

    @Test
    fun escapeThroughTheBackDispatcherLeavesOnDesktop() {
        assertEquals(OnboardingAction.Finish, OnboardingPaging.forBack(3, OnboardingMode.REPLAY, keyboard = true))
        assertTrue(OnboardingPaging.interceptsBack(0, OnboardingMode.FIRST_RUN, keyboard = true))
    }
}
