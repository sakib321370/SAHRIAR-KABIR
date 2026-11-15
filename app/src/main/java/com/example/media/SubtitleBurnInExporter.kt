package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.R
import com.example.data.model.SubtitleCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class SubtitleBurnInExporter {

  suspend fun burnSubtitlesToMp4(
    context: Context,
    videoUri: Uri,
    projectTitle: String,
    cues: List<SubtitleCue>,
    fontSizeSp: Float,
    showEnglishDualLine: Boolean,
    onProgress: suspend (Int) -> Unit,
  ): Result<Uri> {
    return runCatching {
      val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
      val safeName = SrtCodec.sanitizeFilename(projectTitle)
      val tempOutput = File(exportsDir, "${safeName}_bn_export.mp4")
      if (tempOutput.exists()) tempOutput.delete()

      val bengaliTypeface =
        ResourcesCompat.getFont(context, R.font.noto_sans_bengali) ?: Typeface.DEFAULT_BOLD
      val overlay =
        BengaliCueBitmapOverlay(
          cues = cues.sortedBy { it.startMs },
          typeface = bengaliTypeface,
          fontSizeScale = fontSizeSp / 20f,
          showEnglishDualLine = showEnglishDualLine,
        )

      val overlayEffect = OverlayEffect(listOf(overlay))
      val editedMediaItem =
        EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
          .setEffects(Effects(emptyList(), listOf(overlayEffect)))
          .build()

      runExportWithProgress(context, editedMediaItem, tempOutput.absolutePath, onProgress)
      saveVideoToMoviesOrFileProvider(context, tempOutput, "${safeName}_bn.mp4")
    }
  }

  private suspend fun runExportWithProgress(
    context: Context,
    editedMediaItem: EditedMediaItem,
    outputPath: String,
    onProgress: suspend (Int) -> Unit,
  ) =
    withContext(Dispatchers.Main) {
      val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        suspendCancellableCoroutine { continuation ->
          val handler = Handler(Looper.getMainLooper())
          val progressHolder = ProgressHolder()
          var isFinished = false

          val transformer =
            Transformer.Builder(context.applicationContext)
              .setVideoMimeType(MimeTypes.VIDEO_H264)
              .setAudioMimeType(MimeTypes.AUDIO_AAC)
              .addListener(
                object : Transformer.Listener {
                  override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    isFinished = true
                    handler.removeCallbacksAndMessages(null)
                    if (continuation.isActive) continuation.resume(Unit)
                  }

                  override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                  ) {
                    isFinished = true
                    handler.removeCallbacksAndMessages(null)
                    if (continuation.isActive) {
                      continuation.resumeWithException(
                        IllegalStateException(
                          "Video export failed: ${exportException.localizedMessage ?: exportException.errorCodeName}",
                          exportException,
                        )
                      )
                    }
                  }
                }
              )
              .build()

          val progressRunnable =
            object : Runnable {
              override fun run() {
                if (isFinished) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                  val pct = progressHolder.progress.coerceIn(0, 99)
                  progressScope.launch { onProgress(pct) }
                }
                handler.postDelayed(this, 600L)
              }
            }

          continuation.invokeOnCancellation {
            isFinished = true
            handler.removeCallbacksAndMessages(null)
            transformer.cancel()
          }

          transformer.start(editedMediaItem, outputPath)
          handler.postDelayed(progressRunnable, 600L)
        }
      } finally {
        progressScope.cancel()
      }
    }

  suspend fun saveVideoFileToMovies(
    context: Context,
    sourceFile: File,
    displayFileName: String,
  ): Uri = saveVideoToMoviesOrFileProvider(context, sourceFile, displayFileName)

  private suspend fun saveVideoToMoviesOrFileProvider(
    context: Context,
    sourceFile: File,
    displayFileName: String,
  ): Uri =
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayFileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BanglaSub")
            put(MediaStore.Video.Media.IS_PENDING, 1)
          }
        val resolver = context.contentResolver
        val uri =
          resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              sourceFile,
            )
        resolver.openOutputStream(uri)?.use { out ->
          FileInputStream(sourceFile).use { input -> input.copyTo(out) }
        } ?: error("Failed to open MediaStore output stream.")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        sourceFile.delete()
        uri
      } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)
      }
    }
}

