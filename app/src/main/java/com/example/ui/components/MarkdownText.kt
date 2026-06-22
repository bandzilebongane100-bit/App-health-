package com.example.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val annotatedString = parseMarkdown(markdown)
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.fillMaxWidth()
    )
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val length = text.length

        while (cursor < length) {
            // Find bold markers **
            if (cursor + 1 < length && text[cursor] == '*' && text[cursor + 1] == '*') {
                val endBold = text.indexOf("**", cursor + 2)
                if (endBold != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(cursor + 2, endBold))
                    pop()
                    cursor = endBold + 2
                    continue
                }
            }

            // Find italic markers *
            if (text[cursor] == '*' && (cursor == 0 || text[cursor - 1] != '*')) {
                val endItalic = text.indexOf("*", cursor + 1)
                // Ensure it's not actually the start of block bold further down
                if (endItalic != -1 && (endItalic + 1 >= length || text[endItalic + 1] != '*')) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(cursor + 1, endItalic))
                    pop()
                    cursor = endItalic + 1
                    continue
                }
            }

            // Find headers starting with # at a newline or beginning of string
            if (text[cursor] == '#' && (cursor == 0 || text[cursor - 1] == '\n')) {
                var headerLevel = 0
                while (cursor < length && text[cursor] == '#') {
                    headerLevel++
                    cursor++
                }
                // Skip space
                if (cursor < length && text[cursor] == ' ') {
                    cursor++
                }
                val endLine = text.indexOf('\n', cursor)
                val lineEnd = if (endLine != -1) endLine else length
                val headingText = text.substring(cursor, lineEnd)

                val sizeMultiplier = when (headerLevel) {
                    1 -> 1.4f
                    2 -> 1.25f
                    3 -> 1.15f
                    else -> 1.1f
                }
                val color = when (headerLevel) {
                    1, 2 -> SpanStyle(
                        fontSize = (16 * sizeMultiplier).sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SpanStyle().color // Default
                    )
                    else -> SpanStyle(
                        fontSize = (16 * sizeMultiplier).sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                pushStyle(color)
                append(headingText)
                pop()
                cursor = lineEnd
                continue
            }

            // Default character
            append(text[cursor])
            cursor++
        }
    }
}
