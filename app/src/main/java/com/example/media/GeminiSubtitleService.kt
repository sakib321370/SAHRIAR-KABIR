package com.example.media

import android.util.Log
import com.example.data.model.SubtitleCue
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

class GeminiSubtitleService {

  private val subtitleSchema =
    Schema.obj(
      properties =
        mapOf(
          "segments" to
            Schema.array(
              items =
                Schema.obj(
                  properties =
                    mapOf(
                      "startOffsetMs" to
                        Schema.integer(
                          description =
                            "Start time in milliseconds from the beginning of this audio clip."
                        ),
                      "endOffsetMs" to
                        Schema.integer(
                          description =
                            "End time in milliseconds from the beginning of this audio clip."
                        ),
                      "englishText" to
                        Schema.string(
                          description = "Accurate verbatim transcription of the English speech."
                        ),
                      "bengaliText" to
                        Schema.string(
                          description =
                            "Natural, idiomatic, fluent Bengali translation in Bengali script (বাংলা)."
                        ),
                    )
                ),
              description = "Chronological subtitle segments covering spoken dialogue in the clip.",
            )
        )
    )

  private val model by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = subtitleSchema
          temperature = 0.2f
        },
    )
  }

  suspend fun transcribeAndTranslateAudioChunk(
    audioFile: File,
    mimeType: String,
    chunkStartMs: Long,
    chunkDurationMs: Long,
    startingCueIndex: Int,
  ): List<SubtitleCue> {
    val bytes = audioFile.readBytes()
    require(bytes.isNotEmpty()) { "Audio chunk ${audioFile.name} is empty." }
    require(bytes.size <= MAX_INLINE_BYTES) {
      "Audio chunk exceeds 18MB inline safety threshold (${bytes.size} bytes)."
    }

    val prompt =
      """
      You are an expert English-to-Bengali audiovisual subtitle translator.
      Listen carefully to this English audio clip (total duration: ${chunkDurationMs} ms).
      Task:
      1. Transcribe every spoken English sentence accurately with precise startOffsetMs and endOffsetMs relative to 0 ms of THIS audio clip.
      2. Split long sentences into readable subtitle cues (roughly 2 to 6 seconds per cue, max ~2 lines).
      3. Translate each cue into natural, idiomatic, conversational Bengali (বাংলা লিপি) that preserves tone, speaker intent, and timing synchronization. Avoid robotic literal translation.
      4. Ensure startOffsetMs >= 0, endOffsetMs > startOffsetMs, and segments are strictly chronological and non-overlapping.
      5. If a section is purely silent or music without speech, omit it rather than inventing text.
      """
        .trimIndent()

    val response =
      try {
        withTimeout(REQUEST_TIMEOUT_MS) {
          model.generateContent(
            content {
              inlineData(bytes, mimeType)
              text(prompt)
            }
          )
        }
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Firebase AI transcription timed out for chunk at ${chunkStartMs}ms", e)
        throw IllegalStateException(
          "AI transcription timed out while processing segment at ${SrtCodec.formatDisplayTimestamp(chunkStartMs)}. Please retry.",
          e,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Firebase AI transcription failed for chunk at ${chunkStartMs}ms", e)
        throw IllegalStateException(
          e.localizedMessage ?: "Cloud AI transcription failed. Check connectivity and retry.",
          e,
        )
      }

    val jsonText =
      response.text?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("AI model returned an empty subtitle response.")

    return parseResponseSegments(
      jsonText = jsonText,
      chunkStartMs = chunkStartMs,
      chunkDurationMs = chunkDurationMs,
      startingCueIndex = startingCueIndex,
    )
  }

  private fun parseResponseSegments(
    jsonText: String,
    chunkStartMs: Long,
    chunkDurationMs: Long,
    startingCueIndex: Int,
  ): List<SubtitleCue> {
    val root = JSONObject(jsonText)
    val segments = root.optJSONArray("segments") ?: return emptyList()
    val cues = mutableListOf<SubtitleCue>()
    var nextIndex = startingCueIndex
    var prevEndMs = chunkStartMs

    for (i in 0 until segments.length()) {
      val obj = segments.optJSONObject(i) ?: continue
      val rawStart = obj.optLong("startOffsetMs", 0L).coerceIn(0L, chunkDurationMs.coerceAtLeast(1L))
      val rawEnd =
        obj.optLong("endOffsetMs", rawStart + 2_000L).coerceIn(rawStart + 300L, chunkDurationMs + 1_000L)
      val en = obj.optString("englishText", "").trim()
      val bn = obj.optString("bengaliText", "").trim()
      if (en.isBlank() && bn.isBlank()) continue

      val absStart = maxOf(prevEndMs, chunkStartMs + rawStart)
      val absEnd = maxOf(absStart + 400L, chunkStartMs + rawEnd)
      prevEndMs = absEnd

      cues.add(
        SubtitleCue(
          id = "cue_$nextIndex",
          index = nextIndex,
          startMs = absStart,
          endMs = absEnd,
          englishText = en,
          bengaliText = bn.ifBlank { en },
        )
      )
      nextIndex++
    }
    return cues
  }

  private companion object {
    const val TAG = "GeminiSubtitleService"
    const val REQUEST_TIMEOUT_MS = 60_000L
    const val MAX_INLINE_BYTES = 18 * 1024 * 1024
  }
}
