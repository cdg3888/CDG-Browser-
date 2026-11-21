(() => {
  const FILTER_LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
  const MIN_UPDATED_DOMAINS = 50000;
  const MAX_FILTER_BYTES = 5 * 1024 * 1024;
  const NATIVE_APP = "com.cdg.browser";
  const DEFAULT_DOMAINS = new Set(
    globalThis.CDG_DEFAULT_FILTERS
      .map(CDGFilterRules.normalizeDomain)
      .filter(Boolean)
  );
  const blockedDomains = new Set(DEFAULT_DOMAINS);

  let enabled = false;
  let configured = false;
  let configurationReceived = false;
  let filtersLoaded = false;
  let allowedSites = new Set();
  let blockedCount = 0;
  let lastUpdated = null;
  let updateInProgress = false;
  let nativePort = null;
  let reconnectDelayMs = 500;
  const reportedMedia = new Set();

  function send(message) {
    if (nativePort) {
      nativePort.postMessage(message);
    }
  }

  function publishState(type = "state", message = "") {
    send({
      type,
      enabled,
      configured,
      blockedCount,
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

  function reportMedia(details) {
    const pageUrl = details.documentUrl || details.originUrl || details.initiator;
    const mediaType = details.incognito === true
      ? null
      : CDGMediaSniffer.classify(details.url);
    if (mediaType && pageUrl) {
      const key = `${pageUrl}\n${details.url}`;
      if (!reportedMedia.has(key)) {
        reportedMedia.add(key);
        if (reportedMedia.size > 200) {
          reportedMedia.delete(reportedMedia.values().next().value);
        }
        send({
          type: "media-detected",
          mediaType,
          url: details.url,
          pageUrl
        });
      }
    }
  }

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
      if (blockedCount === 1 || blockedCount % 25 === 0) {
        publishState();
      }
      return { cancel: true };
    }

    reportMedia(details);
    return {};
  }

  browser.webRequest.onBeforeRequest.addListener(
    handleRequest,
    { urls: ["<all_urls>"] },
    ["blocking"]
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
    try {
      const response = await fetch(FILTER_LIST_URL, {
        cache: "no-store",
        credentials: "omit",
        referrerPolicy: "no-referrer"
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
      const message = error instanceof Error
        ? error.message
        : "No se pudo actualizar la lista.";
      publishState("update-error", message);
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
