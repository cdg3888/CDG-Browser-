(() => {
  const FILTER_LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
  const MIN_UPDATED_DOMAINS = 50000;
  const MAX_FILTER_BYTES = 5 * 1024 * 1024;
  const FILTER_FETCH_TIMEOUT_MS = 30_000;
  const NATIVE_APP = "com.cdg.browser";
  const DEFAULT_DOMAINS = new Set(
    globalThis.CDG_DEFAULT_FILTERS
      .map(CDGFilterRules.normalizeDomain)
      .filter(Boolean)
  );
  const blockedDomains = new Set(DEFAULT_DOMAINS);

  let enabled = false;
  let mediaDetectionEnabled = true;
  let configured = false;
  let configurationReceived = false;
  let filtersLoaded = false;
  let allowedSites = new Set();
  let blockedCount = 0;
  const blockedByDomain = new Map();
  let lastUpdated = null;
  let updateInProgress = false;
  let nativePort = null;
  let reconnectDelayMs = 500;
  const reportedMedia = new Map();
  const pageMediaMetadata = new Map();

  function send(message) {
    if (nativePort) {
      nativePort.postMessage(message);
    }
  }

  function isSafeHttpUrl(value) {
    try {
      const uri = new URL(value);
      return (uri.protocol === "http:" || uri.protocol === "https:")
        && !uri.username && !uri.password && Boolean(uri.hostname);
    } catch {
      return false;
    }
  }

  function publishState(type = "state", message = "") {
    send({
      type,
      enabled,
      configured,
      blockedCount,
      blockedByDomain: Array.from(blockedByDomain.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 8)
        .map(([domain, count]) => ({ domain, count })),
      ruleCount: blockedDomains.size,
      lastUpdated,
      updateInProgress,
      message
    });
  }

  function getHost(value) {
    if (typeof value !== "string" || !value) {
      return null;
    }
    try {
      return CDGFilterRules.normalizeDomain(new URL(value).hostname);
    } catch {
      return null;
    }
  }

  function isAllowedSite(host) {
    for (const allowedSite of allowedSites) {
      if (host === allowedSite || host.endsWith(`.${allowedSite}`)) {
        return true;
      }
    }
    return false;
  }

  function reportMedia(details, contentType, isPlaying = false, pageMetadata = null) {
    const pageUrl = details.documentUrl || details.originUrl || details.initiator;
    const urlType = CDGMediaSniffer.classify(details.url);
    const responseType = CDGMediaSniffer.classifyContentType(contentType);
    const mediaType = details.incognito === true || !mediaDetectionEnabled
      ? null
      : responseType || urlType;
    if (mediaType === "Video" && !isPlaying
        && details.type !== "media" && responseType !== "Video") {
      return;
    }
    if (mediaType && pageUrl) {
      const key = `${pageUrl}\n${details.url}`;
      const previous = reportedMedia.get(key) || {};
      const savedPageMetadata = pageMediaMetadata.get(pageUrl);
      const effectiveMetadata = pageMetadata || savedPageMetadata;
      const metadata = {
        pageTitle: typeof effectiveMetadata?.pageTitle === "string"
          ? effectiveMetadata.pageTitle.trim().slice(0, 200) : "",
        thumbnailUrl: typeof effectiveMetadata?.thumbnailUrl === "string"
          && effectiveMetadata.thumbnailUrl.length <= 2048
          && /^https?:\/\//i.test(effectiveMetadata.thumbnailUrl)
          ? effectiveMetadata.thumbnailUrl : "",
        videoWidth: Number.isInteger(effectiveMetadata?.videoWidth)
          ? Math.min(Math.max(effectiveMetadata.videoWidth, 0), 16384) : 0,
        videoHeight: Number.isInteger(effectiveMetadata?.videoHeight)
          ? Math.min(Math.max(effectiveMetadata.videoHeight, 0), 16384) : 0,
        durationSeconds: Number.isInteger(effectiveMetadata?.durationSeconds)
          ? Math.min(Math.max(effectiveMetadata.durationSeconds, 0), 604800) : 0
      };
      const combined = {
        pageTitle: metadata.pageTitle || previous.pageTitle || "",
        thumbnailUrl: metadata.thumbnailUrl || previous.thumbnailUrl || "",
        videoWidth: metadata.videoWidth || previous.videoWidth || 0,
        videoHeight: metadata.videoHeight || previous.videoHeight || 0,
        durationSeconds: metadata.durationSeconds || previous.durationSeconds || 0
      };
      const changed = !reportedMedia.has(key)
        || Object.keys(combined).some((field) => combined[field] !== previous[field]);
      if (changed) {
        reportedMedia.set(key, { ...combined, mediaType });
        if (reportedMedia.size > 200) {
          reportedMedia.delete(reportedMedia.keys().next().value);
        }
        send({
          type: "media-detected",
          mediaType,
          url: details.url,
          pageUrl,
          ...combined
        });
      }
    }
  }

  browser.runtime.onMessage.addListener((message, sender) => {
    if (!message || message.type !== "page-media-detected"
        && message.type !== "page-media-metadata"
        && message.type !== "page-scroll"
        && message.type !== "page-images-detected"
        || sender.tab?.incognito === true) {
      return;
    }
    if (message.type === "page-media-metadata") {
      const pageUrl = sender.url || sender.tab?.url;
      if (!pageUrl || !getHost(pageUrl)) {
        return;
      }
      const metadata = {
        pageTitle: typeof message.pageTitle === "string"
          ? message.pageTitle.trim().slice(0, 200) : "",
        thumbnailUrl: typeof message.thumbnailUrl === "string"
          && message.thumbnailUrl.length <= 2048
          && /^https?:\/\//i.test(message.thumbnailUrl) ? message.thumbnailUrl : "",
        videoWidth: Number.isInteger(message.videoWidth)
          ? Math.min(Math.max(message.videoWidth, 0), 16384) : 0,
        videoHeight: Number.isInteger(message.videoHeight)
          ? Math.min(Math.max(message.videoHeight, 0), 16384) : 0,
        durationSeconds: Number.isInteger(message.durationSeconds)
          ? Math.min(Math.max(message.durationSeconds, 0), 604800) : 0
      };
      pageMediaMetadata.delete(pageUrl);
      pageMediaMetadata.set(pageUrl, metadata);
      if (pageMediaMetadata.size > 100) {
        pageMediaMetadata.delete(pageMediaMetadata.keys().next().value);
      }
      for (const [key, previous] of reportedMedia) {
        if (!key.startsWith(`${pageUrl}\n`)) {
          continue;
        }
        const combined = {
          pageTitle: metadata.pageTitle || previous.pageTitle || "",
          thumbnailUrl: metadata.thumbnailUrl || previous.thumbnailUrl || "",
          videoWidth: metadata.videoWidth || previous.videoWidth || 0,
          videoHeight: metadata.videoHeight || previous.videoHeight || 0,
          durationSeconds: metadata.durationSeconds || previous.durationSeconds || 0
        };
        if (Object.keys(combined).some((field) => combined[field] !== previous[field])) {
          reportedMedia.set(key, { ...combined, mediaType: previous.mediaType });
          const url = key.slice(pageUrl.length + 1);
          send({
            type: "media-detected",
            mediaType: previous.mediaType || CDGMediaSniffer.classify(url) || "Video",
            url,
            pageUrl,
            ...combined
          });
        }
      }
      if (typeof message.url === "string" && CDGMediaSniffer.classify(message.url)) {
        reportMedia({
          url: message.url,
          documentUrl: pageUrl,
          type: "media",
          incognito: false
        }, null, true, metadata);
      }
      return;
    }
    if (message.type === "page-images-detected") {
      const pageUrl = sender.url || sender.tab?.url;
      if (!pageUrl || !getHost(pageUrl) || !Array.isArray(message.images)) {
        return;
      }
      const images = message.images.slice(0, 200).filter((image) =>
        image && typeof image.url === "string" && image.url.length <= 8192
        && isSafeHttpUrl(image.url)
      ).map((image) => ({
        url: image.url,
        label: typeof image.label === "string" ? image.label.slice(0, 120) : "",
        width: Number.isInteger(image.width) ? Math.min(Math.max(image.width, 0), 32768) : 0,
        height: Number.isInteger(image.height) ? Math.min(Math.max(image.height, 0), 32768) : 0
      }));
      if (images.length) {
        send({
          type: "page-images-detected",
          pageUrl,
          pageTitle: typeof message.pageTitle === "string"
            ? message.pageTitle.slice(0, 200) : "",
          images
        });
      }
      return;
    }
    if (message.type === "page-scroll") {
      const pageUrl = sender.url || sender.tab?.url;
      if (pageUrl && getHost(pageUrl)
          && (message.direction === "up" || message.direction === "down")) {
        send({
          type: "page-scroll",
          pageUrl,
          direction: message.direction
        });
      }
      return;
    }
    const url = typeof message.url === "string" ? message.url : "";
    const pageUrl = sender.url || sender.tab?.url;
    const mediaType = CDGMediaSniffer.classify(url);
    if (mediaType === "Video" && pageUrl && getHost(pageUrl)) {
      reportMedia({
        url,
        documentUrl: pageUrl,
        type: "media",
        incognito: sender.tab?.incognito === true
      }, null, true, message);
    }
  });

  function handleRequest(details) {
    const requestHost = getHost(details.url);
    const pageHost = getHost(details.documentUrl || details.originUrl || details.initiator);
    const shouldBlock = enabled
      && details.type !== "main_frame"
      && requestHost
      && pageHost
      && !isAllowedSite(pageHost)
      && !CDGFilterRules.isFirstParty(pageHost, requestHost)
      && CDGFilterRules.matches(requestHost, blockedDomains);
    if (shouldBlock) {
      blockedCount += 1;
      blockedByDomain.set(requestHost, (blockedByDomain.get(requestHost) || 0) + 1);
      if (blockedCount === 1 || blockedCount % 25 === 0) {
        publishState();
      }
      return { cancel: true };
    }

    reportMedia(details);
    return {};
  }

  function handleResponseHeaders(details) {
    const contentType = details.responseHeaders
      ?.find((header) => header.name.toLowerCase() === "content-type")
      ?.value;
    if (contentType) {
      reportMedia(details, contentType);
    }
    return {};
  }

  browser.webRequest.onBeforeRequest.addListener(
    handleRequest,
    { urls: ["<all_urls>"] },
    ["blocking"]
  );
  browser.webRequest.onHeadersReceived.addListener(
    handleResponseHeaders,
    { urls: ["http://*/*", "https://*/*"] },
    ["responseHeaders"]
  );

  async function loadSavedFilters() {
    const saved = await browser.storage.local.get(["updatedDomains", "lastUpdated"]);
    if (Array.isArray(saved.updatedDomains)) {
      if (saved.updatedDomains.length > CDGFilterRules.MAX_DOMAINS) {
        throw new Error("La lista guardada supera el límite de dominios permitido.");
      }
      if (saved.updatedDomains.length >= MIN_UPDATED_DOMAINS) {
        for (const value of saved.updatedDomains) {
          const domain = CDGFilterRules.normalizeDomain(value);
          if (domain) {
            blockedDomains.add(domain);
          }
        }
        lastUpdated = typeof saved.lastUpdated === "string" ? saved.lastUpdated : null;
      }
    }
    filtersLoaded = true;
    configured = configurationReceived;
    publishState();
  }

  async function updateFilters() {
    if (updateInProgress) {
      return;
    }
    updateInProgress = true;
    publishState("update-started");
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), FILTER_FETCH_TIMEOUT_MS);
    try {
      const response = await fetch(FILTER_LIST_URL, {
        cache: "no-store",
        credentials: "omit",
        referrerPolicy: "no-referrer",
        signal: controller.signal
      });
      if (!response.ok) {
        throw new Error(`El servidor respondió con HTTP ${response.status}.`);
      }
      const contentLength = Number(response.headers.get("content-length"));
      if (Number.isFinite(contentLength) && contentLength > MAX_FILTER_BYTES) {
        throw new Error("El archivo de filtros excede el tamaño permitido.");
      }
      const text = await response.text();
      if (text.length > MAX_FILTER_BYTES) {
        throw new Error("El archivo de filtros excede el tamaño permitido.");
      }
      const updatedDomains = CDGFilterRules.parseHostsList(
        text,
        CDGFilterRules.MAX_DOMAINS
      );
      if (updatedDomains.size < MIN_UPDATED_DOMAINS) {
        throw new Error("La lista recibida está incompleta o tiene un formato no compatible.");
      }

      const updatedAt = new Date().toISOString();
      await browser.storage.local.set({
        updatedDomains: Array.from(updatedDomains),
        lastUpdated: updatedAt
      });
      for (const domain of updatedDomains) {
        blockedDomains.add(domain);
      }
      lastUpdated = updatedAt;
      await browser.webRequest.handlerBehaviorChanged();
      updateInProgress = false;
      publishState("update-complete", "Lista actualizada.");
    } catch (error) {
      updateInProgress = false;
      const message = error?.name === "AbortError"
        ? "La actualización de filtros superó el tiempo máximo."
        : error instanceof Error ? error.message : "No se pudo actualizar la lista.";
      publishState("update-error", message);
    } finally {
      clearTimeout(timeoutId);
    }
  }

  function connectNative() {
    const port = browser.runtime.connectNative(NATIVE_APP);
    nativePort = port;
    port.onMessage.addListener((message) => {
      if (port !== nativePort) {
        return;
      }
      reconnectDelayMs = 500;
      if (!message || typeof message !== "object") {
        publishState("engine-error", "Se recibió una configuración no válida.");
        return;
      }
      if (message.type === "configure") {
        enabled = message.enabled === true;
        const nextMediaDetectionEnabled = message.mediaDetectionEnabled !== false;
        if (nextMediaDetectionEnabled !== mediaDetectionEnabled) {
          reportedMedia.clear();
        }
        mediaDetectionEnabled = nextMediaDetectionEnabled;
        configurationReceived = true;
        configured = filtersLoaded;
        allowedSites = new Set(
          Array.isArray(message.allowedSites)
            ? message.allowedSites
                .map(CDGFilterRules.normalizeDomain)
                .filter(Boolean)
            : []
        );
        publishState();
      } else if (message.type === "update-filters") {
        updateFilters();
      }
    });
    port.onDisconnect.addListener(() => {
      if (port !== nativePort) {
        return;
      }
      nativePort = null;
      configured = false;
      const delay = reconnectDelayMs;
      reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30000);
      setTimeout(connectNative, delay);
    });
  }

  connectNative();
  loadSavedFilters().catch((error) => {
    const message = error instanceof Error
      ? error.message
      : "No se pudo leer la lista local.";
    publishState("engine-error", message);
  });

})();
