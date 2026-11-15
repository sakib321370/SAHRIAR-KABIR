package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleProject
import com.example.ui.components.ProjectHistoryCard
import com.example.ui.viewmodel.UiState

private enum class HistoryFilter(val label: String) {
  ALL("All"),
  READY("Ready"),
  ACTIVE("Processing"),
  EXPORTED("Exported"),
  FAILED("Failed"),
}

@Composable
fun HistoryScreen(
  projectsState: UiState<List<SubtitleProject>>,
  onOpenProject: (String) -> Unit,
  onRetryProject: (SubtitleProject) -> Unit,
  onDeleteProject: (String) -> Unit,
  onGoToStudio: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
  var pendingDelete by remember { mutableStateOf<SubtitleProject?>(null) }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
      Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Processing History",
          style = MaterialTheme.typography.headlineLarge,
        )
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HistoryFilter.entries.forEach { filter ->
            FilterChip(
              selected = selectedFilter == filter,
              onClick = { selectedFilter = filter },
              label = { Text(filter.label) },
              modifier = Modifier.testTag("history_filter_${filter.name.lowercase()}"),
            )
          }
        }
      }

      when (projectsState) {
        is UiState.Loading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          }
        }
        is UiState.Error -> {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = projectsState.message,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        is UiState.Success -> {
          val filtered =
            projectsState.data.filter { project ->
              when (selectedFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.READY -> project.statusEnum == ProjectStatus.READY
                HistoryFilter.ACTIVE -> project.statusEnum.isInProgress
                HistoryFilter.EXPORTED -> project.statusEnum == ProjectStatus.EXPORTED
                HistoryFilter.FAILED -> project.statusEnum == ProjectStatus.FAILED
              }
            }

          if (filtered.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Card(
                shape = MaterialTheme.shapes.large,
                colors =
                  CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              ) {
                Column(
                  modifier = Modifier.padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                  Image(
                    painter = painterResource(id = R.drawable.img_studio_hero),
                    contentDescription = "No subtitle projects yet",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                  )
                  Text(
                    text =
                      if (projectsState.data.isEmpty()) "No videos processed yet"
                      else "No projects in ${selectedFilter.label}",
                    style = MaterialTheme.typography.titleLarge,
                  )
                  Text(
                    text =
                      "Upload an English MP4, MOV, or MKV video in Studio to generate synchronized Bengali subtitles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Button(onClick = onGoToStudio) { Text("Upload Video") }
                }
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(filtered, key = { it.id }) { project ->
                ProjectHistoryCard(
                  project = project,
                  onOpen = { onOpenProject(project.id) },
                  onRetry = { onRetryProject(project) },
                  onDelete = { pendingDelete = project },
                )
              }
            }
          }
        }
      }
    }
  }

  pendingDelete?.let { project ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete Subtitle Project?") },
      text = { Text("Remove \"${project.title}\" and its Bengali subtitle history?") },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteProject(project.id)
            pendingDelete = null
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
      },
    )
  }
}
