package com.example.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.data.repository.AppSettings
import com.example.data.repository.ProjectRepository
import com.example.data.repository.SettingsRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.viewmodel.EditorViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.StudioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.serialization.Serializable

@Serializable data object StudioRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data class EditorRoute(val projectId: String)

private data class TopTab(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
)

private val TOP_TABS =
  listOf(
    TopTab(StudioRoute, "Studio", Icons.Filled.MovieCreation, Icons.Outlined.MovieCreation),
    TopTab(HistoryRoute, "History", Icons.Filled.History, Icons.Outlined.History),
    TopTab(SettingsRoute, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
  )

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
  val context = LocalContext.current
  val credentialManager = remember { CredentialManager.create(context) }
  val currentUser by
    auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

  LaunchedEffect(Unit) {
    if (auth.currentUser == null) {
      attemptAutoSignIn(
        context = context,
        credentialManager = credentialManager,
        onAuthSuccess = {},
        onUnauthenticated = {},
        scope = this,
      )
    }
  }

  val user = currentUser
  if (user == null) {
    AuthScreen(onAuthSuccess = {})
  } else {
    val uid = user.uid
    key(uid) { AuthenticatedStudioShell(currentUserId = uid) }
  }
}

@Composable
private fun AuthenticatedStudioShell(currentUserId: String) {
  val context = LocalContext.current
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDest = navBackStackEntry?.destination

  val studioViewModel: StudioViewModel =
    viewModel(
      key = "studio_$currentUserId",
      factory =
        viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY]) as Application
            val dbId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            StudioViewModel(ProjectRepository(db), currentUserId)
          }
        },
    )

  val settingsViewModel: SettingsViewModel =
    viewModel(
      key = "settings_$currentUserId",
      factory =
        viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY]) as Application
            SettingsViewModel(SettingsRepository(app))
          }
        },
    )

  val projectsState by studioViewModel.projectsState.collectAsStateWithLifecycle()
  val selectedDraft by studioViewModel.selectedDraft.collectAsStateWithLifecycle()
  val isInspecting by studioViewModel.isInspecting.collectAsStateWithLifecycle()
  val actionError by studioViewModel.actionError.collectAsStateWithLifecycle()
  val settings by settingsViewModel.settingsState.collectAsStateWithLifecycle()
  val healthState by settingsViewModel.healthState.collectAsStateWithLifecycle()

  val isEditorScreen = currentDest?.hasRoute<EditorRoute>() == true

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavRail = maxWidth >= 700.dp

    val navigateTab: (Any) -> Unit = { route ->
      navController.navigate(route) {
        popUpTo(StudioRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    }

    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (!useNavRail && !isEditorScreen) {
          NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            TOP_TABS.forEach { tab ->
              val selected =
                when (tab.route) {
                  StudioRoute -> currentDest?.hasRoute<StudioRoute>() == true
                  HistoryRoute -> currentDest?.hasRoute<HistoryRoute>() == true
                  SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                  else -> false
                }
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTab(tab.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                  )
                },
                label = { Text(tab.label) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useNavRail && !isEditorScreen) {
          NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            TOP_TABS.forEach { tab ->
              val selected =
                when (tab.route) {
                  StudioRoute -> currentDest?.hasRoute<StudioRoute>() == true
                  HistoryRoute -> currentDest?.hasRoute<HistoryRoute>() == true
                  SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                  else -> false
                }
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTab(tab.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                  )
                },
                label = { Text(tab.label) },
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = StudioRoute,
          modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
          composable<StudioRoute> {
            StudioScreen(
              projectsState = projectsState,
              selectedDraft = selectedDraft,
              isInspecting = isInspecting,
              actionError = actionError,
              settings = settings,
              onVideoPicked = { uri -> studioViewModel.inspectVideo(context, uri) },
              onDraftTitleChanged = studioViewModel::updateDraftTitle,
              onClearDraft = studioViewModel::clearSelectedDraft,
              onStartGeneration = {
                studioViewModel.startGeneration(
                  context = context,
                  mode = settings.processingMode,
                  backendBaseUrl = settings.backendBaseUrl,
                ) { projectId ->
                  navController.navigate(EditorRoute(projectId))
                }
              },
              onOpenProject = { projectId -> navController.navigate(EditorRoute(projectId)) },
              onRetryProject = { proj -> studioViewModel.retryProject(context, proj) },
              onDeleteProject = studioViewModel::deleteProject,
              onOpenSettings = { navigateTab(SettingsRoute) },
              onDismissError = studioViewModel::clearError,
            )
          }

          composable<HistoryRoute> {
            HistoryScreen(
              projectsState = projectsState,
              onOpenProject = { projectId -> navController.navigate(EditorRoute(projectId)) },
              onRetryProject = { proj -> studioViewModel.retryProject(context, proj) },
              onDeleteProject = studioViewModel::deleteProject,
              onGoToStudio = { navigateTab(StudioRoute) },
            )
          }

          composable<SettingsRoute> {
            SettingsScreen(
              settings = settings,
              healthState = healthState,
              onSelectMode = settingsViewModel::setProcessingMode,
              onUpdateBackendUrl = settingsViewModel::setBackendBaseUrl,
              onTestBackend = settingsViewModel::testBackendConnection,
              onUpdateFontSize = settingsViewModel::setSubtitleFontSize,
              onToggleDualLine = settingsViewModel::setShowEnglishDualLine,
            )
          }

          composable<EditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditorRoute>()
            val editorViewModel: EditorViewModel =
              viewModel(
                key = "editor_${currentUserId}_${route.projectId}",
                factory =
                  viewModelFactory {
                    initializer {
                      val app = checkNotNull(this[APPLICATION_KEY]) as Application
                      val dbId = app.getString(R.string.firestore_database_id)
                      val db = FirebaseFirestore.getInstance(dbId)
                      EditorViewModel(ProjectRepository(db), currentUserId, route.projectId)
                    }
                  },
              )

            val projectState by editorViewModel.projectState.collectAsStateWithLifecycle()
            val cues by editorViewModel.cuesState.collectAsStateWithLifecycle()
            val feedback by editorViewModel.feedbackMessage.collectAsStateWithLifecycle()
            val isSaving by editorViewModel.isSaving.collectAsStateWithLifecycle()

            EditorScreen(
              projectState = projectState,
              cues = cues,
              settings = settings,
              feedbackMessage = feedback,
              isSaving = isSaving,
              onBack = { navController.popBackStack() },
              onClearFeedback = editorViewModel::clearFeedback,
              onUpdateCue = editorViewModel::updateCue,
              onNudgeCue = editorViewModel::nudgeCue,
              onAddCue = editorViewModel::addCue,
              onDeleteCue = editorViewModel::deleteCue,
              onDownloadSrt = { title ->
                editorViewModel.downloadSrt(context, title, settings.showEnglishDualLine)
              },
              onBuildShareSrtUri = { title ->
                editorViewModel.buildShareSrtUri(context, title, settings.showEnglishDualLine)
              },
              onStartBurnInExport = { editorViewModel.startMp4BurnInExport(context) },
              onRetryProcessing = { proj -> editorViewModel.retryProcessing(context, proj) },
              onDismissProjectError = { proj -> editorViewModel.clearProjectError(proj) },
            )
          }
        }
      }
    }
  }
}
