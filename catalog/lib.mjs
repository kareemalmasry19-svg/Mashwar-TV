// Pure helpers for the free-channels catalogue (kept separate so they are unit-tested).

/** Egypt first, then the rest of the Arab League members. */
export const ARAB_COUNTRIES = [
  "EG", "SA", "AE", "KW", "QA", "BH", "OM", "YE", "IQ", "SY", "LB", "JO", "PS",
  "SD", "LY", "TN", "DZ", "MA", "MR", "SO", "DJ", "KM",
];

export const STATUSES = new Set(["online", "offline", "timeout", "blocked", "error", "unknown"]);

export const arr = (v) => (Array.isArray(v) ? v : []);
export const str = (v) => (typeof v === "string" && v.trim() ? v.trim() : null);

export function isHttpUrl(s) {
  if (typeof s !== "string") return false;
  try {
    const u = new URL(s.trim());
    return (u.protocol === "http:" || u.protocol === "https:") && !!u.hostname;
  } catch {
    return false;
  }
}

/** One stream in compact form, or null if unusable. */
export function compactStream(s) {
  const url = typeof s === "string" ? s : s?.url;
  if (!isHttpUrl(url)) return null;
  const h = (s && typeof s === "object" && s.health) || {};
  const status = STATUSES.has(h.status) ? h.status : "unknown";
  return {
    u: url.trim(),
    q: str(s?.quality) ?? str(h?.media?.resolution),
    st: status,
    sc: Number.isFinite(h.score) ? Math.round(h.score) : 0,
    rk: Number.isFinite(s?.rank) ? s.rank : 0,
    ua: str(s?.user_agent),
    ref: str(s?.referrer),
  };
}

const STATUS_ORDER = { online: 0, unknown: 1, timeout: 2, blocked: 3, error: 4, offline: 5 };

/** Best first: working streams, then higher rank / score. */
export function sortStreams(list) {
  return [...list].sort((a, b) =>
    (STATUS_ORDER[a.st] - STATUS_ORDER[b.st]) || (b.rk - a.rk) || (b.sc - a.sc));
}

/** Compact channel record (null when it has no usable stream or is NSFW). */
export function compactChannel(c, sourceTag) {
  if (!c || typeof c !== "object") return null;
  const id = str(c.id);
  const name = str(c.name);
  if (!id || !name || c.is_nsfw === true || c.closed) return null;
  const streams = sortStreams(arr(c.streams).map(compactStream).filter(Boolean)).slice(0, 4);
  if (!streams.length) return null;
  const best = streams[0];
  return {
    id,
    n: name,
    alt: arr(c.alt_names).map(str).filter(Boolean).slice(0, 4),
    c: (str(c.country) ?? "").toUpperCase(),
    l: arr(c.languages).map(str).filter(Boolean),
    cat: arr(c.categories).map(str).filter(Boolean),
    logo: isHttpUrl(c.logo) ? c.logo : null,
    q: str(c.best_quality) ?? best.q,
    st: best.st,
    sc: Number.isFinite(c.score) ? Math.round(c.score) : best.sc,
    s: streams,
    src: sourceTag,
  };
}

/** Merge several lists, keeping the record with the better stream for each id. */
export function dedupe(lists) {
  const byId = new Map();
  for (const list of lists) {
    for (const c of arr(list)) {
      if (!c) continue;
      const prev = byId.get(c.id);
      if (!prev) { byId.set(c.id, c); continue; }
      const better = sortStreams([prev.s[0], c.s[0]])[0] === c.s[0] && c !== prev ? c : prev;
      // keep union of streams, best first
      const seen = new Set();
      const streams = sortStreams([...prev.s, ...c.s]).filter((s) => !seen.has(s.u) && seen.add(s.u)).slice(0, 4);
      byId.set(c.id, { ...better, s: streams, st: streams[0].st, alt: [...new Set([...prev.alt, ...c.alt])].slice(0, 6) });
    }
  }
  return [...byId.values()];
}

/** Egypt first, then country order, working first, then score, then name. */
export function sortChannels(list) {
  const rank = (cc) => { const i = ARAB_COUNTRIES.indexOf(cc); return i < 0 ? 99 : i; };
  return [...list].sort((a, b) =>
    (rank(a.c) - rank(b.c)) || ((a.st === "online" ? 0 : 1) - (b.st === "online" ? 0 : 1)) ||
    (b.sc - a.sc) || a.n.localeCompare(b.n));
}

/** iptv-org fallback: build the same shape from channels.json + streams.json (status unknown). */
export function fromIptvOrg(channels, streams, countries) {
  const want = new Set(countries);
  const byChannel = new Map();
  for (const s of arr(streams)) {
    if (!s?.channel) continue;
    const l = byChannel.get(s.channel) ?? [];
    l.push({ url: s.url, quality: s.quality, user_agent: s.user_agent, referrer: s.referrer });
    byChannel.set(s.channel, l);
  }
  return arr(channels)
    .filter((c) => want.has((c?.country ?? "").toUpperCase()))
    .map((c) => compactChannel({ ...c, streams: byChannel.get(c.id) ?? [] }, "iptv-org"))
    .filter(Boolean);
}

/** True when a cached file younger than maxAgeMs can be reused. */
export function isFresh(generatedAtIso, nowMs, maxAgeMs) {
  const t = Date.parse(generatedAtIso ?? "");
  return Number.isFinite(t) && nowMs - t >= 0 && nowMs - t < maxAgeMs;
}

/** Keeps only XMLTV <channel>/<programme> elements for the given ids, within [fromMs, toMs]. */
export function filterXmltv(xml, ids, fromMs, toMs) {
  const out = [];
  const chRe = /<channel\b[^>]*\bid="([^"]+)"[^>]*>[\s\S]*?<\/channel>/g;
  const prRe = /<programme\b([^>]*)>[\s\S]*?<\/programme>/g;
  for (const m of xml.matchAll(chRe)) if (ids.has(m[1])) out.push(m[0]);
  for (const m of xml.matchAll(prRe)) {
    const attrs = m[1];
    const ch = /\bchannel="([^"]+)"/.exec(attrs)?.[1];
    if (!ch || !ids.has(ch)) continue;
    const start = xmltvTime(/\bstart="([^"]+)"/.exec(attrs)?.[1]);
    const stop = xmltvTime(/\bstop="([^"]+)"/.exec(attrs)?.[1]);
    if (start == null || stop == null || stop < fromMs || start > toMs) continue;
    out.push(m[0]);
  }
  return out;
}

export function xmltvTime(s) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?/.exec(s ?? "");
  if (!m) return null;
  const [, y, mo, d, h, mi, se, tz] = m;
  let t = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se);
  if (tz) t -= (tz[0] === "-" ? -1 : 1) * ((+tz.slice(1, 3)) * 60 + (+tz.slice(3, 5))) * 60000;
  return t;
}
