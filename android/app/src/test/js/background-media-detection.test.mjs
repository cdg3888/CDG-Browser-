import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const extensionPath = new URL("../../main/assets/web_extensions/cdg-adblock/", import.meta.url);
const snifferSource = await readFile(new URL("media-sniffer.js", extensionPath), "utf8");
const backgroundSource = await readFile(new URL("background.js", extensionPath), "utf8");

function createBackground(fetchImpl = async () => {}) {
  const messages = [];
  const listeners = {};
  const timers = [];
  let onPortMessage;
  const port = {
    onMessage: {
      addListener(listener) {
        onPortMessage = listener;
      }
    },
    onDisconnect: { addListener() {} },
    postMessage(message) {
      messages.push(message);
    }
  };
  const scope = {
    URL,
    AbortController,
    setTimeout(callback) {
      timers.push(callback);
      return timers.length;
    },
    clearTimeout() {},
    fetch: fetchImpl,
    CDG_DEFAULT_FILTERS: [],
    CDGFilterRules: {
      MAX_DOMAINS: 100,
      normalizeDomain: (domain) => domain,
      isFirstParty: () => false,
      matches: () => false
    },
    CDGMediaSniffer: {
      classify(url) {
        return url.endsWith(".mp4") ? "Video" : null;
      },
      classifyContentType(contentType) {
        return contentType?.split(";", 1)[0].trim().toLowerCase() === "video/mp4"
          ? "Video"
          : null;
      }
    },
    browser: {
      runtime: {
        connectNative: () => port,
        onMessage: {
          addListener(listener) {
            listeners.runtimeMessage = listener;
          }
        }
      },
      webRequest: {
        onBeforeRequest: {
          addListener(listener) {
            listeners.beforeRequest = listener;
          }
        },
        onHeadersReceived: {
          addListener(listener) {
            listeners.headersReceived = listener;
          }
        },
        handlerBehaviorChanged: async () => {}
      },
      storage: {
        local: {
          get: async () => ({}),
          set: async () => {}
        }
      }
    }
  };
  scope.globalThis = scope;
  vm.runInNewContext(snifferSource, scope);
  vm.runInNewContext(backgroundSource, scope);
  return {
    listeners,
    messages,
    timers,
    sendPortMessage: (message) => onPortMessage(message)
  };
}

test("detects response MIME types and sends one record for repeated requests", () => {
  const { listeners, messages } = createBackground();
  const details = {
    url: "https://cdn.example/playlist?id=42",
    documentUrl: "https://site.example/watch/42",
    responseHeaders: [
      { name: "Content-Type", value: "application/vnd.apple.mpegurl; charset=utf-8" }
    ]
  };

  listeners.headersReceived(details);
  listeners.headersReceived(details);

  const detected = messages.filter((message) => message.type === "media-detected");
  assert.equal(detected.length, 1);
  assert.equal(detected[0].mediaType, "HLS");
  assert.equal(detected[0].pageUrl, details.documentUrl);
  assert.equal(detected[0].url, details.url);
});

test("deduplicates manifest detection between request and response hooks", () => {
  const { listeners, messages } = createBackground();
  const details = {
    url: "https://cdn.example/live.m3u8",
    documentUrl: "https://site.example/watch",
    type: "xmlhttprequest",
    responseHeaders: [{ name: "Content-Type", value: "application/vnd.apple.mpegurl" }]
  };

  listeners.beforeRequest(details);
  listeners.headersReceived(details);

  assert.equal(
    messages.filter((message) => message.type === "media-detected").length,
    1
  );
});

test("accepts HTML5 media reports from normal tabs and ignores private tabs", () => {
  const { listeners, messages } = createBackground();
  const message = {
    type: "page-media-detected",
    url: "https://cdn.example/movie.mp4"
  };

  listeners.runtimeMessage(message, {
    url: "https://site.example/watch",
    tab: { url: "https://site.example/watch", incognito: false }
  });
  listeners.runtimeMessage(message, {
    url: "https://private.example/watch",
    tab: { url: "https://private.example/watch", incognito: true }
  });

  const detected = messages.filter((item) => item.type === "media-detected");
  assert.equal(detected.length, 1);
  assert.equal(detected[0].pageUrl, "https://site.example/watch");
  assert.equal(detected[0].mediaType, "Video");
});

test("enriches an existing detection with page title and poster metadata", () => {
  const { listeners, messages } = createBackground();
  const url = "https://cdn.example/movie.mp4";
  const pageUrl = "https://site.example/watch";
  listeners.beforeRequest({
    url,
    documentUrl: pageUrl,
    type: "media"
  });
  listeners.runtimeMessage({
    type: "page-media-detected",
    url,
    pageTitle: "Example film",
    thumbnailUrl: "https://images.example/poster.jpg",
    videoWidth: 1920,
    videoHeight: 1080,
    durationSeconds: 120
  }, {
    url: pageUrl,
    tab: { url: pageUrl, incognito: false }
  });
  const detected = messages.filter((message) => message.type === "media-detected");
  assert.equal(detected.length, 2);
  assert.equal(detected[1].pageTitle, "Example film");
  assert.equal(detected[1].thumbnailUrl, "https://images.example/poster.jpg");
  assert.equal(detected[1].videoHeight, 1080);
});

