package com.example.itinerary.reminders

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

// R-L2: a task reminder or snooze rang; the clock is then set back, so its time is ahead again. It must not ring twice.
class DeliveredAlarmsTest {
    // Just enough of SharedPreferences for DeliveredAlarms (and AlarmLedger).
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            override fun putString(key: String, value: String?) = apply { changes += { it[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes += { it[key] = values } }
            override fun putInt(key: String, value: Int) = apply { changes += { it[key] = value } }
            override fun putLong(key: String, value: Long) = apply { changes += { it[key] = value } }
            override fun putFloat(key: String, value: Float) = apply { changes += { it[key] = value } }
            override fun putBoolean(key: String, value: Boolean) = apply { changes += { it[key] = value } }
            override fun remove(key: String) = apply { changes += { it.remove(key) } }
            override fun clear() = apply { changes += { it.clear() } }
            override fun commit(): Boolean { changes.forEach { it(values) }; return true }
            override fun apply() { commit() }
        }
    }

    @Test fun aRungTimeIsNotSetAgainButANewTimeIs() {
        val delivered = DeliveredAlarms(MemoryPrefs())
        val task = MissedReminders.taskKey("t1"); val snooze = MissedReminders.eventKey(7)
        assertFalse(delivered.delivered(task, 1_000L))
        delivered.record(task, 1_000L); delivered.record(snooze, 5_000L)
        assertTrue(delivered.delivered(task, 1_000L)); assertTrue(delivered.delivered(snooze, 5_000L))
        // Another time for the same task or reminder (moved, snoozed again, the next repeat) still rings.
        assertFalse(delivered.delivered(task, 2_000L)); assertFalse(delivered.delivered(snooze, 6_000L))
        // Changing or removing the reminder forgets it.
        delivered.forget(task)
        assertFalse(delivered.delivered(task, 1_000L)); assertTrue(delivered.delivered(snooze, 5_000L))
    }
}
