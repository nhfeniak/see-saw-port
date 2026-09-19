package com.seesawport

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
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
        // than the window being shrunk away from it.
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val css = (top / resources.displayMetrics.density).toInt()
            web.evaluateJavascript(
                "document.documentElement.style.setProperty('--safe-top','${css}px')", null
            )
            insets
        }

        // A route in, for the pages that have no way of being asked for —
        // ?bookmarks is the one that carries marks in from another browser.
        val route = intent?.getStringExtra("route").orEmpty()
        web.loadUrl("https://$DOMAIN/assets/index.html$route")
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
    }
}
