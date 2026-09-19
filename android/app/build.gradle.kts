plugins {
    id("com.android.application")   // includes built-in Kotlin since AGP 9
}

android {
    namespace = "com.seesawport"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seesawport"
        minSdk = 26                   // Android 8.0+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // Debug only for now: a sideloaded build signed with the debug key.
        // A release key is a decision for when the app is worth keeping.
        getByName("debug") { isMinifyEnabled = false }
    }
}

// ---- the site, copied in at build time --------------------------------
// The page is not duplicated in the repo. It is copied out of the site root
// into the APK's assets every build, so the app can never ship a stale copy
// of a file we edited five minutes ago. The assets directory is generated and
// git-ignored for exactly that reason.
val siteRoot = rootProject.projectDir.parentFile          // .../seesaw-port-site

// Straight into the standard assets directory rather than a generated one
// wired through the variant API: AGP 9 will not take a Provider as a source
// directory, and the plain path is easier to read than the machinery that
// would replace it. The directory is git-ignored, so nothing is duplicated
// in the repo — it is rebuilt from the site on every build.
val copySite by tasks.registering(Copy::class) {
    from(siteRoot) { include("index.html", "map.svg") }
    from(siteRoot.resolve("data")) { include("*.json"); into("data") }
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copySite) }

dependencies {
    // WebViewAssetLoader: serves the bundled page over a real https origin
    // instead of file://, so localStorage and fetch behave as they do online.
    implementation("androidx.webkit:webkit:1.15.0")
    // Pinned to what compileSdk 36 will take: androidx.core 1.19 and
    // activity 1.13 both demand 37, which is not installed and is not worth a
    // download for two classes.
    implementation("androidx.activity:activity:1.9.3")
    // window insets, so the page can leave room for the status bar
    implementation("androidx.core:core-ktx:1.15.0")

    // the refresh: a job that survives the app being closed, and retries
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
