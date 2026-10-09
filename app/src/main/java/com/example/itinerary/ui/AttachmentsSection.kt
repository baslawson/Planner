package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentsSection(
    attachments: List<Attachment>,
    store: AttachmentStore,
    onTakePhoto: () -> Unit,
    onAttachFile: () -> Unit,
    onAddLink: () -> Unit,
    onScanDocument: () -> Unit,
    onRemove: (Attachment) -> Unit,
    onOpen: (Attachment) -> Unit,
    readingText: Boolean = false,
    onReadText: (Attachment) -> Unit = {},
    onViewText: (Attachment) -> Unit = {},
    // Null hides "Suggest bill details", e.g. in a task's time block, which can't be a bill.
    onSuggestBill: ((Attachment) -> Unit)? = {},
    // False when whatever holds it draws the divider and heading (the event editor's FoldSection).
    heading: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (heading) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HeadingText(
                "Attachments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        attachments.forEach { attachment ->
            AttachmentRow(attachment, store, enabled = !readingText, onOpen = { onOpen(attachment) }, onRemove = { onRemove(attachment) })
            if (com.example.itinerary.scanner.DocumentText.supports(attachment)) {
                Text(when (attachment.textStatus) {
                    "READY" -> "Text searchable after Save"
                    "EMPTY" -> "No readable text found"
                    "PARTIAL" -> "Partly searchable · first 50 pages or 200,000 characters"
                    "FAILED" -> "Couldn't read the text. You can retry."
                    else -> "Document text hasn't been read yet"
                }, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.example.itinerary.ui.MatrixTextButton(enabled = !readingText, onClick = { onReadText(attachment) }) { Text(if (attachment.textStatus == "NOT_INDEXED") "Read text" else "Read text again") }
                    if (attachment.recognizedText.isNotBlank()) com.example.itinerary.ui.MatrixTextButton(onClick = { onViewText(attachment) }) { Text("View text") }
                    if (attachment.recognizedText.isNotBlank() && onSuggestBill != null) com.example.itinerary.ui.MatrixTextButton(enabled = !readingText, onClick = { onSuggestBill(attachment) }) { Text("Suggest bill details") }
                }
            }
        }
        // Wraps onto further lines if the four buttons don't fit side by side.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(enabled = !readingText, onClick = onTakePhoto) { Text("Take photo") }
            OutlinedButton(enabled = !readingText, onClick = onScanDocument) { Text("Scan document") }
            OutlinedButton(enabled = !readingText, onClick = onAttachFile) { Text("Attach file") }
            OutlinedButton(enabled = !readingText, onClick = onAddLink) { Text("Add link") }
        }
    }
}

// One attachment: its preview (a photo, a PDF's first page, else its kind), its name (and a link's address), and a
// remove button. Shared by the event, task and note editors (wish list #4).
@Composable
fun AttachmentRow(attachment: Attachment, store: AttachmentStore, enabled: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    // The remove button already sits at the end of the row, so no arrow here.
    // Not opened while the editor is busy, as before the shared row (ED-13).
    TappableRow(onClick = { if (enabled) onOpen() }, modifier = Modifier.fillMaxWidth(), arrow = false) {
        AttachmentThumb(attachment, store)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(
                attachment.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // A link also shows where it goes.
            attachment.url?.let { url ->
                Text(
                    url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(enabled = enabled, onClick = onRemove, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove ${attachment.name}")
        }
    }
}

@Composable
private fun AttachmentThumb(attachment: Attachment, store: AttachmentStore) {
    val bitmap by produceState<ImageBitmap?>(null, attachment.fileName) {
        value = if (attachment.url == null && (attachment.mimeType.startsWith("image/") || attachment.mimeType == "application/pdf")) {
            withContext(Dispatchers.IO) { store.thumbnail(attachment.fileName, 160)?.asImageBitmap() }
        } else {
            null
        }
    }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (attachment.url != null) {
            Text("LINK", style = MaterialTheme.typography.labelSmall)
        } else {
            val ext = attachment.name.substringAfterLast('.', "").uppercase().take(4)
            Text(ext.ifEmpty { kindFromMime(attachment.mimeType) }, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// What the tile says when the attachment has no usable extension in its name. A picked file keeps its original name,
// so its extension is the better label; a scan is stored under a bare UUID, so its kind has to come from the mime
// type. Anything unrecognised stays "FILE" rather than showing a nonsense stub like "OCTE" or "VND.".
private fun kindFromMime(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
    "application/pdf" -> "PDF"
    "image/jpeg", "image/jpg" -> "JPG"
    "image/png" -> "PNG"
    "image/webp" -> "WEBP"
    "image/gif" -> "GIF"
    "image/heic", "image/heif" -> "HEIC"
    "text/plain" -> "TXT"
    else -> "FILE"
}

// Hands the file (or link) to whichever installed app can show it.
fun openAttachment(context: Context, store: AttachmentStore, attachment: Attachment) {
    val link = attachment.url
    if (link != null) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(store.uriFor(attachment.fileName), attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}
