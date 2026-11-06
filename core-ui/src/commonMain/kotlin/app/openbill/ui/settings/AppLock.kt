package app.openbill.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextAlign
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.model.Permission
import kotlinx.coroutines.flow.first
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
 * not the database file; device encryption covers that (docs/03 section 13). Wrong PINs back off
 * exponentially (persisted, so restarting does not reset it). On desktop it can also lock again
 * after [idleMinutes] without input or when the window is minimised.
 */
class AppLockController(private val store: SettingsStore) {
    val isEnabled: Boolean get() = !runCatching { store.getString(KEY_PIN) }.getOrNull().isNullOrEmpty()
    private val _locked = MutableStateFlow(isEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundAt: Long? = null
    private var lastActivityAt: Long? = null

    init {
        Access.loadManagersChangeSettings(runCatching { store.getString(KEY_MANAGER_SETTINGS) }.getOrNull() == "true")
    }

    /** Owner's switch: managers may change business settings (default off); persisted on this device. */
    var managersChangeSettings: Boolean
        get() = Access.managersChangeSettings
        set(value) {
            Access.setManagersChangeSettings(value)
            store.putString(KEY_MANAGER_SETTINGS, value.toString())
        }

    /** Desktop: minutes without input before it locks again; 0 = never. When set, minimising locks too. */
    var idleMinutes: Int
        get() = store.getString(KEY_IDLE_MINUTES)?.toIntOrNull() ?: 0
        set(value) = store.putString(KEY_IDLE_MINUTES, value.toString())

    /** Sets the PIN, or turns the lock off when null. */
    suspend fun setPin(pin: String?) {
        store.putString(KEY_PIN, pin?.let { hashPin(it) } ?: "")
    }

    /** The owner's (app-lock) PIN. */
    suspend fun check(pin: String): Boolean = verifyPin(pin, store.getString(KEY_PIN))

    /** Milliseconds before another PIN try is accepted (0 = now). */
    fun retryInMs(nowMs: Long): Long = ((store.getString(KEY_RETRY_AT)?.toLongOrNull() ?: 0L) - nowMs).coerceAtLeast(0)

    /**
     * Checks [pin] with [verify] (the owner's PIN by default, a staff user's hash otherwise) unless
     * still backing off; failures of every user share one persisted counter, so switching names
     * does not reset the wait. [onSuccess] runs before the screen unlocks.
     */
    suspend fun unlock(pin: String, nowMs: Long, verify: suspend (String) -> Boolean = ::check, onSuccess: () -> Unit = {}): Boolean {
        if (retryInMs(nowMs) > 0) return false
        if (verify(pin)) {
            store.putString(KEY_FAILURES, "0")
            store.putString(KEY_RETRY_AT, "0")
            onSuccess()
            open()
            return true
        }
        val failures = (store.getString(KEY_FAILURES)?.toIntOrNull() ?: 0) + 1
        store.putString(KEY_FAILURES, failures.toString())
        store.putString(KEY_RETRY_AT, (nowMs + backoffMs(failures)).toString())
        return false
    }

    /** After the platform (biometric) prompt passed. */
    fun unlockTrusted() = open()

    private fun open() {
        lastActivityAt = null
        _locked.value = false
    }

    fun onActivity(nowMs: Long) {
        lastActivityAt = nowMs
    }

    /** "Switch user": back to the sign-in screen. */
    fun lockNow() {
        if (isEnabled) _locked.value = true
    }

    fun onMinimised() {
        if (isEnabled && idleMinutes > 0) _locked.value = true
    }

    /** Called periodically on desktop; locks after [idleMinutes] without [onActivity]. */
    fun checkIdle(nowMs: Long) {
        val minutes = idleMinutes
        if (!isEnabled || minutes <= 0 || _locked.value) return
        val last = lastActivityAt ?: nowMs.also { lastActivityAt = it }
        if (nowMs - last >= minutes * 60_000L) _locked.value = true
    }

    fun onBackground(nowMs: Long) {
        backgroundAt = nowMs
    }

    fun onForeground(nowMs: Long) {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (isEnabled && nowMs - since >= RELOCK_AFTER_MS) _locked.value = true
    }

    companion object {
        /** A new PIN is [PIN_MIN] to [PIN_MAX] digits; PINs set before this rule (up to 12) still sign in. */
        const val PIN_MIN = 4
        const val PIN_MAX = 8
        const val PIN_HINT = "4 to 8 digits. 6 or more is safer: backups hold the PIN hashes, and short PINs are easy to guess offline."

        /** Null when [pin] can be set, else what to fix. The length is not stored; sign-in needs an explicit Sign in. */
        fun pinError(pin: String): String? = when {
            !pin.all(Char::isDigit) -> "Use digits only"
            pin.length < PIN_MIN -> "Use at least $PIN_MIN digits"
            pin.length > PIN_MAX -> "Use at most $PIN_MAX digits"
            else -> null
        }

        /** "iterations:salt:hash" with a fresh 16-byte salt; PINs are never stored in clear. */
        suspend fun hashPin(pin: String): String {
            pinError(pin)?.let { throw IllegalArgumentException(it) }
            val salt = secureRandomBytes(16)
            return "$ITERATIONS:${salt.hex()}:${hash(pin, salt, ITERATIONS).hex()}"
        }

        /** Constant-time comparison against a [hashPin] value; false for a missing or malformed one. */
        suspend fun verifyPin(pin: String, stored: String?): Boolean {
            val (iterations, salt, expected) = stored?.split(':')?.takeIf { it.size == 3 } ?: return false
            val rounds = iterations.toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
            val saltBytes = runCatching { salt.unhex() }.getOrNull() ?: return false
            val actual = hash(pin, saltBytes, rounds).hex()
            return actual.length == expected.length && actual.indices.fold(0) { acc, i -> acc or (actual[i].code xor expected[i].code) } == 0
        }

        private suspend fun hash(pin: String, salt: ByteArray, iterations: Int) =
            withContext(Dispatchers.Default) { pbkdf2(pin.toCharArray(), salt, iterations) }

        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        const val KEY_PIN = "lock.pin"
        const val KEY_FAILURES = "lock.failures"
        const val KEY_RETRY_AT = "lock.retryAt"
        const val KEY_IDLE_MINUTES = "lock.idleMinutes"
        const val KEY_MANAGER_SETTINGS = "access.managersChangeSettings"
        const val FREE_TRIES = 3
        val IDLE_CHOICES = listOf(0, 1, 5, 15, 30)

        /** No wait for the first [FREE_TRIES] failures, then 30 s doubling per failure, capped at an hour. */
        fun backoffMs(failures: Int): Long =
            if (failures < FREE_TRIES) 0 else minOf(30_000L shl minOf(failures - FREE_TRIES, 7), 3_600_000L)
        const val RELOCK_AFTER_MS = 60_000L
        private const val ITERATIONS = 120_000
    }
}

/**
 * Unlock, and with staff users also sign in: pick a name, enter that person's PIN. The last
 * user is preselected so an idle lock returns to them. Fingerprint/face stays owner-only and is
 * offered only while the owner is the sole active user (a shared tablet may hold staff prints).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LockScreen(container: AppContainer) {
    val lock = container.appLock
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val users by remember { container.repositories.users.observeUsers() }.collectAsState(initial = null)
    val active = users.orEmpty().filter { it.active }
    val multi = active.size > 1
    var selectedId by remember { mutableStateOf(Access.current?.id ?: Access.OWNER_ID) }
    val selected = active.firstOrNull { it.id == selectedId } ?: active.firstOrNull { it.isOwner }
    fun biometric() = scope.launch {
        if (runCatching { platform.biometricUnlock() }.getOrDefault(false)) {
            Access.current = null
            lock.unlockTrusted()
        }
    }
    fun submit() = scope.launch {
        val user = selected ?: return@launch
        val now = container.now().toEpochMilliseconds()
        val wait = lock.retryInMs(now)
        val verify: suspend (String) -> Boolean = if (user.isOwner) lock::check else { p -> AppLockController.verifyPin(p, user.pinHash) }
        error = when {
            wait > 0 -> "Too many tries. Wait ${(wait + 999) / 1000} s"
            lock.unlock(pin, now, verify) { Access.current = user } -> {
                if (multi) recordSignIn(container, user)
                null
            }
            else -> lock.retryInMs(now).let { if (it > 0) "Wrong PIN. Wait ${(it + 999) / 1000} s" else "Wrong PIN" }
        }
        pin = ""
    }
    LaunchedEffect(users != null, multi) { if (users != null && !multi && platform.supportsBiometric) biometric() }
    Column(
        // imePadding: in landscape the keyboard would otherwise cover the PIN field and Sign in.
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(rememberVectorPainter(BrandLogo.Mark), contentDescription = null, modifier = Modifier.size(64.dp))
        Text(if (multi) "Who is using OpenBill?" else "OpenBill is locked", style = MaterialTheme.typography.titleLarge)
        if (multi) {
            FlowRow(
                Modifier.widthIn(max = 480.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
            ) {
                active.forEach { u ->
                    FilterChip(
                        selected = u.id == selected?.id,
                        onClick = { selectedId = u.id; pin = ""; error = null },
                        label = { Text(if (u.name == u.role.label) u.name else "${u.name} · ${u.role.label}") },
                    )
                }
            }
        }
        Column(Modifier.widthIn(max = 360.dp)) {
            FormField(
                "PIN", pin, { pin = it.filter(Char::isDigit).take(12); error = null }, error = error,
                keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, onImeAction = { submit() },
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        Button(onClick = { submit() }, enabled = pin.length >= 4 && selected != null) { Text(if (multi) "Sign in" else "Unlock") }
        if (platform.supportsBiometric && users != null && !multi) TextButton(onClick = { biometric() }) { Text("Use fingerprint or face") }
    }
}

/** Activity-log entry for a sign-in on a shared install, against the active business. */
private suspend fun recordSignIn(container: AppContainer, user: AppUser) {
    val repos = container.repositories
    val firmId = container.activeFirm.firmId.value ?: runCatching { repos.firms.observeFirms().first().firstOrNull()?.id }.getOrNull() ?: return
    runCatching {
        repos.audit.record(AuditEvent(container.newId(), firmId, "user", user.id, action = "user.signed_in", actor = user.id, createdAt = container.now()))
    }
}

/** Shown instead of a screen the signed-in user's role does not allow. */
@Composable
fun NotAllowedScreen(permission: Permission, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
        Text("Not allowed", style = MaterialTheme.typography.titleLarge)
        Text(
            "Your role (${Access.role.label}) cannot ${permission.verb}. Ask the owner.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text("Go back") }
    }
}

/** More screen row: turn the PIN lock on (PIN twice), off (current PIN), or change the PIN (current, then new twice). */
@Composable
fun AppLockItem(container: AppContainer, colors: ListItemColors) {
    val lock = container.appLock
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(lock.isEnabled) }
    var open by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val messages = app.openbill.ui.components.LocalAppMessages.current
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val staff by remember { container.repositories.users.observeUsers() }.collectAsState(initial = emptyList())
    val staffActive = staff.any { it.active && !it.isOwner }
    val biometric = if (container.platform.supportsBiometric) " or fingerprint" else ""
    ListItem(
        headlineContent = { Text("App lock") },
        supportingContent = { Text(if (enabled) "PIN$biometric needed to open OpenBill" else "Ask for a PIN$biometric when OpenBill opens") },
        leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        colors = colors,
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch) { pin = ""; newPin = ""; confirm = ""; error = null; changing = false; open = true },
    )
    if (enabled) {
        ListItem(
            headlineContent = { Text("Change PIN") },
            supportingContent = { Text("The owner's PIN; ${AppLockController.PIN_MIN} to ${AppLockController.PIN_MAX} digits") },
            colors = colors,
            modifier = Modifier.clickable(role = Role.Button) { pin = ""; newPin = ""; confirm = ""; error = null; changing = true; open = true },
        )
    }
    if (enabled && app.openbill.ui.theme.platformPrefersKeyboard) {
        var idle by remember { mutableStateOf(lock.idleMinutes) }
        ListItem(
            headlineContent = { Text("Lock when idle") },
            supportingContent = { Text(if (idle == 0) "Off" else "After $idle min without input, or when minimised") },
            colors = colors,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Change") {
                val choices = AppLockController.IDLE_CHOICES
                idle = choices[(choices.indexOf(idle) + 1) % choices.size]
                lock.idleMinutes = idle
            },
        )
    }
    if (!open) return
    // The current PIN may be an older one of up to 12 digits; a new one is 4 to 8.
    fun digits(s: String, max: Int = 12) = s.filter(Char::isDigit).take(max)
    val setting = !enabled || changing
    val next = if (changing) newPin else pin
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(if (changing) "Change PIN" else if (enabled) "Turn off app lock" else "Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val hidden = PasswordVisualTransformation()
                if (enabled) FormField("Current PIN", pin, { pin = digits(it); error = null }, error = error, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden)
                if (setting) {
                    val typed = { v: String -> digits(v, AppLockController.PIN_MAX).also { error = null } }
                    FormField(
                        if (changing) "New PIN" else "PIN", next, { if (changing) newPin = typed(it) else pin = typed(it) },
                        error = error.takeIf { !enabled }, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden,
                        supporting = AppLockController.PIN_HINT,
                    )
                    FormField(
                        "Repeat PIN", confirm, { confirm = digits(it, AppLockController.PIN_MAX) }, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden,
                        error = "PINs do not match".takeIf { confirm.length >= next.length && confirm.isNotEmpty() && confirm != next },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = (!enabled || pin.length >= AppLockController.PIN_MIN) && (!setting || (AppLockController.pinError(next) == null && next == confirm)), onClick = {
                scope.launch {
                    if (enabled && !changing && staffActive) {
                        error = "Deactivate the other users first; they sign in through this lock"
                        return@launch
                    }
                    if (enabled && !lock.unlock(pin, container.now().toEpochMilliseconds())) {
                        error = lock.retryInMs(container.now().toEpochMilliseconds()).let { if (it > 0) "Wrong PIN. Wait ${(it + 999) / 1000} s" else "Wrong PIN" }
                        return@launch
                    }
                    lock.setPin(if (setting) next else null)
                    if (!changing) enabled = !enabled
                    open = false
                    if (changing) messages?.show("PIN changed")
                }
            }) { Text(if (changing) "Change" else if (enabled) "Turn off" else "Turn on") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
