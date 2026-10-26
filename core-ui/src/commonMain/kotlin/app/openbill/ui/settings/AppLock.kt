package app.openbill.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.openbill.ui.AppContainer
import app.openbill.ui.components.FormField
import app.openbill.ui.theme.BrandLogo
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.pbkdf2
import app.openbill.ui.theme.secureRandomBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional app lock: a PIN (stored as a salted PBKDF2 hash, never in clear) asked when OpenBill
 * starts and when it returns after [RELOCK_AFTER_MS] in the background. It guards the screen,
 * not the database file; device encryption covers that (docs/03 section 13).
 */
class AppLockController(private val store: SettingsStore) {
    val isEnabled: Boolean get() = !runCatching { store.getString(KEY_PIN) }.getOrNull().isNullOrEmpty()
    private val _locked = MutableStateFlow(isEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundAt: Long? = null

    /** Sets the PIN, or turns the lock off when null. */
    suspend fun setPin(pin: String?) {
        val value = pin?.let {
            val salt = secureRandomBytes(16)
            "$ITERATIONS:${salt.hex()}:${hash(it, salt, ITERATIONS).hex()}"
        }
        store.putString(KEY_PIN, value ?: "")
    }

    suspend fun check(pin: String): Boolean {
        val (iterations, salt, expected) = store.getString(KEY_PIN)?.split(':')?.takeIf { it.size == 3 } ?: return false
        val actual = hash(pin, salt.unhex(), iterations.toIntOrNull() ?: return false).hex()
        return actual.length == expected.length && actual.indices.fold(0) { acc, i -> acc or (actual[i].code xor expected[i].code) } == 0
    }

    suspend fun unlock(pin: String): Boolean = check(pin).also { if (it) _locked.value = false }

    /** After the platform (biometric) prompt passed. */
    fun unlockTrusted() {
        _locked.value = false
    }

    fun onBackground(nowMs: Long) {
        backgroundAt = nowMs
    }

    fun onForeground(nowMs: Long) {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (isEnabled && nowMs - since >= RELOCK_AFTER_MS) _locked.value = true
    }

    private suspend fun hash(pin: String, salt: ByteArray, iterations: Int) =
        withContext(Dispatchers.Default) { pbkdf2(pin.toCharArray(), salt, iterations) }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val KEY_PIN = "lock.pin"
        const val RELOCK_AFTER_MS = 60_000L
        private const val ITERATIONS = 120_000
    }
}

@Composable
fun LockScreen(container: AppContainer) {
    val lock = container.appLock
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun biometric() = scope.launch { if (runCatching { platform.biometricUnlock() }.getOrDefault(false)) lock.unlockTrusted() }
    fun submit() = scope.launch { if (!lock.unlock(pin)) { error = "Wrong PIN"; pin = "" } }
    LaunchedEffect(Unit) { if (platform.supportsBiometric) biometric() }
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(rememberVectorPainter(BrandLogo.Mark), contentDescription = null, modifier = Modifier.size(64.dp))
        Text("OpenBill is locked", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.widthIn(max = 360.dp)) {
            FormField(
                "PIN", pin, { pin = it.filter(Char::isDigit); error = null }, error = error,
                keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, onImeAction = { submit() },
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        Button(onClick = { submit() }, enabled = pin.length >= 4) { Text("Unlock") }
        if (platform.supportsBiometric) TextButton(onClick = { biometric() }) { Text("Use fingerprint or face") }
    }
}

/** More screen row: turn the PIN lock on (PIN twice) or off (current PIN). */
@Composable
fun AppLockItem(container: AppContainer, colors: ListItemColors) {
    val lock = container.appLock
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(lock.isEnabled) }
    var open by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val biometric = if (container.platform.supportsBiometric) " or fingerprint" else ""
    ListItem(
        headlineContent = { Text("App lock") },
        supportingContent = { Text(if (enabled) "PIN$biometric needed to open OpenBill" else "Ask for a PIN$biometric when OpenBill opens") },
        leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        colors = colors,
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch) { pin = ""; confirm = ""; error = null; open = true },
    )
    if (!open) return
    fun digits(s: String) = s.filter(Char::isDigit).take(12)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(if (enabled) "Turn off app lock" else "Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val hidden = PasswordVisualTransformation()
                FormField(if (enabled) "Current PIN" else "PIN (4 or more digits)", pin, { pin = digits(it); error = null }, error = error, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden)
                if (!enabled) FormField("Repeat PIN", confirm, { confirm = digits(it) }, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden)
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4 && (enabled || pin == confirm), onClick = {
                scope.launch {
                    if (enabled && !lock.check(pin)) {
                        error = "Wrong PIN"
                        return@launch
                    }
                    lock.setPin(if (enabled) null else pin)
                    enabled = !enabled
                    open = false
                }
            }) { Text(if (enabled) "Turn off" else "Turn on") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
