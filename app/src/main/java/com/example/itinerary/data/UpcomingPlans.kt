package com.example.itinerary.data

// The "upcoming plans" setting (on/off and how many days ahead). The screen that used it, My plans, has been removed;
// the setting is kept so saved settings and backups still read and write the same values.
object UpcomingPlans {
    const val DEFAULT_DAYS = 7
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365
}
