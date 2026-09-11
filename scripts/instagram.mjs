// Harvests each gallery's Instagram handle from its own website, since See Saw
// carries no social fields and Wikidata does not cover commercial galleries.
//
// Writes data/instagram.json: { "Gallery Name": "handle" | null }
//   "handle" - found
//   null     - searched properly and found nothing; not retried
//   absent   - never searched
//
// Run: node scripts/instagram.mjs [--recheck]

import { readFile, writeFile } from "node:fs/promises";

const DATA = new URL("../data/nyc.json", import.meta.url);
const OUT = new URL("../data/instagram.json", import.meta.url);
const RECHECK = process.argv.includes("--recheck");

const BROWSER_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

// paths worth a second look when the homepage has no social links in its markup
const DEEP_PATHS = ["/contact", "/about", "/info", "/contact-us", "/about-us", "/visit", "/gallery"];

// instagram.com/<these> are not gallery accounts
const RESERVED = new Set([
  "p", "reel", "reels", "explore", "accounts", "about", "developer", "legal",
  "privacy", "tv", "stories", "direct", "challenge", "oauth", "sitemap", "web",
  "graphql", "static", "embed", "help", "press", "api", "instagram", "download",
  "session", "emails", "ajax", "igtv", "locations", "topics", "invites", "share",
]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function handlesFrom(html) {
  const counts = new Map();
  const re = /instagram\.com\\?\/([A-Za-z0-9_][A-Za-z0-9_.]{0,29})/gi;
  for (const m of html.matchAll(re)) {
    const h = m[1].replace(/\.+$/, "");
    if (h.length < 2 || RESERVED.has(h.toLowerCase())) continue;
    counts.set(h, (counts.get(h) || 0) + 1);
  }
  if (!counts.size) return null;
  // the account linked most often on the page is the gallery's own
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].length - b[0].length)[0][0];
}

async function grab(url, ms = 15000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, {
      signal: ctl.signal,
      redirect: "follow",
      headers: { "User-Agent": BROWSER_UA, Accept: "text/html,*/*" },
    });
    if (!r.ok) return { ok: false, status: r.status };
    return { ok: true, html: await r.text(), finalUrl: r.url };
  } catch (e) {
    return { ok: false, status: e.name === "AbortError" ? "timeout" : e.message.slice(0, 40) };
  } finally {
    clearTimeout(t);
  }
}

export async function findHandle(rawUrl) {
  let base;
  try {
    base = new URL(rawUrl.startsWith("http") ? rawUrl : `https://${rawUrl}`);
  } catch {
    return { handle: null, note: "bad url" };
  }

  const home = await grab(base.href);
  if (home.ok) {
    const h = handlesFrom(home.html);
    if (h) return { handle: h, note: "homepage" };
  }

  // deeper pass: contact/about pages, where many sites keep their social links
  const root = home.ok && home.finalUrl ? new URL(home.finalUrl) : base;
  for (const path of DEEP_PATHS) {
    const res = await grab(new URL(path, root.origin).href, 12000);
    await sleep(250);
    if (!res.ok) continue;
    const h = handlesFrom(res.html);
    if (h) return { handle: h, note: `via ${path}` };
  }

  return { handle: null, note: home.ok ? "no link found" : `unreachable (${home.status})` };
}

// Only sweep every gallery when run directly; refresh.mjs imports findHandle
// to fill in newly seen galleries a few at a time.
if (process.argv[1] && process.argv[1].endsWith("instagram.mjs")) {
  const data = JSON.parse(await readFile(DATA, "utf8"));
  let store = {};
  try {
    store = JSON.parse(await readFile(OUT, "utf8"));
  } catch {
    console.log("no existing instagram.json — starting fresh");
  }

  // one entry per gallery, keyed by the name shown on the page
  const galleries = new Map();
  for (const s of data.shows) {
    if (s.name && s.url && String(s.url).trim() && !galleries.has(s.name)) {
      galleries.set(s.name, s.url);
    }
  }

  const todo = [...galleries.entries()].filter(
    ([name]) => RECHECK || !Object.prototype.hasOwnProperty.call(store, name)
  );
  console.log(`${galleries.size} galleries with a website; ${todo.length} to search\n`);

  let found = 0, none = 0;
  for (const [name, url] of todo) {
    const { handle, note } = await findHandle(url);
    store[name] = handle;
    if (handle) {
      found++;
      console.log(`  @${handle.padEnd(28)} ${name}  [${note}]`);
    } else {
      none++;
      console.log(`  ${"—".padEnd(29)} ${name}  [${note}]`);
    }
    await sleep(400);
  }

  const ordered = Object.fromEntries(Object.entries(store).sort(([a], [b]) => a.localeCompare(b)));
  await writeFile(OUT, JSON.stringify(ordered, null, 2) + "\n");

  const total = Object.keys(ordered).length;
  const have = Object.values(ordered).filter(Boolean).length;
  console.log(`\nthis run: ${found} found, ${none} not found`);
  console.log(`overall: ${have}/${total} galleries have a handle (${Math.round((have / total) * 100)}%)`);
}
