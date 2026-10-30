# ExtremisBrowser — project notes

## What this is
Android WebView browser, Kotlin, single `app` module. Package `com.extremis.browser`.
compileSdk 35, minSdk 24, AGP 8.5.2, Kotlin 2.1.0, Gradle wrapper 8.7, view binding enabled.
AdMob (banner / interstitial / native / app-open) is wired in — do not remove the ad calls.

## Design language (as of 2026-09-23)
The UI deliberately mirrors **Google Chrome for Android**. Keep it that way:
- Chrome palette lives in `values/colors.xml`. Legacy names (`extremis_blue`, `url_bar_*`,
  `chrome_*`) are aliases — prefer the `chrome_*` names in new code.
- Metrics live in `values/dimens.xml`; text/toolbar/sheet styles in `values/themes.xml`.
- Incognito has its own full dark palette (`incognito_*`) — every surface, icon tint and
  text colour must be switched in `applyNormalTheme()` / `applyIncognitoTheme()` in
  `MainActivity`. If you add a view, add it to both.
- `values-v31/themes.xml` only adds splash items on top of `Theme.ExtremisBrowser.Base`.
  Never redeclare the full theme there — style items do not merge across config qualifiers.

## Conventions
- `MainActivity` builds the New Tab Page tiles and the Discover feed programmatically
  (`buildMostVisitedRow()`, `buildFeed()`), because the tile count depends on bookmarks.
- The overflow menu is a `BottomSheetDialog`. Every entry must go through
  `LinearLayout.addMenuRow(...)` so it dismisses the sheet before acting — this was an
  explicit user requirement.
- The first "Most visited" tile is pinned to `https://updates.today/` (`mostVisitedLinks()`).
- Favicons go through `utils/FaviconLoader` (LRU cache, no image library). Pass
  `fallbackIconRes = 0` to keep a letter placeholder visible behind the favicon.
- `isShowingWeb()` (checks `swipeRefresh.visibility`) is the correct "is the WebView on
  screen" test. `webView.visibility` is always VISIBLE because its parent is what gets
  hidden — do not reintroduce that check.
- Activity toolbars: set `toolbar.title` and `toolbar.setNavigationOnClickListener { finish() }`
  directly. Do not call `setSupportActionBar` — it replaces the Chrome back icon.

## Tabs and WebView ownership (as of 2026-09-23)
There is **no** `binding.webView` any more. The layout has `swipeRefresh > webHost` (a
FrameLayout) and `tabs/TabManager` adds/removes one WebView per tab there.
- Get the visible WebView with `activeWebView` (a getter on `MainActivity`). Never assume a
  single WebView exists — it is null before the first tab and during teardown.
- Each tab gets its **own** `WebViewClient`/`WebChromeClient` built in
  `createConfiguredWebView(session)`, capturing that `session`. Do not refactor these back into
  one shared client keyed on WebView identity — that was KoiBrowser's bug.
- A callback firing for a background tab must bail out early via `isActiveSession(session)`
  before touching any UI.
- `TabSession.webView` is `lateinit`; outside the class use `session.hasWebView`, because
  `::webView.isInitialized` does not compile on another instance.
- Tabs persist as JSON in `BrowserPreferences.savedTabs`. Incognito tabs are never serialised.

## Preferences
All settings live in `utils/BrowserPreferences` (plain `SharedPreferences`, no
`androidx.preference` dependency). Add a typed accessor there rather than calling
`getSharedPreferences` from a feature class. `MainActivity.applyContentSettingsToTabs()`
re-asserts JS / images / desktop-UA on every live tab in `onResume`, so a Settings change
takes effect when the user returns.

## Ported feature set (from KoiBrowser/, a Java project)
Multi-tab engine, Google autocomplete + 8 search engines, hosts-file ad blocking
(`assets/hosts.txt`, 44,881 domains), find in page, screenshot, long-press link/image menus,
`<input type=file>` chooser, fullscreen video, translate, add to Home screen, panic wipe,
clipboard monitor, pop-up blocking, storage browser (`FileBrowserActivity`).
Downloads stay on Android DownloadManager, honouring the Wi-Fi-only / notification / custom
folder preferences. See README.md "Porting notes" for what was intentionally left out and why.

## Build environment
- `local.properties` is tracked in git and points at `C:\Users\Fahim\...`. It must be
  repointed per machine; a backup exists at `local.properties.bak`.
- `debug.keystore` is gitignored and may be missing — regenerate with keytool
  (`storepass android`, alias `androiddebugkey`).
- `gradle.properties` carries a JVM workaround: `-XX:TieredStopAtLevel=1`,
  `org.gradle.workers.max=2`, `org.gradle.parallel=false`,
  `kotlin.compiler.execution.strategy=in-process`. Without it the Gradle daemon dies with
  `EXCEPTION_ACCESS_VIOLATION` in `jvm.dll` on this machine.
- No AVD or system image installed — builds can be verified, UI cannot be screenshotted.

## Gotchas
- AAPT2 fails on apostrophes in `strings.xml` ("Invalid unicode escape sequence").
  Avoid `'` and `&apos;` in string resources.
- `rm -rf ~/.gradle/caches/...` is blocked by the sandbox safe-delete guard; retry the build.
