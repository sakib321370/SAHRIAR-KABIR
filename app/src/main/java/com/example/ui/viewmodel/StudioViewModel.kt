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
import com.example.data.model.ProcessingMode
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleProject
import com.example.data.repository.ProjectRepository
import com.example.media.AudioChunkExtractor
import com.example.media.VideoFileMetadata
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

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

data class SelectedVideoDraft(
  val uri: Uri,
  val metadata: VideoFileMetadata,
  val customTitle: String,
)

class StudioViewModel(
  private val repository: ProjectRepository,
  private val currentUserId: String,
) : ViewModel() {

  val projectsState: StateFlow<UiState<List<SubtitleProject>>> =
    repository
      .observeProjects(currentUserId)
      .map<List<SubtitleProject>, UiState<List<SubtitleProject>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing projects", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load processing history."))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Loading,
      )

  private val _selectedDraft = MutableStateFlow<SelectedVideoDraft?>(null)
  val selectedDraft: StateFlow<SelectedVideoDraft?> = _selectedDraft.asStateFlow()

  private val _isInspecting = MutableStateFlow(false)
  val isInspecting: StateFlow<Boolean> = _isInspecting.asStateFlow()

  private val _actionError = MutableStateFlow<String?>(null)
  val actionError: StateFlow<String?> = _actionError.asStateFlow()

  fun clearError() {
    _actionError.value = null
  }

  fun clearSelectedDraft() {
    _selectedDraft.value = null
  }

  fun updateDraftTitle(title: String) {
    _selectedDraft.value = _selectedDraft.value?.copy(customTitle = title)
  }

  fun inspectVideo(context: Context, uri: Uri) {
    viewModelScope.launch {
      _isInspecting.value = true
      _actionError.value = null
      try {
        val metadata = AudioChunkExtractor.inspectVideo(context, uri)
        if (!metadata.hasAudioTrack) {
          _actionError.value = "This video has no audio track to transcribe."
          _selectedDraft.value = null
        } else {
          val baseTitle = metadata.displayName.substringBeforeLast('.').ifBlank { "Untitled Video" }
          _selectedDraft.value =
            SelectedVideoDraft(uri = uri, metadata = metadata, customTitle = baseTitle)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to inspect selected video", e)
        _actionError.value = e.localizedMessage ?: "Unable to read the selected video file."
      } finally {
        _isInspecting.value = false
      }
    }
  }

  fun startGeneration(
    context: Context,
    mode: ProcessingMode,
    backendBaseUrl: String,
    onProjectStarted: (String) -> Unit,
  ) {
    val draft = _selectedDraft.value ?: return
    if (mode == ProcessingMode.FFMPEG_BACKEND && backendBaseUrl.isBlank()) {
      _actionError.value =
        "Please configure your Remote FFmpeg Server URL in Settings or switch to Built-in Cloud AI + Media3."
      return
    }

    viewModelScope.launch {
      _actionError.value = null
      val result =
        repository.createProject(
          title = draft.customTitle,
          fileName = draft.metadata.displayName,
          mimeType = draft.metadata.mimeType,
          fileSizeBytes = draft.metadata.sizeBytes,
          durationMs = draft.metadata.durationMs,
          processingMode = mode,
          localVideoUri = draft.uri.toString(),
        )

      result.fold(
        onSuccess = { projectId ->
          enqueueProcessingWork(context, projectId, draft.uri.toString(), mode)
          _selectedDraft.value = null
          onProjectStarted(projectId)
        },
        onFailure = { error ->
          _actionError.value = error.localizedMessage ?: "Failed to create project."
        },
      )
    }
  }

  fun retryProject(context: Context, project: SubtitleProject) {
    val videoUri = project.localVideoUri
    if (videoUri.isNullOrBlank()) {
      _actionError.value = "Original video file reference is unavailable for retry."
      return
    }
    viewModelScope.launch {
      repository.updateProjectProgress(
        projectId = project.id,
        status = ProjectStatus.QUEUED,
        progressPercent = 0,
        errorMessage = null,
      )
      enqueueProcessingWork(context, project.id, videoUri, project.modeEnum)
    }
  }

  fun deleteProject(projectId: String) {
    viewModelScope.launch {
      repository.deleteProject(projectId).onFailure { error ->
        _actionError.value = error.localizedMessage ?: "Failed to delete project."
      }
    }
  }

  private fun enqueueProcessingWork(
    context: Context,
    projectId: String,
    videoUri: String,
    mode: ProcessingMode,
  ) {
    val request =
      OneTimeWorkRequestBuilder<SubtitleProcessingWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(
          workDataOf(
            SubtitleProcessingWorker.KEY_PROJECT_ID to projectId,
            SubtitleProcessingWorker.KEY_VIDEO_URI to videoUri,
            SubtitleProcessingWorker.KEY_PROCESSING_MODE to mode.wireValue,
          )
        )
        .build()

    WorkManager.getInstance(context.applicationContext)
      .enqueueUniqueWork("process_$projectId", ExistingWorkPolicy.REPLACE, request)
  }

  private companion object {
    const val TAG = "StudioViewModel"
  }
}
