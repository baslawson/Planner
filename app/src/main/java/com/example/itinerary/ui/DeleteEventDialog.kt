package com.example.itinerary.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// A plain event goes at once (Undo bar); a bill (its payment history goes with it) or a repeating entry asks first.
fun deleteAsks(bill: Boolean, repeating: Boolean) = bill || repeating

// Deleting from the event editor or from a card's ⋮. [onDelete] gets true for the entire series.
@Composable
fun DeleteEventDialog(bill: Boolean, repeating: Boolean, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    PlannerDialog(if (bill) { if (!repeating) "Delete bill?" else "Delete repeating bill?" } else "Delete repeating event?",
        onDismissRequest = onDismiss,
        primary = DialogAction(if (!repeating) "Delete bill" else if (bill) "This bill" else "This event", danger = true) { onDelete(false) },
        dismiss = DialogAction("Cancel", onClick = onDismiss),
        // The whole series is the bigger step, so it is the quieter (red text) button beside Cancel.
        extra = if (repeating) listOf(DialogAction("Entire series", danger = true) { onDelete(true) }) else emptyList(),
    ) {
        Text(if (!repeating) "This bill, its payment history and attachments will be kept in Recently deleted for 30 days." else "Delete just this occurrence or every remaining entry in this series? An Undo action will be available afterwards.")
    }
}
