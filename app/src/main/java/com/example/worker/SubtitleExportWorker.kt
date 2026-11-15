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
import com.example.data.remote.BackendApiClient
import com.example.data.repository.ProjectRepository
import com.example.data.repository.SettingsRepository
import com.example.media.SrtCodec
import com.example.media.SubtitleBurnInExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

class SubtitleExportWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  private val repository = ProjectRepository(appContext)
  private val settingsRepository = SettingsRepository(appContext)
  private val exporter = SubtitleBurnInExporter()
  private val backendClient = BackendApiClient()

  override suspend fun doWork(): Result {
    val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
    ensureNotificationChannel()
    setForeground(createForegroundInfo(projectId, "Preparing MP4 subtitle burn-in...", 5))

    return try {
      val project =
        repository.getProject(projectId).getOrThrow()
          ?: throw IllegalStateException("Project not found.")
      val cues = repository.getCuesOnce(projectId).getOrThrow()
      require(cues.isNotEmpty()) { "No Bengali subtitles available to burn into the video." }

      val settings = settingsRepository.settingsFlow.first()
      repository.updateProjectExport(
        projectId = projectId,
        status = ProjectStatus.EXPORTING,
        progressPercent = 10,
      )

      val exportedUri: Uri =
        if (
          project.modeEnum == ProcessingMode.FFMPEG_BACKEND &&
            !project.remoteJobId.isNullOrBlank() &&
            settings.backendBaseUrl.isNotBlank()
        ) {
          val baseUrl = settings.backendBaseUrl
          val jobId = project.remoteJobId
          backendClient.startBurnInExport(
            baseUrl = baseUrl,
            jobId = jobId,
            cues = cues,
            fontSizeSp = settings.subtitleFontSizeSp,
            showEnglishDualLine = settings.showEnglishDualLine,
          )
          var consecutiveErrors = 0
          while (true) {
            delay(2500L)
            val status =
              try {
                val resp = backendClient.getStatus(baseUrl, jobId)
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
            val pct = status.progressPercent.coerceIn(15, 95)
            repository.updateProjectExport(
              projectId = projectId,
              status = ProjectStatus.EXPORTING,
              progressPercent = pct,
            )
            setForeground(createForegroundInfo(projectId, "FFmpeg burning Bengali subtitles ($pct%)...", pct))
            if (status.status == ProjectStatus.FAILED.wireValue) {
              throw IllegalStateException(status.error ?: "FFmpeg server export failed.")
            }
            if (status.status == ProjectStatus.EXPORTED.wireValue) break
          }

          val exportsDir = File(applicationContext.filesDir, "exports").apply { mkdirs() }
          val safeName = SrtCodec.sanitizeFilename(project.title)
          val downloaded = File(exportsDir, "${safeName}_bn_server.mp4")
          backendClient.downloadExportedMp4(baseUrl, jobId, downloaded)
          val savedUri = exporter.saveVideoFileToMovies(applicationContext, downloaded, "${safeName}_bn.mp4")
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            downloaded.delete()
          }
          savedUri
        } else {
          val localUriStr =
            project.localVideoUri
              ?: throw IllegalStateException("Original local video URI is missing for on-device MP4 export.")
          val videoUri = Uri.parse(localUriStr)
          exporter
            .burnSubtitlesToMp4(
              context = applicationContext,
              videoUri = videoUri,
              projectTitle = project.title,
              cues = cues,
              fontSizeSp = settings.subtitleFontSizeSp,
              showEnglishDualLine = settings.showEnglishDualLine,
            ) { pct ->
              val mapped = pct.coerceIn(10, 98)
              repository.updateProjectExport(
                projectId = projectId,
                status = ProjectStatus.EXPORTING,
                progressPercent = mapped,
              )
              setForeground(createForegroundInfo(projectId, "Burning Bengali subtitles ($mapped%)...", mapped))
            }
            .getOrThrow()
        }

      repository.updateProjectExport(
        projectId = projectId,
        status = ProjectStatus.EXPORTED,
        progressPercent = 100,
        exportedMp4Path = exportedUri.toString(),
        errorMessage = null,
      )
      setForeground(createForegroundInfo(projectId, "MP4 export ready", 100))
      Result.success(workDataOf(KEY_EXPORTED_URI to exportedUri.toString()))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "MP4 export failed for $projectId", e)
      val message = e.localizedMessage ?: "MP4 burn-in export failed."
      runCatching {
        repository.updateProjectExport(
          projectId = projectId,
          status = ProjectStatus.READY,
          progressPercent = 100,
          errorMessage = message,
        )
      }
      Result.failure(workDataOf(KEY_ERROR to message))
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
        )
      nm.createNotificationChannel(channel)
    }
  }

  private fun createForegroundInfo(projectId: String, message: String, progress: Int): ForegroundInfo {
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle(applicationContext.getString(R.string.app_name))
        .setContentText(message)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, progress.coerceIn(0, 100), false)
        .build()

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
        ("export_$projectId").hashCode(),
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      ForegroundInfo(("export_$projectId").hashCode(), notification)
    }
  }

  companion object {
    private const val TAG = "SubtitleExportWorker"
    private const val CHANNEL_ID = "banglasub_processing"
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_EXPORTED_URI = "exported_uri"
    const val KEY_ERROR = "error"
  }
}
