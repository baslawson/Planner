package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The big + button's place (kept as fractions of the space it moves in) and its menu's order.
class AddButtonPlaceTest {
    @Test fun theDefaultIsBottomCentreAndItsMenuOpensUpwards() {
        val place = AddButtonPlace()
        assertEquals(458 to 1800, place.offset(width = 1000, height = 1884, size = 84))
        assertFalse(place.opensDown)
    }

    @Test fun aDroppedButtonIsKeptAsFractionsAndLandsInTheSamePlaceAgain() {
        val place = AddButtonPlace.fromOffset(left = 229f, top = 475f, width = 1000, height = 1984, size = 84)
        assertEquals(0.25f, place.x, 0.001f)
        assertEquals(0.25f, place.y, 0.001f)
        assertEquals(229 to 475, place.offset(1000, 1984, 84))
        assertTrue("top half: the menu opens downwards", place.opensDown)
        // A rotation keeps it the same way across: a quarter of the new space.
        assertEquals(469 to 229, place.offset(width = 1960, height = 1000, size = 84))
    }

    @Test fun outsideTheSpaceOrUnreadableIsClampedOrTheDefault() {
        assertEquals(AddButtonPlace(1f, 0f), AddButtonPlace.fromOffset(5000f, -300f, 1000, 2000, 84))
        assertEquals(AddButtonPlace(), AddButtonPlace.of(Float.NaN, Float.NaN))
        assertEquals(AddButtonPlace(0f, 1f), AddButtonPlace.of(-2f, 7f))
        // No room to move (a space no bigger than the button): the default, not a division by zero.
        assertEquals(AddButtonPlace(), AddButtonPlace.fromOffset(10f, 10f, 84, 84, 84))
    }

    @Test fun quickEntryIsLastByDefaultAndAStoredOrderIsMadeWhole() {
        assertEquals(listOf("task", "event", "note", "quick"), AddMenuOrder.decode(null))
        assertEquals(listOf("quick", "task", "event", "note"), AddMenuOrder.decode("quick,task,event,note"))
        // Unknown names and repeats dropped; a missing row goes back at its default place.
        assertEquals(listOf("event", "task", "note", "quick"), AddMenuOrder.decode("event,bogus,task,event,quick"))
        assertEquals(AddMenuOrder.DEFAULT, AddMenuOrder.decode(""))
        assertEquals("quick,task,event,note", AddMenuOrder.encode(listOf("quick", "task", "event", "note")))
    }

    // Quick entry stays next to the button: a menu opening downwards (✕ on top) is the order mirrored, and a row moved
    // there is kept the right way round.
    @Test fun aMenuOpeningDownwardsIsMirrored() {
        assertEquals(listOf("task", "event", "note", "quick"), AddMenuOrder.onScreen(AddMenuOrder.DEFAULT, opensDown = false))
        assertEquals(listOf("quick", "note", "event", "task"), AddMenuOrder.onScreen(AddMenuOrder.DEFAULT, opensDown = true))
        // Shown downwards, "task" (at the bottom) dragged to the top, next to the ✕: kept as the row nearest the button.
        val shown = AddMenuOrder.move(AddMenuOrder.onScreen(AddMenuOrder.DEFAULT, true), 3, 0)
        assertEquals(listOf("event", "note", "quick", "task"), AddMenuOrder.onScreen(shown, opensDown = true))
    }

    @Test fun aRowMovesUpOrDown() {
        val order = AddMenuOrder.DEFAULT
        assertEquals(listOf("quick", "task", "event", "note"), AddMenuOrder.move(order, 3, 0))
        assertEquals(listOf("event", "task", "note", "quick"), AddMenuOrder.move(order, 0, 1))
        assertEquals(listOf("event", "note", "quick", "task"), AddMenuOrder.move(order, 0, 99))
        assertEquals(order, AddMenuOrder.move(order, 7, 0))
    }
}
