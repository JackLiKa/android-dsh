package com.example.adsh

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small, dependency-free Markdown renderer for assistant replies.
 *
 * Supports fenced code blocks (with copy), headings, bullet/numbered lists, quotes, rules and
 * inline bold / italic / strikethrough / code / links. Anything else falls back to plain text.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    if (blocks.isEmpty()) return
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Paragraph -> Text(
                        inlineMarkdown(block.text, codeBackground, linkColor),
                        style = style,
                        color = color,
                    )
                    is MdBlock.Heading -> Text(
                        inlineMarkdown(block.text, codeBackground, linkColor),
                        style = style.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = when (block.level) {
                                1 -> 22.sp
                                2 -> 19.sp
                                else -> 17.sp
                            },
                            lineHeight = when (block.level) {
                                1 -> 30.sp
                                2 -> 26.sp
                                else -> 24.sp
                            },
                        ),
                        color = color,
                    )
                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEach { item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text("•", style = style, color = muted, modifier = Modifier.padding(end = 8.dp))
                                Text(
                                    inlineMarkdown(item, codeBackground, linkColor),
                                    style = style,
                                    color = color,
                                )
                            }
                        }
                    }
                    is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "${index + 1}.",
                                    style = style,
                                    color = muted,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    inlineMarkdown(item, codeBackground, linkColor),
                                    style = style,
                                    color = color,
                                )
                            }
                        }
                    }
                    is MdBlock.Quote -> Surface(
                        color = codeBackground,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            inlineMarkdown(block.text, codeBackground, linkColor),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = style.copy(fontStyle = FontStyle.Italic),
                            color = muted,
                        )
                    }
                    is MdBlock.Code -> CodeBlock(block)
                    is MdBlock.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(block.code))
                        Toast.makeText(
                            context,
                            context.getString(R.string.code_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(R.string.copy_code),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                block.code,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun inlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                        ),
                    ) { append(text.substring(index + 1, end)) }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '[' -> {
                val close = text.indexOf(']', index + 1)
                val open = if (close > 0) text.indexOf('(', close + 1) else -1
                val urlEnd = if (open > 0) text.indexOf(')', open + 1) else -1
                if (close > 0 && open == close + 1 && urlEnd > open) {
                    val label = text.substring(index + 1, close)
                    val url = text.substring(open + 1, urlEnd)
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) { append(label) }
                    } else {
                        append(label)
                    }
                    index = urlEnd + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '_' -> {
                val end = text.indexOf('_', index + 1)
                val wordBefore = index > 0 && text[index - 1].isLetterOrDigit()
                val wordAfter = end >= 0 && end + 1 < text.length && text[end + 1].isLetterOrDigit()
                if (end > index + 1 && !wordBefore && !wordAfter) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end > index + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}

private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        val hashes = trimmed.takeWhile { it == '#' }.length
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks += MdBlock.Code(language, code.toString().trimEnd())
            }
            trimmed.isEmpty() -> {
                flushParagraph()
                i++
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += MdBlock.Rule
                i++
            }
            hashes in 1..3 && (trimmed.length == hashes || trimmed[hashes] == ' ') -> {
                flushParagraph()
                blocks += MdBlock.Heading(hashes, trimmed.drop(hashes).trim())
                i++
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Quote(quote.joinToString("\n"))
            }
            isBullet(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && isBullet(lines[i].trim())) {
                    items += lines[i].trim().drop(2).trim()
                    i++
                }
                blocks += MdBlock.Bullets(items)
            }
            isOrdered(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && isOrdered(lines[i].trim())) {
                    items += lines[i].trim().substringAfter('.', "").trim()
                    i++
                }
                blocks += MdBlock.Ordered(items)
            }
            else -> {
                paragraph.appendLine(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

private fun isBullet(line: String): Boolean =
    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")

private fun isOrdered(line: String): Boolean {
    val dot = line.indexOf('.')
    if (dot <= 0 || dot + 1 >= line.length || line[dot + 1] != ' ') return false
    return line.substring(0, dot).all { it.isDigit() }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Ordered(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}
