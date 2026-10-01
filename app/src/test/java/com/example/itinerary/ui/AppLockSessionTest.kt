package com.example.itinerary.ui

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// App lock across Planner's screens: a second Planner screen (a notification or share tapped while Planner is open or
// locked) coming and going is not leaving Planner.
class AppLockSessionTest {
    private var clock = 0L
    private fun lock(after: LockAfter = LockAfter.IMMEDIATELY) = AppLock(MemoryPrefs()) { clock }.apply {
        setEnabled(true); setLockAfter(after)
    }

    // MainActivity's onStart and onStop (not a rotation).
    private fun AppLock.start() = checkOnStart()
    private fun AppLock.stop() = onStopped(changingConfigurations = false)

    @Test fun homeAndBackLocksImmediately() {
        val lock = lock()
        assertFalse(lock.start())
        lock.stop(); clock += 1000
        assertTrue(lock.start())
    }

    @Test fun rotationIsNotLeaving() {
        val lock = lock()
        assertFalse(lock.start())
        lock.onStopped(changingConfigurations = true); clock += 1000
        assertFalse(lock.start())
        lock.stop(); clock += 1000
        assertTrue(lock.start())
    }

    @Test fun secondScreenOverTheFirstIsNotLeaving() {
        val lock = lock()
        assertFalse(lock.start())          // Planner open, unlocked
        clock += 1000
        assertFalse(lock.start())          // a notification opens a second Planner screen over it
        lock.stop()                        // the first is now hidden under it
        clock += 1000
        assertFalse(lock.start())          // Back: the first comes into view again
        lock.stop()                        // the second closes
        assertFalse(lock.locked.value)
        // Then Home and back still locks.
        lock.stop(); clock += 1000
        assertTrue(lock.start())
    }

    @Test fun unlockOnTheSecondScreenCoversTheFirst() {
        // A fresh start: locked.
        val lock = AppLock(MemoryPrefs().apply { edit().putBoolean(AppLock.KEY_ENABLED, true).apply() }) { clock }
        lock.setLockAfter(LockAfter.IMMEDIATELY)
        assertTrue(lock.start())           // the lock screen goes over the first screen,
        lock.stop()                        // which stops under it
        assertTrue(lock.start())           // a notification opens a second screen: its own lock screen
        lock.stop()
        lock.onUnlocked()
        assertFalse(lock.locked.value)
        assertFalse(lock.start())          // the second screen, unlocked
        clock += 1000
        assertFalse(lock.start())          // Back to the first: open, no lock screen
        lock.stop()                        // the second closes
        lock.stop(); clock += 1000         // Home from the first, and back
        assertTrue(lock.start())
    }
}

// SharedPreferences in memory.
private class MemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private fun put(key: String, value: Any?): SharedPreferences.Editor { values[key] = value; return this }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor { values.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { values.clear(); return this }
        override fun commit() = true
        override fun apply() = Unit
    }
}
