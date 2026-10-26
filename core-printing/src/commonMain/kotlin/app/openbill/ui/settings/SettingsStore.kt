package app.openbill.ui.settings

/**
 * Tiny key-value store for per-device UI preferences (never business data, which lives in the
 * database). Android backs it with SharedPreferences, desktop with java.util.prefs.
 */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemorySettingsStore(initial: Map<String, String> = emptyMap()) : SettingsStore {
    private val values = initial.toMutableMap()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
