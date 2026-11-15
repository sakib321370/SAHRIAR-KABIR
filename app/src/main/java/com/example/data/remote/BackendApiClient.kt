package com.example.data.remote

import android.content.Context
import android.net.Uri
import com.example.data.model.SubtitleCue
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class InitJobRequest(
  val fileName: String,
  val fileSizeBytes: Long,
  val totalChunks: Int,
)

@JsonClass(generateAdapter = true)
data class InitJobResponse(
  val jobId: String,
  val chunkSizeBytes: Int,
)

@JsonClass(generateAdapter = true)
data class BackendCueDto(
  val id: String = "",
  val index: Int = 1,
  val startMs: Long = 0L,
  val endMs: Long = 0L,
  val englishText: String = "",
  val bengaliText: String = "",
)

@JsonClass(generateAdapter = true)
data class JobStatusResponse(
  val jobId: String,
  val status: String,
  val progressPercent: Int,
  val cueCount: Int = 0,
  val cues: List<BackendCueDto> = emptyList(),
  val error: String? = null,
)

@JsonClass(generateAdapter = true)
data class ExportRequest(
  val cues: List<BackendCueDto>,
  val fontSizeSp: Float = 20f,
  val showEnglishDualLine: Boolean = false,
)

class BackendApiClient {

  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val initReqAdapter = moshi.adapter(InitJobRequest::class.java)
  private val initRespAdapter = moshi.adapter(InitJobResponse::class.java)
  private val statusAdapter = moshi.adapter(JobStatusResponse::class.java)
  private val exportReqAdapter = moshi.adapter(ExportRequest::class.java)

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(120, TimeUnit.SECONDS)
      .build()

  suspend fun checkHealth(baseUrl: String): Boolean =
    withContext(Dispatchers.IO) {
      val clean = baseUrl.trim().trimEnd('/')
      if (clean.isBlank()) return@withContext false
      runCatching {
          val req = Request.Builder().url("$clean/health").get().build()
          client.newCall(req).execute().use { it.isSuccessful }
        }
        .getOrDefault(false)
    }

  suspend fun initJob(
    baseUrl: String,
    fileName: String,
    fileSizeBytes: Long,
    totalChunks: Int,
  ): InitJobResponse =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val json = initReqAdapter.toJson(InitJobRequest(fileName, fileSizeBytes, totalChunks))
      val req =
        Request.Builder()
          .url("$clean/api/jobs/init")
          .post(json.toRequestBody("application/json".toMediaType()))
          .build()
      client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          throw IllegalStateException("Backend init failed (${resp.code}): $body")
        }
        initRespAdapter.fromJson(body) ?: throw IllegalStateException("Invalid init response")
      }
    }

  suspend fun uploadVideoInChunks(
    context: Context,
    baseUrl: String,
    jobId: String,
    videoUri: Uri,
    fileSizeBytes: Long,
    chunkSizeBytes: Int,
    onProgress: suspend (uploadedChunks: Int, totalChunks: Int) -> Unit,
  ) =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val safeChunkSize = chunkSizeBytes.coerceIn(512 * 1024, 8 * 1024 * 1024)
      val totalChunks = ((fileSizeBytes + safeChunkSize - 1) / safeChunkSize).toInt().coerceAtLeast(1)
      val buffer = ByteArray(safeChunkSize)

      context.contentResolver.openInputStream(videoUri)?.use { input ->
        var chunkIndex = 0
        while (true) {
          var bytesReadTotal = 0
          while (bytesReadTotal < safeChunkSize) {
            val read = input.read(buffer, bytesReadTotal, safeChunkSize - bytesReadTotal)
            if (read == -1) break
            bytesReadTotal += read
          }
          if (bytesReadTotal == 0) break

          val payload = if (bytesReadTotal == safeChunkSize) buffer else buffer.copyOf(bytesReadTotal)
          uploadChunkWithRetry(clean, jobId, chunkIndex, payload)
          chunkIndex++
          onProgress(chunkIndex, totalChunks)
        }
      } ?: throw IllegalStateException("Unable to read selected video stream.")
    }

  private suspend fun uploadChunkWithRetry(
    baseUrl: String,
    jobId: String,
    index: Int,
    bytes: ByteArray,
  ) {
    var attempt = 0
    var lastError: Exception? = null
    while (attempt < 3) {
      try {
        val req =
          Request.Builder()
            .url("$baseUrl/api/jobs/$jobId/chunks/$index")
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            throw IllegalStateException("Chunk $index upload failed (${resp.code})")
          }
        }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lastError = e
        attempt++
        if (attempt < 3) delay(1500L * attempt)
      }
    }
    throw lastError ?: IllegalStateException("Failed uploading chunk $index")
  }

  suspend fun startProcessing(baseUrl: String, jobId: String) =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val req =
        Request.Builder()
          .url("$clean/api/jobs/$jobId/process")
          .post("{}".toRequestBody("application/json".toMediaType()))
          .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("Failed to start FFmpeg processing (${resp.code})")
        }
      }
    }

  suspend fun getStatus(baseUrl: String, jobId: String): JobStatusResponse =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val req = Request.Builder().url("$clean/api/jobs/$jobId/status").get().build()
      client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          throw IllegalStateException("Status check failed (${resp.code}): $body")
        }
        statusAdapter.fromJson(body) ?: throw IllegalStateException("Malformed status JSON")
      }
    }

  suspend fun startBurnInExport(
    baseUrl: String,
    jobId: String,
    cues: List<SubtitleCue>,
    fontSizeSp: Float = 20f,
    showEnglishDualLine: Boolean = false,
  ) =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val dtoList =
        cues.map {
          BackendCueDto(
            id = it.id,
            index = it.index,
            startMs = it.startMs,
            endMs = it.endMs,
            englishText = it.englishText,
            bengaliText = it.bengaliText,
          )
        }
      val json =
        exportReqAdapter.toJson(
          ExportRequest(
            cues = dtoList,
            fontSizeSp = fontSizeSp,
            showEnglishDualLine = showEnglishDualLine,
          )
        )
      val req =
        Request.Builder()
          .url("$clean/api/jobs/$jobId/export")
          .post(json.toRequestBody("application/json".toMediaType()))
          .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("Backend FFmpeg export request failed (${resp.code})")
        }
      }
    }

  suspend fun downloadExportedMp4(baseUrl: String, jobId: String, destinationFile: File) =
    withContext(Dispatchers.IO) {
      val clean = validateBaseUrl(baseUrl)
      val req = Request.Builder().url("$clean/api/jobs/$jobId/export.mp4").get().build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("Failed to download exported MP4 (${resp.code})")
        }
        val body = resp.body ?: throw IllegalStateException("Empty video response")
        FileOutputStream(destinationFile).use { out -> body.byteStream().copyTo(out) }
      }
    }

  private fun validateBaseUrl(baseUrl: String): String {
    val clean = baseUrl.trim().trimEnd('/')
    require(clean.startsWith("http://") || clean.startsWith("https://")) {
      "Please enter a valid FFmpeg server URL (starting with https:// or http://) in Settings."
    }
    return clean
  }
}
