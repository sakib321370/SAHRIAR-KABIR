package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ProcessingMode
import com.example.data.model.SubtitleProject
import com.example.data.repository.AppSettings
import com.example.media.SrtCodec
import com.example.ui.components.MiniMetaChip
import com.example.ui.components.ProcessingProgressCard
import com.example.ui.components.ProjectHistoryCard
import com.example.ui.components.formatFileSize
import com.example.ui.theme.NotoSansBengaliFontFamily
import com.example.ui.viewmodel.SelectedVideoDraft
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
  projectsState: UiState<List<SubtitleProject>>,
  selectedDraft: SelectedVideoDraft?,
  isInspecting: Boolean,
  actionError: String?,
  settings: AppSettings,
  onVideoPicked: (android.net.Uri) -> Unit,
  onDraftTitleChanged: (String) -> Unit,
  onClearDraft: () -> Unit,
  onStartGeneration: () -> Unit,
  onOpenProject: (String) -> Unit,
  onRetryProject: (SubtitleProject) -> Unit,
  onDeleteProject: (String) -> Unit,
  onOpenSettings: () -> Unit,
  onDismissError: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  val notificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      onStartGeneration()
    }

  val videoPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        onVideoPicked(uri)
      }
    }

  val launchVideoPicker = {
    videoPickerLauncher.launch(
      arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/matroska", "video/*")
    )
  }

  val startWithNotificationPermission = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      onStartGeneration()
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "BanglaSub AI",
              style = MaterialTheme.typography.headlineLarge,
            )
            Text(
              text = "ইংরেজি ভিডিও থেকে নিখুঁত বাংলা সাবটাইটেল",
              fontFamily = NotoSansBengaliFontFamily,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Surface(
            modifier = Modifier.clickable(onClick = onOpenSettings).minimumInteractiveComponentSize(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector =
                  if (settings.processingMode == ProcessingMode.FFMPEG_BACKEND)
                    Icons.Filled.CloudQueue
                  else Icons.Filled.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text =
                  if (settings.processingMode == ProcessingMode.FFMPEG_BACKEND) "FFmpeg Server"
                  else "Cloud AI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }

      if (actionError != null) {
        item {
          Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = actionError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              IconButton(onClick = onDismissError) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "Dismiss error",
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }
        }
      }

      item {
        Card(
          modifier =
            Modifier.fillMaxWidth()
              .testTag("upload_video_card")
              .clickable(enabled = !isInspecting, onClick = launchVideoPicker),
          shape = MaterialTheme.shapes.large,
          border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .background(
                  Brush.linearGradient(
                    listOf(
                      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                      MaterialTheme.colorScheme.surfaceContainer,
                      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f),
                    )
                  )
                )
                .padding(22.dp)
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                Box(
                  modifier =
                    Modifier.size(54.dp)
                      .clip(RoundedCornerShape(16.dp))
                      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                  contentAlignment = Alignment.Center,
                ) {
                  if (isInspecting) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(26.dp),
                      color = MaterialTheme.colorScheme.primary,
                      strokeWidth = 2.5.dp,
                    )
                  } else {
                    Icon(
                      imageVector = Icons.Filled.UploadFile,
                      contentDescription = "Select video file",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(28.dp),
                    )
                  }
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = if (isInspecting) "Inspecting Video Track..." else "Upload English Video",
                    style = MaterialTheme.typography.titleLarge,
                  )
                  Text(
                    text = "Select MP4, MOV, or MKV from device storage (10 min – 2 hours supported).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                MiniMetaChip(icon = Icons.Filled.VideoFile, label = "MP4 / MOV / MKV")
                MiniMetaChip(icon = Icons.Filled.AutoAwesome, label = "Background Chunked AI")
                MiniMetaChip(
                  icon = Icons.Filled.CloudQueue,
                  label =
                    if (settings.processingMode == ProcessingMode.FFMPEG_BACKEND)
                      "Server FFmpeg Burn-in"
                    else "On-Device Media3 Burn-in",
                )
              }
            }
          }
        }
      }

      if (selectedDraft != null) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
          ) {
            Column(
              modifier = Modifier.padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Ready to Process",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
                IconButton(
                  onClick = onClearDraft,
                  modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                  Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear selection")
                }
              }

              OutlinedTextField(
                value = selectedDraft.customTitle,
                onValueChange = onDraftTitleChanged,
                label = { Text("Project Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("project_title_input"),
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                MiniMetaChip(
                  icon = Icons.Filled.VideoFile,
                  label = selectedDraft.metadata.displayName,
                )
                MiniMetaChip(
                  icon = Icons.Filled.Memory,
                  label =
                    "${SrtCodec.formatDisplayTimestamp(selectedDraft.metadata.durationMs)} • ${formatFileSize(selectedDraft.metadata.sizeBytes)}",
                )
              }

              Button(
                onClick = startWithNotificationPermission,
                modifier =
                  Modifier.fillMaxWidth()
                    .height(52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("start_generation_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = MaterialTheme.shapes.medium,
              ) {
                Icon(imageVector = Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Generate Bengali Subtitles",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
      }

      if (projectsState is UiState.Success) {
        val activeJobs = projectsState.data.filter { it.statusEnum.isInProgress }
        val recentProjects = projectsState.data.filterNot { it.statusEnum.isInProgress }.take(5)

        if (activeJobs.isNotEmpty()) {
          item {
            Text(
              text = "Active Processing Queue",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
          items(activeJobs, key = { "active_${it.id}" }) { project ->
            ProcessingProgressCard(
              project = project,
              onRetry = { onRetryProject(project) },
              modifier = Modifier.clickable { onOpenProject(project.id) },
            )
          }
        }

        if (recentProjects.isNotEmpty()) {
          item {
            Text(
              text = "Recent Subtitle Projects",
              style = MaterialTheme.typography.titleMedium,
            )
          }
          items(recentProjects, key = { "recent_${it.id}" }) { project ->
            ProjectHistoryCard(
              project = project,
              onOpen = { onOpenProject(project.id) },
              onRetry = { onRetryProject(project) },
              onDelete = { onDeleteProject(project.id) },
            )
          }
        }
      } else if (projectsState is UiState.Loading) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          }
        }
      } else if (projectsState is UiState.Error) {
        item {
          Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
          ) {
            Text(
              text = projectsState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
          }
        }
      }
    }
  }
}
