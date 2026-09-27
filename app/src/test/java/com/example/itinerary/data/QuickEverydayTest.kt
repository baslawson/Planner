package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuickEverydayTest {
    private val today = LocalDate.of(2030,12,30)
    private fun parse(text: String) = QuickEntry.parse(text,today)
    @Test fun wordDatesCrossCalendarBoundariesAndKeepSpans() {
        for ((phrase,days) in listOf("day after tomorrow" to 2,"the day after tomorrow" to 2,"in two days" to 2,
            "in a week" to 7,"a week from today" to 7,"two weeks from today" to 14,"in twelve days" to 12)) {
            val raw="Dentist $phrase 3pm"; val result=parse(raw)
            assertNull(raw,result.error); assertEquals(today.plusDays(days.toLong()),result.date)
            assertEquals("Dentist",result.title);assertEquals(LocalTime.of(15,0),result.time)
            assertEquals(phrase,result.phrases.filter { it.kind==QuickPhraseKind.DATE }.single().let { raw.substring(it.start,it.end) })
        }
        assertEquals(today.plusMonths(2),parse("Dentist in two months").date)
        assertNotNull(parse("Dentist in two fortnights").error)
        assertNotNull(parse("Dentist in two").error)
        assertEquals("Meet in two days",parse("\"Meet in two days\"").title)
    }
    @Test fun naturalReminderAliasesUseExistingReminderModel() {
        for ((phrase,minutes) in listOf("remind me half an hour before" to 30,"notify me 10 minutes before" to 10,
            "remind me a day before" to 1440,"notify me half an hour before" to 30)) {
            val result=parse("Dentist tomorrow 3pm at Cafe, $phrase")
            assertNull(phrase,result.error);assertEquals("Dentist",result.title);assertEquals("Cafe",result.location)
            assertEquals(minutes,result.reminderMinutes)
        }
        assertNotNull(parse("Dentist notify me half a minute before").error)
        assertNotNull(parse("Dentist notify me").error)
        assertNull(parse("\"notify me 10 minutes before\"").reminderMinutes)
    }
    @Test fun onlyExplicitAdjacentDateCorrectionsReplaceDates() {
        for (bridge in listOf("—actually ",", actually "," actually "," - actually on ")) {
            val raw="Dentist Friday${bridge}Saturday at 3pm"
            val result=parse(raw)
            assertNull(raw,result.error);assertEquals("Dentist",result.title)
            assertEquals(DayOfWeek.SATURDAY,result.date.dayOfWeek);assertEquals(LocalTime.of(15,0),result.time)
        }
        assertNotNull(parse("Dentist Friday Saturday 3pm").error)
        assertNotNull(parse("Dentist Friday actually").error)
        assertNotNull(parse("Dentist Friday actually February 30").error)
        assertEquals("Friday actually Saturday",parse("\"Friday actually Saturday\" tomorrow").title)
        val input=QuickInput("Dentist Friday actually Saturday 3pm",baseDate=today,dateOverride="2031-01-10")
        assertEquals(input.dateOverride,input.edited(input.text.replace("Dentist","Dentist checkup")).dateOverride)
        assertNull(input.edited(input.text.replace("Saturday","Sunday")).dateOverride)
    }
    @Test fun vagueTimePreservesDateTitlePlaceAndNeedsExplicitTime() {
        val result=parse("Dinner tomorrow evening at Cafe remind me half an hour before")
        assertEquals("Dinner",result.title);assertEquals(today.plusDays(1),result.date)
        assertEquals("Cafe",result.location);assertEquals(30,result.reminderMinutes)
        assertTrue(result.clarificationOnly);assertNotNull(result.error);assertNull(result.time)
        assertNotNull(result.corrected(null,"").error)
        val corrected=result.corrected(null,"18:30")
        assertNull(corrected.error);assertEquals(LocalTime.of(18,30),corrected.time)
        assertEquals("Cafe",parse("Dinner at Cafe after lunch").location)
        assertEquals("Dinner",parse("Dinner at Cafe after lunch").title)
        assertNotNull(parse("Dinner tomorrow evening 3pm").error)
        assertNotNull(parse("Dinner next evening").error)
        assertNotNull(parse("Dinner this evening tomorrow").error)
        assertNotNull(parse("Dinner every Friday tonight").error)
    }
    @Test fun literalVagueTimeAndSavedManualCorrectionsRemainExplicit() {
        val raw="Dinner tomorrow evening"
        val result=parse(raw);val phrase=result.phrases.single { it.kind==QuickPhraseKind.TIME }
        val literal=QuickEntry.parse(raw,today,listOf(phrase.start until phrase.end))
        assertNull(literal.error);assertNull(literal.time);assertEquals("Dinner evening",literal.title)
        val input=QuickInput(raw,baseDate=today,timeOverride="18:30")
        assertEquals("18:30",input.edited("Dinner with Sam tomorrow evening").timeOverride)
        assertNull(input.edited("Dinner tomorrow morning").timeOverride)
        assertNotNull(QuickInput("Dinner tomorrow evening",baseDate=today).suggestion().error)
        assertNull(QuickInput("Dentist in two days 3pm",baseDate=today).suggestion().error)
    }
}
