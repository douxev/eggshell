package com.douxev.eggshell.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.theme.EggShapes

/**
 * The privacy inset of the refonte: a quiet strip, not a card, that states one
 * fact about where the data actually lives. Deliberately flatter than an
 * [com.douxev.eggshell.ui.components.EggCard] so it reads as a footnote to the
 * block above it rather than as another piece of content.
 */
@Composable
fun PrivacyNote(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Lock,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, EggShapes.Note)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The inset that frames the security-mode picker. Spells out that the mode
 * choice is about *how the user unlocks*, not whether their data is encrypted
 * (it always is). Avoids the common misread "the no-code mode means my data
 * isn't protected".
 */
@Composable
fun EncryptionNoteCard(modifier: Modifier = Modifier) {
    PrivacyNote(
        text = stringResource(R.string.security_mode_encryption_note),
        modifier = modifier,
    )
}
