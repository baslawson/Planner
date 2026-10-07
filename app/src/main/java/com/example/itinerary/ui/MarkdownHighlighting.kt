package com.example.itinerary.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.example.itinerary.data.MarkdownHighlight

/**
 * The note box's live formatting (MarkdownHighlight): styles only, so the text, cursor and selection are exactly what is
 * typed. [mark] is the faint colour of the Markdown signs, [list] of list bullets and checkboxes, [codeBackground] behind
 * `code`.
 */
internal fun markdownHighlighting(mark: Color, list: Color, codeBackground: Color) = VisualTransformation { text ->
    val styled = buildAnnotatedString {
        append(text)
        for (span in MarkdownHighlight.spans(text.text)) addStyle(when (span.style) {
            MarkdownHighlight.Style.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.25.em)
            MarkdownHighlight.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MarkdownHighlight.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            MarkdownHighlight.Style.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            MarkdownHighlight.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            MarkdownHighlight.Style.MARK -> SpanStyle(color = mark)
            MarkdownHighlight.Style.LIST -> SpanStyle(color = list, fontWeight = FontWeight.Bold)
        }, span.start, span.end)
    }
    TransformedText(styled, OffsetMapping.Identity)
}
