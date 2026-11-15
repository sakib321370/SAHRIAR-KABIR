package com.example.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoFileMetadata(
  val displayName: String,
  val mimeType: String,
  val sizeBytes: Long,
  val durationMs: Long,
  val hasAudioTrack: Boolean,
)

data class ExtractedAudioChunk(
  val file: File,
  val startMs: Long,
  val durationMs: Long,
  val mimeType: String,
)

object AudioChunkExtractor {

  private const val CHUNK_DURATION_MS = 240_000L // 4 minutes per chunk (~1.4 MB AAC)

  suspend fun inspectVideo(context: Context, uri: Uri): VideoFileMetadata =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      var displayName = "video.mp4"
      var sizeBytes = 0L

      resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
        }
      }

      if (sizeBytes <= 0L) {
        sizeBytes =
          runCatching {
              resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0L) afd.length else 0L
              } ?: 0L
            }
            .getOrDefault(0L)
      }

      if (sizeBytes <= 0L) {
        sizeBytes =
          runCatching {
              resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                  val read = input.read(buf)
                  if (read == -1) break
                  total += read
                }
                total
              } ?: 0L
            }
            .getOrDefault(0L)
      }

      val rawMime = resolver.getType(uri)
      val mimeType =
        if (rawMime.isNullOrBlank() || rawMime == "application/octet-stream") {
          guessMimeFromName(displayName)
        } else {
          rawMime
        }

      var durationMs = 0L
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, uri)
        durationMs =
          retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
      } catch (_: Exception) {
        durationMs = 0L
      } finally {
        runCatching { retriever.release() }
      }

      var hasAudio = false
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
          val format = extractor.getTrackFormat(i)
          val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
          if (trackMime.startsWith("audio/")) {
            hasAudio = true
          }
          if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
            val us = format.getLong(MediaFormat.KEY_DURATION)
            if (us > 0L) {
              durationMs = maxOf(durationMs, us / 1_000L)
            }
          }
        }
      } catch (_: Exception) {
        // Allow Media3 Transformer to probe if MediaExtractor cannot directly read container
        hasAudio = true
      } finally {
        runCatching { extractor.release() }
      }

      VideoFileMetadata(
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        hasAudioTrack = hasAudio,
      )
    }

  @OptIn(UnstableApi::class)
  suspend fun extractAudioChunks(
    context: Context,
    videoUri: Uri,
    totalDurationMs: Long,
    outputDir: File,
    onChunkExtracted: suspend (chunkIndex: Int, totalChunks: Int) -> Unit,
  ): List<ExtractedAudioChunk> {
    outputDir.mkdirs()

    if (totalDurationMs <= 0L) {
      val fullAudio = File(outputDir, "audio_full.mp4")
      if (fullAudio.exists()) fullAudio.delete()
      val editedFull =
        EditedMediaItem.Builder(MediaItem.fromUri(videoUri)).setRemoveVideo(true).build()
      runTransformerOnMain(context, editedFull, fullAudio.absolutePath)

      val measuredMs = probeLocalFileDurationMs(fullAudio).coerceAtLeast(1_000L)
      if (measuredMs <= CHUNK_DURATION_MS) {
        onChunkExtracted(1, 1)
        return listOf(
          ExtractedAudioChunk(
            file = fullAudio,
            startMs = 0L,
            durationMs = measuredMs,
            mimeType = "audio/mp4",
          )
        )
      }
      val sliced =
        extractAudioChunks(
          context = context,
          videoUri = Uri.fromFile(fullAudio),
          totalDurationMs = measuredMs,
          outputDir = outputDir,
          onChunkExtracted = onChunkExtracted,
        )
      fullAudio.delete()
      return sliced
    }

    val safeTotalMs = totalDurationMs.coerceAtLeast(1_000L)
    val ranges = mutableListOf<Pair<Long, Long>>()
    var cursor = 0L
    while (cursor < safeTotalMs) {
      val end = minOf(safeTotalMs, cursor + CHUNK_DURATION_MS)
      ranges.add(cursor to end)
      cursor = end
    }

    val extracted = mutableListOf<ExtractedAudioChunk>()
    ranges.forEachIndexed { idx, (startMs, endMs) ->
      val outFile = File(outputDir, "audio_chunk_${idx}.mp4")
      if (outFile.exists()) outFile.delete()

      val clipping =
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs)
          .setEndPositionMs(endMs)
          .build()

      val mediaItem =
        MediaItem.Builder()
          .setUri(videoUri)
          .setClippingConfiguration(clipping)
          .build()

      val editedMediaItem =
        EditedMediaItem.Builder(mediaItem)
          .setRemoveVideo(true)
          .build()

      runTransformerOnMain(context, editedMediaItem, outFile.absolutePath)
      val actualDurationMs =
        probeLocalFileDurationMs(outFile).takeIf { it > 0L } ?: (endMs - startMs).coerceAtLeast(1_000L)

      extracted.add(
        ExtractedAudioChunk(
          file = outFile,
          startMs = startMs,
          durationMs = actualDurationMs,
          mimeType = "audio/mp4",
        )
      )
      onChunkExtracted(idx + 1, ranges.size)
    }
    return extracted
  }

  private fun probeLocalFileDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
      0L
    } finally {
      runCatching { retriever.release() }
    }
  }

  @OptIn(UnstableApi::class)
  private suspend fun runTransformerOnMain(
    context: Context,
    editedMediaItem: EditedMediaItem,
    outputPath: String,
  ) =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        val transformer =
          Transformer.Builder(context.applicationContext)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
              object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                  if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                  composition: Composition,
                  exportResult: ExportResult,
                  exportException: ExportException,
                ) {
                  if (continuation.isActive) {
                    continuation.resumeWithException(
                      IllegalStateException(
                        "Audio extraction failed: ${exportException.localizedMessage ?: exportException.errorCodeName}",
                        exportException,
                      )
                    )
                  }
                }
              }
            )
            .build()

        continuation.invokeOnCancellation { transformer.cancel() }
        transformer.start(editedMediaItem, outputPath)
      }
    }

  private fun guessMimeFromName(name: String): String {
    return when {
      name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
      name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
      name.endsWith(".webm", ignoreCase = true) -> "video/webm"
      else -> "video/mp4"
    }
  }
}
