(() => {
  const extensions = new Map([
    [".m3u8", "HLS"],
    [".mpd", "DASH"],
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

  globalThis.CDGMediaSniffer = { classify };
})();
