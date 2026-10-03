package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

class AppVersionTest {
    @Test fun laterVersionsAreNewer() {
        assertTrue(AppVersion.newer("0.0.15", "0.0.14"))
        assertTrue(AppVersion.newer("v0.0.14", "0.0.9")) // part by part, not as text
        assertTrue(AppVersion.newer("0.1", "0.0.99"))
        assertTrue(AppVersion.newer("1.0.0", "0.9.9"))
        assertTrue(AppVersion.newer("0.0.14.1", "0.0.14"))
        assertFalse(AppVersion.newer("0.0.14", "0.0.14"))
        assertFalse(AppVersion.newer("v0.0.14", "0.0.14.0"))
        assertFalse(AppVersion.newer("0.0.13", "0.0.14"))
        // Unreadable is never newer.
        assertFalse(AppVersion.newer("latest", "0.0.14"))
        assertFalse(AppVersion.newer("0.0.15-beta", "0.0.14"))
        assertFalse(AppVersion.newer("0.0.15", ""))
    }

    @Test fun checksumFilesAreRead() {
        val hash = "e6d0f1054f6d9cb8953ae8bad35398d88f4ffafe72b13d0aec7188b6fc7c209a"
        assertEquals(hash, AppVersion.sha256Of("$hash  Planner.apk\n"))
        assertEquals(hash, AppVersion.sha256Of(hash.uppercase()))
        assertNull(AppVersion.sha256Of(""))
        assertNull(AppVersion.sha256Of("not a hash  Planner.apk"))
        assertNull(AppVersion.sha256Of(hash.dropLast(1)))
    }
}
