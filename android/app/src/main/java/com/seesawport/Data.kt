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

    private val FILES = listOf("nyc.json", "instagram.json")

    /** The data directory, seeded from the bundled copy if it is empty. */
    fun seed(context: Context): File {
        val dir = File(context.filesDir, "data").apply { mkdirs() }
        for (name in FILES) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 0) continue
            runCatching {
                context.assets.open("data/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        return dir
    }
}
