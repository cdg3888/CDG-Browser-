(() => {
  const reported = new WeakMap();

  function inspect(video) {
    if (!video || video.tagName !== "VIDEO" || video.paused || video.readyState < 2
        || video.videoWidth <= 0) {
      return;
    }
    const url = video.currentSrc || video.src;
    if (!url || !CDGMediaSniffer.classify(url)) {
      return;
    }
    if (reported.get(video) === url) {
      return;
    }
    reported.set(video, url);
    browser.runtime.sendMessage({ type: "page-media-detected", url });
  }

  document.addEventListener("playing", (event) => inspect(event.target), true);
  document.addEventListener("loadedmetadata", (event) => inspect(event.target), true);
})();
