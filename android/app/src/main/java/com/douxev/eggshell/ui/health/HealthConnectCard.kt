package com.douxev.eggshell.ui.health

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.health.HealthConnectPrefs
import com.douxev.eggshell.data.health.HealthConnectSync
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val sync: HealthConnectSync,
    private val prefs: HealthConnectPrefs,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = prefs.enabled
    val lastSyncMs: StateFlow<Long?> = prefs.lastSyncMs
    val status: StateFlow<HealthConnectSync.Status> = sync.status

    /** What the permission request asks for: the four reads, nothing more. */
    val permissions: Set<String> = HealthConnectSync.PERMISSIONS

    /**
     * Re-read on every resume: Health Connect can be installed, updated or
     * removed while this screen is in the background, and the card must not
     * keep offering a store link for something that is now there.
     */
    private val _availability = MutableStateFlow(sync.availability())
    val availability: StateFlow<HealthConnectSync.Availability> = _availability.asStateFlow()
    fun recheck() { _availability.value = sync.availability() }

    /** Set when the permission request came back with nothing granted. */
    private val _refused = MutableStateFlow(false)
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    /**
     * The permission request answered. Anything granted turns the import on
     * and syncs at once; nothing granted leaves it off and says so — a switch
     * that is "on" while allowed to read nothing would be lying.
     */
    fun onPermissionResult(granted: Set<String>) {
        if (granted.intersect(permissions).isEmpty()) {
            _refused.value = true
            return
        }
        _refused.value = false
        prefs.setEnabled(true)
        sync.syncInBackground(force = true)
    }

    /**
     * Off, and the access handed back: an app that stops importing has no
     * reason to keep the right to read, and Health Connect then lists one app
     * fewer with access to the user's workouts. What was imported stays.
     */
    fun disable() {
        _refused.value = false
        prefs.setEnabled(false)
        viewModelScope.launch { sync.revokeAccess() }
    }

    fun syncNow() = sync.syncInBackground(force = true)
}

/**
 * The Health Connect import, as a card in Sport › Types et podomètre.
 *
 * Off until the user turns it on; turning it on is what asks Health Connect
 * for access, through Health Connect's own permission screen. The card then
 * says when it last synced and how that went, and offers a manual sync and the
 * « what is read, and why » screen.
 */
@Composable
fun HealthConnectCard(vm: HealthConnectViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val enabled by vm.enabled.collectAsState()
    val lastSync by vm.lastSyncMs.collectAsState()
    val status by vm.status.collectAsState()
    val availability by vm.availability.collectAsState()
    val refused by vm.refused.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.recheck() }

    val requestAccess = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> vm.onPermissionResult(granted) }
    val available = availability == HealthConnectSync.Availability.AVAILABLE

    EggCard(variant = CardVariant.Low) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.health_switch),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.health_card_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled && available,
                // Off stays reachable even when Health Connect went away, so
                // a stale « on » can always be switched off.
                enabled = available || enabled,
                onCheckedChange = { wanted ->
                    if (wanted) requestAccess.launch(vm.permissions) else vm.disable()
                },
            )
        }

        when (availability) {
            HealthConnectSync.Availability.UNAVAILABLE -> Note(stringResource(R.string.health_unavailable))
            HealthConnectSync.Availability.NEEDS_PROVIDER -> {
                Note(stringResource(R.string.health_needs_provider))
                TextButton(onClick = { openProviderInStore(context) }) {
                    Text(stringResource(R.string.health_install))
                }
            }
            HealthConnectSync.Availability.AVAILABLE -> {
                if (refused && !enabled) Note(stringResource(R.string.health_refused))
                if (enabled) {
                    statusLine(status)?.let { Note(it) }
                    if (status.last is HealthConnectSync.Result.NotPermitted && !status.running) {
                        TextButton(onClick = { requestAccess.launch(vm.permissions) }) {
                            Text(stringResource(R.string.health_grant))
                        }
                    }
                    Note(
                        lastSync?.let { stringResource(R.string.health_last_sync, formatInstant(it)) }
                            ?: stringResource(R.string.health_never_synced)
                    )
                    TextButton(onClick = vm::syncNow, enabled = !status.running) {
                        Text(stringResource(R.string.health_sync_now))
                    }
                }
            }
        }

        TextButton(onClick = {
            runCatching { context.startActivity(HealthConnectRationaleActivity.intent(context)) }
        }) {
            Text(stringResource(R.string.health_why))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** What the last sync did, in words, or null when there is nothing to say yet. */
@Composable
private fun statusLine(status: HealthConnectSync.Status): String? {
    if (status.running) return stringResource(R.string.health_syncing)
    return when (val last = status.last) {
        null -> null
        is HealthConnectSync.Result.Imported ->
            if (last.sessions == 0 && last.stepDays == 0) {
                stringResource(R.string.health_nothing_new)
            } else {
                stringResource(
                    R.string.health_imported,
                    pluralStringResource(R.plurals.health_sessions, last.sessions, last.sessions),
                    pluralStringResource(R.plurals.health_step_days, last.stepDays, last.stepDays),
                )
            }
        HealthConnectSync.Result.NotPermitted -> stringResource(R.string.health_not_permitted)
        HealthConnectSync.Result.Unavailable -> stringResource(R.string.health_unavailable)
        is HealthConnectSync.Result.Failed -> stringResource(R.string.health_failed, last.reason)
    }
}

private fun formatInstant(atMs: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

/**
 * Health Connect's store page, for a phone where it is missing or too old.
 * The Play Store first, with the onboarding hint Health Connect documents;
 * a plain web link when there is no Play Store to hand it to. Through the
 * application context, like the app's other outbound links.
 */
private fun openProviderInStore(context: Context) {
    val app = context.applicationContext
    val pkg = HealthConnectSync.PROVIDER_PACKAGE
    val market = Intent(
        Intent.ACTION_VIEW,
        "market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding".toUri(),
    )
        .setPackage("com.android.vending")
        .putExtra("overlay", true)
        .putExtra("callerId", app.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.startActivity(market) }.onFailure {
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
