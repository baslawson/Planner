package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Bills
import com.example.itinerary.data.PlanEvent
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthlyBills(events: List<PlanEvent>, today: LocalDate) {
    val settings = (LocalContext.current.applicationContext as ItineraryApp).settings
    val expanded by settings.billsExpanded.collectAsStateWithLifecycle()
    var month by remember(today.withDayOfMonth(1)) { mutableStateOf(YearMonth.from(today)) }
    val summary = remember(events, month) { Bills.summary(events, month) }
    var forecastOpen by remember { mutableStateOf(false) }
    val forecast = remember(events, month) { Bills.forecast(events, month) }
    if (forecastOpen) BillForecastDialog(forecast, month, today) { forecastOpen = false }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Minimized" }
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Minimize unpaid bills" else "Expand unpaid bills") {
                        settings.setBillsExpanded(!expanded)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Unpaid bills · ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null)
            }
            if (expanded) {
                summary.totals.forEach { (currency, amount) -> Text(Bills.format(amount, currency), style = MaterialTheme.typography.titleLarge) }
                if (summary.unpaidCount == 0) Text("No unpaid bills this month")
                if (summary.withoutAmount > 0) Text("${summary.withoutAmount} unpaid ${if (summary.withoutAmount == 1) "bill has" else "bills have"} no amount yet")
                TextButton(onClick = { forecastOpen = true }) { Text("View monthly bill forecast") }
                Text("Bill tasks · paid and skipped bills excluded", style = MaterialTheme.typography.bodySmall)
                // Wraps at large text instead of squeezing the last button to nothing.
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("Previous month") }
                    TextButton(onClick = { month = YearMonth.from(today) }) { Text("This month") }
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("Next month") }
                }
            }
        }
    }
}