test("applies player metadata to HLS links detected before or after the player", () => {
  const { listeners, messages } = createBackground();
  const url = "https://cdn.example/live.m3u8?token=abc";
  const pageUrl = "https://site.example/watch";
  listeners.beforeRequest({ url, documentUrl: pageUrl, type: "xmlhttprequest" });
  listeners.runtimeMessage({
    type: "page-media-metadata",
    pageTitle: "Live event",
    thumbnailUrl: "https://images.example/live.jpg",
    videoWidth: 1920,
    videoHeight: 1080,
    durationSeconds: 3600
  }, {
    url: pageUrl,
    tab: { url: pageUrl, incognito: false }
  });

  const enriched = messages.filter((message) => message.type === "media-detected").at(-1);
  assert.equal(enriched.url, url);
  assert.equal(enriched.mediaType, "HLS");
  assert.equal(enriched.pageTitle, "Live event");
  assert.equal(enriched.thumbnailUrl, "https://images.example/live.jpg");
  assert.equal(enriched.videoHeight, 1080);
  assert.equal(enriched.durationSeconds, 3600);

  listeners.beforeRequest({
    url: "https://cdn.example/another.m3u8",
    documentUrl: pageUrl,
    type: "xmlhttprequest"
  });
  const later = messages.filter((message) => message.type === "media-detected").at(-1);
  assert.equal(later.pageTitle, "Live event");
  assert.equal(later.thumbnailUrl, "https://images.example/live.jpg");
});

test("forwards top-page scroll events and honors the media detection setting", () => {
  const { listeners, messages, sendPortMessage } = createBackground();
  const pageUrl = "https://site.example/watch";
  listeners.runtimeMessage({
    type: "page-scroll",
    direction: "down"
  }, {
    url: pageUrl,
    tab: { url: pageUrl, incognito: false }
  });
  assert.equal(messages.at(-1).type, "page-scroll");
  assert.equal(messages.at(-1).direction, "down");

  sendPortMessage({
    type: "configure",
    enabled: false,
    mediaDetectionEnabled: false
  });
  listeners.beforeRequest({
    url: "https://cdn.example/movie.mp4",
    documentUrl: pageUrl,
    type: "media"
  });
  assert.equal(messages.filter((message) => message.type === "media-detected").length, 0);
});

test("forwards only bounded HTTP image candidates from normal tabs", () => {
  const { listeners, messages } = createBackground();
  const pageUrl = "https://site.example/gallery";
  listeners.runtimeMessage({
    type: "page-images-detected",
    pageTitle: "Gallery",
    images: [
      { url: "https://cdn.example/full.jpg", width: 1920, height: 1080 },
      { url: "data:image/png;base64,abc" },
      { url: "https://user:pass@cdn.example/private.jpg" }
    ]
  }, {
    url: pageUrl,
    tab: { url: pageUrl, incognito: false }
  });
  listeners.runtimeMessage({
    type: "page-images-detected",
    images: [{ url: "https://cdn.example/private.jpg" }]
  }, {
    url: "https://private.example/gallery",
    tab: { url: "https://private.example/gallery", incognito: true }
  });

  const detected = messages.filter((message) => message.type === "page-images-detected");
  assert.equal(detected.length, 1);
  assert.equal(detected[0].pageUrl, pageUrl);
  assert.equal(detected[0].images.length, 1);
  assert.equal(detected[0].images[0].url, "https://cdn.example/full.jpg");
});

test("ignores MP4 URLs requested as images unless the response is video", () => {
  const { listeners, messages } = createBackground();
  const details = {
    url: "https://cdn.example/preview.mp4",
    documentUrl: "https://site.example/watch",
    type: "image"
  };

  listeners.beforeRequest(details);
  listeners.headersReceived({
    ...details,
    responseHeaders: [{ name: "Content-Type", value: "image/jpeg" }]
  });
  assert.equal(messages.filter((message) => message.type === "media-detected").length, 0);

  listeners.headersReceived({
    ...details,
    type: "xmlhttprequest",
    responseHeaders: [{ name: "Content-Type", value: "video/mp4" }]
  });
  const detected = messages.filter((message) => message.type === "media-detected");
  assert.equal(detected.length, 1);
  assert.equal(detected[0].mediaType, "Video");
});

test("aborts a stalled manual filter update and reports the timeout", async () => {
  const { messages, sendPortMessage, timers } = createBackground(
    (_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener("abort", () => {
        reject(Object.assign(new Error("aborted"), { name: "AbortError" }));
      });
    })
  );

  sendPortMessage({ type: "update-filters" });
  timers[0]();
  await new Promise((resolve) => setImmediate(resolve));

  const error = messages.find((message) => message.type === "update-error");
  assert.match(error?.message ?? "", /superó el tiempo máximo/);
  assert.equal(error?.updateInProgress, false);
});
