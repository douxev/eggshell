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
import com.douxev.eggshell.ui.recovery.RecoverySetupScreen
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
    @Inject lateinit var decoyPresence: com.douxev.eggshell.data.DecoyPresence

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
            runCatching {
                com.douxev.eggshell.data.lab.LabResultOcrService
                    .purgeDecryptedCache(applicationContext)
            }
            if (vault.currentMode == com.douxev.eggshell.security.VaultPrefs.Mode.PARANOID) {
                vault.lock()
            }
        }
    }

    /**
     * Set while a departure is *our* doing rather than the user's.
     *
     * `onUserLeaveHint` alone cannot tell the two apart: a plain
     * `startActivity` from inside the app also counts as a user leave unless
     * the caller sets `FLAG_ACTIVITY_NO_USER_ACTION`, which the AndroidX
     * ActivityResult launchers do not. A first attempt at background locking
     * relied on that distinction and locked the vault whenever the photo
     * picker, the camera or a permission dialog opened — and because the route
     * had already been computed as Home, the app kept drawing Home against a
     * null session, rendering every list empty. It presented as total data
     * loss. This flag is the missing discriminator.
     *
     * Every AndroidX launcher funnels through `startActivityForResult`, so the
     * two overrides below catch all 25 call sites without touching any of them.
     */
    private var leavingForOwnActivity = false

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        leavingForOwnActivity = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        leavingForOwnActivity = true
        super.startActivity(intent, options)
    }

    /**
     * The user deliberately left — Home, Recents, another app. Lock in every
     * mode: with only PARANOID re-locking, the decoy was walked straight past
     * by pressing Home twice and reopening.
     *
     * Consumed one-shot rather than merely read, so a launch that never
     * actually happens cannot leave the flag stuck and silently disable
     * locking for the rest of the session.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val ours = leavingForOwnActivity
        leavingForOwnActivity = false
        if (!ours) vault.lock()
    }

    override fun onResume() {
        super.onResume()
        // Back on screen: whatever we launched is done with.
        leavingForOwnActivity = false
    }

    /**
     * Coming back: the route was computed while the vault was still open, so
     * without this the app would redraw the screen the user left instead of
     * the lock screen.
     */
    override fun onStart() {
        super.onStart()
        rootViewModel.syncLockState()
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
                    decoyPresence.onScreen,
                ) { block, route, decoyOnScreen ->
                    // Onboarding and Unlock accept PINs and passphrases, so
                    // they are always secure. Home follows the user's setting —
                    // and that deliberately includes the decoy, which renders
                    // inside the Unlock route: a plain notes app does not refuse
                    // screenshots or show a blank card in Recents, so forcing
                    // the flag there is itself the tell it was meant to prevent.
                    val forceSecure = route == AppRootViewModel.Route.Onboarding ||
                        (route == AppRootViewModel.Route.Unlock && !decoyOnScreen)
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
        AppRootViewModel.Route.RecoverySetup ->
            RecoverySetupScreen(
                onDone = { rootVm.refresh() },
                onGiveUp = { rootVm.dismissRecoveryGate() },
            )
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

    enum class Route { Onboarding, Unlock, RecoverySetup, Home }

    /** Where the widget (or future deep links) ask the app to land. */
    enum class DeepLink { JournalAdd }

    private val _route = MutableStateFlow(initialRoute())
    val route: StateFlow<Route> = _route.asStateFlow()

    init {
        // Leave Home the moment the vault closes, whoever closed it. The
        // onStart poll below is a second line of defence, not the mechanism:
        // a session can be dropped while the activity stays started, and that
        // used to leave the app rendering Home with every list empty.
        viewModelScope.launch {
            repo.unlocked.collect { open ->
                if (!open && _route.value == Route.Home) _route.value = Route.Unlock
            }
        }
    }

    /** Pending deep-link target. Consumed once HomeNavHost mounts. */
    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink.asStateFlow()

    /**
     * True iff there is release copy the user has not read yet.
     *
     * Gated on the **catalogue's** version, not on `BuildConfig.VERSION_CODE`:
     * the sheet is about what changed, and a patch release that ships no new
     * copy must not re-open the previous release's notes at someone who has
     * already dismissed them.
     */
    private val _showWhatsNew = MutableStateFlow(
        whatsNew.shouldShow(WhatsNewCatalog.LATEST.versionCode)
    )
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    fun dismissWhatsNew() {
        whatsNew.markSeen(WhatsNewCatalog.LATEST.versionCode)
        _showWhatsNew.value = false
    }

    /**
     * Recompute the route after a background lock, without the Home warm-up
     * work that [refresh] does. Only ever moves Home -> Unlock here: the
     * reverse transition goes through a real unlock, which calls refresh().
     */
    /**
     * Let someone past the recovery gate who cannot complete it — a broken
     * sensor, a Keystore key already dead. For this session only: the flag is
     * not persisted, so the gate returns on the next launch and keeps asking
     * as long as the vault has no second way in.
     */
    private var recoveryGateDismissed = false

    fun dismissRecoveryGate() {
        recoveryGateDismissed = true
        refresh()
    }

    fun syncLockState() {
        val r = initialRoute()
        if (r != _route.value) _route.value = r
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
        // Sits between Unlock and Home on purpose: someone in biometric mode
        // with no second wrap is one fingerprint enrollment away from losing
        // the vault outright, and that is not a state to leave a user parked
        // in behind a dismissible banner they will tap away.
        repo.needsRecoverySetup && !recoveryGateDismissed -> Route.RecoverySetup
        else -> Route.Home
    }
}
