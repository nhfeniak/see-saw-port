// Refreshes data/nyc.json from the See Saw API and writes a short visual
// summary for any show we haven't judged before, using the Gemini free tier.
//
// Run:  GEMINI_API_KEY=... node scripts/refresh.mjs
// A missing key is not fatal: the listings still refresh, new shows just
// arrive without a summary and get one on a later run.

import { readFile, writeFile } from "node:fs/promises";

const UA = "See Saw/37.2 CFNetwork/1498.700.2 Darwin/23.6.0";
const LIST_URL = "https://seesawmap.com/api/v1/cities/nyc";
const SHOW_URL = (id) => `https://seesawmap.com/api/v1/shows/${id}`;
const DATA_PATH = new URL("../data/nyc.json", import.meta.url);

const GEMINI_KEY = process.env.GEMINI_API_KEY || "";
const BATCH_SIZE = 8;          // press releases per Gemini call
const GEMINI_GAP_MS = 4500;    // free tier is ~15 req/min; stay well under
const SHOW_GAP_MS = 200;       // be polite to seesawmap.com

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SUMMARY_RULES = `You write one-line visual descriptions of art exhibitions for a personal gallery-going app.

FORMAT: \`Genre[, qualifier]. Specific defining detail.\`
- Lead with the genre: Painting. / Sculpture. / Photography. / Video. / Drawing. / Prints. / Installation. / Textile. / Ceramics. / Performance. / Design. / Collage. / Watercolor. / Mixed media. (Two if genuinely split: "Painting, ceramics.")
- Add a qualifier only if it is a real circumstantial fact about the show: group, two-person, historical, survey, retrospective, posthumous, outdoor, site-specific, archival, early work, career-spanning.
- Then the single most defining, specific thing about THIS show - what separates it from every other show. Prioritise subject matter when the release names it, and materials when they are unusual or when the work is sculpture.

HARD RULES:
- Maximum 9 words total, counting every word including the genre. Fewer is better. No unessential words. Count before you answer; if a line runs to 10 words, rewrite it shorter rather than submitting it.
- Purely visual and physical. NOT curatorial meaning, NOT what the work "explores" or "examines". No thesis statements.
- No generic filler. Every summary must give a specific mental picture. Never write "wide-ranging", "thought-provoking", "mixed media works", "explores identity".
- Medium may be inferred from the artist's or gallery's known practice when the release does not state it.

RETURN AN EMPTY STRING "" when the press release is only a bio or CV, only a list of artist names, or is an artist statement, poem or essay that never describes the physical work - anything that gives no specific idea of what the show looks like. Roughly one in ten lands here. Never invent vague filler instead.

GOOD EXAMPLES:
"Sculpture, historical. Duchamp readymades: bicycle wheel, bottle rack."
"Painting. Surreal landscapes: electric color, moonscapes, ghostly shadows."
"Video. AI-reconstructed brain imagery, outdoor sculpture-garden screens."
"Textile. Hung wool, mud cloth, indigo shibori, military ribbon."
"Group, mixed media. Towers as symbol: Babel, tarot, panopticon."`;

const DANGLERS = /^(a|an|the|and|or|of|in|on|at|to|by|for|with|from|as|into|onto|over|under|through|across|between|its|their|his|her|this|that|these|those)$/i;

// The model sometimes runs a word or two over. Slicing at exactly 9 leaves a
// dangle ("...color orbs on unstretched"), so back off to the last complete
// image instead: the 9th word is mid-thought by definition, and dropping it
// can strip a preposition that is now hanging.
function capWords(s, max = 9) {
  const t = String(s ?? "").trim().replace(/\s+/g, " ");
  if (!t) return "";
  const words = t.split(" ");
  if (words.length <= max) return t;

  const kept = words.slice(0, max);
  if (!/[.!?]$/.test(kept[kept.length - 1])) {
    kept.pop();
    while (kept.length && DANGLERS.test(kept[kept.length - 1].replace(/[.,:;]+$/, ""))) kept.pop();
  }
  const out = kept.join(" ").replace(/[,:;]+$/, "");
  return out && !/[.!?]$/.test(out) ? out + "." : out;
}

async function getJSON(url, label) {
  const res = await fetch(url, { headers: { Accept: "application/json", "User-Agent": UA } });
  if (!res.ok) throw new Error(`${label}: HTTP ${res.status}`);
  return res.json();
}

