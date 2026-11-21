(() => {
  const reported = new WeakMap();

  function inspect(video, allowPaused = false) {
    if (!video || video.tagName !== "VIDEO" || video.readyState < 1
        || (!allowPaused && video.paused)) {
      return;
    }
    const url = video.currentSrc || video.src;
    if (!url || !CDGMediaSniffer.classify(url)) {
      return;
    }
    const rawThumbnail = video.poster
      || document.querySelector('meta[property="og:image"]')?.content
      || "";
    let thumbnailUrl = "";
    if (rawThumbnail) {
      try {
        const parsedThumbnail = new URL(rawThumbnail, document.baseURI);
        if (parsedThumbnail.protocol === "http:" || parsedThumbnail.protocol === "https:") {
          thumbnailUrl = parsedThumbnail.href;
        }
      } catch {
        thumbnailUrl = "";
      }
    }
    const metadata = {
      pageTitle: document.title.slice(0, 200),
      thumbnailUrl,
      videoWidth: video.videoWidth,
      videoHeight: video.videoHeight,
      durationSeconds: Number.isFinite(video.duration) ? Math.floor(video.duration) : 0
    };
    const reportKey = `${url}\n${metadata.thumbnailUrl}\n${metadata.videoWidth}x${metadata.videoHeight}`;
    if (reported.get(video) === reportKey) {
      return;
    }
    reported.set(video, reportKey);
    browser.runtime.sendMessage({ type: "page-media-detected", url, ...metadata });
  }

  document.addEventListener("playing", (event) => inspect(event.target), true);
  document.addEventListener(
    "loadedmetadata",
    (event) => inspect(event.target, true),
    true
  );

  if (window.top === window) {
    let previousScrollY = window.scrollY || document.documentElement.scrollTop || 0;
    let lastDirection = "";
    let lastReportAt = 0;
    window.addEventListener("scroll", () => {
      const currentScrollY = window.scrollY || document.documentElement.scrollTop || 0;
      const delta = currentScrollY - previousScrollY;
      previousScrollY = currentScrollY;
      if (Math.abs(delta) < 12) {
        return;
      }
      const direction = delta > 0 ? "down" : "up";
      const now = Date.now();
      if (direction === lastDirection && now - lastReportAt < 250) {
        return;
      }
      lastDirection = direction;
      lastReportAt = now;
      browser.runtime.sendMessage({ type: "page-scroll", direction });
    }, { passive: true });
  }
})();
