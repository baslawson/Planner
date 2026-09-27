package com.example.itinerary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.LocalDate

@Entity(tableName = "templates")
data class EventTemplate(@PrimaryKey val id: String, val name: String, val payload: String)

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY name COLLATE NOCASE, id") fun observeAll(): Flow<List<EventTemplate>>
    @Query("SELECT * FROM templates ORDER BY name COLLATE NOCASE, id") suspend fun all(): List<EventTemplate>
    @Upsert suspend fun upsert(template: EventTemplate)
    @Insert suspend fun insertAll(templates: List<EventTemplate>)
    @Delete suspend fun delete(template: EventTemplate)
    @Query("DELETE FROM templates") suspend fun deleteAll()
}

data class TemplateContent(val item: ItineraryItem, val reminders: List<Reminder>, val repeat: RepeatRule, val count: Int) {
    fun forDate(date: LocalDate): ItineraryItem = item.copy(id = 0, tripId = 0, date = date, paid = false, payments = emptyList(), skipped = false,
        linkedTaskId = null, seriesId = null, repeatRule = "NONE", draftToken = null, checklist = item.checklist.map { it.copy(done = false) })
    fun validate() {
        require(item.title.isNotBlank()); ChecklistCodec.validate(item.checklist); Bills.validate(item.billAmountMinor, item.billCurrency)
        require(item.bufferBeforeMinutes in 0..1440 && item.bufferAfterMinutes in 0..1440)
        require(item.durationMinutes == null || item.startTime != null && item.durationMinutes in 1..1440)
        require(count in 1..365 && (repeat == RepeatRule.NONE || count >= 2))
        require(reminders.all { it.amount >= 0 })
    }
    fun encode(): String {
        validate()
        return JSONObject().put("item", DraftCodec.item(forDate(LocalDate.of(2000, 1, 1))))
            .put("reminders", DraftCodec.reminders(reminders.map { it.copy(id=0,itemId=0,snoozedUntil=null) }))
            .put("repeat", repeat.name).put("count", count).toString()
    }
    companion object {
        fun decode(payload: String): TemplateContent = JSONObject(payload).let {
            TemplateContent(DraftCodec.item(it.getJSONObject("item")), DraftCodec.reminders(it.getJSONArray("reminders")),
                RepeatRule.valueOf(it.getString("repeat")), it.getInt("count"))
        }.also { it.validate() }
    }
}
