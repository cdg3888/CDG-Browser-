import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(
  new URL("../../main/assets/web_extensions/cdg-adblock/media-sniffer.js", import.meta.url),
  "utf8"
);
const scope = { globalThis: {}, URL };
vm.runInNewContext(source, scope);
const { classify } = scope.globalThis.CDGMediaSniffer;

test("classifies HLS and DASH manifests, including query strings", () => {
  assert.equal(classify("https://cdn.example/live.m3u8?token=abc"), "HLS");
  assert.equal(classify("https://cdn.example/live.m3u?token=abc"), "HLS");
  assert.equal(classify("https://cdn.example/stream.mpd?sig=abc"), "DASH");
  assert.equal(classify("https://cdn.example/channel.ism/manifest"), "DASH");
});

test("classifies direct video URLs and ignores segments and non-HTTP schemes", () => {
  assert.equal(classify("https://cdn.example/movie.mp4"), "Video");
  assert.equal(classify("https://cdn.example/chunk.m4s"), null);
  assert.equal(classify("blob:https://example.com/movie.mp4"), null);
  assert.equal(classify("not a URL"), null);
});

test("classifies streaming responses by content type", () => {
  const { classifyContentType } = scope.globalThis.CDGMediaSniffer;
  assert.equal(classifyContentType("application/vnd.apple.mpegurl; charset=utf-8"), "HLS");
  assert.equal(classifyContentType("application/dash+xml"), "DASH");
  assert.equal(classifyContentType("video/mp4"), "Video");
  assert.equal(classifyContentType("video/mp2t"), null);
  assert.equal(classifyContentType(null), null);
});
