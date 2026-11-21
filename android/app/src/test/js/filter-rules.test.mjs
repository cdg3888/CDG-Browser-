import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(
  new URL("../../main/assets/web_extensions/cdg-adblock/filter-rules.js", import.meta.url),
  "utf8"
);
const scope = { globalThis: {} };
vm.runInNewContext(source, scope);
const rules = scope.globalThis.CDGFilterRules;

test("parses hosts entries and ignores comments, IPs, and malformed names", () => {
  const parsed = rules.parseHostsList([
    "# generated list",
    "0.0.0.0 ads.example",
    "127.0.0.1 tracker.example.",
    "::1 localhost",
    "0.0.0.0 192.0.2.1",
    "0.0.0.0 invalid..example"
  ].join("\n"));

  assert.deepEqual([...parsed].sort(), ["ads.example", "tracker.example"]);
});

test("matches exact hosts and subdomains without matching lookalike suffixes", () => {
  const blocked = new Set(["tracker.example"]);

  assert.equal(rules.matches("tracker.example", blocked), true);
  assert.equal(rules.matches("cdn.tracker.example", blocked), true);
  assert.equal(rules.matches("nottracker.example", blocked), false);
});

test("identifies same-site subdomains in either direction", () => {
  assert.equal(rules.isFirstParty("shop.example.com", "example.com"), true);
  assert.equal(rules.isFirstParty("example.com", "shop.example.com"), true);
  assert.equal(rules.isFirstParty("example.com", "evil-example.com"), false);
});

test("rejects oversized host lists", () => {
  assert.throws(
    () => rules.parseHostsList("0.0.0.0 one.example\n0.0.0.0 two.example", 1),
    (error) => error.name === "RangeError"
  );
});
