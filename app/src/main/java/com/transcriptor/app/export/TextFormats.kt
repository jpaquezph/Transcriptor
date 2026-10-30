package com.transcriptor.app.export

import com.transcriptor.app.data.Segment

/** Una pausa así de larga suele ser un cambio de idea o de hablante. */
private const val PARAGRAPH_GAP_MS = 2_000L
private const val PARAGRAPH_MAX_CHARS = 600

/** m:ss, o h:mm:ss a partir de la primera hora. */
fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** El texto corrido: es lo que se copia y se comparte. */
fun paragraphs(segments: List<Segment>): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var previousEnd = 0L
    for (segment in segments) {
        val isBreak = segment.startMs - previousEnd >= PARAGRAPH_GAP_MS || current.length >= PARAGRAPH_MAX_CHARS
        if (current.isNotEmpty() && isBreak) {
            result += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(segment.text)
        previousEnd = segment.endMs
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

fun timestampedLines(segments: List<Segment>): List<String> =
    segments.map { "[${formatClock(it.startMs)}] ${it.text}" }

fun plainText(segments: List<Segment>): String = paragraphs(segments).joinToString("\n\n")

fun toSrt(segments: List<Segment>): String = buildString {
    segments.forEachIndexed { index, segment ->
        append(index + 1).append('\n')
        append(srtTime(segment.startMs)).append(" --> ").append(srtTime(segment.endMs)).append('\n')
        append(segment.text).append("\n\n")
    }
}

private fun srtTime(ms: Long): String =
    "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

fun safeFileName(title: String): String =
    title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(80).ifEmpty { "transcripcion" }
