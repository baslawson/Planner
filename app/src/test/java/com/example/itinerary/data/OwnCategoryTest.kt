package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fourth bug hunt D9: "Bills" typed as your own category must not turn the event into a bill.
class OwnCategoryTest {
    @Test fun billsInAnyCaseIsNotAnOwnCategory() {
        for (typed in listOf("Bills", "bills", "BILLS", "  bIlLs ")) {
            assertNull(typed, Categories.ownCategory(typed))
            assertTrue(typed, Categories.isBillsName(typed))
        }
    }

    @Test fun otherNamesStillWork() {
        assertEquals("Food", Categories.ownCategory("food"))
        assertEquals("Gym", Categories.ownCategory("gym", listOf("Gym")))
        assertEquals("Bill", Categories.ownCategory("Bill"))
        assertEquals("Bills to file", Categories.ownCategory("Bills  to file"))
        assertNull(Categories.ownCategory("   "))
        assertFalse(Categories.isBillsName("Bill"))
        assertFalse(Categories.isBillsName(""))
    }
}
