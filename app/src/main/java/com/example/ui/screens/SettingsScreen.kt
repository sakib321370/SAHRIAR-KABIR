package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.auth.signOutUser
import com.example.data.model.ProcessingMode
import com.example.data.repository.AppSettings
import com.example.ui.theme.NotoSansBengaliFontFamily
import com.example.ui.viewmodel.BackendHealthState
import com.google.firebase.auth.FirebaseAuth
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
  settings: AppSettings,
  healthState: BackendHealthState,
  onSelectMode: (ProcessingMode) -> Unit,
  onUpdateBackendUrl: (String) -> Unit,
  onTestBackend: (String) -> Unit,
  onUpdateFontSize: (Float) -> Unit,
  onToggleDualLine: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember { CredentialManager.create(context) }
  val user = remember { FirebaseAuth.getInstance().currentUser }
  var urlDraft by rememberSaveable { mutableStateOf(settings.backendBaseUrl) }
  LaunchedEffect(settings.backendBaseUrl) {
    if (urlDraft.isEmpty() && settings.backendBaseUrl.isNotEmpty()) {
      urlDraft = settings.backendBaseUrl
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Text(
          text = "Studio Settings",
          style = MaterialTheme.typography.headlineLarge,
        )
      }

      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
              )
              Column {
                Text(
                  text = user?.displayName ?: "Signed-in Creator",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = user?.email ?: "Synced with Cloud Firestore",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            OutlinedButton(
              onClick = { signOutUser(credentialManager, scope) },
              modifier = Modifier.testTag("sign_out_button").minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Sign Out")
            }
          }
        }
      }

      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "Processing Architecture",
              style = MaterialTheme.typography.titleMedium,
            )

            EngineOptionRow(
              selected = settings.processingMode == ProcessingMode.CLOUD_AI,
              icon = Icons.Filled.Memory,
              title = "Built-in Cloud AI + Media3 (Recommended)",
              subtitle =
                "Extracts & chunks audio on-device via Media3, transcribes & translates via Firebase AI Logic (zero keys in APK), and burns MP4 locally.",
              onClick = { onSelectMode(ProcessingMode.CLOUD_AI) },
            )

            EngineOptionRow(
              selected = settings.processingMode == ProcessingMode.FFMPEG_BACKEND,
              icon = Icons.Filled.CloudQueue,
              title = "Remote FFmpeg Server (Chunked Upload)",
              subtitle =
                "Uploads multi-GB videos in 5MB chunks to your Node.js + FFmpeg Cloud Run backend for server-side audio extraction and libass MP4 burn-in.",
              onClick = { onSelectMode(ProcessingMode.FFMPEG_BACKEND) },
            )

            if (settings.processingMode == ProcessingMode.FFMPEG_BACKEND) {
              OutlinedTextField(
                value = urlDraft,
                onValueChange = {
                  urlDraft = it
                  onUpdateBackendUrl(it)
                },
                label = { Text("FFmpeg Backend Base URL") },
                placeholder = { Text("https://banglasub-backend-xxxx.run.app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backend_url_input"),
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Button(
                  onClick = { onTestBackend(urlDraft) },
                  enabled = healthState !is BackendHealthState.Checking,
                  modifier =
                    Modifier.testTag("test_backend_button").minimumInteractiveComponentSize(),
                ) {
                  if (healthState is BackendHealthState.Checking) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(16.dp),
                      strokeWidth = 2.dp,
                      color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                  }
                  Text("Test Connection")
                }

                when (healthState) {
                  BackendHealthState.Reachable -> {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                      )
                      Text(
                        text = "FFmpeg backend online",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                  is BackendHealthState.Unreachable -> {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                      )
                      Text(
                        text = healthState.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                  else -> Unit
                }
              }
            }
          }
        }
      }

      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "Bengali Subtitle Appearance",
              style = MaterialTheme.typography.titleMedium,
            )

            Surface(
              shape = RoundedCornerShape(14.dp),
              color = Color(0xFF05070B),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = "বাংলা সাবটাইটেল প্রিভিউ — স্পষ্ট ও স্বাভাবিক অনুবাদ",
                  fontFamily = NotoSansBengaliFontFamily,
                  fontSize = settings.subtitleFontSizeSp.sp,
                  color = Color.White,
                )
                if (settings.showEnglishDualLine) {
                  Text(
                    text = "English subtitle reference line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Font Size",
                style = MaterialTheme.typography.bodyLarge,
              )
              Text(
                text = "${settings.subtitleFontSizeSp.roundToInt()} sp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
            Slider(
              value = settings.subtitleFontSizeSp,
              onValueChange = onUpdateFontSize,
              valueRange = 14f..32f,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Dual English Reference Line",
                  style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                  text = "Show original English text beneath Bengali subtitles in preview & burn-in.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.showEnglishDualLine,
                onCheckedChange = onToggleDualLine,
              )
            }
          }
        }
      }

      item {
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "Cloud & Backend Setup Status",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text =
                "• Firebase AI Logic (App Check protected) & Cloud Firestore History are active out of the box with zero API keys stored in this app.\n" +
                  "• To offload 1–2 hour 4K FFmpeg rendering to the cloud, deploy the included /backend folder to Google Cloud Run (with GEMINI_API_KEY set on the server) and paste the HTTPS URL above.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EngineOptionRow(
  selected: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.small,
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
      else MaterialTheme.colorScheme.surfaceContainerHigh,
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (selected) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(selected = selected, onClick = onClick)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
