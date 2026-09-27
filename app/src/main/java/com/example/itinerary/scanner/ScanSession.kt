package com.example.itinerary.scanner

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ScanSession(val directory: File) {
    private val state get() = AtomicFile(File(directory, "session.json"))
    fun read(): JSONObject? = if (state.baseFile.exists()) JSONObject(state.openRead().bufferedReader().use { it.readText() }) else null
    fun pages(json: JSONObject?): List<ScanImages.Page> {
        val pages = json?.optJSONArray("pages") ?: return emptyList()
        return List(pages.length()) { i ->
            val p = pages.getJSONObject(i)
            val corners = p.getJSONArray("corners")
            ScanImages.Page(File(directory, p.getString("file")), List(4) { n ->
                ScanPoint(corners.getJSONArray(n).getDouble(0).toFloat(), corners.getJSONArray(n).getDouble(1).toFloat())
            }, p.getInt("turns"), p.getBoolean("auto"))
        }.also { list -> require(list.size <= ScanImages.MAX_PAGES && list.all { it.file.isFile && validScanCorners(it.corners) }) }
    }
    fun save(pages: List<ScanImages.Page>, index: Int, pending: File?, review: Boolean) {
        check(directory.isDirectory || directory.mkdirs())
        val json = JSONObject().put("index", index).put("pending", pending?.name).put("review", review)
            .put("pages", JSONArray().apply { pages.forEach { p -> put(JSONObject().put("file", p.file.name)
                .put("turns", p.turns).put("auto", p.autoDetected).put("corners", JSONArray().apply {
                    p.corners.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
                })) } })
        val file = state
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
}
