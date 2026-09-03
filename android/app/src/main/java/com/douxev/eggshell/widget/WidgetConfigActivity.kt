package com.douxev.eggshell.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.NotesRepository
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.theme.EggshellTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Configuration for every module widget — one activity, not ten.
 *
 * A configuration activity is named by `android:configure` per provider, but
 * nothing says each provider needs a different one: the launcher hands over the
 * `appWidgetId`, and [AppWidgetManager.getAppWidgetInfo] says which provider
 * owns it. Ten near-identical screens would be ten places for the consent copy
 * to drift out of step, which is the one thing here that must not.
 *
 * What it shows depends on how the module gets its content:
 *
 * - **Off-vault** (Traitements, Analyses): no opt-in switch, because there is
 *   nothing new to consent to — the widget shows what that module\'s own
 *   reminder notification already shows. Just how many rows.
 * - **Mirrored** (everything else): the opt-in, off by default, with the copy
 *   that says what turning it on costs. In paranoid mode the switch is replaced
 *   by the reason there isn\'t one.
 *
 * The Notes folder picker is the one module-specific control, and it needs the
 * vault open because folder names live in it. Shut, the widget is still
 * configurable — the screen says to come back rather than showing an empty list
 * with no explanation.
 */
@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var vaultPrefs: VaultPrefs
    @Inject lateinit var notes: NotesRepository
    @Inject lateinit var mirrors: WidgetMirrorUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (runCatching { SecurityPrefs(this).blockScreenshots.value }.getOrDefault(true)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelled unless the user finishes. A configuration activity that
        // returns nothing tells the launcher to drop the placement, which is
        // the right outcome for a back press.
        setResult(Activity.RESULT_CANCELED, resultIntent(widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val module = moduleOf(widgetId)
        if (module == null) {
            // Nothing sensible to configure for a provider we cannot identify;
            // accept the placement rather than making the launcher drop it.
            setResult(Activity.RESULT_OK, resultIntent(widgetId))
            finish()
            return
        }

        val configs = WidgetConfigPrefs(this)
        val existing = runCatching { configs.get(widgetId) }
            .getOrDefault(WidgetConfigPrefs.Config())
        val mirrored = module in MIRRORED
        val mayShowContent = WidgetContentMirror.writable(vaultPrefs.mode)
        val unlocked = vault.isUnlocked

        setContent {
            EggshellTheme {
                var showContent by remember {
                    mutableStateOf(
                        if (mirrored) existing.showsContent && mayShowContent else true
                    )
                }
                var rows by remember { mutableIntStateOf(existing.rows.coerceIn(1, MAX_ROWS)) }
                var folderId by remember {
                    mutableStateOf(
                        existing.targetId.takeIf {
                            existing.targetKind == WidgetMirrorUpdater.TARGET_FOLDER
                        }
                    )
                }
                var folders by remember {
                    mutableStateOf<List<uniffi.transition.NoteFolder>>(emptyList())
                }
                LaunchedEffect(unlocked, module) {
                    if (unlocked && module == AppModule.Notes) {
                        folders = runCatching { notes.folders(null) }.getOrDefault(emptyList())
                    }
                }

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = {
                        Text(getString(R.string.widget_config_title, getString(module.labelRes)))
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when {
                                !mirrored -> Text(
                                    getString(R.string.widget_config_offvault),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                mayShowContent -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            getString(R.string.widget_config_show),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Switch(
                                            checked = showContent,
                                            onCheckedChange = { showContent = it },
                                        )
                                    }
                                    Text(
                                        getString(R.string.widget_config_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                else -> Text(
                                    getString(R.string.widget_config_paranoid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Only the modules that draw a list have a row count
                            // to pick: the summary ones are one line by nature.
                            if (showContent && module in LISTING) {
                                Text(
                                    getString(R.string.widget_config_rows),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..MAX_ROWS).forEach { n ->
                                        FilterChip(
                                            selected = rows == n,
                                            onClick = { rows = n },
                                            label = { Text(n.toString()) },
                                        )
                                    }
                                }
                            }

                            if (showContent && module == AppModule.Notes) {
                                Text(
                                    getString(R.string.notes_widget_config_target),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!unlocked) {
                                    Text(
                                        getString(R.string.notes_widget_target_locked),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilterChip(
                                    selected = folderId == null,
                                    onClick = { folderId = null },
                                    label = { Text(getString(R.string.notes_widget_target_recent)) },
                                )
                                folders.forEach { folder ->
                                    FilterChip(
                                        selected = folderId == folder.id,
                                        onClick = { folderId = folder.id },
                                        label = { Text(folder.name) },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            save(
                                configs, widgetId, module,
                                WidgetConfigPrefs.Config(
                                    // Belt and braces with WidgetContentMirror.writable:
                                    // an opt-in must not be persistable in a mode
                                    // that refuses to honour it.
                                    showsContent = mirrored && showContent && mayShowContent,
                                    rows = rows,
                                    targetKind = folderId?.let {
                                        WidgetMirrorUpdater.TARGET_FOLDER
                                    },
                                    targetId = folderId,
                                ),
                            )
                        }) { Text(getString(R.string.action_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text(getString(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }

    /** Which module owns this placement, per the provider the launcher bound. */
    private fun moduleOf(widgetId: Int): AppModule? {
        val provider = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        }.getOrNull() ?: return null
        return moduleWidgets(this)
            .firstOrNull { (component, _) -> component == provider }
            ?.second
            ?.module
    }

    /**
     * Write the config, fill the mirror, repaint, then hand the launcher its
     * result — in that order.
     *
     * A launcher that gets RESULT_OK first may render immediately, and would
     * find a config not yet on disk and a mirror still empty: an opted-in widget
     * showing the "open the app" line it was just configured not to show.
     */
    private fun save(
        configs: WidgetConfigPrefs,
        widgetId: Int,
        module: AppModule,
        config: WidgetConfigPrefs.Config,
    ) {
        runCatching { configs.put(widgetId, config) }
        lifecycleScope.launch {
            runCatching { mirrors.refresh() }
            runCatching { WidgetRefresh.refreshModule(this@WidgetConfigActivity, module) }
            setResult(Activity.RESULT_OK, resultIntent(widgetId))
            finish()
        }
    }

    private fun resultIntent(widgetId: Int) =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private companion object {
        /** What widget_content.xml and widget_meds.xml have room for. */
        const val MAX_ROWS = 4

        /** Modules whose widget draws content from the opt-in mirror. */
        val MIRRORED = setOf(
            AppModule.Notes, AppModule.Journal, AppModule.Appointments,
            AppModule.Weight, AppModule.Bleeding, AppModule.Photos,
            AppModule.Voice, AppModule.Dreams, AppModule.Sport,
        )

        /** Modules whose widget draws a list, and so has a row count to pick. */
        val LISTING = setOf(
            AppModule.Meds, AppModule.Labs, AppModule.Notes,
            AppModule.Journal, AppModule.Appointments,
        )
    }
}
