package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.SubtitleCue
import java.io.File
import java.util.Locale

object SrtCodec {

  fun formatSrtTimestamp(millis: Long): String {
    val safeMs = millis.coerceAtLeast(0L)
    val hours = safeMs / 3_600_000L
    val minutes = (safeMs % 3_600_000L) / 60_000L
    val seconds = (safeMs % 60_000L) / 1_000L
    val ms = safeMs % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms)
  }

  fun formatDisplayTimestamp(millis: Long): String {
    val safeMs = millis.coerceAtLeast(0L)
    val hours = safeMs / 3_600_000L
    val minutes = (safeMs % 3_600_000L) / 60_000L
    val seconds = (safeMs % 60_000L) / 1_000L
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }

  fun toSrt(cues: List<SubtitleCue>, includeEnglishLine: Boolean = false): String {
    if (cues.isEmpty()) return ""
    val sb = StringBuilder()
    cues.sortedBy { it.startMs }.forEachIndexed { idx, cue ->
      val start = formatSrtTimestamp(cue.startMs)
      val end = formatSrtTimestamp(cue.endMs.coerceAtLeast(cue.startMs + 400L))
      sb.append(idx + 1).append('\n')
      sb.append(start).append(" --> ").append(end).append('\n')
      val primary = cue.bengaliText.trim().ifBlank { cue.englishText.trim() }
      sb.append(primary).append('\n')
      if (includeEnglishLine && cue.englishText.isNotBlank() && cue.bengaliText.isNotBlank()) {
        sb.append(cue.englishText.trim()).append('\n')
      }
      sb.append('\n')
    }
    return sb.toString()
  }

  fun parseSrt(rawSrt: String): List<SubtitleCue> {
    val normalized = rawSrt.replace("\r\n", "\n").replace("\r", "\n").trim()
    if (normalized.isEmpty()) return emptyList()
    val blocks = normalized.split(Regex("\n{2,}"))
    val result = mutableListOf<SubtitleCue>()
    var autoIdx = 1
    for (block in blocks) {
      val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
      if (lines.size < 2) continue
      val timingLineIdx = lines.indexOfFirst { it.contains("-->") }
      if (timingLineIdx == -1) continue
      val parts = lines[timingLineIdx].split("-->")
      if (parts.size != 2) continue
      val startMs = parseSrtTimestamp(parts[0].trim()) ?: continue
      val endMs = parseSrtTimestamp(parts[1].trim()) ?: continue
      val textLines = lines.drop(timingLineIdx + 1)
      val text = textLines.joinToString("\n").trim()
      if (text.isEmpty()) continue
      result.add(
        SubtitleCue(
          id = "cue_$autoIdx",
          index = autoIdx,
          startMs = startMs,
          endMs = endMs.coerceAtLeast(startMs + 250L),
          englishText = "",
          bengaliText = text,
        )
      )
      autoIdx++
    }
    return result
  }

  private fun parseSrtTimestamp(ts: String): Long? {
    val cleaned = ts.replace('.', ',').trim()
    val match = Regex("""^(\d{1,2}):(\d{2}):(\d{2}),(\d{1,3})$""").find(cleaned) ?: return null
    val (h, m, s, msRaw) = match.destructured
    val ms = msRaw.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return (h.toLong() * 3_600_000L) + (m.toLong() * 60_000L) + (s.toLong() * 1_000L) + ms
  }

  fun saveSrtForShare(context: Context, projectTitle: String, srtContent: String): Uri {
    val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
    val safeName = sanitizeFilename(projectTitle)
    val file = File(dir, "${safeName}_bn.srt")
    file.writeText(srtContent, Charsets.UTF_8)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }

  fun saveSrtToDownloads(context: Context, projectTitle: String, srtContent: String): Result<Uri> {
    return runCatching {
      val safeName = "${sanitizeFilename(projectTitle)}_bn.srt"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values =
          ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BanglaSub")
            put(MediaStore.Downloads.IS_PENDING, 1)
          }
        val resolver = context.contentResolver
        val uri =
          resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads entry.")
        resolver.openOutputStream(uri)?.use { out ->
          out.write(srtContent.toByteArray(Charsets.UTF_8))
        } ?: error("Unable to write SRT file.")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
      } else {
        saveSrtForShare(context, projectTitle, srtContent)
      }
    }
  }

  fun sanitizeFilename(input: String): String {
    val cleaned =
      input
        .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    return cleaned.ifBlank { "banglasub_export" }.take(64)
  }
}
