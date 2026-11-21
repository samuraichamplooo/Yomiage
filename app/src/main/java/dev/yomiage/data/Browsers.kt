package dev.yomiage.data

/** Apps where the bubble shows in "Browsers only" mode. */
object Browsers {
    private val packages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.opera.gx",
        "com.opera.mini.native", "com.duckduckgo.mobile.android", "com.vivaldi.browser",
        "com.kiwibrowser.browser", "app.vanadium.browser", "com.ecosia.android",
        "com.google.android.googlequicksearchbox", // Google app (Discover articles, search results)
        "com.google.android.apps.magazines",       // Google News
        "com.google.android.apps.searchlite",
    )

    fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in packages
}
