package app.openbill.ui.onboarding

/** What the app shows before (or instead of) the main shell. */
enum class LaunchStage {
    /** Firms are still loading from the database. */
    LOADING,

    /** First launch: the animated introduction. */
    ONBOARDING,

    /** Introduction done (or skipped) but no business yet: business details setup. */
    SETUP,

    /** A business exists: the main app. */
    APP,
}

/**
 * Routing rule for first launch. Existing users with a business always go straight to the app,
 * even if they never saw the introduction (it arrived in an update); the introduction shows only
 * while there is no business and it has not been finished or skipped on this device.
 */
fun launchStage(firmsLoaded: Boolean, hasFirm: Boolean, onboardingCompleted: Boolean): LaunchStage = when {
    !firmsLoaded -> LaunchStage.LOADING
    hasFirm -> LaunchStage.APP
    !onboardingCompleted -> LaunchStage.ONBOARDING
    else -> LaunchStage.SETUP
}

/** Where the introduction was opened from, which decides how it ends. */
enum class OnboardingMode(val finishLabel: String) {
    /** First launch: ends in business setup. */
    FIRST_RUN("Set up my business"),

    /** More > View introduction: ends back on More. */
    REPLAY("Done"),
}

/** Keys the introduction reacts to on desktop. */
enum class OnboardingKey { LEFT, RIGHT, ENTER, ESCAPE }

/** What a button press or key does. */
sealed interface OnboardingAction {
    data class GoTo(val page: Int) : OnboardingAction

    /** Leave the introduction (finish or skip; both count as completed). */
    data object Finish : OnboardingAction

    data object None : OnboardingAction
}

/** Pure paging rules, shared by the buttons, the keyboard and the system back gesture. */
object OnboardingPaging {
    fun next(page: Int, pageCount: Int): OnboardingAction =
        if (page >= pageCount - 1) OnboardingAction.Finish else OnboardingAction.GoTo(page + 1)

    fun previous(page: Int): OnboardingAction =
        if (page <= 0) OnboardingAction.None else OnboardingAction.GoTo(page - 1)

    fun forKey(key: OnboardingKey, page: Int, pageCount: Int): OnboardingAction = when (key) {
        // Arrow keys never leave the introduction; Enter on the last page does.
        OnboardingKey.RIGHT -> if (page >= pageCount - 1) OnboardingAction.None else OnboardingAction.GoTo(page + 1)
        OnboardingKey.LEFT -> previous(page)
        OnboardingKey.ENTER -> next(page, pageCount)
        OnboardingKey.ESCAPE -> OnboardingAction.Finish
    }

    /**
     * System back (Android) or Escape routed through the app's back dispatcher (desktop replay).
     * Touch: steps back a page, and from the first page leaves a replay. Keyboard: leaves.
     */
    fun forBack(page: Int, mode: OnboardingMode, keyboard: Boolean): OnboardingAction = when {
        keyboard -> OnboardingAction.Finish
        page > 0 -> OnboardingAction.GoTo(page - 1)
        mode == OnboardingMode.REPLAY -> OnboardingAction.Finish
        else -> OnboardingAction.None
    }

    /** Whether the back handler should intercept at all (first page of first run lets the OS exit). */
    fun interceptsBack(page: Int, mode: OnboardingMode, keyboard: Boolean): Boolean =
        forBack(page, mode, keyboard) != OnboardingAction.None
}
