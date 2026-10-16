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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.NotesRepository
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.theme.EggshellTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
 * - **Off-vault** (Traitements, Analyses): no content opt-in, because the
 *   widget shows what that module's own reminder notification already shows.
 *   Analyses has one switch all the same: its reminders carry a label the user
 *   typed, which the notification deliberately never shows, so putting it on
 *   the home screen takes the same per-widget consent as mirrored content —
 *   and is refused in paranoid mode.
 * - **Mirrored** (everything else): the opt-in, off by default, with the copy
 *   that says what turning it on costs. In paranoid mode the switch is replaced
 *   by the reason there isn't one.
 *
 * Two module-specific pickers need the vault open, because the names they
 * offer live in it: Notes (the latest notes, one folder, or one note shown with
 * its opening) and Traitements (every medication, or one). Shut, the widget is
 * still configurable: the screen says to come back rather than showing an
 * empty list, and keeps whatever target was already chosen.
 *
 * A tap outside the dialog does nothing. The launcher treats a configuration
 * activity that returns without RESULT_OK as "drop the widget", and a stray
 * tap on the wallpaper is not a decision to lose a widget the user just placed;
 * Annuler and the back gesture are.
 */
@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var vaultPrefs: VaultPrefs
    @Inject lateinit var notes: NotesRepository
    @Inject lateinit var medications: MedicationRepository
    @Inject lateinit var mirrors: WidgetMirrorUpdater

    /** A note the picker can point a widget at, with where it is filed. */
    private data class NoteChoice(val id: Long, val title: String, val folder: String?)

    /** How a Notes widget chooses what to show. */
    private enum class NotesTarget { RECENT, FOLDER, NOTE }

    @OptIn(ExperimentalLayoutApi::class)
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
        // The modules whose widget asks before it shows something: everything
        // mirrored, and Analyses for its labels.
        val asksConsent = mirrored || module in LABEL_OPT_IN
        val mayShowContent = WidgetContentMirror.writable(vaultPrefs.mode)
        val unlocked = vault.isUnlocked

        setContent {
            EggshellTheme {
                var consent by remember {
                    mutableStateOf(asksConsent && existing.showsContent && mayShowContent)
                }
                var rows by remember { mutableIntStateOf(existing.rows.coerceIn(1, MAX_ROWS)) }
                var target by remember {
                    mutableStateOf(
                        when (existing.targetKind) {
                            WidgetMirrorUpdater.TARGET_FOLDER -> NotesTarget.FOLDER
                            WidgetMirrorUpdater.TARGET_NOTE -> NotesTarget.NOTE
                            else -> NotesTarget.RECENT
                        }
                    )
                }
                var folderId by remember {
                    mutableStateOf(
                        existing.targetId.takeIf {
                            existing.targetKind == WidgetMirrorUpdater.TARGET_FOLDER
                        }
                    )
                }
                var noteId by remember {
                    mutableStateOf(
                        existing.targetId.takeIf {
                            existing.targetKind == WidgetMirrorUpdater.TARGET_NOTE
                        }
                    )
                }
                var medicationId by remember {
                    mutableStateOf(
                        existing.targetId.takeIf {
                            existing.targetKind == WidgetMirrorUpdater.TARGET_MEDICATION
                        }
                    )
                }
                var folders by remember {
                    mutableStateOf<List<uniffi.transition.NoteFolder>>(emptyList())
                }
                var choices by remember { mutableStateOf<List<NoteChoice>>(emptyList()) }
                var meds by remember {
                    mutableStateOf<List<uniffi.transition.Medication>>(emptyList())
                }
                LaunchedEffect(unlocked, module) {
                    if (unlocked && module == AppModule.Notes) {
                        folders = runCatching { notes.folders(null) }.getOrDefault(emptyList())
                        choices = runCatching { allNotes() }.getOrDefault(emptyList())
                    }
                    if (unlocked && module == AppModule.Meds) {
                        meds = runCatching { medications.list(includeArchived = false) }
                            .getOrDefault(emptyList())
                    }
                }

                AlertDialog(
                    // Only the back gesture reaches this: outside taps are switched
                    // off below, so a stray tap on the wallpaper cannot cancel the
                    // placement — but back still means Annuler, as the class doc
                    // promises, instead of being swallowed by the dialog.
                    onDismissRequest = { finish() },
                    properties = DialogProperties(dismissOnClickOutside = false),
                    title = {
                        Text(getString(R.string.widget_config_title, getString(module.labelRes)))
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!mirrored) {
                                Text(
                                    getString(R.string.widget_config_offvault),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (asksConsent && mayShowContent) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        getString(
                                            if (mirrored) R.string.widget_config_show
                                            else R.string.widget_config_labs_show
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = consent,
                                        onCheckedChange = { consent = it },
                                    )
                                }
                                Text(
                                    getString(
                                        if (mirrored) R.string.widget_config_warning
                                        else R.string.widget_config_labs_warning
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (asksConsent) {
                                Text(
                                    getString(
                                        if (mirrored) R.string.widget_config_paranoid
                                        else R.string.widget_config_labs_paranoid
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Only the modules that draw a list have a row count
                            // to pick: the summary ones are one card by nature,
                            // and a single note fills the card on its own.
                            val listing = module in LISTING &&
                                !(module == AppModule.Notes && target == NotesTarget.NOTE)
                            // A mirrored widget has rows only once it is opted in;
                            // the off-vault ones always have them.
                            if (listing && (!mirrored || consent)) {
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

                            if (consent && module == AppModule.Notes) {
                                Text(
                                    getString(R.string.notes_widget_config_target),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = target == NotesTarget.RECENT,
                                        onClick = { target = NotesTarget.RECENT },
                                        label = { Text(getString(R.string.notes_widget_target_recent)) },
                                    )
                                    FilterChip(
                                        selected = target == NotesTarget.FOLDER,
                                        onClick = { target = NotesTarget.FOLDER },
                                        label = { Text(getString(R.string.notes_widget_target_folder)) },
                                    )
                                    FilterChip(
                                        selected = target == NotesTarget.NOTE,
                                        onClick = { target = NotesTarget.NOTE },
                                        label = { Text(getString(R.string.notes_widget_target_note)) },
                                    )
                                }
                                if (!unlocked && target != NotesTarget.RECENT) {
                                    Text(
                                        getString(R.string.notes_widget_target_locked),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (target == NotesTarget.FOLDER && unlocked) {
                                    if (folders.isEmpty()) {
                                        Text(
                                            getString(R.string.notes_widget_no_folders),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        folders.forEach { folder ->
                                            FilterChip(
                                                selected = folderId == folder.id,
                                                onClick = { folderId = folder.id },
                                                label = { Text(folder.name) },
                                            )
                                        }
                                    }
                                }
                                if (target == NotesTarget.NOTE) {
                                    Text(
                                        getString(R.string.notes_widget_note_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (unlocked) {
                                        if (choices.isEmpty()) {
                                            Text(
                                                getString(R.string.notes_widget_no_notes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        NotePicker(
                                            choices = choices,
                                            selected = noteId,
                                            onPick = { noteId = it },
                                        )
                                    }
                                }
                            }

                            if (module == AppModule.Meds) {
                                MedicationPicker(
                                    unlocked = unlocked,
                                    meds = meds,
                                    selected = medicationId,
                                    onPick = { medicationId = it },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        val targetKind = when {
                            module == AppModule.Meds && medicationId != null ->
                                WidgetMirrorUpdater.TARGET_MEDICATION
                            module != AppModule.Notes -> null
                            target == NotesTarget.FOLDER && folderId != null ->
                                WidgetMirrorUpdater.TARGET_FOLDER
                            target == NotesTarget.NOTE && noteId != null ->
                                WidgetMirrorUpdater.TARGET_NOTE
                            else -> null
                        }
                        TextButton(onClick = {
                            save(
                                configs, widgetId, module,
                                WidgetConfigPrefs.Config(
                                    // Belt and braces with WidgetContentMirror.writable:
                                    // an opt-in must not be persistable in a mode
                                    // that refuses to honour it.
                                    showsContent = asksConsent && consent && mayShowContent,
                                    rows = rows,
                                    targetKind = targetKind,
                                    targetId = when (targetKind) {
                                        WidgetMirrorUpdater.TARGET_FOLDER -> folderId
                                        WidgetMirrorUpdater.TARGET_NOTE -> noteId
                                        WidgetMirrorUpdater.TARGET_MEDICATION -> medicationId
                                        else -> null
                                    },
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

    /**
     * A radio list of every note, root first then folder by folder, capped in
     * height so the dialog still fits a phone with a large vault.
     */
    @androidx.compose.runtime.Composable
    private fun NotePicker(
        choices: List<NoteChoice>,
        selected: Long?,
        onPick: (Long) -> Unit,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(choices, key = { it.id }) { choice ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == choice.id, onClick = { onPick(choice.id) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(choice.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        choice.folder?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Which doses a Traitements widget lists: every medication's, or one's.
     *
     * The names come from the vault, so a shut vault offers only « Tous » plus
     * the reason, and keeps a medication chosen earlier — a reconfiguration
     * with the app locked must not silently widen the widget to everything.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @androidx.compose.runtime.Composable
    private fun MedicationPicker(
        unlocked: Boolean,
        meds: List<uniffi.transition.Medication>,
        selected: Long?,
        onPick: (Long?) -> Unit,
    ) {
        Text(
            getString(R.string.meds_widget_target_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == null,
                onClick = { onPick(null) },
                label = { Text(getString(R.string.meds_widget_target_all)) },
            )
            meds.forEach { med ->
                FilterChip(
                    selected = selected == med.id,
                    onClick = { onPick(med.id) },
                    label = { Text(med.name, maxLines = 1) },
                )
            }
        }
        when {
            !unlocked -> Text(
                getString(R.string.meds_widget_target_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            meds.isEmpty() -> Text(
                getString(R.string.meds_widget_no_meds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * Every note the vault holds, as picker choices: the root's, then each
     * folder's, folders walked to [FOLDER_DEPTH] so the list is bounded even on
     * a vault whose folders nest.
     */
    private suspend fun allNotes(): List<NoteChoice> {
        val untitled = getString(R.string.notes_untitled)
        val out = mutableListOf<NoteChoice>()
        notes.list(null).forEach { out += NoteChoice(it.id, it.title.ifBlank { untitled }, null) }
        suspend fun walk(parentId: Long?, path: String?, depth: Int) {
            if (depth > FOLDER_DEPTH) return
            notes.folders(parentId).forEach { folder ->
                val here = if (path == null) folder.name else "$path / ${folder.name}"
                notes.list(folder.id).forEach {
                    out += NoteChoice(it.id, it.title.ifBlank { untitled }, here)
                }
                walk(folder.id, here, depth + 1)
            }
        }
        walk(null, null, 0)
        return out
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
     *
     * An opt-out erases this widget's rows at once, before anything else: the
     * consent is gone the moment the user says so, not at the next rebuild.
     */
    private fun save(
        configs: WidgetConfigPrefs,
        widgetId: Int,
        module: AppModule,
        config: WidgetConfigPrefs.Config,
    ) {
        runCatching { configs.put(widgetId, config) }
        if (!config.showsContent) {
            runCatching { WidgetContentMirror(this).remove(intArrayOf(widgetId)) }
        }
        lifecycleScope.launch {
            try {
                mirrors.refreshNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("WidgetConfig", "mirror rebuild failed", failure)
            }
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
        /** How deep the note picker follows nested folders. */
        const val FOLDER_DEPTH = 3

        /** Modules whose widget draws content from the opt-in mirror. */
        val MIRRORED = setOf(
            AppModule.Notes, AppModule.Journal, AppModule.Appointments,
            AppModule.Bleeding, AppModule.Photos, AppModule.Voice,
            AppModule.Dreams, AppModule.Sport,
        )

        /**
         * Off-vault modules whose widget can show the user's own labels, with
         * the same per-widget consent as mirrored content.
         */
        val LABEL_OPT_IN = setOf(AppModule.Labs)

        /** Modules whose widget draws a list, and so has a row count to pick. */
        val LISTING = setOf(
            AppModule.Meds, AppModule.Labs, AppModule.Notes, AppModule.Appointments,
        )
    }
}
