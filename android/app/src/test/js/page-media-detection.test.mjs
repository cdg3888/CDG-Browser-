import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const extensionPath = new URL("../../main/assets/web_extensions/cdg-adblock/", import.meta.url);
const snifferSource = await readFile(new URL("media-sniffer.js", extensionPath), "utf8");
const pageSource = await readFile(new URL("page-media.js", extensionPath), "utf8");

function createPageSniffer() {
  const listeners = {};
  const messages = [];
  const window = {
    scrollY: 0,
    addEventListener(name, callback) {
      listeners[`window:${name}`] = callback;
    }
  };
  window.top = window;
  const scope = {
    URL,
    window,
    document: {
      title: "Example video",
      baseURI: "https://site.example/watch",
      documentElement: { scrollTop: 0 },
      querySelector: () => ({ content: "https://images.example/poster.jpg" }),
      addEventListener(name, callback) {
        listeners[name] = callback;
      }
    },
    browser: {
      runtime: {
        sendMessage(message) {
          messages.push(message);
        }
      }
    }
  };
  scope.globalThis = scope;
  vm.runInNewContext(snifferSource, scope);
  vm.runInNewContext(pageSource, scope);
  return { listeners, messages, window };
}

test("reports a playing HTML5 video source once and ignores paused videos", () => {
  const { listeners, messages } = createPageSniffer();
  const video = {
    tagName: "VIDEO",
    paused: true,
    readyState: 4,
    videoWidth: 1280,
    poster: "/poster.jpg",
    currentSrc: "https://cdn.example/movie.mp4"
  };

  listeners.playing({ target: video });
  assert.equal(messages.length, 0);

  video.paused = false;
  listeners.playing({ target: video });
  listeners.loadedmetadata({ target: video });

  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "page-media-metadata");
  assert.equal(messages[0].url, "https://cdn.example/movie.mp4");
  assert.equal(messages[0].pageTitle, "Example video");
  assert.equal(messages[0].thumbnailUrl, "https://site.example/poster.jpg");
  assert.equal(messages[0].videoWidth, 1280);
  assert.equal(messages[0].durationSeconds, 0);
});

test("detects a paused video once its metadata has loaded", () => {
  const { listeners, messages } = createPageSniffer();
  listeners.loadedmetadata({
    target: {
      tagName: "VIDEO",
      paused: true,
      readyState: 1,
      videoWidth: 0,
      currentSrc: "https://cdn.example/movie.mp4"
    }
  });

  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "page-media-metadata");
  assert.equal(messages[0].url, "https://cdn.example/movie.mp4");
});

test("reports scroll direction for automatic address-bar hiding", () => {
  const { listeners, messages, window } = createPageSniffer();
  const onScroll = listeners["window:scroll"];
  window.scrollY = 24;
  onScroll();
  window.scrollY = 0;
  onScroll();

  assert.deepEqual(messages.map((message) => message.type), ["page-scroll", "page-scroll"]);
  assert.deepEqual(messages.map((message) => message.direction), ["down", "up"]);
});

test("ignores empty, non-video, and non-media sources", () => {
  const { listeners, messages } = createPageSniffer();
  listeners.playing({
    target: { tagName: "VIDEO", paused: false, readyState: 4, videoWidth: 640, currentSrc: "" }
  });
  listeners.playing({
    target: {
      tagName: "VIDEO",
      paused: false,
      readyState: 4,
      videoWidth: 640,
      currentSrc: "https://cdn.example/poster.jpg"
    }
  });
  listeners.playing({
    target: {
      tagName: "AUDIO",
      paused: false,
      readyState: 4,
      videoWidth: 640,
      currentSrc: "https://cdn.example/movie.mp4"
    }
  });

  assert.equal(messages.length, 0);
});
