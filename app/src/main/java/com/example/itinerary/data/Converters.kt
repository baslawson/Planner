package com.example.itinerary.data

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalTime

// ISO-8601 strings sort correctly in SQLite, so ORDER BY date, startTime just works.
class Converters {
    @TypeConverter fun fromStrings(value: List<String>): String = org.json.JSONArray(value).toString()
    @TypeConverter fun toStrings(value: String): List<String> = StringListCodec.decode(org.json.JSONArray(value))
    @TypeConverter fun fromTaskAttachments(value: List<Attachment>): String = DraftCodec.attachments(value).toString()
    @TypeConverter fun toTaskAttachments(value: String): List<Attachment> = DraftCodec.attachments(org.json.JSONArray(value))
    @TypeConverter fun fromPayments(value: List<BillPayment>): String = Payments.encode(value)
    @TypeConverter fun toPayments(value: String): List<BillPayment> = Payments.decode(value)
    @TypeConverter fun fromChecklist(value: List<ChecklistEntry>): String = ChecklistCodec.encode(value)
    @TypeConverter fun toChecklist(value: String): List<ChecklistEntry> = ChecklistCodec.decode(value)

    @TypeConverter fun fromDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun toDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    @TypeConverter fun fromTime(value: LocalTime?): String? = value?.toString()
    @TypeConverter fun toTime(value: String?): LocalTime? = value?.let { LocalTime.parse(it) }

    @TypeConverter fun fromReminderUnit(value: ReminderUnit): String = value.name
    @TypeConverter fun toReminderUnit(value: String): ReminderUnit = ReminderUnit.valueOf(value)
}
