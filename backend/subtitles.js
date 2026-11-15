function formatSrtTime(ms) {
  const safe = Math.max(0, Math.floor(ms));
  const h = String(Math.floor(safe / 3600000)).padStart(2, "0");
  const m = String(Math.floor((safe % 3600000) / 60000)).padStart(2, "0");
  const s = String(Math.floor((safe % 60000) / 1000)).padStart(2, "0");
  const rem = String(safe % 1000).padStart(3, "0");
  return `${h}:${m}:${s},${rem}`;
}

function normalizeCues(cues) {
  const sorted = [...(cues || [])].sort((a, b) => Number(a.startMs || 0) - Number(b.startMs || 0));
  return sorted.map((c, idx) => {
    const startMs = Math.max(0, Math.round(Number(c.startMs || 0)));
    const endMs = Math.max(startMs + 300, Math.round(Number(c.endMs || startMs + 2000)));
    return {
      id: String(c.id || `cue_${idx + 1}`),
      index: idx + 1,
      startMs,
      endMs,
      englishText: String(c.englishText || "").trim(),
      bengaliText: String(c.bengaliText || "").trim(),
    };
  });
}

function buildSrt(cues, includeEnglishLine = false) {
  const normalized = normalizeCues(cues);
  return normalized
    .map((c, i) => {
      const primary = c.bengaliText || c.englishText;
      const lines = [primary];
      if (includeEnglishLine && c.englishText && c.bengaliText) {
        lines.push(c.englishText);
      }
      return `${i + 1}\n${formatSrtTime(c.startMs)} --> ${formatSrtTime(c.endMs)}\n${lines.join("\n")}\n`;
    })
    .join("\n");
}

function parseFfmpegTimeSeconds(stderrChunk) {
  const matches = String(stderrChunk).match(/time=(\d+):(\d{2}):(\d{2}(?:\.\d+)?)/g);
  if (!matches || matches.length === 0) return null;
  const last = matches[matches.length - 1];
  const m = last.match(/time=(\d+):(\d{2}):(\d{2}(?:\.\d+)?)/);
  if (!m) return null;
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number.parseFloat(m[3]);
}

function escapeSubtitlesFilterPath(filePath) {
  return String(filePath)
    .replace(/\\/g, "/")
    .replace(/:/g, "\\:")
    .replace(/'/g, "\\'");
}

module.exports = {
  formatSrtTime,
  normalizeCues,
  buildSrt,
  parseFfmpegTimeSeconds,
  escapeSubtitlesFilterPath,
};
