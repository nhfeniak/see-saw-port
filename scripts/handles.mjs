// Which galleries have no Instagram handle, and what their website is.
//
// Run: node scripts/handles.mjs
//
// The harvester in refresh.mjs looks a gallery up once, the first time it
// appears in the listings, and writes null if it cannot find a link on their
// website — which is most of the time, because galleries render their social
// icons in JavaScript. It then never tries that gallery again, on purpose: it
// should not re-scrape 300 sites every three hours. So a null is permanent
// until someone looks it up by hand.
//
// That is fine as long as somebody knows. This prints the list.

import { readFile } from "node:fs/promises";

const IG = new URL("../data/instagram.json", import.meta.url);
const NYC = new URL("../data/nyc.json", import.meta.url);

const handles = JSON.parse(await readFile(IG, "utf8"));
const { shows } = JSON.parse(await readFile(NYC, "utf8"));

// A fair is not a gallery: it links to See Saw's own web link for the fair,
// so it never wants a handle and is not missing one.
const isFair = (name) => name.startsWith("\u{1F3AA}");

const missing = new Map();
for (const s of shows) {
  const name = s.name;
  if (!name || isFair(name)) continue;
  if (handles[name]) continue;                       // has one
  if (!missing.has(name)) {
    missing.set(name, {
      url: s.url || "",
      known: Object.prototype.hasOwnProperty.call(handles, name),
      shows: 0,
    });
  }
  missing.get(name).shows++;
}

if (!missing.size) {
  console.log(`every gallery on the list has a handle (${Object.keys(handles).length} in the file)`);
} else {
  const rows = [...missing].sort((a, b) => b[1].shows - a[1].shows);
  console.log(`${rows.length} gallery(s) with no Instagram handle:\n`);
  for (const [name, m] of rows) {
    const why = m.known ? "looked, found nothing" : "never looked";
    console.log(`  ${name}`);
    console.log(`      ${m.shows} show(s) · ${why} · ${m.url || "no website either"}`);
  }
  console.log(`\nThey link to their website meanwhile. To fix one, put the handle`);
  console.log(`in data/instagram.json — the harvester will not overwrite it.`);
}
