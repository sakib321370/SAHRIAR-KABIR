package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.data.model.ProcessingMode
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleCue
import com.example.data.remote.BackendApiClient
import com.example.data.repository.ProjectRepository
import com.example.data.repository.SettingsRepository
import com.example.media.AudioChunkExtractor
import com.example.media.GeminiSubtitleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

class SubtitleProcessingWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  private val repository = ProjectRepository(appContext)
  private val settingsRepository = SettingsRepository(appContext)
  private val geminiService = GeminiSubtitleService()
  private val backendClient = BackendApiClient()

  override suspend fun doWork(): Result {
    val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
    val videoUriStr = inputData.getString(KEY_VIDEO_URI) ?: return Result.failure()
    val modeWire = inputData.getString(KEY_PROCESSING_MODE) ?: ProcessingMode.CLOUD_AI.wireValue
    val mode = ProcessingMode.fromWire(modeWire)
    val videoUri = Uri.parse(videoUriStr)

    ensureNotificationChannel()
    setForeground(createForegroundInfo(projectId, "Preparing video...", 2))

    return try {
      val metadata = AudioChunkExtractor.inspectVideo(applicationContext, videoUri)
      if (!metadata.hasAudioTrack) {
        repository.updateProjectProgress(
          projectId = projectId,
          status = ProjectStatus.FAILED,
          progressPercent = 0,
          errorMessage = "No audio track detected in the selected video.",
        )
        return Result.failure(workDataOf(KEY_ERROR to "No audio track detected."))
      }

      if (mode == ProcessingMode.FFMPEG_BACKEND) {
        runBackendPipeline(projectId, videoUri, metadata.displayName, metadata.sizeBytes, metadata.durationMs)
      } else {
        runCloudAiPipeline(projectId, videoUri, metadata.durationMs)
      }
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Processing failed for project $projectId", e)
      val message = e.localizedMessage ?: "Subtitle processing failed."
      runCatching {
        repository.updateProjectProgress(
          projectId = projectId,
          status = ProjectStatus.FAILED,
          progressPercent = 0,
          errorMessage = message,
        )
      }
      Result.failure(workDataOf(KEY_ERROR to message))
    }
  }

  private suspend fun runCloudAiPipeline(
    projectId: String,
    videoUri: Uri,
    durationMs: Long,
  ) {
    repository.updateProjectProgress(
      projectId = projectId,
      status = ProjectStatus.EXTRACTING,
      progressPercent = 8,
      durationMs = durationMs,
    )
    setForeground(createForegroundInfo(projectId, "Extracting audio track...", 8))

    val workDir = File(applicationContext.cacheDir, "audio_chunks_$projectId")
    try {
      val chunks =
        AudioChunkExtractor.extractAudioChunks(
          context = applicationContext,
          videoUri = videoUri,
          totalDurationMs = durationMs,
          outputDir = workDir,
        ) { done, total ->
          val pct = 10 + ((done.toFloat() / total.coerceAtLeast(1)) * 25f).toInt()
          repository.updateProjectProgress(
            projectId = projectId,
            status = ProjectStatus.EXTRACTING,
            progressPercent = pct,
          )
          setForeground(createForegroundInfo(projectId, "Extracting audio ($done/$total)...", pct))
        }

      val allCues = mutableListOf<SubtitleCue>()
      var nextIndex = 1

      chunks.forEachIndexed { idx, chunk ->
        val basePct = 36 + ((idx.toFloat() / chunks.size.coerceAtLeast(1)) * 58f).toInt()
        val stage =
          if (idx >= chunks.size / 2) ProjectStatus.TRANSLATING else ProjectStatus.TRANSCRIBING
        repository.updateProjectProgress(
          projectId = projectId,
          status = stage,
          progressPercent = basePct,
        )
        setForeground(
          createForegroundInfo(
            projectId,
            "Transcribing & translating Bengali (${idx + 1}/${chunks.size})...",
            basePct,
          )
        )

        val chunkCues = transcribeWithRetry(chunk.file, chunk.mimeType, chunk.startMs, chunk.durationMs, nextIndex)
        allCues.addAll(chunkCues)
        nextIndex += chunkCues.size

        // Save incrementally so progress survives interruptions
        repository.saveCuePages(projectId, allCues).getOrThrow()
      }

      repository.updateProjectProgress(
        projectId = projectId,
        status = ProjectStatus.READY,
        progressPercent = 100,
        cueCount = allCues.size,
        errorMessage = null,
      )
      setForeground(createForegroundInfo(projectId, "Bengali subtitles ready (${allCues.size} cues)", 100))
    } finally {
      workDir.deleteRecursively()
    }
  }

  private suspend fun transcribeWithRetry(
    audioFile: File,
    mimeType: String,
    chunkStartMs: Long,
    chunkDurationMs: Long,
    startingCueIndex: Int,
  ): List<SubtitleCue> {
    var attempt = 0
    var lastError: Exception? = null
    while (attempt < 3) {
      try {
        return geminiService.transcribeAndTranslateAudioChunk(
          audioFile = audioFile,
          mimeType = mimeType,
          chunkStartMs = chunkStartMs,
          chunkDurationMs = chunkDurationMs,
          startingCueIndex = startingCueIndex,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lastError = e
        attempt++
        if (attempt < 3) delay(2500L * attempt)
      }
    }
    throw lastError ?: IllegalStateException("AI transcription failed.")
  }

  private suspend fun runBackendPipeline(
    projectId: String,
    videoUri: Uri,
    fileName: String,
    fileSizeBytes: Long,
    durationMs: Long,
  ) {
    val settings = settingsRepository.settingsFlow.first()
    val baseUrl = settings.backendBaseUrl
    require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
      "FFmpeg backend URL is not configured in Settings."
    }
    require(fileSizeBytes > 0L) {
      "Selected video size could not be determined or file is empty."
    }

    val defaultChunkSize = 5 * 1024 * 1024
    val totalChunks = ((fileSizeBytes + defaultChunkSize - 1) / defaultChunkSize).toInt().coerceAtLeast(1)
    val initResp = backendClient.initJob(baseUrl, fileName, fileSizeBytes, totalChunks)

    repository.updateProjectProgress(
      projectId = projectId,
      status = ProjectStatus.UPLOADING,
      progressPercent = 5,
      durationMs = durationMs,
      remoteJobId = initResp.jobId,
    )
    setForeground(createForegroundInfo(projectId, "Uploading video chunks...", 5))

    backendClient.uploadVideoInChunks(
      context = applicationContext,
      baseUrl = baseUrl,
      jobId = initResp.jobId,
      videoUri = videoUri,
      fileSizeBytes = fileSizeBytes,
      chunkSizeBytes = initResp.chunkSizeBytes,
    ) { uploaded, total ->
      val pct = 5 + ((uploaded.toFloat() / total.coerceAtLeast(1)) * 30f).toInt()
      repository.updateProjectProgress(
        projectId = projectId,
        status = ProjectStatus.UPLOADING,
        progressPercent = pct,
      )
      setForeground(createForegroundInfo(projectId, "Uploading chunk $uploaded/$total...", pct))
    }

    backendClient.startProcessing(baseUrl, initResp.jobId)

    var consecutiveErrors = 0
    while (true) {
      delay(2500L)
      val statusResp =
        try {
          val resp = backendClient.getStatus(baseUrl, initResp.jobId)
          consecutiveErrors = 0
          resp
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          consecutiveErrors++
          if (consecutiveErrors >= 6 || e.message?.contains("(404)") == true) throw e
          delay(3500L)
          continue
        }

      val mappedStatus = ProjectStatus.fromWire(statusResp.status)
      when (mappedStatus) {
        ProjectStatus.FAILED ->
          throw IllegalStateException(statusResp.error ?: "Remote FFmpeg server failed.")
        ProjectStatus.READY, ProjectStatus.EXPORTED -> {
          val cues =
            statusResp.cues.mapIndexed { idx, dto ->
              SubtitleCue(
                id = dto.id.ifBlank { "cue_${idx + 1}" },
                index = idx + 1,
                startMs = dto.startMs,
                endMs = dto.endMs,
                englishText = dto.englishText,
                bengaliText = dto.bengaliText,
              )
            }
          repository.saveCuePages(projectId, cues).getOrThrow()
          repository.updateProjectProgress(
            projectId = projectId,
            status = ProjectStatus.READY,
            progressPercent = 100,
            cueCount = cues.size,
            errorMessage = null,
          )
          return
        }
        else -> {
          val pct = statusResp.progressPercent.coerceIn(36, 98)
          repository.updateProjectProgress(
            projectId = projectId,
            status = mappedStatus,
            progressPercent = pct,
          )
          setForeground(createForegroundInfo(projectId, "${mappedStatus.label} ($pct%)...", pct))
        }
      }
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Subtitle Processing",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Shows progress for audio extraction, Bengali translation, and MP4 export."
        }
      nm.createNotificationChannel(channel)
    }
  }

  private fun createForegroundInfo(projectId: String, message: String, progress: Int): ForegroundInfo {
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle(applicationContext.getString(R.string.app_name))
        .setContentText(message)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, progress.coerceIn(0, 100), false)
        .build()

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
        projectId.hashCode(),
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      ForegroundInfo(projectId.hashCode(), notification)
    }
  }

  companion object {
    private const val TAG = "SubtitleProcessWorker"
    private const val CHANNEL_ID = "banglasub_processing"
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_VIDEO_URI = "video_uri"
    const val KEY_PROCESSING_MODE = "processing_mode"
    const val KEY_ERROR = "error"
  }
}
