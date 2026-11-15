package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ProcessingMode
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleProject
import com.example.media.SrtCodec
import java.util.Locale

fun formatFileSize(bytes: Long): String {
  if (bytes <= 0L) return "Unknown size"
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  val gb = mb / 1024.0
  return when {
    gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
    mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
    else -> String.format(Locale.US, "%.0f KB", kb)
  }
}

@Composable
fun StatusBadge(status: ProjectStatus, modifier: Modifier = Modifier) {
  val (bg, fg, icon) =
    when (status) {
      ProjectStatus.READY, ProjectStatus.EXPORTED ->
        Triple(
          MaterialTheme.colorScheme.primaryContainer,
          MaterialTheme.colorScheme.onPrimaryContainer,
          Icons.Filled.CheckCircle,
        )
      ProjectStatus.FAILED ->
        Triple(
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
          Icons.Filled.ErrorOutline,
        )
      ProjectStatus.TRANSLATING, ProjectStatus.TRANSCRIBING ->
        Triple(
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
          Icons.Filled.Translate,
        )
      ProjectStatus.UPLOADING, ProjectStatus.EXPORTING ->
        Triple(
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
          Icons.Filled.CloudUpload,
        )
      else ->
        Triple(
          MaterialTheme.colorScheme.surfaceContainerHigh,
          MaterialTheme.colorScheme.tertiary,
          Icons.Filled.GraphicEq,
        )
    }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(999.dp),
    color = bg,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = fg,
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
fun ProcessingProgressCard(
  project: SubtitleProject,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val status = project.statusEnum
  Card(
    modifier = modifier.fillMaxWidth().animateContentSize(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = project.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text =
              "${project.fileName} • ${SrtCodec.formatDisplayTimestamp(project.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusBadge(status = status)
      }

      if (status.isInProgress) {
        val progress = (project.progressPercent.toFloat() / 100f).coerceIn(0.02f, 1f)
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          strokeCap = StrokeCap.Round,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stageHint(status, project.modeEnum),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${project.progressPercent}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      } else if (status == ProjectStatus.FAILED) {
        Text(
          text = project.errorMessage ?: "Processing interrupted. You can retry safely.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Button(
          onClick = onRetry,
          colors =
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          modifier = Modifier.testTag("retry_processing_button").minimumInteractiveComponentSize(),
        ) {
          Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Retry Processing")
        }
      }
    }
  }
}

private fun stageHint(status: ProjectStatus, mode: ProcessingMode): String =
  when (status) {
    ProjectStatus.QUEUED -> "Preparing background worker..."
    ProjectStatus.EXTRACTING -> "Extracting audio track into chunks..."
    ProjectStatus.UPLOADING -> "Uploading 5MB chunks to FFmpeg server..."
    ProjectStatus.TRANSCRIBING -> "AI transcribing English speech..."
    ProjectStatus.TRANSLATING -> "Translating to natural Bengali..."
    ProjectStatus.EXPORTING ->
      if (mode == ProcessingMode.FFMPEG_BACKEND) "FFmpeg burning Bengali subtitles..."
      else "Media3 burning Bengali subtitles into MP4..."
    else -> status.label
  }

@Composable
fun ProjectHistoryCard(
  project: SubtitleProject,
  onOpen: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("project_card_${project.id}")
        .clickable(onClick = onOpen),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier =
              Modifier.size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Movie,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = project.title,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                "${SrtCodec.formatDisplayTimestamp(project.durationMs)} • ${formatFileSize(project.fileSizeBytes)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        StatusBadge(status = project.statusEnum)
      }

      if (project.statusEnum.isInProgress) {
        LinearProgressIndicator(
          progress = { (project.progressPercent.toFloat() / 100f).coerceIn(0.03f, 1f) },
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          strokeCap = StrokeCap.Round,
        )
      }

      if (project.statusEnum == ProjectStatus.FAILED && !project.errorMessage.isNullOrBlank()) {
        Text(
          text = project.errorMessage,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          MiniMetaChip(
            icon = Icons.Filled.Subtitles,
            label = "${project.cueCount} cues",
          )
          MiniMetaChip(
            icon = Icons.Filled.Translate,
            label =
              if (project.modeEnum == ProcessingMode.FFMPEG_BACKEND) "FFmpeg" else "Cloud AI",
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (project.statusEnum == ProjectStatus.FAILED) {
            IconButton(
              onClick = onRetry,
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Retry processing",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          }
          IconButton(
            onClick = onDelete,
            modifier =
              Modifier.testTag("delete_project_${project.id}").minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Filled.DeleteOutline,
              contentDescription = "Delete project",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Box(
            modifier =
              Modifier.size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = "Open editor",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
fun MiniMetaChip(
  icon: ImageVector,
  label: String,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
