package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.*

class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val trigger = intent.getLongExtra("trigger", 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try { (context.applicationContext as ItineraryApp).repository.actOnTaskReminder(id, trigger) }
            catch (e: Exception) {
                android.util.Log.w("TaskActionReceiver", "Couldn't complete task", e)
                android.widget.Toast.makeText(context, e.message ?: "Couldn't complete task. Open Planner to review it.", android.widget.Toast.LENGTH_LONG).show()
            }
            finally { pending.finish() }
        }
    }
    companion object {
        fun done(context: Context, id: String, trigger: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, TaskActionReceiver::class.java)
                .setData(Uri.Builder().scheme("planner").authority("task-done").appendPath(id).appendQueryParameter("trigger", trigger.toString()).build())
                .putExtra("trigger", trigger), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
