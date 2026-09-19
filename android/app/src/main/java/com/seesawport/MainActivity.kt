package com.seesawport

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader

/**
 * See Saw Port, as an app that contains the app.
 *
 * The page is not fetched from anywhere. index.html, map.svg and the show data
 * are inside the APK, and WebViewAssetLoader serves them over a real https
 * origin — appassets.androidplatform.net — rather than file://. That matters
 * for more than tidiness: a file:// page gets an opaque origin, which means no
 * localStorage, and localStorage is where the bookmarks and the map pile live.
 *
 * Two path handlers, most specific first:
 *
 *   /assets/data/   the show data, from the app's own files directory
 *   /assets/        the page and the basemap, straight out of the APK
 *
 * The page asks for "data/nyc.json" relative to itself and gets whatever the
 * first handler has, so when the refresh lands in the next milestone the page
 * needs no change at all — it is already reading from a directory Kotlin owns.
 * Until then the data directory is seeded from the bundled copy on first run.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataDir = Data.seed(this)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/data/", WebViewAssetLoader.InternalStoragePathHandler(this, dataDir))
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            // The bookmarks and the map pile are localStorage; without this
            // they silently do nothing.
            settings.domStorageEnabled = true
            // The scene is a canvas that is repainted every frame. Letting the
            // WebView shrink text on its own would move the list out from
            // under a street drawn in CSS pixels.
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            }
        }
        setContentView(web)
        web.loadUrl("https://$DOMAIN/assets/index.html")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DOMAIN = "appassets.androidplatform.net"
    }
}
