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

    override fun onCreate() {
        super.onCreate()
        // The widget receiver's PackageManager-enabled state lives outside
        // the app lifecycle, so a previous run that disabled it (for the
        // decoy gate) can leave it disabled even after the user cleared
        // the decoy PIN through some other path (reset, reinstall, etc.).
        // Sync it to the current decoy state on every cold start so the
        // launcher never thinks a freshly-installed app's widget is
        // permanently unavailable.
        runCatching { widgetVisibility.setEnabled(!decoy.hasDecoyPin) }
    }
}
