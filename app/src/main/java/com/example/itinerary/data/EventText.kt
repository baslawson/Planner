package com.example.itinerary.data

// The most text an event holds: what Planner takes from a calendar file, the phone's calendars and Nextcloud (see
// ServerEvents), so an event Planner keeps in sync can always be read back. Longer text on Nextcloud is read-only there.
object EventText {
    const val MAX_TITLE = 500
    const val MAX_LOCATION = 2000
    const val MAX_NOTES = 20_000

    // Saving: new text must fit. Text that is the same as [original]'s (an event from before these limits) may stay.
    fun validate(item: ItineraryItem, original: ItineraryItem?) {
        fun fits(text: String, max: Int, before: String?) = text.length <= max || text == before
        require(fits(item.title, MAX_TITLE, original?.title) && fits(item.location, MAX_LOCATION, original?.location) &&
            fits(item.notes, MAX_NOTES, original?.notes)) {
            "An event's title can be up to 500 characters, its location up to 2,000 and its notes up to 20,000."
        }
    }

    // Typing in the editor: [next] replaces [current] while it fits; a paste is cut to fit. Text already longer (an event
    // from before these limits) can be shortened but not lengthened.
    fun typed(current: String, next: String, max: Int): String = when {
        next.length <= max || next.length < current.length -> next
        current.length >= max -> current
        else -> next.take(max).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    }
}
