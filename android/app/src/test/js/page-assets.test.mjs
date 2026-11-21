import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL(
  "../../main/assets/web_extensions/cdg-adblock/page-assets.js",
  import.meta.url
), "utf8");

function createPage(images, topLevel = true) {
  const messages = [];
  const listeners = {};
  const window = {
    addEventListener(name, callback) {
      listeners[name] = callback;
    }
  };
  window.top = topLevel ? window : {};
  class MutationObserver {
    constructor(callback) {
      this.callback = callback;
    }

    observe() {}
  }
  const scope = {
    URL,
    window,
    document: {
      images,
      title: "Gallery",
      baseURI: "https://site.example/gallery",
      documentElement: {},
    },
    browser: {
      runtime: {
        sendMessage(message) {
          messages.push(message);
        }
      }
    },
    MutationObserver,
    setTimeout(callback) {
      callback();
    }
  };
  scope.globalThis = scope;
  vm.runInNewContext(source, scope);
  return { listeners, messages };
}

test("collects the highest-resolution HTTP source and ignores tiny or unsafe images", () => {
  const { messages } = createPage([
    {
      src: "/small.jpg",
      currentSrc: "https://cdn.example/current.jpg",
      srcset: "/small.jpg 320w, https://cdn.example/large.jpg 1920w",
      naturalWidth: 640,
      naturalHeight: 360,
      alt: "Poster",
      dataset: {}
    },
    {
      src: "https://cdn.example/pixel.png",
      currentSrc: "https://cdn.example/pixel.png",
      srcset: "",
      naturalWidth: 1,
      naturalHeight: 1,
      alt: "",
      dataset: {}
    },
    {
      src: "data:image/png;base64,abc",
      currentSrc: "data:image/png;base64,abc",
      srcset: "",
      naturalWidth: 400,
      naturalHeight: 300,
      alt: "",
      dataset: {}
    }
  ]);

  assert.equal(messages.length, 1);
  assert.equal(messages[0].type, "page-images-detected");
  assert.equal(messages[0].images.length, 1);
  assert.equal(messages[0].images[0].url, "https://cdn.example/large.jpg");
  assert.equal(messages[0].images[0].label, "Poster");
});

test("deduplicates unchanged scans and ignores iframe documents", () => {
  const image = {
    src: "https://cdn.example/image.jpg",
    currentSrc: "https://cdn.example/image.jpg",
    srcset: "",
    naturalWidth: 800,
    naturalHeight: 600,
    alt: "Image",
    dataset: {}
  };
  const { listeners, messages } = createPage([image]);
  listeners.load();

  assert.equal(messages.length, 1);
  assert.equal(messages[0].images.length, 1);

  const iframe = createPage([image], false);
  assert.equal(iframe.messages.length, 0);
});
