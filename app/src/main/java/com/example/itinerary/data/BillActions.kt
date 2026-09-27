package com.example.itinerary.data

import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** A stale notification must never pay a changed/replaced event. */
fun billReminderToken(item: ItineraryItem, reminder: Reminder): String =
    MessageDigest.getInstance("SHA-256").digest("$item|$reminder".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Schedule identity excludes title/notes so unrelated edits keep an active Snooze usable. */
fun eventReminderToken(item: ItineraryItem, reminder: Reminder): String =
    MessageDigest.getInstance("SHA-256").digest(
        "${item.id}|${item.date}|${item.startTime}|${item.paid}|${item.skipped}|$reminder".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class PendingPayment(
    val token: String = UUID.randomUUID().toString(),
    val before: ItineraryItem,
    val paid: Boolean,
    val remindersBefore: List<Reminder>,
    val remindersAfter: List<Reminder>,
    val paymentsAfter: List<BillPayment> = emptyList(),
)

fun billOverdue(date: LocalDate, paid: Boolean, skipped: Boolean, today: LocalDate): Boolean =
    !paid && !skipped && date < today

/** Series identity, not similar titles, determines which saved occurrences belong together. */
fun billHistory(items: List<ItineraryItem>, id: Long): List<ItineraryItem> {
    val current = items.find { it.id == id && it.category == "Bills" } ?: return emptyList()
    return items.filter { it.category == "Bills" && (it.id == id || current.seriesId != null && it.seriesId == current.seriesId) }
        .sortedWith(compareByDescending<ItineraryItem> { it.date }.thenByDescending { it.id })
}
