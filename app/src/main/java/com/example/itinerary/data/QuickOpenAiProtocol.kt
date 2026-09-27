package com.example.itinerary.data

import org.json.JSONObject

internal object QuickOpenAiProtocol {
    fun request(model: String, input: QuickInput, answers: List<Pair<String, String>>): JSONObject =
        JSONObject().put("model", model).put("store", false).put("max_output_tokens", 16000)
            .put("instructions", QuickAiContract.instruction)
            .put("input", QuickAiContract.context(input, answers).toString())
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema")
                .put("name", "planner_quick_entry").put("strict", true).put("schema", JSONObject(QuickAiContract.schema))))

    fun result(body: JSONObject, input: QuickInput): QuickAiResult {
        if (body.optString("status") != "completed" || !body.isNull("error") || !body.isNull("incomplete_details"))
            throw QuickAiException("OpenAI couldn't finish this interpretation. Shorten the text or continue offline.")
        val output = body.getJSONArray("output")
        val text = buildString {
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                when (item.getString("type")) {
                    "reasoning" -> Unit
                    "message" -> {
                        require(item.getString("role") == "assistant" && item.getString("status") == "completed")
                        val content = item.getJSONArray("content")
                        for (n in 0 until content.length()) {
                            val part = content.getJSONObject(n)
                            if (part.optString("type") == "refusal")
                                throw QuickAiException("OpenAI couldn't interpret this text. Reword it or continue offline.")
                            require(part.getString("type") == "output_text" && part.get("text") is String)
                            append(part.getString("text"))
                        }
                    }
                    else -> error("Unexpected AI output")
                }
            }
        }
        return QuickAiResult.decode(JSONObject(text), input.text, input.task)
    }
}
