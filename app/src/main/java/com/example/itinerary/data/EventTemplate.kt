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
    // A multi-day template keeps its length on the new start date.
    fun forDate(date: LocalDate): ItineraryItem = item.startingOn(date).copy(id = 0, tripId = 0, paid = false, payments = emptyList(), skipped = false,
        linkedTaskId = null, seriesId = null, repeatRule = "NONE", draftToken = null, checklist = item.checklist.map { it.copy(done = false) })
    fun validate() {
        require(item.title.isNotBlank()); ChecklistCodec.validate(item.checklist); Bills.validate(item.billAmountMinor, item.billCurrency)
        require(item.bufferBeforeMinutes in 0..1440 && item.bufferAfterMinutes in 0..1440)
        require(item.durationMinutes == null || item.startTime != null && item.durationMinutes in 1..1440)
        MultiDay.validate(item)
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

/**
 * Several events saved as one template (user, 14 Oct: "a weekend away"): each keeps how many days after the first one it
 * starts ([events]: offset to content) and its details, checklist, amount and reminders. Repeats aren't kept: each is
 * one event. Stored beside the event templates, told apart by its "set" field; a backup holding one is format 18, which
 * an older Planner refuses rather than failing on it.
 */
data class TemplateSet(val events: List<Pair<Int, TemplateContent>>) {
    fun validate() {
        require(events.size in 1..MAX_EVENTS)
        events.forEach { (offset, content) -> require(offset in 0..MAX_OFFSET && content.repeat == RepeatRule.NONE && content.count == 1); content.validate() }
    }
    fun encode(): String {
        validate()
        return JSONObject().put("set", org.json.JSONArray(events.map { (offset, content) ->
            JSONObject().put("offset", offset).put("event", JSONObject(content.encode()))
        })).toString()
    }
    // The events (with their reminders) from [start]: the first on that day, each other as many days on as it was.
    fun from(start: LocalDate): List<Pair<ItineraryItem, List<Reminder>>> =
        events.map { (offset, content) -> content.forDate(start.plusDays(offset.toLong())) to content.reminders }

    companion object {
        const val MAX_EVENTS = 50
        // A year and a day: a set is days or weeks of plans, not a calendar.
        const val MAX_OFFSET = 366
        fun isSet(payload: String): Boolean = runCatching { JSONObject(payload).has("set") }.getOrDefault(false)
        fun decode(payload: String): TemplateSet = JSONObject(payload).getJSONArray("set").let { list ->
            TemplateSet((0 until list.length()).map { i ->
                list.getJSONObject(i).let { it.getInt("offset") to TemplateContent.decode(it.getJSONObject("event").toString()) }
            })
        }.also { it.validate() }
        // From saved events: in date and time order, each one day-counted from the first, without its repeat.
        fun of(events: List<Pair<ItineraryItem, List<Reminder>>>): TemplateSet {
            require(events.isNotEmpty())
            val first = events.minOf { it.first.date }
            return TemplateSet(events.sortedWith(compareBy({ it.first.date }, { it.first.startTime }, { it.first.id })).map { (item, reminders) ->
                java.time.temporal.ChronoUnit.DAYS.between(first, item.date).toInt() to TemplateContent(item, reminders, RepeatRule.NONE, 1)
            })
        }
    }
}
