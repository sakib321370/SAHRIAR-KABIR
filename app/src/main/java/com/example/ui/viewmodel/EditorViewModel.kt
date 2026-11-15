package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleCue
import com.example.data.model.SubtitleProject
import com.example.data.repository.ProjectRepository
import com.example.media.SrtCodec
import com.example.worker.SubtitleExportWorker
import com.example.worker.SubtitleProcessingWorker
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EditorViewModel(
  private val repository: ProjectRepository,
  private val currentUserId: String,
  private val projectId: String,
) : ViewModel() {

  val projectState: StateFlow<UiState<SubtitleProject?>> =
    repository
      .observeProject(currentUserId, projectId)
      .map<SubtitleProject?, UiState<SubtitleProject?>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing project $projectId", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load project."))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Loading,
      )

  val cuesState: StateFlow<List<SubtitleCue>> =
    repository
      .observeCues(currentUserId, projectId)
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        Log.w(TAG, "Error observing cues for $projectId", firestoreError ?: error)
        emit(emptyList())
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList(),
      )

  private val _feedbackMessage = MutableStateFlow<String?>(null)
  val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

  private val _isSaving = MutableStateFlow(false)
  val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

  fun clearFeedback() {
    _feedbackMessage.value = null
  }

  fun updateCue(
    cueId: String,
    bengaliText: String,
    englishText: String,
    startMs: Long,
    endMs: Long,
  ) {
    val current = cuesState.value
    val updated =
      current
        .map { cue ->
          if (cue.id == cueId) {
            cue.copy(
              bengaliText = bengaliText.trim(),
              englishText = englishText.trim(),
              startMs = startMs.coerceAtLeast(0L),
              endMs = endMs.coerceAtLeast(startMs.coerceAtLeast(0L) + 300L),
            )
          } else {
            cue
          }
        }
        .sortedBy { it.startMs }
    persistCues(updated, "Subtitle cue saved")
  }

  fun nudgeCue(cueId: String, deltaStartMs: Long, deltaEndMs: Long) {
    val current = cuesState.value
    val updated =
      current
        .map { cue ->
          if (cue.id == cueId) {
            val nextStart = (cue.startMs + deltaStartMs).coerceAtLeast(0L)
            val nextEnd = (cue.endMs + deltaEndMs).coerceAtLeast(nextStart + 300L)
            cue.copy(startMs = nextStart, endMs = nextEnd)
          } else {
            cue
          }
        }
        .sortedBy { it.startMs }
    persistCues(updated, null)
  }

  fun addCue(startMs: Long, endMs: Long, englishText: String, bengaliText: String) {
    val current = cuesState.value
    val newCue =
      SubtitleCue(
        id = "cue_${System.currentTimeMillis()}",
        index = current.size + 1,
        startMs = startMs.coerceAtLeast(0L),
        endMs = endMs.coerceAtLeast(startMs.coerceAtLeast(0L) + 1_500L),
        englishText = englishText.trim(),
        bengaliText = bengaliText.trim().ifBlank { "নতুন বাংলা সাবটাইটেল" },
      )
    val updated = (current + newCue).sortedBy { it.startMs }
    persistCues(updated, "Added new Bengali subtitle cue")
  }

  fun deleteCue(cueId: String) {
    val updated = cuesState.value.filterNot { it.id == cueId }
    persistCues(updated, "Subtitle cue removed")
  }

  private fun persistCues(cues: List<SubtitleCue>, successToast: String?) {
    viewModelScope.launch {
      _isSaving.value = true
      repository
        .saveCuePages(projectId, cues)
        .onSuccess {
          repository.updateProjectProgress(
            projectId = projectId,
            status =
              (projectState.value as? UiState.Success)?.data?.statusEnum ?: ProjectStatus.READY,
            progressPercent =
              ((projectState.value as? UiState.Success)?.data?.progressPercent ?: 100L).toInt(),
            cueCount = cues.size,
          )
          if (successToast != null) _feedbackMessage.value = successToast
        }
        .onFailure { error ->
          _feedbackMessage.value = error.localizedMessage ?: "Failed to save subtitle changes."
        }
      _isSaving.value = false
    }
  }

  fun downloadSrt(context: Context, projectTitle: String, includeEnglish: Boolean) {
    val cues = cuesState.value
    if (cues.isEmpty()) {
      _feedbackMessage.value = "No subtitles available to export yet."
      return
    }
    viewModelScope.launch {
      val srt = SrtCodec.toSrt(cues, includeEnglishLine = includeEnglish)
      SrtCodec.saveSrtToDownloads(context, projectTitle, srt).fold(
        onSuccess = {
          val fileName = "${SrtCodec.sanitizeFilename(projectTitle)}_bn.srt"
          _feedbackMessage.value =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
              "Saved $fileName to Downloads/BanglaSub"
            } else {
              "Saved $fileName (tap Share SRT to save externally)"
            }
        },
        onFailure = { error ->
          _feedbackMessage.value = error.localizedMessage ?: "Failed to save SRT file."
        },
      )
    }
  }

  fun buildShareSrtUri(context: Context, projectTitle: String, includeEnglish: Boolean): Uri? {
    val cues = cuesState.value
    if (cues.isEmpty()) {
      _feedbackMessage.value = "No subtitles available to share yet."
      return null
    }
    val srt = SrtCodec.toSrt(cues, includeEnglishLine = includeEnglish)
    return SrtCodec.saveSrtForShare(context, projectTitle, srt)
  }

  fun startMp4BurnInExport(context: Context) {
    if (cuesState.value.isEmpty()) {
      _feedbackMessage.value = "Generate or add Bengali subtitles before exporting MP4."
      return
    }
    viewModelScope.launch {
      repository.updateProjectExport(
        projectId = projectId,
        status = ProjectStatus.EXPORTING,
        progressPercent = 5,
        errorMessage = null,
      )
      val request =
        OneTimeWorkRequestBuilder<SubtitleExportWorker>()
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .setInputData(workDataOf(SubtitleExportWorker.KEY_PROJECT_ID to projectId))
          .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork("export_$projectId", ExistingWorkPolicy.REPLACE, request)
    }
  }

  fun clearProjectError(project: SubtitleProject) {
    viewModelScope.launch {
      repository.updateProjectExport(
        projectId = project.id,
        status = project.statusEnum,
        progressPercent = project.progressPercent.toInt(),
        errorMessage = null,
      )
    }
  }

  fun retryProcessing(context: Context, project: SubtitleProject) {
    val videoUri = project.localVideoUri
    if (videoUri.isNullOrBlank()) {
      _feedbackMessage.value = "Original video URI is missing."
      return
    }
    viewModelScope.launch {
      repository.updateProjectProgress(
        projectId = project.id,
        status = ProjectStatus.QUEUED,
        progressPercent = 0,
        errorMessage = null,
      )
      val request =
        OneTimeWorkRequestBuilder<SubtitleProcessingWorker>()
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .setInputData(
            workDataOf(
              SubtitleProcessingWorker.KEY_PROJECT_ID to project.id,
              SubtitleProcessingWorker.KEY_VIDEO_URI to videoUri,
              SubtitleProcessingWorker.KEY_PROCESSING_MODE to project.modeEnum.wireValue,
            )
          )
          .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork("process_${project.id}", ExistingWorkPolicy.REPLACE, request)
    }
  }

  private companion object {
    const val TAG = "EditorViewModel"
  }
}
