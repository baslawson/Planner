package com.example.itinerary.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.CalendarExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun rememberCalendarExporter(eventId: Long): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val item = (context.applicationContext as ItineraryApp).repository.eventDetails(eventId)?.first
                        ?: error("Event no longer exists")
                    val prefs = context.getSharedPreferences("calendar_export", Context.MODE_PRIVATE)
                    val namespace = prefs.getString("namespace", null) ?: UUID.randomUUID().toString().also {
                        check(prefs.edit().putString("namespace", it).commit())
                    }
                    val text = CalendarExport.encode(item, "$namespace-${item.id}@planner")
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                // Saved where the file picker said: no message. Only a failure says anything.
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't export this event. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }
    return {
        try { launcher.launch("Planner-event-$eventId.ics") }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No file picker is available", Toast.LENGTH_SHORT).show()
        }
    }
}
