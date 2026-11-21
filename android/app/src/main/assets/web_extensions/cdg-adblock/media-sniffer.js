(() => {
  const extensions = new Map([
    [".m3u8", "HLS"],
    [".m3u", "HLS"],
    [".mpd", "DASH"],
    [".ism/manifest", "DASH"],
    [".mp4", "Video"],
    [".m4v", "Video"],
    [".mov", "Video"],
    [".webm", "Video"],
    [".mkv", "Video"],
    [".avi", "Video"],
    [".mpeg", "Video"],
    [".mpg", "Video"],
    [".3gp", "Video"]
  ]);

  function classify(url) {
    if (typeof url !== "string" || !url) {
      return null;
    }
    try {
      const parsed = new URL(url);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        return null;
      }
      const path = parsed.pathname.toLowerCase();
      for (const [extension, type] of extensions) {
        if (path.endsWith(extension)) {
          return type;
        }
      }
    } catch {
      return null;
    }
    return null;
  }

  function classifyContentType(contentType) {
    if (typeof contentType !== "string") {
      return null;
    }
    const mimeType = contentType.split(";", 1)[0].trim().toLowerCase();
    if ([
      "application/vnd.apple.mpegurl",
      "application/x-mpegurl",
      "audio/mpegurl",
      "audio/x-mpegurl"
    ].includes(mimeType)) {
      return "HLS";
    }
    if (mimeType === "application/dash+xml") {
      return "DASH";
    }
    if ([
      "video/mp4",
      "video/webm",
      "video/quicktime",
      "video/x-matroska",
      "video/x-msvideo",
      "video/3gpp",
      "video/mpeg"
    ].includes(mimeType)) {
      return "Video";
    }
    return null;
  }

  globalThis.CDGMediaSniffer = { classify, classifyContentType };
})();
