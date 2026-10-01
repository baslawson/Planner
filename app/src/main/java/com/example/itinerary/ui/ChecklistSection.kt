package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import com.example.itinerary.data.ChecklistEntry
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// [anchor]: where the section is on screen, for ChecklistJumpButton.
@Composable
fun ChecklistSection(entries: List<ChecklistEntry>, onChange: (List<ChecklistEntry>) -> Unit, anchor: ChecklistAnchor? = null) {
    HorizontalDivider(if (anchor == null) Modifier else Modifier.onGloballyPositioned { anchor.top = it.positionInWindow().y })
    HeadingText("Checklist", style = MaterialTheme.typography.titleMedium)
    entries.forEachIndexed { index, task ->
        key(task.id) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = task.done, onCheckedChange = { done ->
                    onChange(entries.map { if (it.id == task.id) it.copy(done = done) else it })
                }, modifier = Modifier.semantics { contentDescription = "Complete task ${index + 1}" })
                OutlinedTextField(value = task.text, onValueChange = { value ->
                    onChange(entries.map { if (it.id == task.id) it.copy(text = value.replace('\n', ' ')) else it })
                }, label = { Text("Task ${index + 1}") }, modifier = Modifier.weight(1f),
                    isError = task.text.isBlank(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                IconButton(onClick = { onChange(entries.filterNot { it.id == task.id }) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove task ${index + 1}")
                }
            }
        }
    }
    if (entries.any { it.text.isBlank() }) Text("Enter a task or remove the empty row before saving.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = entries.size < 100, onClick = { onChange(entries + ChecklistEntry(text = "")) },
        modifier = if (anchor == null) Modifier else Modifier.onGloballyPositioned { anchor.bottom = it.positionInWindow().y + it.size.height }) {
        Text("Add task")
    }
}

fun checklistProgress(entries: List<ChecklistEntry>): String = "${entries.count { it.done }}/${entries.size} tasks done"

// Where a ChecklistSection is on screen (window y of its top line and of the bottom of its Add button, also while it is
// scrolled out of sight), for ChecklistJumpButton.
class ChecklistAnchor {
    var top by mutableStateOf<Float?>(null)
    var bottom by mutableStateOf<Float?>(null)
}

// A floating "Checklist 2/5" button over a long editor ([scroll] is its scroll state; this goes in ScrollHints' overlay):
// one tap scrolls the checklist up to the top of the screen. Shown only while the checklist has items, none of it is on
// screen and the keyboard is closed (so it never covers what's being typed).
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.ChecklistJumpButton(entries: List<ChecklistEntry>, anchor: ChecklistAnchor, scroll: ScrollState) {
    var viewTop by remember { mutableStateOf<Float?>(null) }
    var viewBottom by remember { mutableStateOf<Float?>(null) }
    // The visible part of the scrolling area.
    Spacer(Modifier.matchParentSize().onGloballyPositioned { viewTop = it.positionInWindow().y; viewBottom = it.positionInWindow().y + it.size.height })
    val top = anchor.top; val bottom = anchor.bottom; val vTop = viewTop; val vBottom = viewBottom
    val inView = top != null && bottom != null && vTop != null && vBottom != null && bottom > vTop && top < vBottom
    val known = top != null && bottom != null && vTop != null && vBottom != null
    val show = entries.isNotEmpty() && known && !inView && !WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val margin = with(LocalDensity.current) { 12.dp.toPx() }
    fun jump() {
        val at = anchor.top ?: return
        val from = viewTop ?: return
        scope.launch { scroll.animateScrollTo((scroll.value + at - from - margin).roundToInt().coerceIn(0, scroll.maxValue)) }
    }
    val label = "Go to checklist, ${entries.count { it.done }} of ${entries.size} done"
    AnimatedVisibility(show, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
        ExtendedFloatingActionButton(
            onClick = ::jump,
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            text = { Text("Checklist ${entries.count { it.done }}/${entries.size}") },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // One node for a screen reader: the full label, a button, activated like a tap.
            modifier = Modifier.clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick(label) { jump(); true } },
        )
    }
}
