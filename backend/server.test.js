const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  formatSrtTime,
  normalizeCues,
  buildSrt,
  parseFfmpegTimeSeconds,
  escapeSubtitlesFilterPath,
} = require("./subtitles");

test("formatSrtTime formats hours, minutes, seconds, milliseconds accurately", () => {
  assert.equal(formatSrtTime(0), "00:00:00,000");
  assert.equal(formatSrtTime(3723456), "01:02:03,456");
  assert.equal(formatSrtTime(7200000), "02:00:00,000");
});

test("normalizeCues sorts chronologically and enforces minimum duration", () => {
  const cues = normalizeCues([
    { id: "b", startMs: 5000, endMs: 5100, englishText: "Second", bengaliText: "দ্বিতীয়" },
    { id: "a", startMs: 1000, endMs: 2500, englishText: "First", bengaliText: "প্রথম" },
  ]);
  assert.equal(cues[0].index, 1);
  assert.equal(cues[0].bengaliText, "প্রথম");
  assert.equal(cues[1].index, 2);
  assert.equal(cues[1].endMs, 5300);
});

test("buildSrt generates valid UTF-8 Bengali and optional dual-line SRT", () => {
  const cues = [
    { startMs: 0, endMs: 2000, englishText: "Hello everyone", bengaliText: "সবাইকে স্বাগতম" },
  ];
  const single = buildSrt(cues, false);
  assert.ok(single.includes("00:00:00,000 --> 00:00:02,000"));
  assert.ok(single.includes("সবাইকে স্বাগতম"));
  assert.ok(!single.includes("Hello everyone"));

  const dual = buildSrt(cues, true);
  assert.ok(dual.includes("সবাইকে স্বাগতম\nHello everyone"));
});

test("parseFfmpegTimeSeconds extracts progress timestamp from FFmpeg stderr", () => {
  const line = "frame=  420 fps= 30 q=28.0 size=   1024kB time=01:15:30.50 bitrate= 512.0kbits/s";
  assert.equal(parseFfmpegTimeSeconds(line), 4530.5);
  assert.equal(parseFfmpegTimeSeconds("no timestamp here"), null);
});

test("escapeSubtitlesFilterPath escapes colons and quotes for FFmpeg filtergraph", () => {
  assert.equal(
    escapeSubtitlesFilterPath("C:\\tmp\\my's\\subtitles.srt"),
    "C\\:/tmp/my\\'s/subtitles.srt"
  );
});

test("real FFmpeg pipeline extracts MP3 audio, probes duration, and burns Bengali subtitles into MP4", async (t) => {
  const { execFileSync, spawnSync } = require("node:child_process");
  const fs = require("node:fs");
  const os = require("node:os");
  const path = require("node:path");

  const hasFfmpeg = spawnSync("ffmpeg", ["-version"], { stdio: "ignore" }).status === 0;
  if (!hasFfmpeg) {
    t.skip("ffmpeg not installed in current test environment");
    return;
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "banglasub-ffmpeg-test-"));
  try {
    const inputMp4 = path.join(tmpDir, "sample.mp4");
    const audioMp3 = path.join(tmpDir, "audio.mp3");
    const srtPath = path.join(tmpDir, "subtitles.srt");
    const outputMp4 = path.join(tmpDir, "exported_bn.mp4");
    const fontsDir = path.join(__dirname, "fonts");

    // 1. Create a 2-second test MP4 with synthetic video + audio
    execFileSync("ffmpeg", [
      "-y",
      "-f", "lavfi", "-i", "color=c=0x090D16:s=320x240:d=2",
      "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
      "-c:v", "libx264", "-pix_fmt", "yuv420p",
      "-c:a", "aac", "-shortest",
      inputMp4,
    ], { stdio: "ignore" });

    // 2. Extract compressed mono MP3 audio (same parameters as server.js)
    execFileSync("ffmpeg", [
      "-y", "-i", inputMp4, "-vn", "-acodec", "libmp3lame", "-b:a", "64k", "-ac", "1", audioMp3,
    ], { stdio: "ignore" });
    assert.ok(fs.existsSync(audioMp3) && fs.statSync(audioMp3).size > 1000);

    // 3. Probe audio duration with ffprobe
    const durOut = execFileSync("ffprobe", [
      "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", audioMp3,
    ], { encoding: "utf8" });
    const durationSec = Number.parseFloat(durOut.trim());
    assert.ok(durationSec >= 1.5 && durationSec <= 2.5, `Unexpected audio duration: ${durationSec}`);

    // 4. Write UTF-8 Bengali SRT and burn into MP4 with Noto Sans Bengali
    const srtContent = buildSrt([
      { startMs: 0, endMs: 1800, englishText: "Hello world", bengaliText: "সবাইকে স্বাগতম" },
    ], true);
    fs.writeFileSync(srtPath, srtContent, "utf8");

    const escapedSrt = escapeSubtitlesFilterPath(srtPath);
    const fontsArg = fs.existsSync(fontsDir)
      ? `:fontsdir='${escapeSubtitlesFilterPath(fontsDir)}'`
      : "";
    const vf = `subtitles='${escapedSrt}'${fontsArg}:force_style='FontName=Noto Sans Bengali,FontSize=20,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H99090D16,BorderStyle=4,Outline=1.5,MarginV=28'`;

    execFileSync("ffmpeg", [
      "-y", "-i", inputMp4, "-vf", vf,
      "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
      "-movflags", "+faststart", "-c:a", "aac", "-b:a", "128k",
      outputMp4,
    ], { stdio: "ignore" });

    assert.ok(fs.existsSync(outputMp4) && fs.statSync(outputMp4).size > 5000);

    // 5. Verify output MP4 streams via ffprobe
    const streamsOut = execFileSync("ffprobe", [
      "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", outputMp4,
    ], { encoding: "utf8" });
    const parsed = JSON.parse(streamsOut);
    const codecTypes = (parsed.streams || []).map((s) => s.codec_type);
    assert.ok(codecTypes.includes("video"), "Exported MP4 missing video stream");
    assert.ok(codecTypes.includes("audio"), "Exported MP4 missing audio stream");
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
