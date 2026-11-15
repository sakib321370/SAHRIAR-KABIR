package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleCue
import com.example.data.model.SubtitleProject
import com.example.data.repository.AppSettings
import com.example.media.SrtCodec
import com.example.ui.components.ProcessingProgressCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NotoSansBengaliFontFamily
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.delay

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
  projectState: UiState<SubtitleProject?>,
  cues: List<SubtitleCue>,
  settings: AppSettings,
  feedbackMessage: String?,
  isSaving: Boolean,
  onBack: () -> Unit,
  onClearFeedback: () -> Unit,
  onUpdateCue: (cueId: String, bengali: String, english: String, startMs: Long, endMs: Long) -> Unit,
  onNudgeCue: (cueId: String, deltaStart: Long, deltaEnd: Long) -> Unit,
  onAddCue: (startMs: Long, endMs: Long, english: String, bengali: String) -> Unit,
  onDeleteCue: (cueId: String) -> Unit,
  onDownloadSrt: (title: String) -> Unit,
  onBuildShareSrtUri: (title: String) -> Uri?,
  onStartBurnInExport: () -> Unit,
  onRetryProcessing: (SubtitleProject) -> Unit,
  onDismissProjectError: (SubtitleProject) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  var editingCue by remember { mutableStateOf<SubtitleCue?>(null) }
  var selectedTab by remember { mutableIntStateOf(0) }
  var previewExportedMp4 by remember { mutableStateOf(false) }

  LaunchedEffect(feedbackMessage) {
    if (feedbackMessage != null) {
      snackbarHostState.showSnackbar(feedbackMessage)
      onClearFeedback()
    }
  }

  val project = (projectState as? UiState.Success)?.data
  val videoUriString =
    if (previewExportedMp4 && !project?.exportedMp4Path.isNullOrBlank()) {
      project?.exportedMp4Path
    } else {
      project?.localVideoUri
    }

  val exoPlayer =
    remember(videoUriString) {
      if (!videoUriString.isNullOrBlank()) {
        ExoPlayer.Builder(context).build().apply {
          setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))
          prepare()
        }
      } else {
        null
      }
    }

  DisposableEffect(exoPlayer) { onDispose { exoPlayer?.release() } }

  var isPlaying by remember(exoPlayer) { mutableStateOf(false) }
  var currentPositionMs by remember(exoPlayer) { mutableLongStateOf(0L) }
  var totalDurationMs by remember(exoPlayer) { mutableLongStateOf(project?.durationMs ?: 0L) }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }
      }
    exoPlayer?.addListener(listener)
    onDispose { exoPlayer?.removeListener(listener) }
  }

  LaunchedEffect(exoPlayer, isPlaying) {
    while (exoPlayer != null) {
      currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      val dur = exoPlayer.duration
      if (dur > 0L) totalDurationMs = dur
      delay(if (isPlaying) 120L else 400L)
    }
  }

  val activeCue =
    remember(cues, currentPositionMs) {
      cues.firstOrNull { currentPositionMs in it.startMs..it.endMs }
    }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("editor_back_button").minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
        title = {
          Column {
            Text(
              text = project?.title ?: "Subtitle Studio",
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (isSaving) {
              Text(
                text = "Saving to cloud...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        },
        actions = {
          if (project != null) {
            StatusBadge(status = project.statusEnum, modifier = Modifier.padding(end = 12.dp))
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      when (projectState) {
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
              text = projectState.message,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        is UiState.Success -> {
          if (project == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text("Project not found.", style = MaterialTheme.typography.titleMedium)
            }
          } else {
            val listState = rememberLazyListState()

            LaunchedEffect(activeCue?.id, isPlaying) {
              if (isPlaying && activeCue != null) {
                val activeIdx = cues.indexOfFirst { it.id == activeCue.id }
                if (activeIdx >= 0) {
                  listState.animateScrollToItem(activeIdx)
                }
              }
            }

            Column(modifier = Modifier.widthIn(max = 820.dp).fillMaxSize()) {
              // Video Preview + Real-time Bengali Subtitle Overlay
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black)
              ) {
                if (exoPlayer != null) {
                  AndroidView(
                    factory = { ctx ->
                      PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                      }
                    },
                    update = { view -> view.player = exoPlayer },
                    modifier = Modifier.fillMaxSize(),
                  )
                } else {
                  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                      text = "Local video source unavailable for preview",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }

                // Live Subtitle Overlay when previewing original video
                if (!previewExportedMp4 && activeCue != null) {
                  Surface(
                    modifier =
                      Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xCC090D16),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                  ) {
                    Column(
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                      Text(
                        text = activeCue.bengaliText.ifBlank { activeCue.englishText },
                        fontFamily = NotoSansBengaliFontFamily,
                        fontSize = (settings.subtitleFontSizeSp * 0.85f).sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                      )
                      if (settings.showEnglishDualLine && activeCue.englishText.isNotBlank()) {
                        Text(
                          text = activeCue.englishText,
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          textAlign = TextAlign.Center,
                        )
                      }
                    }
                  }
                }
              }

              // Video Transport Controls
              if (exoPlayer != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                  val safeTotal = totalDurationMs.coerceAtLeast(1_000L)
                  Slider(
                    value = currentPositionMs.toFloat().coerceIn(0f, safeTotal.toFloat()),
                    onValueChange = { pos ->
                      currentPositionMs = pos.toLong()
                      exoPlayer.seekTo(pos.toLong())
                    },
                    valueRange = 0f..safeTotal.toFloat(),
                  )
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = SrtCodec.formatDisplayTimestamp(currentPositionMs),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      IconButton(
                        onClick = { exoPlayer.seekTo((currentPositionMs - 5_000L).coerceAtLeast(0L)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                      ) {
                        Icon(Icons.Filled.Replay5, contentDescription = "Rewind 5 seconds")
                      }
                      Surface(
                        onClick = {
                          if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp).testTag("player_play_pause_button"),
                      ) {
                        Box(contentAlignment = Alignment.Center) {
                          Icon(
                            imageVector =
                              if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                          )
                        }
                      }
                      IconButton(
                        onClick = { exoPlayer.seekTo(currentPositionMs + 5_000L) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                      ) {
                        Icon(Icons.Filled.Forward5, contentDescription = "Forward 5 seconds")
                      }
                    }
                    Text(
                      text = SrtCodec.formatDisplayTimestamp(safeTotal),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }

              // Processing / Exporting Banner or Export Controls
              if (project.statusEnum.isInProgress || project.statusEnum == ProjectStatus.FAILED) {
                ProcessingProgressCard(
                  project = project,
                  onRetry = { onRetryProcessing(project) },
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
              } else {
                Column(
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (!project.errorMessage.isNullOrBlank()) {
                    Surface(
                      shape = MaterialTheme.shapes.small,
                      color = MaterialTheme.colorScheme.errorContainer,
                      border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    ) {
                      Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        Text(
                          text = project.errorMessage,
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onErrorContainer,
                          modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDismissProjectError(project) }) {
                          Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                      }
                    }
                  }
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    OutlinedButton(
                      onClick = { onDownloadSrt(project.title) },
                      enabled = cues.isNotEmpty(),
                      modifier =
                        Modifier.weight(1f)
                          .minimumInteractiveComponentSize()
                          .testTag("download_srt_button"),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Save SRT", maxLines = 1)
                    }

                    OutlinedButton(
                      onClick = {
                        val uri = onBuildShareSrtUri(project.title)
                        if (uri != null) {
                          val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                              type = "application/x-subrip"
                              putExtra(Intent.EXTRA_STREAM, uri)
                              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                          context.startActivity(Intent.createChooser(shareIntent, "Share Bengali SRT"))
                        }
                      },
                      enabled = cues.isNotEmpty(),
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("share_srt_button"),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share SRT",
                        modifier = Modifier.size(16.dp),
                      )
                    }

                    Button(
                      onClick = onStartBurnInExport,
                      enabled = cues.isNotEmpty() && !project.localVideoUri.isNullOrBlank(),
                      modifier =
                        Modifier.weight(1.3f)
                          .minimumInteractiveComponentSize()
                          .testTag("burn_mp4_button"),
                      colors =
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.MovieCreation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Burn MP4", maxLines = 1)
                    }
                  }

                  if (!project.exportedMp4Path.isNullOrBlank()) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      FilterChip(
                        selected = previewExportedMp4,
                        onClick = { previewExportedMp4 = !previewExportedMp4 },
                        label = {
                          Text(
                            if (previewExportedMp4) "Watching Burned MP4"
                            else "Preview Burned MP4"
                          )
                        },
                      )
                      FilledTonalButton(
                        onClick = {
                          val mp4Uri = Uri.parse(project.exportedMp4Path)
                          val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                              type = "video/mp4"
                              putExtra(Intent.EXTRA_STREAM, mp4Uri)
                              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                          context.startActivity(
                            Intent.createChooser(shareIntent, "Share Subtitled MP4")
                          )
                        },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                      ) {
                        Icon(
                          imageVector = Icons.Filled.Share,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share MP4")
                      }
                    }
                  }
                }
              }

              // Tab Selector: Bengali Cue Editor vs Raw SRT
              TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
              ) {
                Tab(
                  selected = selectedTab == 0,
                  onClick = { selectedTab = 0 },
                  text = { Text("Bengali Cues (${cues.size})") },
                )
                Tab(
                  selected = selectedTab == 1,
                  onClick = { selectedTab = 1 },
                  text = { Text("SRT Preview") },
                )
              }

              if (selectedTab == 0) {
                if (cues.isEmpty()) {
                  Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                      Text(
                        text =
                          if (project.statusEnum.isInProgress)
                            "Generating synchronized Bengali subtitles..."
                          else "No subtitle cues yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                      if (!project.statusEnum.isInProgress) {
                        OutlinedButton(
                          onClick = {
                            onAddCue(
                              currentPositionMs,
                              currentPositionMs + 3_000L,
                              "",
                              "নতুন বাংলা সাবটাইটেল",
                            )
                          }
                        ) {
                          Icon(Icons.Filled.Add, contentDescription = null)
                          Spacer(modifier = Modifier.width(6.dp))
                          Text("Add Manual Cue")
                        }
                      }
                    }
                  }
                } else {
                  LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    items(cues, key = { it.id }) { cue ->
                      val isActive = activeCue?.id == cue.id
                      CueEditorCard(
                        cue = cue,
                        isActive = isActive,
                        onSeek = {
                          exoPlayer?.seekTo(cue.startMs)
                          currentPositionMs = cue.startMs
                        },
                        onEdit = { editingCue = cue },
                        onNudge = { dStart, dEnd -> onNudgeCue(cue.id, dStart, dEnd) },
                        onDelete = { onDeleteCue(cue.id) },
                      )
                    }
                    item {
                      OutlinedButton(
                        onClick = {
                          val start = cues.lastOrNull()?.endMs?.plus(500L) ?: currentPositionMs
                          onAddCue(start, start + 3_000L, "", "নতুন বাংলা সাবটাইটেল")
                        },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                      ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Subtitle Cue")
                      }
                    }
                  }
                }
              } else {
                val srtText =
                  remember(cues, settings.showEnglishDualLine) {
                    SrtCodec.toSrt(cues, includeEnglishLine = settings.showEnglishDualLine)
                  }
                Column(
                  modifier =
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                ) {
                  Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = srtText.ifBlank { "# SRT will appear once subtitles are generated." },
                      fontFamily = JetBrainsMonoFontFamily,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.padding(16.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  editingCue?.let { cue ->
    CueEditDialog(
      cue = cue,
      onDismiss = { editingCue = null },
      onSave = { bn, en, start, end ->
        onUpdateCue(cue.id, bn, en, start, end)
        editingCue = null
      },
    )
  }
}

@Composable
private fun CueEditorCard(
  cue: SubtitleCue,
  isActive: Boolean,
  onSeek: () -> Unit,
  onEdit: () -> Unit,
  onNudge: (Long, Long) -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onSeek),
    shape = MaterialTheme.shapes.medium,
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
          else MaterialTheme.colorScheme.surfaceContainer
      ),
    border =
      BorderStroke(
        width = if (isActive) 1.5.dp else 1.dp,
        color =
          if (isActive) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            "#${cue.index}  ${SrtCodec.formatSrtTimestamp(cue.startMs)} → ${SrtCodec.formatSrtTimestamp(cue.endMs)}",
          fontFamily = JetBrainsMonoFontFamily,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
          IconButton(
            onClick = onEdit,
            modifier =
              Modifier.testTag("edit_cue_${cue.index}").minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Filled.Edit,
              contentDescription = "Edit Bengali text",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onDelete,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Filled.DeleteOutline,
              contentDescription = "Delete cue",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      Text(
        text = cue.bengaliText,
        fontFamily = NotoSansBengaliFontFamily,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      if (cue.englishText.isNotBlank()) {
        Text(
          text = cue.englishText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = { onNudge(-250L, -250L) }) { Text("-0.25s") }
        TextButton(onClick = { onNudge(250L, 250L) }) { Text("+0.25s") }
      }
    }
  }
}

@Composable
private fun CueEditDialog(
  cue: SubtitleCue,
  onDismiss: () -> Unit,
  onSave: (bengali: String, english: String, startMs: Long, endMs: Long) -> Unit,
) {
  var bnText by remember(cue.id) { mutableStateOf(cue.bengaliText) }
  var enText by remember(cue.id) { mutableStateOf(cue.englishText) }
  var startMs by remember(cue.id) { mutableLongStateOf(cue.startMs) }
  var endMs by remember(cue.id) { mutableLongStateOf(cue.endMs) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit Bengali Subtitle #${cue.index}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = bnText,
          onValueChange = { bnText = it },
          label = { Text("Bengali Text (বাংলা)") },
          textStyle =
            MaterialTheme.typography.bodyLarge.copy(fontFamily = NotoSansBengaliFontFamily),
          minLines = 2,
          modifier = Modifier.fillMaxWidth().testTag("cue_bengali_input"),
        )
        OutlinedTextField(
          value = enText,
          onValueChange = { enText = it },
          label = { Text("English Source") },
          minLines = 2,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text("Start", style = MaterialTheme.typography.labelSmall)
            Text(
              text = SrtCodec.formatSrtTimestamp(startMs),
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelMedium,
            )
            Row {
              TextButton(onClick = { startMs = (startMs - 1_000L).coerceAtLeast(0L) }) {
                Text("-1s")
              }
              TextButton(onClick = { startMs = (startMs - 250L).coerceAtLeast(0L) }) {
                Text("-250ms")
              }
              TextButton(onClick = { startMs = (startMs + 250L).coerceAtMost(endMs - 300L) }) {
                Text("+250ms")
              }
            }
          }
          Column {
            Text("End", style = MaterialTheme.typography.labelSmall)
            Text(
              text = SrtCodec.formatSrtTimestamp(endMs),
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelMedium,
            )
            Row {
              TextButton(onClick = { endMs = (endMs - 250L).coerceAtLeast(startMs + 300L) }) {
                Text("-250ms")
              }
              TextButton(onClick = { endMs += 250L }) { Text("+250ms") }
              TextButton(onClick = { endMs += 1_000L }) { Text("+1s") }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(bnText, enText, startMs, endMs) },
        modifier = Modifier.testTag("save_cue_edit_button"),
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
