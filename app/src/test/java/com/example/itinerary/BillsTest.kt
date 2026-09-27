package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class BillsTest {
    @Test fun amountsUseExactMinorUnitsAndRejectAmbiguousInput() {
        assertEquals(10L,Bills.parse("0.10"));assertEquals(12345L,Bills.parse("123,45"));assertEquals(0L,Bills.parse("0"))
        for(value in listOf("", "-1", "1.234", "1,234.56", "1e3", "1000000000", "abc")) assertNull(value,Bills.parse(value))
        assertEquals("123.45",Bills.input(12345))
    }
    private fun bill(id:Long,amount:Long?,currency:String="AUD") = PlanEvent(1,id,LocalDate.of(2026,9,25),null,"Bill",0,null,
        category="Bills",billAmountMinor=amount,billCurrency=currency)
    @Test fun totalsKeepCurrenciesSeparateAndExcludePaidSkippedAndOtherMonths() {
        val events=listOf(bill(1,10),bill(2,20),bill(3,999).copy(paid=true),bill(4,888).copy(skipped=true),
            bill(5,77).copy(date=LocalDate.of(2026,10,1)),bill(6,null),bill(7,50,"USD"),bill(8,100).copy(category="Other"))
        val summary=Bills.summary(events,YearMonth.of(2026,9))
        assertEquals(mapOf("AUD" to BigDecimal("0.30"),"USD" to BigDecimal("0.50")),summary.totals)
        assertEquals(1,summary.withoutAmount);assertEquals(4,summary.unpaidCount)
    }
    @Test fun zeroAmountIsKnownAndSkippedEventsDoNotClash() {
        assertEquals(0,Bills.summary(listOf(bill(1,0)),YearMonth.of(2026,9)).withoutAmount)
        val date=LocalDate.of(2026,9,25)
        val event=ItineraryItem(tripId=1,date=date,startTime=java.time.LocalTime.NOON,title="Skipped",skipped=true)
        assertTrue(overlappingEvents(listOf(event),listOf(date),event.startTime,60,emptySet()).isEmpty())
    }
    @Test fun recognizedDocumentTextIsSearchableAndIdentifiesTheAttachment() {
        val date=LocalDate.of(2026,9,25)
        val trip=Trip(1,"Agenda","",date,date)
        val event=ItineraryItem(1,1,date,null,"Electricity")
        val attachment=Attachment(1,1,"Receipt","scan.pdf","application/pdf",recognizedText="ZEBRA70831 invoice",textStatus="READY")
        val outcome=Search.run("ZEBRA70831",emptySet(),listOf(trip),listOf(event),listOf(attachment),date)
        assertEquals(1,outcome.hits.size);assertEquals("Receipt",outcome.hits.single().documentName)
        assertTrue(Search.run("ZEBRA70831",emptySet(),listOf(trip),listOf(event),emptyList(),date).hits.isEmpty())
    }
    @Test fun overdueStartsAfterDueDayAndExcludesPaidAndSkipped() {
        val today=LocalDate.of(2026,9,25)
        assertTrue(billOverdue(today.minusDays(1),false,false,today))
        assertFalse(billOverdue(today,false,false,today));assertFalse(billOverdue(today.plusDays(1),false,false,today))
        assertFalse(billOverdue(today.minusDays(1),true,false,today));assertFalse(billOverdue(today.minusDays(1),false,true,today))
    }
    @Test fun historyUsesSeriesIdentityAndKeepsStandaloneBillsSeparate() {
        val first=ItineraryItem(1,1,LocalDate.of(2026,1,1),null,"Power",category="Bills",seriesId="a",paid=true)
        val second=first.copy(id=2,date=first.date.plusMonths(1),paid=false)
        val unrelated=first.copy(id=3,seriesId="b")
        val standalone=first.copy(id=4,seriesId=null)
        val items=listOf(first,second,unrelated,standalone,first.copy(id=5,category="Other"))
        assertEquals(listOf(2L,1L),billHistory(items,1).map { it.id })
        assertEquals(listOf(4L),billHistory(items,4).map { it.id });assertTrue(billHistory(items,99).isEmpty())
    }

}
