package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.itinerary.data.Links

@Composable
fun BillPaymentDetails(link: String, reference: String, biller: String, bpayReference: String,
                       onLink: (String) -> Unit, onReference: (String) -> Unit,
                       onBiller: (String) -> Unit, onBpayReference: (String) -> Unit) {
    val context = LocalContext.current
    HeadingText("Payment details (optional)", style = MaterialTheme.typography.titleMedium)
    @Composable fun field(label: String, value: String, limit: Int, change: (String) -> Unit) {
        OutlinedTextField(value, onValueChange = { if (it.length <= limit) change(it.replace('\n', ' ')) },
            label = { Text(label) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), modifier = Modifier.fillMaxWidth())
        if (value.isNotBlank()) TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        }) { Text("Copy $label") }
    }
    field("Payment link", link, 2000, onLink)
    if (link.isNotBlank()) {
        val url = Links.normalize(link)
        if (url == null) Text("Enter a valid web address to open this link.", color = MaterialTheme.colorScheme.error)
        TextButton(enabled = url != null, onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, url!!.toUri())) }
            catch (_: Exception) { Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show() }
        }) { Text("Open payment link") }
    }
    field("Reference", reference, 500, onReference)
    field("BPAY biller code", biller, 100, onBiller)
    field("BPAY reference", bpayReference, 500, onBpayReference)
}
