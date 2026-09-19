package com.seesawport

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The scrape, on the phone.
 *
 * A port of scripts/refresh.mjs, which ran on a GitHub runner that never fired
 * on a schedule. The shape is the same and deliberately so:
 *
 *  - one request, two headers, no auth of any kind;
 *  - an If-None-Match against last time's ETag, so most runs are a 304 with no
 *    body and the whole thing costs nothing;
 *  - a refusal to write an empty list over good data;
 *  - summaries kept from the previous file rather than re-derived, because a
 *    summary is expensive and a show's press release does not change;
 *  - Instagram handles merged in from their own file on every write, including
 *    the 304 path, so editing that file alone still corrects a handle.
 *
 * What is NOT here: the Gemini pass that writes summaries for shows seen for
 * the first time. That needs a key on the phone, which is a decision not yet
 * made. Until it is, a new show arrives with no summary key at all — exactly
 * what the script does when GEMINI_API_KEY is unset — and the page simply
 * omits the line.
 */
object Refresh {

    private const val UA = "See Saw/37.2 CFNetwork/1498.700.2 Darwin/23.6.0"
    private const val LIST = "https://seesawmap.com/api/v1/cities/nyc"
    private const val TAG = "SeeSawPort"

    /** What a run did, for the log and for deciding whether to reload the page. */
    data class Result(val changed: Boolean, val note: String)

    fun run(context: Context): Result {
        val dir = Data.seed(context)
        val file = File(dir, "nyc.json")

        val previous = runCatching { JSONObject(file.readText()) }.getOrNull()
        val priorShows = previous?.optJSONArray("shows")
        // opt(), not optString(): org.json hands back the STRING "null" for a
        // JSON null, and an If-None-Match of "null" is a header full of
        // nonsense. Same trap as the handles below, and the same fix.
        val priorEtag = (previous?.opt("list_etag") as? String)?.takeIf { it.isNotEmpty() }

        // A show counts as evaluated if it has a summary KEY AT ALL: an empty
        // string means a previous run read the release and correctly found
        // nothing worth saying, and re-deriving it would cost the same again.
        val summaries = HashMap<String, String>()
        var pendingBefore = 0
        for (i in 0 until (priorShows?.length() ?: 0)) {
            val s = priorShows!!.getJSONObject(i)
            val id = s.opt("id")?.toString() ?: continue
            if (s.has("summary")) summaries[id] = s.optString("summary", "") else pendingBefore++
        }

        val conn = (URL(LIST).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
            // Skipped while work is outstanding: a show still waiting for a
            // summary needs the full list back to be retried, and a 304 would
            // strand it until the listings happened to change on their own.
            if (priorEtag != null && (priorShows?.length() ?: 0) > 0 && pendingBefore == 0) {
                setRequestProperty("If-None-Match", priorEtag)
            }
            connectTimeout = 20_000
            readTimeout = 60_000
        }

        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                // Restamp anyway: fetched_at means "last checked", and a live
                // timestamp is the only sign from outside that this is running.
                if (previous != null) write(context, file, priorEtag, priorShows ?: JSONArray())
                return Result(false, "304, nothing changed")
            }
            if (code != 200) return Result(false, "HTTP $code")

            val etag = conn.getHeaderField("ETag")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val shows = JSONObject(body).optJSONArray("shows")
            if (shows == null || shows.length() == 0) {
                return Result(false, "no shows returned — good data left alone")
            }

            var reused = 0
            var pending = 0
            val out = JSONArray()
            for (i in 0 until shows.length()) {
                val s = shows.getJSONObject(i)
                // The bulk endpoint sends these and the page never reads them;
                // dropping them is most of the difference between 1.4 MB and
                // 330 KB on a phone's storage.
                s.remove("photos")
                s.remove("press_release")
                val id = s.opt("id")?.toString()
                val had = summaries[id]
                if (had != null) { s.put("summary", had); reused++ } else pending++
                out.put(s)
            }

            val kb = write(context, file, etag, out)
            return Result(true, "${out.length()} shows, $kb KB — $reused kept, $pending without a summary")
        } finally {
            conn.disconnect()
        }
    }

    /** Merge the handles in and write the document the page reads. */
    private fun write(context: Context, file: File, etag: String?, shows: JSONArray): Int {
        val handles = runCatching {
            JSONObject(File(file.parentFile, "instagram.json").readText())
        }.getOrNull()

        // A null in the handles file means "looked, and this gallery has no
        // Instagram" — eleven of them do. org.json's optString turns that null
        // into the four-character string "null", so every one of those
        // galleries got instagram: "null" and a link to instagram.com/null.
        // The Node script read the same file with plain property access, where
        // null is falsy and the key simply comes off. opt() behaves that way.
        for (i in 0 until shows.length()) {
            val s = shows.getJSONObject(i)
            val h = (handles?.opt(s.optString("name")) as? String)?.takeIf { it.isNotEmpty() }
            if (h != null) s.put("instagram", h) else s.remove("instagram")
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        val doc = JSONObject()
            .put("fetched_at", stamp)
            .put("list_etag", etag ?: JSONObject.NULL)
            .put("shows", shows)

        val text = doc.toString()
        // Written beside and moved into place, so a refresh killed halfway
        // cannot leave the page reading half a file.
        val tmp = File(file.parentFile, "nyc.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        Log.i(TAG, "refresh wrote ${text.length / 1024} KB")
        return text.length / 1024
    }
}
