package com.douxev.eggshell

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.PhotosRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.data.VoiceRepository
import com.douxev.eggshell.data.WhatsNewPrefs
import com.douxev.eggshell.ui.home.HomeNavHost
import com.douxev.eggshell.ui.onboarding.OnboardingScreen
import com.douxev.eggshell.ui.theme.EggshellTheme
import com.douxev.eggshell.ui.unlock.UnlockScreen
import com.douxev.eggshell.ui.whatsnew.WhatsNewCatalog
import com.douxev.eggshell.ui.whatsnew.WhatsNewSheet
import com.douxev.eggshell.widget.EggshellWidgetProvider

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val rootViewModel: AppRootViewModel by viewModels()
    @Inject lateinit var securityPrefs: SecurityPrefs
    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var photos: com.douxev.eggshell.data.PhotosRepository
    @Inject lateinit var voice: com.douxev.eggshell.data.VoiceRepository
    @Inject lateinit var pdfExports: com.douxev.eggshell.data.PdfReportExporter

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // When the WHOLE app goes background (Recents, locked screen,
            // other app foregrounded), purge plaintext cache files so a
            // background-process leak / forensic snapshot can't recover
            // decrypted photos/voice. Also lock Paranoid-mode vaults so
            // their master key (the one we re-derive from the passphrase
            // each cold start) is wiped from RAM.
            //
            // A generated doctor report is the same class of file: cleartext,
            // and holding hormone values, punctuality, bleeding episodes and
            // whatever photos the user chose to include.
            runCatching { photos.purgeAllCache() }
            runCatching { voice.purgeAllCache() }
            runCatching { pdfExports.purgeExports() }
            if (vault.currentMode == com.douxev.eggshell.security.VaultPrefs.Mode.PARANOID) {
                vault.lock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        // FLAG_SECURE: default ON via SecurityPrefs. We additionally force
        // it on while the user is on Unlock or Onboarding regardless of the
        // toggle, since those screens accept passphrases / PINs and
        // shouldn't be screenshot-friendly even for power users who turned
        // it off for the rest of the app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    securityPrefs.blockScreenshots,
                    rootViewModel.route,
                ) { block, route ->
                    val forceSecure = route != AppRootViewModel.Route.Home
                    block || forceSecure
                }.collect { secure ->
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        setContent {
            EggshellTheme {
                AppRoot(rootVm = rootViewModel)
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    /**
     * Reads the deep-link extra fired by the home-screen widget. We hand the
     * destination to the AppRootViewModel which holds it until the vault is
     * unlocked, then HomeNavHost picks it up to navigate.
     *
     * Deep links are only honoured when the originating intent came from
     * our own widget. An external app sending the same EXTRA_DEEPLINK
     * with a malicious string would otherwise be able to drop the user on
     * an arbitrary in-app screen straight after unlock (UI-redress risk).
     */
    private fun consumeDeepLink(intent: Intent?) {
        intent ?: return
        // Require the widget's own action — exactly the same action
        // EggshellWidgetProvider sets on its PendingIntent. Any other
        // launch path (icon tap, launcher recents, third-party intent)
        // ignores deep-link extras even if they're present.
        if (intent.action != EggshellWidgetProvider.ACTION_JOURNAL_QUICK_ADD) {
            intent.removeExtra(EggshellWidgetProvider.EXTRA_DEEPLINK)
            return
        }
        val link = intent.getStringExtra(EggshellWidgetProvider.EXTRA_DEEPLINK) ?: return
        when (link) {
            EggshellWidgetProvider.DEEPLINK_JOURNAL_ADD ->
                rootViewModel.requestDeepLink(AppRootViewModel.DeepLink.JournalAdd)
        }
        // Clear so a configuration change doesn't re-fire the deep link.
        intent.removeExtra(EggshellWidgetProvider.EXTRA_DEEPLINK)
    }
}

@Composable
fun AppRoot(rootVm: AppRootViewModel = hiltViewModel()) {
    val route by rootVm.route.collectAsState()
    when (route) {
        AppRootViewModel.Route.Onboarding ->
            OnboardingScreen(onComplete = { rootVm.refresh() })
        AppRootViewModel.Route.Unlock ->
            UnlockScreen(onUnlocked = { rootVm.refresh() })
        AppRootViewModel.Route.Home -> {
            HomeNavHost(deepLinkProvider = rootVm)
            // What's-new overlay only on Home: don't pop a sheet on top of
            // the unlock screen or the onboarding flow.
            val showWhatsNew by rootVm.showWhatsNew.collectAsState()
            if (showWhatsNew) {
                WhatsNewSheet(
                    release = WhatsNewCatalog.LATEST,
                    onDismiss = { rootVm.dismissWhatsNew() },
                )
            }
        }
    }
}

@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val repo: VaultRepository,
    private val schedules: ScheduleRepository,
    private val appointments: com.douxev.eggshell.data.AppointmentRepository,
    private val whatsNew: WhatsNewPrefs,
    private val photos: PhotosRepository,
    private val voice: VoiceRepository,
) : ViewModel() {

    enum class Route { Onboarding, Unlock, Home }

    /** Where the widget (or future deep links) ask the app to land. */
    enum class DeepLink { JournalAdd }

    private val _route = MutableStateFlow(initialRoute())
    val route: StateFlow<Route> = _route.asStateFlow()

    /** Pending deep-link target. Consumed once HomeNavHost mounts. */
    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink.asStateFlow()

    /** True iff the current versionCode is higher than the last seen one,
     *  i.e. the user just upgraded and hasn't seen the changelog yet. */
    private val _showWhatsNew = MutableStateFlow(
        whatsNew.shouldShow(BuildConfig.VERSION_CODE)
    )
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    fun dismissWhatsNew() {
        whatsNew.markSeen(BuildConfig.VERSION_CODE)
        _showWhatsNew.value = false
    }

    fun refresh() {
        viewModelScope.launch {
            val r = initialRoute()
            _route.value = r
            if (r == Route.Home) {
                // Drain any "Pris" taps that were queued while locked into the
                // now-open vault BEFORE reconciling, so the advanced schedules
                // are what syncFromDb sees.
                runCatching { schedules.flushPendingDoses() }
                runCatching { schedules.syncFromDb() }
                // Re-arm appointment reminders dropped by a reboot (their fire
                // time lives in the vault, which BootReceiver can't read).
                runCatching { appointments.reschedulePending() }
                // Move legacy voice-clip metadata from plain prefs into the
                // encrypted vault on first unlock after upgrade. No-op if
                // there's nothing to migrate.
                runCatching { voice.migrateLegacyMetadataIfNeeded() }
                // Sweep any media files whose DB rows disappeared (mid-write
                // crash, race delete). Keeps on-disk encrypted blobs in sync
                // with what the user can actually see in the app.
                runCatching { photos.cleanupOrphans() }
                runCatching { voice.cleanupOrphans() }
            }
        }
    }

    fun requestDeepLink(link: DeepLink) {
        _pendingDeepLink.value = link
    }

    fun consumeDeepLink() {
        _pendingDeepLink.value = null
    }

    private fun initialRoute(): Route = when {
        !repo.isInitialized -> Route.Onboarding
        !repo.isUnlocked -> Route.Unlock
        else -> Route.Home
    }
}
