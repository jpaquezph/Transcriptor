package com.transcriptor.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Segment(val startMs: Long, val endMs: Long, val text: String)

@Serializable
enum class TranscriptStatus { QUEUED, TRANSCRIBING, DONE, CANCELLED, INTERRUPTED, FAILED }

@Serializable
enum class TranscriptError { UNSUPPORTED_FORMAT, MODEL_MISSING, GENERIC }

@Serializable
data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    /** Nombre del archivo dentro de la carpeta de audios de la app. */
    val audioFile: String,
    val durationMs: Long = 0,
    val status: TranscriptStatus = TranscriptStatus.QUEUED,
    val error: TranscriptError? = null,
    val segments: List<Segment> = emptyList(),
)
