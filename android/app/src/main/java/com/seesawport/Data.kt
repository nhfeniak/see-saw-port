package com.seesawport

import android.content.Context
import java.io.File

/**
 * Where the show data lives on the phone.
 *
 * It is a directory the app owns rather than a file in the APK, because the
 * whole point of the next milestone is that the phone writes it. Bundling a
 * copy and seeding from it on first run means a fresh install opens with a
 * list rather than an empty page, and means the app has something to show if
 * a refresh ever fails.
 */
object Data {

    /**
     * The shows, which the app owns once it has them. Copied out of the APK
     * the first time and never again: every refresh after that writes it, and
     * the bundled copy is only there so a fresh install opens with a list.
     */
    private const val OWNED = "nyc.json"

    /**
     * The Instagram handles, which the APK owns. Nothing on the phone writes
     * this file — it is edited in the repo and harvested by the Node script —
     * so the bundled copy is always the right one and is taken on every
     * launch. Seeding it once instead meant a handle corrected in a new build
     * never reached the phone: the fix shipped, the file did not.
     */
    private const val SHIPPED = "instagram.json"

    fun seed(context: Context): File {
        val dir = File(context.filesDir, "data").apply { mkdirs() }

        val shows = File(dir, OWNED)
        if (!shows.exists() || shows.length() == 0L) copy(context, OWNED, shows)
        copy(context, SHIPPED, File(dir, SHIPPED))

        return dir
    }

    private fun copy(context: Context, name: String, out: File) {
        runCatching {
            context.assets.open("data/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
