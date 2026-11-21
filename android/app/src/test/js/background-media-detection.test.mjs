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
