package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.ProcessingMode
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleCue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun createAndObserveProject_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = ProjectRepository(firestore, auth)

    val projectId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo
          .createProject(
            title = "AI Lecture Demo",
            fileName = "lecture.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 52_428_800L,
            durationMs = 600_000L,
            processingMode = ProcessingMode.CLOUD_AI,
            localVideoUri = "content://media/external/video/101",
          )
          .getOrThrow()
      }

    assertTrue(projectId.isNotEmpty())

    val projects =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeProjects(uid).first { list -> list.any { it.id == projectId } }
      }
    assertTrue(projects.any { it.id == projectId && it.title == "AI Lecture Demo" })

    val updateResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.updateProjectProgress(
          projectId = projectId,
          status = ProjectStatus.READY,
          progressPercent = 100,
          cueCount = 2,
        )
      }
    assertTrue(updateResult.isSuccess)
  }

  @Test
  fun saveAndObserveCues_authenticatedOwner_persistsBengaliText() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = ProjectRepository(firestore, auth)

    val projectId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo
          .createProject(
            title = "Documentary",
            fileName = "doc.mkv",
            mimeType = "video/x-matroska",
            fileSizeBytes = 120_000_000L,
            durationMs = 1_800_000L,
            processingMode = ProcessingMode.CLOUD_AI,
            localVideoUri = null,
          )
          .getOrThrow()
      }

    val cues =
      listOf(
        SubtitleCue(
          id = "cue_1",
          index = 1,
          startMs = 1_000L,
          endMs = 4_500L,
          englishText = "Welcome to Dhaka.",
          bengaliText = "ঢাকায় স্বাগতম।",
        ),
        SubtitleCue(
          id = "cue_2",
          index = 2,
          startMs = 5_000L,
          endMs = 9_000L,
          englishText = "Let us begin the journey.",
          bengaliText = "চলুন আমাদের যাত্রা শুরু করি।",
        ),
      )

    val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.saveCuePages(projectId, cues) }
    assertTrue(saveResult.isSuccess)

    val observed =
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeCues(uid, projectId).first { it.size == 2 }
      }
    assertEquals("ঢাকায় স্বাগতম।", observed[0].bengaliText)

    // Update cue text
    val edited = observed.mapIndexed { i, c -> if (i == 0) c.copy(bengaliText = "ঢাকায় আপনাদের স্বাগতম।") else c }
    val editResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.saveCuePages(projectId, edited) }
    assertTrue(editResult.isSuccess)

    val fetched = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getCuesOnce(projectId).getOrThrow() }
    assertEquals("ঢাকায় আপনাদের স্বাগতম।", fetched[0].bengaliText)
  }

  @Test
  fun crossUserAccess_readingAnotherUsersProjects_denied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = ProjectRepository(firestore, auth)
    val projectId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRepo
          .createProject(
            title = "Private Film",
            fileName = "film.mov",
            mimeType = "video/quicktime",
            fileSizeBytes = 10_000L,
            durationMs = 60_000L,
            processingMode = ProcessingMode.CLOUD_AI,
            localVideoUri = null,
          )
          .getOrThrow()
      }

    signInTestUser(BOB_EMAIL)
    val bobRepo = ProjectRepository(firestore, auth)

    var thrown: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { bobRepo.observeProject(aliceUid, projectId).first() }
    } catch (e: Throwable) {
      thrown = e
    }
    val firestoreException = thrown?.unwrapFirestoreException()
    assertNotNull("Expected FirebaseFirestoreException for cross-user read", firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
  }

  @Test
  fun unauthenticatedObserver_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = ProjectRepository(firestore, auth)

    var thrown: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeProjects("alice_fake_uid").first() }
    } catch (e: Throwable) {
      thrown = e
    }
    val firestoreException = thrown?.unwrapFirestoreException()
    assertNotNull("Expected FirebaseFirestoreException for unauthenticated read", firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
  }

  private companion object {
    const val ALICE_EMAIL = "alice_banglasub@test.com"
    const val BOB_EMAIL = "bob_banglasub@test.com"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 4000L
  }
}
