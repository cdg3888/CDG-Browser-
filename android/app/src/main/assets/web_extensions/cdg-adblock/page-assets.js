(() => {
  const MAX_IMAGES_PER_PAGE = 200;
  const MIN_IMAGE_EDGE = 96;
  let lastSignature = "";
  let scanScheduled = false;

  function safeImageUrl(value) {
    if (typeof value !== "string" || !value) {
      return "";
    }
    try {
      const url = new URL(value, document.baseURI);
      return url.protocol === "http:" || url.protocol === "https:" ? url.href : "";
    } catch {
      return "";
    }
  }

  function bestSrcset(sourceSet) {
    if (!sourceSet) {
      return "";
    }
    let bestUrl = "";
    let bestScore = -1;
    for (const candidate of sourceSet.split(",")) {
      const match = candidate.trim().match(/^(\S+)(?:\s+([\d.]+)(w|x))?$/i);
      if (!match) {
        continue;
      }
      const score = match[2] ? Number(match[2]) * (match[3].toLowerCase() === "x" ? 1000 : 1) : 0;
      if (score > bestScore) {
        bestUrl = safeImageUrl(match[1]);
        bestScore = score;
      }
    }
    return bestUrl;
  }

  function candidateFor(image) {
    const sources = [
      image.dataset.full,
      image.dataset.original,
      image.dataset.zoomImage,
      image.dataset.large,
      bestSrcset(image.srcset),
      image.currentSrc,
      image.dataset.src,
      image.src
    ];
    const url = sources.map(safeImageUrl).find(Boolean) || "";
    if (!url || (image.naturalWidth > 0 && image.naturalWidth < MIN_IMAGE_EDGE
        && image.naturalHeight > 0 && image.naturalHeight < MIN_IMAGE_EDGE)) {
      return null;
    }
    let label = image.alt || image.getAttribute("aria-label") || "";
    if (!label) {
      try {
        label = new URL(url).pathname.split("/").pop() || "Imagen";
      } catch {
        label = "Imagen";
      }
    }
    return {
      url,
      label: String(label).trim().slice(0, 120),
      width: Number.isInteger(image.naturalWidth) ? image.naturalWidth : 0,
      height: Number.isInteger(image.naturalHeight) ? image.naturalHeight : 0
    };
  }

  function scan() {
    scanScheduled = false;
    const found = new Map();
    for (const image of document.images) {
      const candidate = candidateFor(image);
      if (candidate) {
        const previous = found.get(candidate.url);
        if (!previous || candidate.width * candidate.height > previous.width * previous.height) {
          found.set(candidate.url, candidate);
        }
      }
      if (found.size >= MAX_IMAGES_PER_PAGE) {
        break;
      }
    }
    const images = Array.from(found.values())
      .sort((left, right) => right.width * right.height - left.width * left.height);
    const signature = images.map((image) =>
      `${image.url}\n${image.width}x${image.height}`
    ).join("\n");
    if (!images.length || signature === lastSignature) {
      return;
    }
    lastSignature = signature;
    browser.runtime.sendMessage({
      type: "page-images-detected",
      pageTitle: document.title.slice(0, 200),
      images
    });
  }

  function scheduleScan() {
    if (scanScheduled) {
      return;
    }
    scanScheduled = true;
    setTimeout(scan, 500);
  }

  if (window.top === window) {
    scan();
    window.addEventListener("load", scheduleScan, { once: true });
    const observer = new MutationObserver(scheduleScan);
    observer.observe(document.documentElement, {
      attributes: true,
      childList: true,
      subtree: true,
      attributeFilter: ["src", "srcset", "data-src", "data-original"]
    });
  }
})();
