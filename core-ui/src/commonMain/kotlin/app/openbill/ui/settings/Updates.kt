package app.openbill.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.openbill.ui.AppContainer
import app.openbill.ui.theme.Spacing

private val SEMVER = Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

/** Semantic version order (a leading "v" is allowed); null when either is not a version, such as "dev". */
fun compareVersions(a: String, b: String): Int? {
    val x = SEMVER.matchEntire(a.trim())?.groupValues ?: return null
    val y = SEMVER.matchEntire(b.trim())?.groupValues ?: return null
    for (i in 1..3) x[i].toLong().compareTo(y[i].toLong()).let { if (it != 0) return it }
    val (p, q) = x[4] to y[4]
    // A release outranks its pre-releases: 1.0.0 > 1.0.0-alpha.
    if (p.isEmpty() || q.isEmpty()) return q.length.coerceAtMost(1) - p.length.coerceAtMost(1)
    val ps = p.split('.')
    val qs = q.split('.')
    for (i in 0 until minOf(ps.size, qs.size)) {
        val m = ps[i].toLongOrNull()
        val n = qs[i].toLongOrNull()
        val c = when {
            m != null && n != null -> m.compareTo(n)
            m != null -> -1 // numeric identifiers rank below alphanumeric ones
            n != null -> 1
            else -> ps[i].compareTo(qs[i])
        }
        if (c != 0) return c
    }
    return ps.size.compareTo(qs.size)
}

/**
 * Optional "new version" notice: at most one GitHub releases request a day, only while [enabled].
 * The answer is kept so later launches show the banner without asking again; [dismiss] hides
 * that version for good. Nothing is downloaded or installed.
 */
class UpdateController(private val store: SettingsStore, private val defaultEnabled: Boolean) {
    var enabled: Boolean
        get() = store.getString(KEY_ENABLED)?.toBooleanStrictOrNull() ?: defaultEnabled
        set(value) = store.putString(KEY_ENABLED, value.toString())

    /** True when checking is on and the last attempt was a day ago or more (or the clock went back). */
    fun isDue(nowMs: Long): Boolean {
        if (!enabled) return false
        val last = store.getString(KEY_CHECKED_AT)?.toLongOrNull() ?: return true
        return nowMs - last !in 0 until DAY_MS
    }

    /** Recorded before the request, so a failing network is not retried until tomorrow. */
    fun markChecked(nowMs: Long) = store.putString(KEY_CHECKED_AT, nowMs.toString())

    fun saveLatest(tag: String, url: String) {
        store.putString(KEY_TAG, tag)
        store.putString(KEY_URL, url)
    }

    /** The newer, not dismissed release as (version, page URL), or null. */
    fun available(current: String): Pair<String, String>? {
        if (!enabled) return null
        val version = store.getString(KEY_TAG)?.removePrefix("v") ?: return null
        val url = store.getString(KEY_URL) ?: return null
        if (version == store.getString(KEY_DISMISSED)) return null
        return if ((compareVersions(version, current) ?: 0) > 0) version to url else null
    }

    fun dismiss(version: String) = store.putString(KEY_DISMISSED, version)

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/zenithblue-oss/openbill/releases/latest"
        const val KEY_ENABLED = "update.check"
        const val KEY_CHECKED_AT = "update.checkedAt"
        const val KEY_TAG = "update.latestTag"
        const val KEY_URL = "update.latestUrl"
        const val KEY_DISMISSED = "update.dismissed"
        private const val DAY_MS = 86_400_000L

        /**
         * tag_name and html_url from the releases API. The release's own html_url comes before the
         * author's in GitHub's response; only this project's release pages are accepted.
         */
        fun parseRelease(json: String): Pair<String, String>? {
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val tag = field("tag_name")?.takeIf { compareVersions(it, it) != null } ?: return null
            val url = field("html_url")?.takeIf { it.startsWith("https://github.com/zenithblue-oss/openbill/releases/") } ?: return null
            return tag to url
        }
    }
}

