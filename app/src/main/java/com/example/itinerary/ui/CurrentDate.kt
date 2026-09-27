package com.example.itinerary.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZonedDateTime
import com.example.itinerary.data.millisUntilNextDay

// Refresh at midnight while visible, on resume, and when Android's date/time/time zone changes.
@Composable
fun rememberCurrentDate(nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() }): LocalDate {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var today by remember { mutableStateOf(nowProvider().toLocalDate()) }
    var clockChange by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                today = nowProvider().toLocalDate()
                clockChange++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, clockChange) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = nowProvider()
                today = now.toLocalDate()
                delay(millisUntilNextDay(now))
            }
        }
    }
    return today
}

@Composable
fun rememberCurrentZoneId(): java.time.ZoneId {
    val context = LocalContext.current
    var zone by remember { mutableStateOf(java.time.ZoneId.systemDefault()) }
    androidx.lifecycle.compose.LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { zone = java.time.ZoneId.systemDefault() }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { zone = java.time.ZoneId.systemDefault() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone
}
