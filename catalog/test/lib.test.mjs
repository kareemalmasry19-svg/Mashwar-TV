import { test } from "node:test";
import assert from "node:assert/strict";
import {
  isHttpUrl, compactStream, compactChannel, dedupe, sortChannels, fromIptvOrg, isFresh, filterXmltv, xmltvTime,
} from "../lib.mjs";

const ch = (over = {}) => ({
  id: "NileTV.eg", name: "Nile TV", country: "EG", categories: ["news"], languages: ["ara"],
  alt_names: ["النيل"], logo: "https://x/logo.png", score: 80, best_quality: "720p",
  streams: [{ url: "https://a/live.m3u8", quality: "720p", rank: 70, health: { status: "online", score: 80 } }],
  ...over,
});

test("parses a normal channel", () => {
  const c = compactChannel(ch(), "nexus");
  assert.equal(c.id, "NileTV.eg");
  assert.equal(c.c, "EG");
  assert.deepEqual(c.cat, ["news"]);
  assert.equal(c.s[0].u, "https://a/live.m3u8");
  assert.equal(c.st, "online");
});

test("rejects malformed and non-http URLs", () => {
  for (const u of ["", "ftp://a/b", "rtmp://a/b", "not a url", "javascript:alert(1)", null, 42, "http://"]) {
    assert.equal(isHttpUrl(u), false, String(u));
  }
  assert.equal(compactChannel(ch({ streams: [{ url: "rtmp://x" }, "nonsense"] })), null);
});

test("tolerates missing fields", () => {
  const c = compactChannel({ id: "A.eg", name: "A", streams: ["https://a/1.ts"] }, "x");
  assert.equal(c.c, "");
  assert.deepEqual(c.l, []);
  assert.deepEqual(c.cat, []);
  assert.equal(c.logo, null);
  assert.equal(c.s[0].st, "unknown");
  assert.equal(compactChannel({ name: "no id", streams: ["https://a"] }), null);
  assert.equal(compactChannel(null), null);
});

test("drops NSFW and closed channels", () => {
  assert.equal(compactChannel(ch({ is_nsfw: true })), null);
  assert.equal(compactChannel(ch({ closed: "2020-01-01" })), null);
});

test("keeps statuses distinct and sorts working streams first", () => {
  const c = compactChannel(ch({ streams: [
    { url: "https://a/off", health: { status: "offline" }, rank: 99 },
    { url: "https://a/geo", health: { status: "blocked" }, rank: 90 },
    { url: "https://a/ok", health: { status: "online" }, rank: 10 },
    { url: "https://a/slow", health: { status: "timeout" }, rank: 50 },
  ] }));
  assert.deepEqual(c.s.map((s) => s.st), ["online", "timeout", "blocked", "offline"]);
});

test("dedupes by channel id and merges streams", () => {
  const a = compactChannel(ch(), "nexus");
  const b = compactChannel(ch({ streams: [{ url: "https://b/2.m3u8", health: { status: "offline" } }] }), "nexus");
  const out = dedupe([[a], [b, a]]);
  assert.equal(out.length, 1);
  assert.deepEqual(out[0].s.map((s) => s.u), ["https://a/live.m3u8", "https://b/2.m3u8"]);
});

test("Egypt first, then working channels", () => {
  const sa = compactChannel(ch({ id: "S.sa", name: "S", country: "SA" }));
  const egOff = compactChannel(ch({ id: "E2.eg", name: "E2", streams: [{ url: "https://e/2", health: { status: "offline" } }] }));
  const egOn = compactChannel(ch({ id: "E1.eg", name: "E1" }));
  assert.deepEqual(sortChannels([sa, egOff, egOn]).map((c) => c.id), ["E1.eg", "E2.eg", "S.sa"]);
});

test("iptv-org fallback never claims online", () => {
  const out = fromIptvOrg(
    [{ id: "A.eg", name: "A", country: "EG" }, { id: "B.fr", name: "B", country: "FR" }],
    [{ channel: "A.eg", url: "https://a/1.m3u8" }, { channel: "B.fr", url: "https://b/1.m3u8" }],
    ["EG"],
  );
  assert.equal(out.length, 1);
  assert.equal(out[0].st, "unknown");
  assert.equal(out[0].src, "iptv-org");
});

test("cache freshness", () => {
  const now = Date.parse("2026-11-07T12:00:00Z");
  assert.equal(isFresh("2026-11-07T08:00:00Z", now, 6 * 3600e3), true);
  assert.equal(isFresh("2026-11-07T05:00:00Z", now, 6 * 3600e3), false);
  assert.equal(isFresh("garbage", now, 6 * 3600e3), false);
  assert.equal(isFresh("2026-11-08T00:00:00Z", now, 6 * 3600e3), false); // from the future
});

test("EPG trimming keeps only our channels and the time window", () => {
  const xml = `<tv>
<channel id="A.eg"><display-name>A</display-name></channel>
<channel id="Z.fr"><display-name>Z</display-name></channel>
<programme start="20261107120000 +0000" stop="20261107130000 +0000" channel="A.eg"><title>Now</title></programme>
<programme start="20261101120000 +0000" stop="20261101130000 +0000" channel="A.eg"><title>Old</title></programme>
<programme start="20261107120000 +0000" stop="20261107130000 +0000" channel="Z.fr"><title>Other</title></programme>
</tv>`;
  const from = Date.parse("2026-11-07T10:00:00Z"), to = Date.parse("2026-11-08T10:00:00Z");
  const out = filterXmltv(xml, new Set(["A.eg"]), from, to);
  assert.equal(out.length, 2);
  assert.ok(out[1].includes("Now"));
  assert.equal(xmltvTime("20261107120000 +0200"), Date.parse("2026-11-07T10:00:00Z"));
});
