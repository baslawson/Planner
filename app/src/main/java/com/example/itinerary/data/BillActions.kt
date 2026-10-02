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

/**
 * The payments that can still be undone (Repository): for [UNDO_MS], from the snackbar and from a notification's Undo
 * alike. A newer payment of the same bill replaces the older one's Undo.
 */
class PaymentUndos(private val windowMs: Long = UNDO_MS) {
    private val entries = linkedMapOf<String, Pair<PendingPayment, Long>>()

    fun record(change: PendingPayment, now: Long) {
        entries.values.removeAll { it.first.before.id == change.before.id || it.second < now }
        entries[change.token] = change to now + windowMs
    }

    /** The payment [token] undoes while its Undo lasts, else null; either way it can't be undone again. */
    fun take(token: String, now: Long): PendingPayment? = entries.remove(token)?.takeIf { it.second >= now }?.first

    /**
     * R-4: [queued] without the payments whose Undo ran out or was used. A payment from a notification is queued with no
     * screen open; left there, its stale bar would show hours later and hold back every later one behind it.
     */
    fun live(queued: List<PendingPayment>, now: Long): List<PendingPayment> =
        queued.filter { p -> entries[p.token]?.let { it.second >= now } == true }

    fun clear() = entries.clear()

    companion object { const val UNDO_MS = 60_000L }
}

fun billOverdue(date: LocalDate, paid: Boolean, skipped: Boolean, today: LocalDate): Boolean =
    !paid && !skipped && date < today

/** Series identity, not similar titles, determines which saved occurrences belong together. */
fun billHistory(items: List<ItineraryItem>, id: Long): List<ItineraryItem> {
    val current = items.find { it.id == id && it.category == "Bills" } ?: return emptyList()
    return items.filter { it.category == "Bills" && (it.id == id || current.seriesId != null && it.seriesId == current.seriesId) }
        .sortedWith(compareByDescending<ItineraryItem> { it.date }.thenByDescending { it.id })
}
