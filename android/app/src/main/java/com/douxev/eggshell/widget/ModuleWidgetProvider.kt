package com.douxev.eggshell.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.modules.ModuleDeepLink

/**
 * A home-screen widget per module: the module's name, its icon, and one or two
 * ways into it.
 *
 * **It renders no data, by construction.** Every module's content lives in the
 * encrypted vault, which is shut whenever the widget draws — a widget is
 * rendered by the launcher's process on its own schedule, long after the app
 * has locked. The only reason [EggshellWidgetProvider] can show anything at all
 * is the deliberate plaintext reminder mirror, which exists precisely so that
 * *timings* can be surfaced without the vault. Building equivalent mirrors for
 * notes, journal entries or photos would mean writing exactly the content this
 * app encrypts into a file any forensic reader can open — the widget would be
 * bought at the price of the thing being widgeted.
 *
 * So these are doors, not dashboards: tap the card to open the module, tap
 * « + » to land on its capture screen. That is a real saving of two taps, and
 * it costs nothing in plaintext.
 *
 * **Decoy.** The receivers are disabled wholesale by [WidgetVisibility] while a
 * decoy PIN is set, which removes them from the launcher's widget picker. This
 * class re-checks anyway before drawing: a widget already placed on the home
 * screen keeps its last-rendered `RemoteViews` until something replaces them,
 * and a component disabled *after* placement does not repaint on its own. The
 * check below is what turns a stale card reading « Menstruations » into a blank
 * one on the next update the launcher asks for.
 */
abstract class ModuleWidgetProvider(private val module: AppModule) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            runCatching { render(context, appWidgetManager, id) }
                .onFailure { android.util.Log.e(TAG, "render failed for $module widget $id", it) }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_module)
        val decoyed = runCatching { isDecoyActive(context) }.getOrDefault(false)

        if (decoyed) {
            // Say nothing, and offer nothing to tap. Naming the module here —
            // even to explain the blank — would be the leak.
            views.setTextViewText(R.id.module_widget_title, "")
            views.setTextViewText(R.id.module_widget_subtitle, "")
            views.setViewVisibility(R.id.module_widget_icon, View.GONE)
            views.setViewVisibility(R.id.module_widget_action_add, View.GONE)
            manager.updateAppWidget(widgetId, views)
            return
        }

        views.setTextViewText(R.id.module_widget_title, context.getString(module.labelRes))
        views.setTextViewText(
            R.id.module_widget_subtitle,
            context.getString(R.string.module_widget_subtitle),
        )
        views.setImageViewResource(R.id.module_widget_icon, module.iconRes)
        views.setViewVisibility(R.id.module_widget_icon, View.VISIBLE)

        // The card body opens the module. The « + » gets its own PendingIntent
        // and the root gets none: several launchers collapse child taps into
        // the parent's intent when the parent has one, which would make « + »
        // silently behave like "open".
        views.setOnClickPendingIntent(
            R.id.module_widget_body,
            pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
        )

        if (module.hasCaptureScreen) {
            views.setViewVisibility(R.id.module_widget_action_add, View.VISIBLE)
            views.setTextViewText(
                R.id.module_widget_action_add,
                context.getString(R.string.module_widget_add),
            )
            views.setOnClickPendingIntent(
                R.id.module_widget_action_add,
                pendingIntent(context, ModuleDeepLink.addIntent(context, module), REQ_ADD),
            )
        } else {
            views.setViewVisibility(R.id.module_widget_action_add, View.GONE)
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun pendingIntent(context: Context, intent: android.content.Intent, req: Int) =
        PendingIntent.getActivity(
            context,
            // The request code must differ per module as well as per action:
            // PendingIntents that match on (requestCode, filterEquals) are the
            // same object, and Intent equality ignores extras — so every module
            // sharing code 0 would hand every widget the first one's target.
            req + module.ordinal * REQ_STRIDE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Reads VaultPrefs through [SecurePrefs] rather than by literal name:
     * release builds store that file under a hashed name, and reading the
     * plain name always came back empty — which is how a previous version of
     * this check concluded "no decoy" on every install that had one.
     */
    private fun isDecoyActive(context: Context): Boolean =
        SecurePrefs.get(context.applicationContext, "transition_vault_prefs")
            .contains("decoy_salt")

    private companion object {
        const val TAG = "ModuleWidget"
        const val REQ_OPEN = 0
        const val REQ_ADD = 1
        const val REQ_STRIDE = 10
    }
}

/** Modules whose « + » has a screen of its own to land on. */
private val AppModule.hasCaptureScreen: Boolean
    get() = when (this) {
        // Photos and Voix capture from inside their own screen and Poids opens
        // a dialog over its, so none of the three has a route to push.
        AppModule.Photos, AppModule.Voice, AppModule.Weight -> false
        else -> true
    }

// One concrete receiver per module. They exist because AppWidgetProvider is
// bound to a manifest <receiver>, and a receiver names exactly one class — a
// single parameterised provider cannot be pointed at nine widget types.
class MedsWidgetProvider : ModuleWidgetProvider(AppModule.Meds)
class JournalWidgetProvider : ModuleWidgetProvider(AppModule.Journal)
class LabsWidgetProvider : ModuleWidgetProvider(AppModule.Labs)
class AppointmentsWidgetProvider : ModuleWidgetProvider(AppModule.Appointments)
class NotesWidgetProvider : ModuleWidgetProvider(AppModule.Notes)
class WeightWidgetProvider : ModuleWidgetProvider(AppModule.Weight)
class BleedingWidgetProvider : ModuleWidgetProvider(AppModule.Bleeding)
class PhotosWidgetProvider : ModuleWidgetProvider(AppModule.Photos)
class VoiceWidgetProvider : ModuleWidgetProvider(AppModule.Voice)

/**
 * Every module widget, as a fresh provider instance paired with its component.
 *
 * Instances rather than class names: [WidgetVisibility] needs both the
 * `ComponentName` to toggle and a live provider to push a final render through,
 * and building the second by reflection would depend on R8 keeping a
 * constructor nothing in Kotlin visibly calls. Constructing them directly is
 * also what [EggshellWidgetProvider.broadcastRefresh] already does.
 */
internal fun moduleWidgets(context: Context): List<Pair<ComponentName, ModuleWidgetProvider>> =
    listOf(
        MedsWidgetProvider(),
        JournalWidgetProvider(),
        LabsWidgetProvider(),
        AppointmentsWidgetProvider(),
        NotesWidgetProvider(),
        WeightWidgetProvider(),
        BleedingWidgetProvider(),
        PhotosWidgetProvider(),
        VoiceWidgetProvider(),
    ).map { ComponentName(context, it.javaClass) to it }
