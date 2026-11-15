package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.Serializable

enum class ProjectStatus(val wireValue: String, val label: String) {
  QUEUED("queued", "Queued"),
  EXTRACTING("extracting", "Extracting Audio"),
  UPLOADING("uploading", "Uploading Chunks"),
  TRANSCRIBING("transcribing", "Transcribing English"),
  TRANSLATING("translating", "Translating to Bengali"),
  READY("ready", "Subtitles Ready"),
  EXPORTING("exporting", "Burning MP4"),
  EXPORTED("exported", "Exported MP4"),
  FAILED("failed", "Failed");

  val isTerminalOrEditable: Boolean
    get() = this == READY || this == EXPORTED

  val isInProgress: Boolean
    get() =
      this == QUEUED ||
        this == EXTRACTING ||
        this == UPLOADING ||
        this == TRANSCRIBING ||
        this == TRANSLATING ||
        this == EXPORTING

  companion object {
    fun fromWire(value: String): ProjectStatus =
      entries.firstOrNull { it.wireValue == value } ?: QUEUED
  }
}

enum class ProcessingMode(val wireValue: String, val displayName: String) {
  CLOUD_AI("cloud_ai", "Built-in Cloud AI + Media3"),
  FFMPEG_BACKEND("ffmpeg_backend", "Remote FFmpeg Server");

  companion object {
    fun fromWire(value: String): ProcessingMode =
      entries.firstOrNull { it.wireValue == value } ?: CLOUD_AI
  }
}

@Serializable
data class SubtitleCue(
  val id: String = "",
  val index: Int = 1,
  val startMs: Long = 0L,
  val endMs: Long = 0L,
  val englishText: String = "",
  val bengaliText: String = "",
) {
  fun toFirestoreMap(): Map<String, Any> =
    mapOf(
      "id" to id,
      "index" to index.toLong(),
      "startMs" to startMs,
      "endMs" to endMs,
      "englishText" to englishText,
      "bengaliText" to bengaliText,
    )

  companion object {
    fun fromFirestoreMap(map: Map<*, *>): SubtitleCue =
      SubtitleCue(
        id = (map["id"] as? String).orEmpty(),
        index = (map["index"] as? Number)?.toInt() ?: 1,
        startMs = (map["startMs"] as? Number)?.toLong() ?: 0L,
        endMs = (map["endMs"] as? Number)?.toLong() ?: 0L,
        englishText = (map["englishText"] as? String).orEmpty(),
        bengaliText = (map["bengaliText"] as? String).orEmpty(),
      )
  }
}

@IgnoreExtraProperties
data class SubtitleProject(
  @DocumentId val id: String = "",
  val userId: String = "",
  val title: String = "",
  val fileName: String = "",
  val mimeType: String = "video/mp4",
  val fileSizeBytes: Long = 0L,
  val durationMs: Long = 0L,
  val status: String = ProjectStatus.QUEUED.wireValue,
  val progressPercent: Long = 0L,
  val processingMode: String = ProcessingMode.CLOUD_AI.wireValue,
  val remoteJobId: String? = null,
  val localVideoUri: String? = null,
  val exportedMp4Path: String? = null,
  val cueCount: Long = 0L,
  val errorMessage: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  val statusEnum: ProjectStatus
    get() = ProjectStatus.fromWire(status)

  val modeEnum: ProcessingMode
    get() = ProcessingMode.fromWire(processingMode)
}

@IgnoreExtraProperties
data class CuePage(
  @DocumentId val id: String = "",
  val userId: String = "",
  val projectId: String = "",
  val pageIndex: Long = 0L,
  val cues: List<Map<String, Any>> = emptyList(),
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)
