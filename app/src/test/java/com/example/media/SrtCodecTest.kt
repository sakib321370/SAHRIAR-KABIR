package com.example.media

import com.example.data.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtCodecTest {

  @Test
  fun formatSrtTimestamp_formatsHoursMinutesSecondsMillis() {
    assertEquals("00:00:00,000", SrtCodec.formatSrtTimestamp(0L))
    assertEquals("00:01:24,507", SrtCodec.formatSrtTimestamp(84_507L))
    assertEquals("01:45:03,090", SrtCodec.formatSrtTimestamp(6_303_090L))
  }

  @Test
  fun toSrtAndParseSrt_preservesBengaliUnicodeAndOrder() {
    val cues =
      listOf(
        SubtitleCue(
          id = "c2",
          index = 2,
          startMs = 5_000L,
          endMs = 8_500L,
          englishText = "How are you?",
          bengaliText = "আপনি কেমন আছেন?",
        ),
        SubtitleCue(
          id = "c1",
          index = 1,
          startMs = 1_000L,
          endMs = 4_000L,
          englishText = "Hello everyone.",
          bengaliText = "সবাইকে শুভেচ্ছা।",
        ),
      )

    val srt = SrtCodec.toSrt(cues)
    val parsed = SrtCodec.parseSrt(srt)
    assertEquals(2, parsed.size)
    assertEquals(1_000L, parsed[0].startMs)
    assertEquals("সবাইকে শুভেচ্ছা।", parsed[0].bengaliText)
    assertEquals(5_000L, parsed[1].startMs)
    assertEquals("আপনি কেমন আছেন?", parsed[1].bengaliText)
  }

  @Test
  fun sanitizeFilename_removesUnsafeCharacters() {
    assertEquals("My_Video_2026", SrtCodec.sanitizeFilename("My Video / 2026!"))
  }
}
