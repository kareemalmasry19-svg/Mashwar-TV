// Builds the free-channels catalogue for المصري TV (Egypt + Arab countries) from public indexes.
// Runs on GitHub Actions every 6 hours; the app downloads only the small result.
import fs from "node:fs/promises";
import path from "node:path";
import zlib from "node:zlib";
import { ARAB_COUNTRIES, compactChannel, dedupe, sortChannels, fromIptvOrg, filterXmltv } from "./lib.mjs";

const NEXUS = "https://dearbulut.github.io/iptv";
const IPTVORG = "https://iptv-org.github.io/api";
const OUT = path.resolve(process.argv[2] ?? "catalog/out");
const UA = "AlmasryTV-catalog/1.0 (+https://github.com/kareemalmasry19-svg/Mashwar-TV)";

async function get(url, { json = true, gzip = false, timeoutMs = 60000 } = {}) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { "user-agent": UA } });
    if (!res.ok) throw new Error(`${res.status} for ${url}`);
    if (gzip) return zlib.gunzipSync(Buffer.from(await res.arrayBuffer())).toString("utf8");
    return json ? await res.json() : await res.text();
  } finally {
    clearTimeout(t);
  }
}
const tryGet = (url, opts) => get(url, opts).catch((e) => { console.warn("skip:", e.message); return null; });

await fs.mkdir(OUT, { recursive: true });
const now = Date.now();
let source = "IPTV Nexus";
let sourceGeneratedAt = null;
let channels = [];

const index = await tryGet(`${NEXUS}/api/v1/index.json`);
if (index) {
  sourceGeneratedAt = index.generated_at ?? null;
  const shards = await Promise.all([
    ...ARAB_COUNTRIES.map((cc) => tryGet(`${NEXUS}/api/v1/by-country/${cc.toLowerCase()}.json`)),
    tryGet(`${NEXUS}/api/v1/by-language/ara.json`), // Arabic channels broadcast from elsewhere
  ]);
  channels = dedupe(shards.map((list) => (Array.isArray(list) ? list : []).map((c) => compactChannel(c, "nexus")).filter(Boolean)));
}

// Fallback when the primary source is down or empty: iptv-org metadata (status unknown, never "online").
if (channels.length < 20) {
  console.warn(`primary gave ${channels.length} channels; using iptv-org fallback`);
  const [ch, st] = await Promise.all([tryGet(`${IPTVORG}/channels.json`), tryGet(`${IPTVORG}/streams.json`)]);
  if (ch && st) {
    channels = dedupe([channels, fromIptvOrg(ch, st, ARAB_COUNTRIES)]);
    source = channels.some((c) => c.src === "nexus") ? "IPTV Nexus + iptv-org" : "iptv-org (fallback)";
  }
}
if (channels.length === 0) {
  console.error("no channels from any source; keeping the previous catalogue");
  process.exit(2);
}
channels = sortChannels(channels);

const counts = {
  channels: channels.length,
  online: channels.filter((c) => c.st === "online").length,
  egypt: channels.filter((c) => c.c === "EG").length,
  sports: channels.filter((c) => c.cat.includes("sports")).length,
  news: channels.filter((c) => c.cat.includes("news")).length,
};
const catalog = {
  version: 1,
  generated_at: new Date(now).toISOString(),
  source, source_generated_at: sourceGeneratedAt,
  note: "Publicly listed free-to-air streams. Status is from the source's last scan, not a playback guarantee.",
  counts, channels,
};
await fs.writeFile(path.join(OUT, "arab.json.gz"), zlib.gzipSync(JSON.stringify(catalog), { level: 9 }));

// Programme guide: per-country XMLTV shards, trimmed to our channels and the next ~36 hours.
const ids = new Set(channels.map((c) => c.id));
const parts = [];
for (const cc of ARAB_COUNTRIES) {
  const xml = (await tryGet(`${NEXUS}/epg/${cc.toLowerCase()}.xml.gz`, { gzip: true })) ??
    (await tryGet(`${NEXUS}/epg/${cc}.xml.gz`, { gzip: true }));
  if (xml) parts.push(...filterXmltv(xml, ids, now - 3 * 3600e3, now + 36 * 3600e3));
}
const guide = `<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="AlmasryTV catalog">\n${parts.join("\n")}\n</tv>\n`;
await fs.writeFile(path.join(OUT, "arab-epg.xml.gz"), zlib.gzipSync(guide, { level: 9 }));

const summary = { ...catalog, channels: undefined, epg_elements: parts.length };
await fs.writeFile(path.join(OUT, "summary.json"), JSON.stringify(summary, null, 2));
console.log(JSON.stringify(summary, null, 2));
