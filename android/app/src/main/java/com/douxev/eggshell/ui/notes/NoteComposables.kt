package com.douxev.eggshell.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown

/**
 * Rendering shared between the real notes module and the decoy notes app.
 *
 * Only *rendering*. The two have completely separate storage on purpose: the
 * decoy keeps fabricated content in plain preferences and must never be able
 * to reach the vault, because the whole point of the decoy PIN is that it
 * opens something harmless. Sharing a repository here would turn the decoy
 * into a door into the real notes.
 *
 * Nothing in this file may mention Eggshell, vaults, encryption or medical
 * anything. It is compiled into a screen that claims to be an ordinary notes
 * app, and a stray string is a tell. Callers pass their own labels and colours.
 */

/** The minimum a note needs to be drawn. Neither side's storage type leaks in. */
data class NoteUi(
    val id: Long,
    val title: String,
    val body: String,
)

/**
 * Everything that must differ between the two skins.
 *
 * The decoy uses pastel sticky-note cards because that is what a plain notes
 * app looks like; the real module uses the app's own surfaces. Same layout,
 * same gestures, different paint.
 */
data class NoteSkin(
    val cardColor: (index: Int) -> Color,
    val cardContentColor: Color,
    val cardShape: Shape,
    val elevateWhileDragging: Boolean = true,
)

/** Labels, so no wording is baked into the shared layer. */
data class NoteLabels(
    val titlePlaceholder: String,
    val bodyPlaceholder: String,
)

/**
 * Resolves an inline image reference to decrypted bytes.
 *
 * The real module hands back plaintext from the vault; the decoy returns null
 * for everything, because it has no images and must not gain the ability to
 * read any. Suspending because decryption is real work, not a map lookup.
 */
fun interface NoteImageResolver {
    suspend fun resolve(reference: String): ByteArray?
}

/** A resolver that knows nothing — the decoy's. */
val NoImages = NoteImageResolver { null }

@Composable
fun NoteCardView(
    note: NoteUi,
    index: Int,
    skin: NoteSkin,
    elevated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = skin.cardShape,
        color = skin.cardColor(index),
        contentColor = skin.cardContentColor,
        tonalElevation = if (elevated && skin.elevateWhileDragging) 8.dp else 0.dp,
        shadowElevation = if (elevated && skin.elevateWhileDragging) 8.dp else 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W700),
                    maxLines = 2,
                )
            }
            // Deliberately the raw text, not rendered markdown: a preview is a
            // glance, and half-parsed formatting in a 8-line clamp reads worse
            // than the source does.
            Text(note.body, style = MaterialTheme.typography.bodySmall, maxLines = 8)
        }
    }
}

/**
 * The two text fields, unstyled beyond what the skin says.
 *
 * BasicTextField rather than OutlinedTextField because a notes app is a sheet
 * of paper — a boxed form control would look like a settings screen on both
 * skins.
 */
@Composable
fun NoteEditorFields(
    title: String,
    body: String,
    labels: NoteLabels,
    contentColor: Color,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicTextField(
            value = title,
            onValueChange = onTitle,
            textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.W700, color = contentColor),
            cursorBrush = SolidColor(contentColor),
            decorationBox = { inner ->
                if (title.isEmpty()) Text(
                    labels.titlePlaceholder,
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.W700),
                    color = contentColor.copy(alpha = 0.4f),
                )
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        BasicTextField(
            value = body,
            onValueChange = onBody,
            textStyle = TextStyle(fontSize = 16.sp, color = contentColor),
            cursorBrush = SolidColor(contentColor),
            decorationBox = { inner ->
                if (body.isEmpty()) Text(
                    labels.bodyPlaceholder,
                    style = TextStyle(fontSize = 16.sp),
                    color = contentColor.copy(alpha = 0.4f),
                )
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Rendered markdown.
 *
 * The renderer's image support is a separate artifact that pulls in a network
 * image loader; we do not take it. Note images are encrypted blobs on this
 * device and must never be fetched over a network, so image references are
 * stripped out before parsing and drawn separately by [InlineNoteImage].
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    Markdown(content = stripImageRefs(text), modifier = modifier.fillMaxWidth())
}

/** One decrypted image, or nothing if it could not be read. */
@Composable
fun InlineNoteImage(
    reference: String,
    resolver: NoteImageResolver,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(reference) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(reference) { bytes = runCatching { resolver.resolve(reference) }.getOrNull() }
    val bitmap = remember(bytes) {
        bytes?.let {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    // A missing image degrades to nothing at all rather than to broken markup:
    // the note stays readable, which is the point of keeping images out of the
    // body text in the first place.
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

const val IMAGE_SCHEME = "eggshell-note-image"

/** `![alt](eggshell-note-image:12)` — how a note points at one of its images. */
private val IMAGE_REF = Regex("""!\[[^]]*]\($IMAGE_SCHEME:(\d+)\)""")

/** Every image reference in the body, in order of appearance. */
fun imageRefsIn(body: String): List<Long> =
    IMAGE_REF.findAll(body).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()

fun imageRefFor(imageId: Long): String = "![]($IMAGE_SCHEME:$imageId)"

private fun stripImageRefs(body: String): String = IMAGE_REF.replace(body, "")
