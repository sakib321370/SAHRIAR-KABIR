const express = require("express");
const cors = require("cors");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { v4: uuidv4 } = require("uuid");
const { GoogleGenAI, Type } = require("@google/genai");
const {
  formatSrtTime,
  normalizeCues,
  buildSrt,
  parseFfmpegTimeSeconds,
  escapeSubtitlesFilterPath,
} = require("./subtitles");

const PORT = Number(process.env.PORT || 8080);
const WORK_DIR = process.env.WORK_DIR || path.join(os.tmpdir(), "banglasub-workspace");
const FONTS_DIR = path.join(__dirname, "fonts");
const CHUNK_SIZE_BYTES = 5 * 1024 * 1024; // 5MB chunks
const PRIMARY_MODEL = process.env.GEMINI_MODEL || "gemini-3.5-flash";
const FALLBACK_MODEL = "gemini-2.5-flash";

fs.mkdirSync(WORK_DIR, { recursive: true });

const jobs = new Map();

function saveJobState(job) {
  try {
    fs.mkdirSync(job.jobDir, { recursive: true });
    const serializable = {
      jobId: job.jobId,
      fileName: job.fileName,
      fileSizeBytes: job.fileSizeBytes,
      totalChunks: job.totalChunks,
      receivedChunks: Array.from(job.receivedChunks || []),
      status: job.status,
      progressPercent: job.progressPercent,
      cues: job.cues || [],
      error: job.error || null,
      jobDir: job.jobDir,
      chunksDir: job.chunksDir,
    };
    fs.writeFileSync(path.join(job.jobDir, "job.json"), JSON.stringify(serializable), "utf8");
  } catch (err) {
    console.warn("Failed to persist job state:", err.message);
  }
}

function getJob(jobId) {
  if (jobs.has(jobId)) return jobs.get(jobId);
  const safeId = path.basename(String(jobId || ""));
  if (!safeId) return null;
  const metaPath = path.join(WORK_DIR, safeId, "job.json");
  if (!fs.existsSync(metaPath)) return null;
  try {
    const raw = JSON.parse(fs.readFileSync(metaPath, "utf8"));
    const restored = {
      ...raw,
      receivedChunks: new Set(raw.receivedChunks || []),
    };
    jobs.set(restored.jobId, restored);
    return restored;
  } catch {
    return null;
  }
}

function runCommand(cmd, args, onStderr) {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    proc.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    proc.stderr.on("data", (chunk) => {
      const text = chunk.toString();
      stderr += text;
      if (onStderr) onStderr(text);
    });
    proc.on("error", reject);
    proc.on("close", (code) => {
      if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`${cmd} exited with code ${code}: ${stderr.slice(-600)}`));
    });
  });
}

async function probeDurationMs(mediaPath) {
  try {
    const { stdout } = await runCommand("ffprobe", [
      "-v",
      "error",
      "-show_entries",
      "format=duration",
      "-of",
      "default=noprint_wrappers=1:nokey=1",
      mediaPath,
    ]);
    const seconds = Number.parseFloat(String(stdout).trim());
    if (Number.isFinite(seconds) && seconds > 0) {
      return Math.round(seconds * 1000);
    }
  } catch {
    // Ignore probe errors and fall back
  }
  return 0;
}

async function generateSegmentSubtitlesWithRetry(ai, audioBase64) {
  const modelsToTry = PRIMARY_MODEL === FALLBACK_MODEL ? [PRIMARY_MODEL] : [PRIMARY_MODEL, FALLBACK_MODEL];
  let lastError = null;

  for (const model of modelsToTry) {
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        const result = await ai.models.generateContent({
          model,
          contents: [
            { inlineData: { mimeType: "audio/mp3", data: audioBase64 } },
            {
              text:
                "You are an expert English-to-Bengali audiovisual subtitle translator. " +
                "Transcribe every spoken English sentence accurately with startOffsetMs and endOffsetMs relative to 0 ms of this audio segment, " +
                "split long lines into readable 2-6 second cues, and translate each cue into natural, fluent Bengali (বাংলা লিপি). " +
                "Keep cues strictly chronological and non-overlapping.",
            },
          ],
          config: {
            responseMimeType: "application/json",
            responseSchema: {
              type: Type.OBJECT,
              properties: {
                segments: {
                  type: Type.ARRAY,
                  items: {
                    type: Type.OBJECT,
                    properties: {
                      startOffsetMs: { type: Type.INTEGER },
                      endOffsetMs: { type: Type.INTEGER },
                      englishText: { type: Type.STRING },
                      bengaliText: { type: Type.STRING },
                    },
                    required: ["startOffsetMs", "endOffsetMs", "englishText", "bengaliText"],
                  },
                },
              },
              required: ["segments"],
            },
          },
        });
        return JSON.parse(result.text || '{"segments":[]}');
      } catch (err) {
        lastError = err;
        const msg = String(err && err.message ? err.message : err);
        if (msg.includes("404") || msg.toLowerCase().includes("not found")) {
          break; // Try fallback model
        }
        if (attempt < 3) {
          await new Promise((r) => setTimeout(r, 2000 * attempt));
        }
      }
    }
  }
  throw lastError || new Error("Gemini audio transcription failed.");
}

