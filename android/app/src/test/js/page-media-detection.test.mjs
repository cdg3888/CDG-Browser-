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
  const scope = {
    URL,
    document: {
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
  return { listeners, messages };
}

test("reports a playing HTML5 video source once and ignores paused videos", () => {
  const { listeners, messages } = createPageSniffer();
  const video = {
    tagName: "VIDEO",
    paused: true,
    readyState: 4,
    videoWidth: 1280,
    currentSrc: "https://cdn.example/movie.mp4"
  };

  listeners.playing({ target: video });
  assert.equal(messages.length, 0);

  video.paused = false;
  listeners.playing({ target: video });
  listeners.loadedmetadata({ target: video });

  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "page-media-detected");
  assert.equal(messages[0].url, "https://cdn.example/movie.mp4");
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
