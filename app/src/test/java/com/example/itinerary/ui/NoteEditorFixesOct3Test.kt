package com.example.itinerary.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.itinerary.data.Markdown
import com.example.itinerary.data.Notes
import org.junit.Assert.*
import org.junit.Test

/** Bug hunt 3 Oct, notes editor (NA-6, NA-10). */
class NoteEditorFixesOct3Test {
    // NA-6: a toolbar edit mid-word kept the keyboard's composing range over the changed text.
    @Test fun toolbarEditsDropTheComposingRange() {
        val typing = TextFieldValue("hello", TextRange(5), composition = TextRange(0, 5))
        val bold = toolbarValue(Markdown.wrap(typing.text, typing.selection.start, typing.selection.end, "**"))
        assertEquals("hello****", bold.text); assertEquals(TextRange(7), bold.selection)
        assertNull(bold.composition)
    }

    // NA-10: the Pinned star and the chosen swatch's tick are black on a light colour, white on a dark one.
    @Test fun marksReadOnLightAndDarkColours() {
        assertEquals(Color.Black, onColour(Color(0xFFFFEB3B))) // yellow
        assertEquals(Color.Black, onColour(Color.White))
        assertEquals(Color.White, onColour(Color(Notes.colors[2]))) // the card blue
    }
}