/** Shows "What's new" once after an update; a fresh install records its version silently. */
class WhatsNewController(private val store: SettingsStore) {
    /**
     * Call once per launch when it is known whether any business exists ([hasData]). True when
     * [current] is newer than the version last seen, or none was recorded but data exists (an
     * upgrade from a build without this). Records [current] either way.
     */
    fun onLaunch(current: String, hasData: Boolean): Boolean {
        if (compareVersions(current, current) == null) return false // "dev" builds
        val last = store.getString(KEY_LAST_SEEN)
        val show = if (last == null) hasData else (compareVersions(current, last) ?: 1) > 0
        store.putString(KEY_LAST_SEEN, current)
        return show
    }

    companion object {
        const val KEY_LAST_SEEN = "whatsNew.lastSeen"
    }
}

/** The CHANGELOG.md section for [version] ("## 1.0.0 - date"), with links reduced to their text. */
fun releaseNotes(changelog: String, version: String): String? {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it == "## $version" || it.startsWith("## $version ") }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
    return lines.subList(start + 1, end).joinToString("\n")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("\n  ", " ") // join wrapped bullet lines
        .replace("`", "")
        .trim().ifEmpty { null }
}

internal fun currentReleaseNotes(version: String): String? = releaseNotes(CHANGELOG, version)

@Composable
fun WhatsNewDialog(version: String, notes: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in OpenBill $version") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(notes) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Root: the one-time "What's new" once the business list ([firms]) has loaded. */
@Composable
fun WhatsNewOnLaunch(container: AppContainer, firms: List<*>?) {
    var show by remember { mutableStateOf(false) }
    val version = container.platform.appVersion
    LaunchedEffect(firms != null) {
        if (firms != null) show = runCatching { container.whatsNew.onLaunch(version, firms.isNotEmpty()) }.getOrDefault(false)
    }
    val notes = if (show) currentReleaseNotes(version) else null
    if (notes != null) WhatsNewDialog(version, notes) { show = false }
}

/** Root: the "OpenBill X.Y.Z is available" banner, after the daily check where enabled. */
@Composable
fun UpdateBanner(container: AppContainer, modifier: Modifier = Modifier) {
    val updates = container.updates
    val current = container.platform.appVersion
    var available by remember { mutableStateOf(runCatching { updates.available(current) }.getOrNull()) }
    LaunchedEffect(Unit) {
        if (!container.platform.supportsUpdateCheck) return@LaunchedEffect
        runCatching {
            val now = container.now().toEpochMilliseconds()
            if (!updates.isDue(now)) return@runCatching
            updates.markChecked(now)
            val (tag, url) = container.platform.httpGet(UpdateController.RELEASES_API)?.let(UpdateController::parseRelease) ?: return@runCatching
            updates.saveLatest(tag, url)
            available = updates.available(current)
        } // network errors stay silent
    }
    val (version, url) = available ?: return
    val uri = LocalUriHandler.current
    Surface(
        modifier.padding(Spacing.lg).widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text("OpenBill $version is available", Modifier.weight(1f))
            TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("View") }
            TextButton(onClick = { updates.dismiss(version); available = null }) { Text("Dismiss") }
        }
    }
}

/** About section rows: "What's new" and, where the platform can check, "Check for updates". */
@Composable
fun UpdateItems(container: AppContainer, colors: ListItemColors) {
    val version = container.platform.appVersion
    val notes = remember(version) { currentReleaseNotes(version) }
    var open by remember { mutableStateOf(false) }
    if (notes != null) {
        ListItem(
            headlineContent = { Text("What's new") },
            supportingContent = { Text("Changes in OpenBill $version") },
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            colors = colors,
            modifier = Modifier.clickable(role = Role.Button) { open = true },
        )
        if (open) WhatsNewDialog(version, notes) { open = false }
    }
    if (!container.platform.supportsUpdateCheck) return
    var enabled by remember { mutableStateOf(container.updates.enabled) }
    ListItem(
        headlineContent = { Text("Check for updates") },
        supportingContent = { Text("Once a day, ask GitHub for the latest version number. No business data is sent and nothing is downloaded.") },
        leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        colors = colors,
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch) {
            enabled = it
            container.updates.enabled = it
        },
    )
}
