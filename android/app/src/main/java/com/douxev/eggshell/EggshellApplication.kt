package com.douxev.eggshell

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.widget.WidgetVisibility

@HiltAndroidApp
class EggshellApplication : Application() {

    @Inject lateinit var decoy: DecoyVerifier
    @Inject lateinit var widgetVisibility: WidgetVisibility
    @Inject lateinit var moduleShortcuts: com.douxev.eggshell.modules.ModuleShortcuts

    override fun onCreate() {
        super.onCreate()
        val decoyActive = decoy.hasDecoyPin
        // The widget receivers' PackageManager-enabled state lives outside
        // the app lifecycle, so a previous run that disabled them (for the
        // decoy gate) can leave them disabled even after the user cleared
        // the decoy PIN through some other path (reset, reinstall, etc.).
        // Sync to the current decoy state on every cold start so the
        // launcher never thinks a freshly-installed app's widget is
        // permanently unavailable.
        runCatching { widgetVisibility.setEnabled(!decoyActive) }
        // Shortcuts are resynced here for a second reason as well: their set
        // depends on which modules are enabled, and a module can be switched
        // off in Réglages. Rebuilding on every start means the launcher can
        // never keep advertising a module the user has since hidden.
        runCatching { moduleShortcuts.refresh(decoyActive) }
    }
}
