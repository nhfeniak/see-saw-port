# See Saw Port

A personal, mobile-friendly reader for NYC gallery openings, built on the
public feed behind the See Saw gallery-guide app.

- `index.html` — the whole site: listings, two maps, bookmarks. No build step.
- `data/nyc.json` — cached show data, refreshed twice daily by GitHub Actions.
- `scripts/refresh.mjs` — the refresh job: fetches the feed, and writes a
  short visual summary for shows it hasn't seen, via the Gemini free tier.

## How it updates

`.github/workflows/refresh.yml` runs at 13:00 and 19:00 UTC (9am/3pm New York
during EDT) and commits `data/nyc.json` when it changes. You can also trigger
it by hand from the Actions tab.

Summaries are only generated for genuinely new shows. A show with an empty
`summary` string has already been read and judged to have nothing worth
saying — that is different from a missing `summary` key, which means it still
needs looking at.

## Bookmarks

Stored in the browser's `localStorage`, per device. Nothing is sent anywhere.
