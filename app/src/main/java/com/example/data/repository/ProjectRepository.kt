package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.CuePage
import com.example.data.model.ProcessingMode
import com.example.data.model.ProjectStatus
import com.example.data.model.SubtitleCue
import com.example.data.model.SubtitleProject
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ProjectRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
  constructor(
    context: Context
  ) : this(
    db =
      FirebaseFirestore.getInstance(
        context.applicationContext.getString(R.string.firestore_database_id)
      ),
    auth = FirebaseAuth.getInstance(),
  )

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing projects.")
  }

  private fun projectsCollection(userId: String) =
    db.collection("users").document(userId).collection("projects")

  private fun cuePagesCollection(userId: String, projectId: String) =
    projectsCollection(userId).document(projectId).collection("cuePages")

  fun observeProjects(userId: String): Flow<List<SubtitleProject>> = flow {
    val path = "users/$userId/projects"
    emitAll(
      projectsCollection(userId)
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .snapshots()
        .map { snapshot ->
          snapshot.documents
            .mapNotNull { doc ->
              doc.toObject(
                SubtitleProject::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
              )
            }
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  fun observeProject(userId: String, projectId: String): Flow<SubtitleProject?> = flow {
    val docRef = projectsCollection(userId).document(projectId)
    emitAll(
      docRef
        .snapshots()
        .map { snapshot ->
          if (!snapshot.exists()) {
            null
          } else {
            snapshot.toObject(
              SubtitleProject::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
          }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
          }
          throw error
        }
    )
  }

  fun observeCues(userId: String, projectId: String): Flow<List<SubtitleCue>> = flow {
    val path = "users/$userId/projects/$projectId/cuePages"
    emitAll(
      cuePagesCollection(userId, projectId)
        .orderBy("pageIndex", Query.Direction.ASCENDING)
        .snapshots()
        .map { snapshot ->
          snapshot.documents
            .mapNotNull { doc ->
              doc.toObject(CuePage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .sortedBy { it.pageIndex }
            .flatMap { page -> page.cues.map { SubtitleCue.fromFirestoreMap(it) } }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun createProject(
    title: String,
    fileName: String,
    mimeType: String,
    fileSizeBytes: Long,
    durationMs: Long,
    processingMode: ProcessingMode,
    localVideoUri: String?,
  ): Result<String> {
    val uid = requireUserId()
    val docRef = projectsCollection(uid).document()
    val safeTitle = title.trim().ifBlank { "Untitled Video" }.take(160)
    val safeFileName = fileName.trim().ifBlank { "video.mp4" }.take(200)
    val safeMime = mimeType.trim().ifBlank { "video/mp4" }.take(80)

    val payload =
      mutableMapOf<String, Any>(
        "userId" to uid,
        "title" to safeTitle,
        "fileName" to safeFileName,
        "mimeType" to safeMime,
        "fileSizeBytes" to fileSizeBytes.coerceAtLeast(0L),
        "durationMs" to durationMs.coerceAtLeast(0L),
        "status" to ProjectStatus.QUEUED.wireValue,
        "progressPercent" to 0L,
        "processingMode" to processingMode.wireValue,
        "cueCount" to 0L,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    if (!localVideoUri.isNullOrBlank()) {
      payload["localVideoUri"] = localVideoUri.take(1000)
    }

    return try {
      docRef.set(payload).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun getProject(projectId: String): Result<SubtitleProject?> {
    val uid = requireUserId()
    val docRef = projectsCollection(uid).document(projectId)
    return try {
      val snapshot = docRef.get().await()
      val project =
        if (snapshot.exists()) {
          snapshot.toObject(
            SubtitleProject::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        } else {
          null
        }
      Result.success(project)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateProjectProgress(
    projectId: String,
    status: ProjectStatus,
    progressPercent: Int,
    durationMs: Long? = null,
    cueCount: Int? = null,
    remoteJobId: String? = null,
    errorMessage: String? = null,
  ): Result<Unit> {
    val uid = requireUserId()
    val docRef = projectsCollection(uid).document(projectId)
    val updates =
      mutableMapOf<String, Any?>(
        "status" to status.wireValue,
        "progressPercent" to progressPercent.coerceIn(0, 100).toLong(),
        "updatedAt" to FieldValue.serverTimestamp(),
        "errorMessage" to errorMessage?.take(500),
      )
    if (durationMs != null) {
      updates["durationMs"] = durationMs.coerceAtLeast(0L)
    }
    if (cueCount != null) {
      updates["cueCount"] = cueCount.coerceAtLeast(0).toLong()
    }
    if (remoteJobId != null) {
      updates["remoteJobId"] = remoteJobId.take(128)
    }

    return try {
      docRef.update(updates).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateProjectExport(
    projectId: String,
    title: String? = null,
    status: ProjectStatus,
    progressPercent: Int,
    exportedMp4Path: String? = null,
    localVideoUri: String? = null,
    cueCount: Int? = null,
    errorMessage: String? = null,
  ): Result<Unit> {
    val uid = requireUserId()
    val docRef = projectsCollection(uid).document(projectId)
    val updates =
      mutableMapOf<String, Any?>(
        "status" to status.wireValue,
        "progressPercent" to progressPercent.coerceIn(0, 100).toLong(),
        "updatedAt" to FieldValue.serverTimestamp(),
        "errorMessage" to errorMessage?.take(500),
      )
    if (!title.isNullOrBlank()) {
      updates["title"] = title.trim().take(160)
    }
    if (exportedMp4Path != null) {
      updates["exportedMp4Path"] = exportedMp4Path.take(1000)
    }
    if (localVideoUri != null) {
      updates["localVideoUri"] = localVideoUri.take(1000)
    }
    if (cueCount != null) {
      updates["cueCount"] = cueCount.coerceAtLeast(0).toLong()
    }

    return try {
      docRef.update(updates).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun saveCuePages(projectId: String, cues: List<SubtitleCue>): Result<Unit> {
    val uid = requireUserId()
    val pagesRef = cuePagesCollection(uid, projectId)
    val reindexed =
      cues.mapIndexed { idx, cue ->
        cue.copy(
          id = cue.id.ifBlank { "cue_${idx + 1}" },
          index = idx + 1,
        )
      }
    val chunks =
      if (reindexed.isEmpty()) {
        listOf(emptyList())
      } else {
        reindexed.chunked(PAGE_SIZE)
      }

    return try {
      val existingDocs = pagesRef.get().await().documents.associateBy { it.id }
      chunks.forEachIndexed { pageIdx, chunk ->
        val pageId = "page_$pageIdx"
        val docRef = pagesRef.document(pageId)
        val cueMaps = chunk.map { it.toFirestoreMap() }
        if (existingDocs.containsKey(pageId)) {
          docRef
            .update(
              mapOf(
                "cues" to cueMaps,
                "updatedAt" to FieldValue.serverTimestamp(),
              )
            )
            .await()
        } else {
          docRef
            .set(
              mapOf(
                "userId" to uid,
                "projectId" to projectId,
                "pageIndex" to pageIdx.toLong(),
                "cues" to cueMaps,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
              )
            )
            .await()
        }
      }
      // Remove extra stale pages if cue count shrank
      existingDocs.keys
        .filter { id ->
          val num = id.removePrefix("page_").toIntOrNull()
          num != null && num >= chunks.size
        }
        .forEach { staleId -> pagesRef.document(staleId).delete().await() }

      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, pagesRef.path)
      Result.failure(e)
    }
  }

  suspend fun getCuesOnce(projectId: String): Result<List<SubtitleCue>> {
    val uid = requireUserId()
    val pagesRef = cuePagesCollection(uid, projectId)
    return try {
      val snapshot = pagesRef.orderBy("pageIndex", Query.Direction.ASCENDING).get().await()
      val cues =
        snapshot.documents
          .mapNotNull { doc ->
            doc.toObject(CuePage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          }
          .sortedBy { it.pageIndex }
          .flatMap { page -> page.cues.map { SubtitleCue.fromFirestoreMap(it) } }
      Result.success(cues)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, pagesRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteProject(projectId: String): Result<Unit> {
    val uid = requireUserId()
    val projectRef = projectsCollection(uid).document(projectId)
    val pagesRef = cuePagesCollection(uid, projectId)
    return try {
      val pageDocs = pagesRef.get().await().documents
      for (pageDoc in pageDocs) {
        pageDoc.reference.delete().await()
      }
      projectRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, projectRef.path)
      Result.failure(e)
    }
  }

  companion object {
    const val PAGE_SIZE = 100
  }
}
