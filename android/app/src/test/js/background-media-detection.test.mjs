import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const extensionPath = new URL("../../main/assets/web_extensions/cdg-adblock/", import.meta.url);
const snifferSource = await readFile(new URL("media-sniffer.js", extensionPath), "utf8");
const backgroundSource = await readFile(new URL("background.js", extensionPath), "utf8");

function createBackground() {
  const messages = [];
  const listeners = {};
  const port = {
    onMessage: { addListener() {} },
    onDisconnect: { addListener() {} },
    postMessage(message) {
      messages.push(message);
    }
  };
  const scope = {
    URL,
    setTimeout() {},
    CDG_DEFAULT_FILTERS: [],
    CDGFilterRules: {
      MAX_DOMAINS: 100,
      normalizeDomain: (domain) => domain,
      isFirstParty: () => false,
      matches: () => false
    },
    browser: {
      runtime: { connectNative: () => port },
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
  return { listeners, messages };
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
