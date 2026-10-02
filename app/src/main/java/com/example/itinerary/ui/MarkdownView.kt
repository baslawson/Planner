package com.example.itinerary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.Markdown

/** A note's Markdown as it reads. [onToggle] (null = read-only) ticks or clears the checklist line it is given. */
@Composable
fun MarkdownView(content: String, modifier: Modifier = Modifier, onToggle: ((Int) -> Unit)? = null) {
    val blocks = remember(content) { Markdown.parse(content) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Heading -> Text(styled(block.text, colors.primary), color = headingTextColor(), style = when (block.level) {
                    1 -> type.headlineSmall; 2 -> type.titleLarge; 3 -> type.titleMedium; else -> type.titleSmall
                })
                is Markdown.Paragraph -> Text(styled(block.text, colors.primary), style = type.bodyLarge)
                is Markdown.Bullet -> ListRow(block.indent, "•") { Text(styled(block.text, colors.primary), style = type.bodyLarge) }
                is Markdown.Numbered -> ListRow(block.indent, "${block.number}.") { Text(styled(block.text, colors.primary), style = type.bodyLarge) }
                is Markdown.Check -> Row(
                    Modifier.fillMaxWidth().padding(start = (block.indent * 20).dp)
                        .then(if (onToggle != null) Modifier.toggleable(block.done, role = Role.Checkbox) { onToggle(block.line) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = block.done, onCheckedChange = null, enabled = onToggle != null || block.done)
                    Spacer(Modifier.width(8.dp))
                    Text(styled(block.text, colors.primary), style = type.bodyLarge.copy(
                        textDecoration = if (block.done) TextDecoration.LineThrough else null,
                        color = if (block.done) colors.onSurfaceVariant else Color.Unspecified))
                }
                is Markdown.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
                    Spacer(Modifier.width(10.dp))
                    Text(styled(block.text, colors.primary), style = type.bodyLarge.copy(fontStyle = FontStyle.Italic), color = colors.onSurfaceVariant)
                }
                is Markdown.Code -> Text(block.text, Modifier.fillMaxWidth()
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(10.dp),
                    style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                is Markdown.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ListRow(indent: Int, mark: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = (indent * 20).dp)) {
        Text(mark, Modifier.widthIn(min = 22.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        content()
    }
}

// One line's bold, italic, strike, code and links.
private fun styled(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    Markdown.inline(text).forEach { run ->
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) link.copy(alpha = 0.12f) else Color.Unspecified,
        )
        if (run.url != null) withLink(LinkAnnotation.Url(run.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
            withStyle(style) { append(run.text) }
        } else withStyle(style) { append(run.text) }
    }
}