function createApp() {
  const app = express();
  app.use(cors());
  app.use(express.json({ limit: "25mb" }));

  app.get("/", (_req, res) => {
    res.json({
      ok: true,
      service: "banglasub-ai-backend",
      health: "/health",
      geminiConfigured: Boolean(process.env.GEMINI_API_KEY),
    });
  });

  app.get("/health", (_req, res) => {
    res.json({
      ok: true,
      service: "banglasub-ai-backend",
      geminiConfigured: Boolean(process.env.GEMINI_API_KEY),
    });
  });

  app.post("/api/jobs/init", (req, res) => {
    const { fileName = "video.mp4", fileSizeBytes = 0, totalChunks = 1 } = req.body || {};
    const jobId = uuidv4();
    const jobDir = path.join(WORK_DIR, jobId);
    const chunksDir = path.join(jobDir, "chunks");
    fs.mkdirSync(chunksDir, { recursive: true });

    const job = {
      jobId,
      fileName: path.basename(String(fileName || "video.mp4")),
      fileSizeBytes: Math.max(0, Number(fileSizeBytes || 0)),
      totalChunks: Math.max(1, Number(totalChunks || 1)),
      receivedChunks: new Set(),
      status: "uploading",
      progressPercent: 0,
      cues: [],
      error: null,
      jobDir,
      chunksDir,
    };
    jobs.set(jobId, job);
    saveJobState(job);
    res.json({ jobId, chunkSizeBytes: CHUNK_SIZE_BYTES });
  });

  app.put(
    "/api/jobs/:jobId/chunks/:index",
    express.raw({ type: "application/octet-stream", limit: "16mb" }),
    (req, res) => {
      const job = getJob(req.params.jobId);
      if (!job) return res.status(404).json({ error: "Job not found" });
      const index = Number.parseInt(req.params.index, 10);
      if (!Number.isInteger(index) || index < 0) {
        return res.status(400).json({ error: "Invalid chunk index" });
      }
      if (!Buffer.isBuffer(req.body)) {
        return res.status(400).json({ error: "Binary octet-stream chunk body required" });
      }
      const chunkPath = path.join(job.chunksDir, `chunk_${String(index).padStart(6, "0")}`);
      fs.writeFileSync(chunkPath, req.body);
      job.receivedChunks.add(index);
      const uploadPct = Math.min(35, Math.round((job.receivedChunks.size / Math.max(1, job.totalChunks)) * 35));
      job.progressPercent = uploadPct;
      saveJobState(job);
      res.json({ ok: true, received: job.receivedChunks.size, totalChunks: job.totalChunks });
    }
  );

  app.post("/api/jobs/:jobId/process", async (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });

    res.json({ ok: true, jobId: job.jobId, status: "extracting" });

    (async () => {
      try {
        job.status = "extracting";
        job.progressPercent = 38;
        job.error = null;
        saveJobState(job);

        const ext = path.extname(job.fileName) || ".mp4";
        const inputPath = path.join(job.jobDir, `input${ext}`);
        const writeStream = fs.createWriteStream(inputPath);
        for (let i = 0; i < job.totalChunks; i++) {
          const chunkPath = path.join(job.chunksDir, `chunk_${String(i).padStart(6, "0")}`);
          if (!fs.existsSync(chunkPath)) {
            throw new Error(`Missing uploaded video chunk ${i + 1} of ${job.totalChunks}`);
          }
          await new Promise((resolve, reject) => {
            const rs = fs.createReadStream(chunkPath);
            rs.on("error", reject);
            rs.on("end", resolve);
            rs.pipe(writeStream, { end: false });
          });
        }
        writeStream.end();
        await new Promise((resolve, reject) => {
          writeStream.on("finish", resolve);
          writeStream.on("error", reject);
        });

        // Clean up chunk fragments immediately after assembly to save container disk/RAM
        fs.rmSync(job.chunksDir, { recursive: true, force: true });

        // Extract compressed mono MP3 audio via FFmpeg (supports MP4, MOV, MKV)
        const audioPath = path.join(job.jobDir, "audio.mp3");
        await runCommand("ffmpeg", [
          "-y",
          "-i",
          inputPath,
          "-vn",
          "-acodec",
          "libmp3lame",
          "-b:a",
          "64k",
          "-ac",
          "1",
          audioPath,
        ]);

        // Split into 8-minute chunks for long videos (10m - 2h)
        const segmentsDir = path.join(job.jobDir, "segments");
        fs.rmSync(segmentsDir, { recursive: true, force: true });
        fs.mkdirSync(segmentsDir, { recursive: true });
        await runCommand("ffmpeg", [
          "-y",
          "-i",
          audioPath,
          "-f",
          "segment",
          "-segment_time",
          "480",
          "-c",
          "copy",
          path.join(segmentsDir, "seg_%03d.mp3"),
        ]);

        const segFiles = fs.readdirSync(segmentsDir).filter((f) => f.endsWith(".mp3")).sort();
        if (segFiles.length === 0) {
          throw new Error("No audio stream found in the uploaded video.");
        }

        const apiKey = process.env.GEMINI_API_KEY;
        if (!apiKey) {
          throw new Error("Server GEMINI_API_KEY environment variable is not configured.");
        }

        const ai = new GoogleGenAI({ apiKey });
        job.status = "transcribing";
        saveJobState(job);

        const allCues = [];
        let cueIndex = 1;
        let chunkOffsetMs = 0;
        let prevEndMs = 0;

        for (let sIdx = 0; sIdx < segFiles.length; sIdx++) {
          const segPath = path.join(segmentsDir, segFiles[sIdx]);
          const actualSegDurationMs = (await probeDurationMs(segPath)) || 480 * 1000;
          const audioBase64 = fs.readFileSync(segPath).toString("base64");

          job.progressPercent = 45 + Math.round(((sIdx + 0.5) / Math.max(1, segFiles.length)) * 48);
          if (sIdx >= segFiles.length / 2) job.status = "translating";
          saveJobState(job);

          const parsed = await generateSegmentSubtitlesWithRetry(ai, audioBase64);
          for (const seg of parsed.segments || []) {
            const en = String(seg.englishText || "").trim();
            const bn = String(seg.bengaliText || "").trim();
            if (!en && !bn) continue;
            const rawStart = Math.max(0, Number(seg.startOffsetMs || 0));
            const rawEnd = Math.max(rawStart + 300, Number(seg.endOffsetMs || rawStart + 2000));
            const absStart = Math.max(prevEndMs, chunkOffsetMs + rawStart);
            const absEnd = Math.max(absStart + 400, chunkOffsetMs + rawEnd);
            prevEndMs = absEnd;

            allCues.push({
              id: `cue_${cueIndex}`,
              index: cueIndex,
              startMs: absStart,
              endMs: absEnd,
              englishText: en,
              bengaliText: bn || en,
            });
            cueIndex++;
          }

          chunkOffsetMs += actualSegDurationMs;
        }

        fs.rmSync(segmentsDir, { recursive: true, force: true });
        if (fs.existsSync(audioPath)) fs.unlinkSync(audioPath);

        job.cues = allCues;
        fs.writeFileSync(path.join(job.jobDir, "subtitles.srt"), buildSrt(allCues), "utf8");
        job.status = "ready";
        job.progressPercent = 100;
        saveJobState(job);
      } catch (err) {
        job.status = "failed";
        job.error = err.message || "Backend processing failed";
        saveJobState(job);
      }
    })();
  });

  app.get("/api/jobs/:jobId/status", (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });
    res.json({
      jobId: job.jobId,
      status: job.status,
      progressPercent: job.progressPercent,
      cueCount: job.cues.length,
      cues: job.status === "ready" || job.status === "exported" ? job.cues : [],
      error: job.error,
    });
  });

  app.get("/api/jobs/:jobId/subtitles.srt", (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });
    const srtPath = path.join(job.jobDir, "subtitles.srt");
    if (!fs.existsSync(srtPath)) return res.status(404).json({ error: "SRT not generated yet" });
    res.type("application/x-subrip").sendFile(srtPath);
  });

  app.post("/api/jobs/:jobId/export", async (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });
    const { cues = job.cues, fontSizeSp = 20, showEnglishDualLine = false } = req.body || {};

    const normalizedCues = normalizeCues(cues);
    if (normalizedCues.length === 0) {
      return res.status(400).json({ error: "No subtitle cues provided for MP4 burn-in." });
    }

    job.cues = normalizedCues;
    const srtPath = path.join(job.jobDir, "subtitles.srt");
    fs.writeFileSync(srtPath, buildSrt(normalizedCues, Boolean(showEnglishDualLine)), "utf8");

    const ext = path.extname(job.fileName) || ".mp4";
    const inputPath = path.join(job.jobDir, `input${ext}`);
    if (!fs.existsSync(inputPath)) {
      return res.status(404).json({ error: "Source video is no longer available on backend." });
    }

    const outputPath = path.join(job.jobDir, "exported_bn.mp4");
    job.status = "exporting";
    job.progressPercent = 12;
    job.error = null;
    saveJobState(job);
    res.json({ ok: true, status: "exporting" });

    (async () => {
      try {
        const totalDurationSec = (await probeDurationMs(inputPath)) / 1000;
        const escapedSrt = escapeSubtitlesFilterPath(srtPath);
        const fontsArg = fs.existsSync(FONTS_DIR)
          ? `:fontsdir='${escapeSubtitlesFilterPath(FONTS_DIR)}'`
          : "";
        const assFontSize = Math.max(16, Math.min(34, Math.round(Number(fontSizeSp || 20) * 1.1)));
        const vf = `subtitles='${escapedSrt}'${fontsArg}:force_style='FontName=Noto Sans Bengali,FontSize=${assFontSize},PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H99090D16,BorderStyle=4,Outline=1.5,MarginV=28'`;

        await runCommand(
          "ffmpeg",
          [
            "-y",
            "-i",
            inputPath,
            "-vf",
            vf,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            outputPath,
          ],
          (stderrChunk) => {
            if (totalDurationSec > 0) {
              const sec = parseFfmpegTimeSeconds(stderrChunk);
              if (sec !== null) {
                const pct = Math.min(98, Math.max(15, Math.round((sec / totalDurationSec) * 90 + 10)));
                if (pct > job.progressPercent) {
                  job.progressPercent = pct;
                  saveJobState(job);
                }
              }
            }
          }
        );

        job.status = "exported";
        job.progressPercent = 100;
        saveJobState(job);
      } catch (err) {
        job.status = "failed";
        job.error = err.message || "FFmpeg burn-in export failed";
        saveJobState(job);
      }
    })();
  });

  app.get("/api/jobs/:jobId/export.mp4", (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });
    const outputPath = path.join(job.jobDir, "exported_bn.mp4");
    if (!fs.existsSync(outputPath)) return res.status(404).json({ error: "Exported MP4 not ready" });
    res.download(outputPath, `${path.parse(job.fileName).name}_bn.mp4`);
  });

  app.delete("/api/jobs/:jobId", (req, res) => {
    const job = getJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: "Job not found" });
    jobs.delete(job.jobId);
    fs.rmSync(job.jobDir, { recursive: true, force: true });
    res.json({ ok: true });
  });

  return app;
}

if (require.main === module) {
  const app = createApp();
  app.listen(PORT, "0.0.0.0", () => {
    console.log(`BanglaSub AI FFmpeg Backend listening on 0.0.0.0:${PORT}`);
  });
}

module.exports = {
  createApp,
  formatSrtTime,
  normalizeCues,
  buildSrt,
  parseFfmpegTimeSeconds,
  escapeSubtitlesFilterPath,
};
