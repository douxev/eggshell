package com.douxev.eggshell.ui.health

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.theme.EggshellTheme
import com.douxev.eggshell.ui.theme.enableEdgeToEdgeCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * What the app reads from Health Connect, why, and where it goes.
 *
 * Health Connect requires this screen and opens it itself: from its permission
 * dialog through `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13
 * and older), and from its app settings through the `VIEW_PERMISSION_USAGE`
 * alias declared next to it in the manifest (Android 14 and newer). The Sport
 * settings card links here too.
 *
 * **Its own activity, never MainActivity.** MainActivity is disabled while the
 * icon wears a disguise — an alias takes its place in the launcher — and a
 * Health Connect link pointing at a disabled component would open nothing, for
 * exactly the people who chose the disguise.
 *
 * **Static, and nothing from the vault.** It is exported, because Health
 * Connect is another app, so anything able to start it sees what it shows:
 * the same fixed text for everyone, no lock needed and none bypassed. It does
 * not name the app either — the same disguise would be undone by a title.
 * Screenshots follow the app-wide setting, like every other sensitive screen.
 */
@AndroidEntryPoint
class HealthConnectRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runCatching { SecurityPrefs(this).blockScreenshots.value }.getOrDefault(true)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdgeCompat()
        setContent {
            EggshellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    RationaleContent(
                        onPolicy = { openPolicy(this, getString(R.string.health_rationale_policy_url)) },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        /** For the Sport settings card's « Ce qui est lu, et pourquoi ». */
        fun intent(context: Context): Intent =
            Intent(context, HealthConnectRationaleActivity::class.java)
    }
}

@Composable
private fun RationaleContent(onPolicy: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.health_rationale_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.health_rationale_lead),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section(R.string.health_rationale_what_title, R.string.health_rationale_what_body)
        Section(R.string.health_rationale_why_title, R.string.health_rationale_why_body)
        Section(R.string.health_rationale_where_title, R.string.health_rationale_where_body)
        Section(R.string.health_rationale_readonly_title, R.string.health_rationale_readonly_body)
        Section(R.string.health_rationale_stop_title, R.string.health_rationale_stop_body)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
        ) {
            TextButton(onClick = onPolicy) { Text(stringResource(R.string.health_rationale_policy)) }
            Button(onClick = onClose) { Text(stringResource(R.string.health_rationale_close)) }
        }
    }
}

@Composable
private fun Section(titleRes: Int, bodyRes: Int) {
    EggCard(variant = CardVariant.Low, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The privacy policy, in the browser. Through the application context and in a
 * new task, like the app's other outbound links: leaving for a browser is a
 * departure, and must not look like the app's own navigation.
 */
private fun openPolicy(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // A phone with no browser at all is unusual, not impossible.
    runCatching { context.applicationContext.startActivity(intent) }
}
