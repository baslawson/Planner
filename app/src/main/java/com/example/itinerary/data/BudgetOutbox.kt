package com.example.itinerary.data

import android.content.Context

/**
 * Messages for MyBudget (BudgetLink) that haven't been answered yet, kept on disk: a bill paid from a notification
 * with Planner closed, or a queue left when Android ended Planner while MyBudget was open, goes the next time
 * Planner's main screen starts. A message leaves only when MyBudget has answered it (or can't be reached); MyBudget
 * adds a payment once, so sending one again never doubles it. Filled only while "Send paid bills to MyBudget" is on.
 */
class BudgetOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("budget_outbox", Context.MODE_PRIVATE)

    @Synchronized fun add(messages: List<BudgetLink.Message>) {
        if (messages.isNotEmpty()) save((pending() + messages).takeLast(MAX))
    }
    @Synchronized fun pending(): List<BudgetLink.Message> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }.mapNotNull { BudgetLink.decode(it) }
    @Synchronized fun remove(message: BudgetLink.Message) {
        val all = pending().toMutableList()
        if (all.remove(message)) save(all)
    }
    @Synchronized fun clear() { prefs.edit().remove(KEY).commit() }
    private fun save(all: List<BudgetLink.Message>) { prefs.edit().putString(KEY, all.joinToString("\n") { BudgetLink.encode(it) }).commit() }

    private companion object {
        const val KEY = "messages"
        const val MAX = 100
    }
}
