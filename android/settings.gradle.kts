// The website's own files are the app's assets, so the Android project lives
// inside the site's repo rather than beside it — one history, one source of
// truth for index.html and map.svg.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "SeeSawPort"
include(":app")
