package com.example.itinerary.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChecklistEntry(val id: String = UUID.randomUUID().toString(), val text: String, val done: Boolean = false)

object ChecklistCodec {
    fun validate(entries: List<ChecklistEntry>) {
        require(entries.size <= 100)
        require(entries.all { it.id.isNotBlank() && it.text.isNotBlank() })
        require(entries.map { it.id }.distinct().size == entries.size)
    }
    fun encode(entries: List<ChecklistEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
    }.toString()
    fun decode(value: String): List<ChecklistEntry> {
        val array = JSONArray(value)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ChecklistEntry(item.getString("id"), item.getString("text"), item.getBoolean("done"))
        }.also(::validate)
    }
}