@OptIn(UnstableApi::class)
private class BengaliCueBitmapOverlay(
  private val cues: List<SubtitleCue>,
  private val typeface: Typeface,
  private val fontSizeScale: Float,
  private val showEnglishDualLine: Boolean,
) : BitmapOverlay() {

  private var videoWidth = 1280
  private var videoHeight = 720
  private var cachedCueId: String? = null
  private var cachedBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
  private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

  private val overlaySettings =
    StaticOverlaySettings.Builder()
      .setBackgroundFrameAnchor(0f, -0.88f)
      .setOverlayFrameAnchor(0f, -1f)
      .build()

  override fun configure(videoSize: Size) {
    super.configure(videoSize)
    videoWidth = videoSize.width.coerceAtLeast(320)
    videoHeight = videoSize.height.coerceAtLeast(240)
    cachedCueId = null
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

  override fun getBitmap(presentationTimeUs: Long): Bitmap {
    val timeMs = presentationTimeUs / 1_000L
    val activeCue = cues.firstOrNull { timeMs in it.startMs..it.endMs } ?: return emptyBitmap
    if (activeCue.id == cachedCueId) return cachedBitmap

    val bitmapWidth = (videoWidth * 0.90f).toInt().coerceIn(280, videoWidth)
    val baseFontPx = (videoHeight * 0.044f * fontSizeScale).coerceIn(22f, 68f)

    val bnPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseFontPx
        this.typeface = this@BengaliCueBitmapOverlay.typeface
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
      }

    val primaryText = activeCue.bengaliText.trim().ifBlank { activeCue.englishText.trim() }
    val horizontalPad = (baseFontPx * 0.85f).toInt()
    val verticalPad = (baseFontPx * 0.48f).toInt()
    val maxTextWidth = (bitmapWidth - horizontalPad * 2).coerceAtLeast(160)

    val bnLayout =
      StaticLayout.Builder.obtain(primaryText, 0, primaryText.length, bnPaint, maxTextWidth)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setLineSpacing(4f, 1.1f)
        .build()

    val enLayout =
      if (showEnglishDualLine && activeCue.englishText.isNotBlank() && activeCue.bengaliText.isNotBlank()) {
        val enPaint =
          TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = baseFontPx * 0.72f
            typeface = Typeface.DEFAULT
          }
        StaticLayout.Builder.obtain(
            activeCue.englishText.trim(),
            0,
            activeCue.englishText.trim().length,
            enPaint,
            maxTextWidth,
          )
          .setAlignment(Layout.Alignment.ALIGN_CENTER)
          .build()
      } else {
        null
      }

    var actualTextWidth = 0f
    for (line in 0 until bnLayout.lineCount) {
      actualTextWidth = maxOf(actualTextWidth, bnLayout.getLineWidth(line))
    }
    if (enLayout != null) {
      for (line in 0 until enLayout.lineCount) {
        actualTextWidth = maxOf(actualTextWidth, enLayout.getLineWidth(line))
      }
    }

    val boxWidth = (actualTextWidth + horizontalPad * 2).toInt().coerceIn(120, bitmapWidth)
    val gap = if (enLayout != null) (baseFontPx * 0.25f).toInt() else 0
    val boxHeight =
      (bnLayout.height + (enLayout?.height ?: 0) + gap + verticalPad * 2).coerceAtLeast(48)

    val bmp = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 9, 13, 22)
        style = Paint.Style.FILL
      }
    val borderPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 16, 185, 129)
        style = Paint.Style.STROKE
        strokeWidth = 2f
      }
    val rect = RectF(1f, 1f, boxWidth - 1f, boxHeight - 1f)
    val radius = baseFontPx * 0.55f
    canvas.drawRoundRect(rect, radius, radius, bgPaint)
    canvas.drawRoundRect(rect, radius, radius, borderPaint)

    val bnOffsetX = (boxWidth - maxTextWidth) / 2f
    canvas.save()
    canvas.translate(bnOffsetX, verticalPad.toFloat())
    bnLayout.draw(canvas)
    canvas.restore()

    if (enLayout != null) {
      canvas.save()
      canvas.translate(bnOffsetX, (verticalPad + bnLayout.height + gap).toFloat())
      enLayout.draw(canvas)
      canvas.restore()
    }

    cachedCueId = activeCue.id
    cachedBitmap = bmp
    return bmp
  }
}
