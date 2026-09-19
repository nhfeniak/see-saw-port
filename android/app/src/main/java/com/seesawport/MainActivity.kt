package com.seesawport

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * first handler has, so when the refresh lands the page needs no change at
 * all — it is already reading from a directory Kotlin owns.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    /**
     * How deep the status bar is, in CSS pixels, or -1 before Android has
     * said. Kept here because the two things that need it happen in either
     * order: the insets arrive when the window is laid out, the page exists
     * when it has loaded, and whichever is second has to do the work. The
     * first version only pushed it from the insets, which on a cold start is
     * always first — so it was set on an empty document and thrown away by
     * the load, and the nav came up sitting on the clock.
     */
    private var safeTopCss = -1

    /** Held from onGeolocationPermissionsShowPrompt until Android answers. */
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.any { it }
        pendingGeoCallback?.invoke(pendingGeoOrigin, ok, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A debug build is already debuggable; this makes the page inside it
        // inspectable too, which is how the scene gets measured from the Mac
        // instead of guessed at from a screenshot.
        WebView.setWebContentsDebuggingEnabled(true)

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
            // geolocation needs no setting: it is on by default and the
            // WebChromeClient below is what actually gates it
            // The scene is a canvas repainted every frame against a list laid
            // out in CSS pixels. Letting the WebView scale text on its own
            // would slide the list out from under the street drawn beside it.
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                // Twice: as soon as there is something on screen, so the
                // header is never drawn in the wrong place, and again at the
                // end in case the first was too early for the stylesheet.
                override fun onPageCommitVisible(view: WebView, url: String) = pushSafeTop()
                override fun onPageFinished(view: WebView, url: String) = pushSafeTop()
            }

            // The page asks the platform for a fix; the platform asks us; we
            // ask Android once and remember nothing — Android is already the
            // thing that remembers.
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String, callback: GeolocationPermissions.Callback
                ) {
                    if (hasLocation()) { callback.invoke(origin, true, false); return }
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    askLocation.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }
        }
        setContentView(web)

        // The header box runs up behind the status bar and the bar's text sits
        // on the roof stock, so the page is told how deep the bar is rather
        // than the window being shrunk away from it — which keeps the strip
        // the scene's own colour instead of one fixed colour that is wrong on
        // half the pages.
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            safeTopCss = (top / resources.displayMetrics.density).toInt()
            pushSafeTop()
            insets
        }
        ViewCompat.requestApplyInsets(web)

        // A route in, for the pages that have no way of being asked for —
        // ?bookmarks is the one that carries marks in from another browser.
        val route = intent?.getStringExtra("route").orEmpty()
        web.loadUrl("https://$DOMAIN/assets/index.html$route")
    }

    /**
     * Hand the page the depth of the status bar, and log what it did with it.
     * The read-back is the point: it is the only way to know from here that
     * the variable reached a stylesheet rather than an empty document.
     */
    private fun pushSafeTop() {
        if (safeTopCss < 0) return
        web.evaluateJavascript(
            "(function(){" +
            "document.documentElement.style.setProperty('--safe-top','${safeTopCss}px');" +
            "var n=document.getElementById('topnav');" +
            "return n?getComputedStyle(n).paddingTop:'no nav yet';})()"
        ) { got -> Log.i(TAG, "safe-top ${safeTopCss}px -> nav padding-top $got") }
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val TAG = "SeeSawPort"
    }
}