// Ask Google which models this key can actually use, rather than hardcoding a
// name that may have been renamed or moved off the free tier.
async function pickModel() {
  const data = await getJSON(
    `https://generativelanguage.googleapis.com/v1beta/models?key=${GEMINI_KEY}&pageSize=200`,
    "gemini models"
  );
  const usable = (data.models || []).filter(
    (m) => (m.supportedGenerationMethods || []).includes("generateContent")
  );
  const flash = usable.filter((m) => /flash/i.test(m.name) && !/thinking|image|audio|live/i.test(m.name));
  const preferLite = flash.filter((m) => /lite/i.test(m.name));
  const chosen = preferLite[0] || flash[0] || usable[0];
  if (!chosen) throw new Error("no Gemini model available for this key");
  return chosen.name.replace(/^models\//, "");
}

async function summarizeBatch(model, batch) {
  const listing = batch
    .map((s, i) => {
      const pr = (s.press_release || "").replace(/\s+/g, " ").slice(0, 2500);
      return `--- ${i + 1} ---\nGallery: ${s.name || ""}\nArtist: ${s.artist || ""}\nTitle: ${s.title || s.headline || ""}\nPress release: ${pr}`;
    })
    .join("\n\n");

  const prompt = `${SUMMARY_RULES}

Write one summary for each of the ${batch.length} exhibitions below.
Reply with ONLY a JSON array of ${batch.length} strings, in the same order. No other text.

${listing}`;

  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${GEMINI_KEY}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.2, responseMimeType: "application/json" },
      }),
    }
  );
  if (!res.ok) throw new Error(`gemini: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);

  const body = await res.json();
  const text = body?.candidates?.[0]?.content?.parts?.map((p) => p.text).join("") || "";
  let parsed;
  try {
    parsed = JSON.parse(text.replace(/^```(?:json)?|```$/g, "").trim());
  } catch {
    throw new Error(`gemini returned unparseable JSON: ${text.slice(0, 200)}`);
  }
  if (!Array.isArray(parsed) || parsed.length !== batch.length) {
    throw new Error(`gemini returned ${Array.isArray(parsed) ? parsed.length : "non-array"}, expected ${batch.length}`);
  }
  return parsed.map(capWords);
}

async function main() {
  let previous = { shows: [] };
  try {
    previous = JSON.parse(await readFile(DATA_PATH, "utf8"));
  } catch {
    console.log("no existing data file — starting fresh");
  }

  // A show counts as evaluated if it has a summary KEY AT ALL. An empty string
  // means a previous run read the release and correctly found nothing to say.
  const evaluated = new Map();
  for (const s of previous.shows || []) {
    if (Object.prototype.hasOwnProperty.call(s, "summary")) {
      evaluated.set(String(s.id), s.summary);
    }
  }

  // Manual "forget these and write them again" lever, for checking the key
  // works or re-running everything after a change to SUMMARY_RULES.
  const forget = Number(process.env.RESUMMARIZE || 0);
  if (forget > 0) {
    for (const id of [...evaluated.keys()].slice(0, forget)) evaluated.delete(id);
    console.log(`re-summarizing ${forget} existing show(s) on request`);
  }

  const list = await getJSON(LIST_URL, "see saw list");
  if (!Array.isArray(list.shows) || list.shows.length === 0) {
    throw new Error("see saw returned no shows — refusing to overwrite good data");
  }
  console.log(`fetched ${list.shows.length} shows; ${evaluated.size} already evaluated`);

  const needed = list.shows.filter((s) => !evaluated.has(String(s.id)));
  console.log(`${needed.length} new show(s) need a summary`);

  const fresh = new Map();
  if (needed.length && GEMINI_KEY) {
    // the bulk endpoint always returns press_release: null, so pull each detail
    const detailed = [];
    for (const s of needed) {
      try {
        const d = await getJSON(SHOW_URL(s.id), `show ${s.id}`);
        detailed.push({ ...s, press_release: d.press_release || "" });
      } catch (e) {
        console.log(`  detail failed for ${s.id}: ${e.message}`);
      }
      await sleep(SHOW_GAP_MS);
    }

    const withText = detailed.filter((s) => s.press_release.trim());
    for (const s of detailed) {
      if (!s.press_release.trim()) fresh.set(String(s.id), ""); // nothing to read
    }

    let model;
    try {
      model = await pickModel();
      console.log(`using ${model} for ${withText.length} summaries`);
    } catch (e) {
      console.log(`could not select a Gemini model: ${e.message}`);
    }

    if (model) {
      for (let i = 0; i < withText.length; i += BATCH_SIZE) {
        const batch = withText.slice(i, i + BATCH_SIZE);
        try {
          const out = await summarizeBatch(model, batch);
          batch.forEach((s, j) => fresh.set(String(s.id), out[j]));
          console.log(`  batch ${i / BATCH_SIZE + 1}: ${out.length} summaries`);
        } catch (e) {
          // leave these unevaluated so a later run retries them
          console.log(`  batch ${i / BATCH_SIZE + 1} failed: ${e.message}`);
        }
        if (i + BATCH_SIZE < withText.length) await sleep(GEMINI_GAP_MS);
      }
    }
  } else if (needed.length) {
    console.log("GEMINI_API_KEY not set — skipping summaries this run");
  }

  let reused = 0, written = 0, blank = 0, pending = 0;
  const shows = list.shows.map((s) => {
    const out = { ...s };
    delete out.photos;
    delete out.press_release;
    const id = String(s.id);
    if (evaluated.has(id)) {
      out.summary = evaluated.get(id);
      reused++;
    } else if (fresh.has(id)) {
      out.summary = fresh.get(id);
      fresh.get(id) ? written++ : blank++;
    } else {
      pending++; // no summary key at all — retried next run
    }
    return out;
  });

  const doc = { fetched_at: new Date().toISOString().replace(/\.\d+Z$/, "Z"), shows };
  await writeFile(DATA_PATH, JSON.stringify(doc));
  const kb = Math.round(JSON.stringify(doc).length / 1024);
  console.log(`wrote ${shows.length} shows (${kb} KB) — ${reused} reused, ${written} new, ${blank} blank, ${pending} pending`);
}

main().catch((e) => {
  console.error("refresh failed:", e.message);
  process.exit(1);
});
