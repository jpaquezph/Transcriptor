package com.transcriptor.app.asr

/**
 * Whisper se entrenó con videos subtitulados y a veces "alucina" los créditos de esos subtítulos
 * o anotaciones de sonido donde no hay palabras. Esos segmentos se descartan.
 */
object TextCleaner {
    private val hallucinations = listOf(
        Regex("""amara\.org""", RegexOption.IGNORE_CASE),
        Regex("""^subt[ií]tulos\s+(realizados\s+)?por\b""", RegexOption.IGNORE_CASE),
        Regex("""^[\[(*♪](.*[\])*♪])?$"""),
    )

    fun clean(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || hallucinations.any { it.containsMatchIn(text) }) return null
        return text
    }
}
