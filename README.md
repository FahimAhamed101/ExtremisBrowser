# Extremis Browser (Kotlin)

An Android browser app built with Kotlin and WebView, with a UI/UX modelled on Google Chrome
for Android (Material 3, Chrome's palette, omnibox, bottom-sheet menu and tab grid).

## Features

**Chrome-style toolbar**
- Back arrow that appears only when the page can go back
- Omnibox pill with a live leading icon (search / padlock / info / incognito)
- Voice search wired to the platform speech recogniser, plus camera search
- Tab counter rendered as Chrome's outlined square
- Thin blue page-load progress bar

**New Tab Page**
- Google-coloured wordmark
- Rounded search bar with microphone and camera actions
- "Most visited" tile row — the first tile is **updates.today**, followed by built-in
  shortcuts and your own bookmarks, ending with an "Add" tile
- "Recently visited" feed rendered as Chrome Discover cards, backed by real browsing history
  (falls back to a few tips when history is empty)

**Overflow menu**
- Chrome's bottom sheet, with the contextual quick-action row
  (forward / bookmark / share / downloads / refresh)
- Tapping any option dismisses the sheet before running its action

**Tab switcher**
- Grid of tab cards with favicon, title and close button
- Selected tab outlined in Chrome blue; incognito tabs use the dark palette
- Search-your-tabs field
- **Real multi-tab engine**: one WebView per tab, tabs persisted across launches

**Incognito**
- Full dark Chrome palette for the toolbar, New Tab Page and tab cards
- No history, no cookies, no ads, no favicon requests
- Incognito tabs are never written to the saved-tab file

**Search**
- Google autocomplete in the omnibox (disk-cached for a day, works offline)
- Eight switchable search engines: Google, Yahoo, Bing, DuckDuckGo, Ask, Baidu, Yandex, Lukayn

**Page tools**
- Find in page with match counter and previous/next
- Screenshot capture to Pictures (MediaStore on Android 10+)
- Translate the current page
- Add to Home screen
- Long-press a link or image for its context menu
- Fullscreen video playback
- Pop-up blocking, desktop user agent, file uploads from `<input type=file>`

**Privacy**
- Hosts-file ad blocking (~45,000 domains bundled)
- Clipboard monitor, "restore tabs", clear-everything wipe

**Library screens**
- Bookmarks: search, open, edit, copy link, delete
- History: search, relative timestamps, clear all, per-item menu
- Downloads: DownloadManager-backed list with live progress bars
- Files: storage browser with folder picker, open, share and delete
- Settings: search, content, privacy, library and advanced sections

**Video downloader**
- Detects media the page loads — direct files (`mp4`, `webm`, `m4a`), HLS (`m3u8`), DASH (`mpd`)
  and YouTube `videoplayback` streams — from the network layer *and* an injected page scanner, so
  players that never expose a usable `src` are still caught
- A download icon appears in the toolbar only while the active tab has something to save; tapping
  it lists every stream found, with type, quality and host
- Direct files go to DownloadManager; HLS playlists go to `MediaDownloadService`, which follows
  master playlists, joins segments, decrypts `AES-128`, honours `#EXT-X-BYTERANGE` and
  `#EXT-X-MAP`, then publishes the finished file into Downloads

## Notes
- Downloads use Android DownloadManager, honouring the Wi-Fi-only, notification and
  custom-folder preferences. Only HLS playlists need the foreground service, because
  DownloadManager cannot join segments.
- Favicons are fetched from Google's public favicon endpoint and cached in memory; the icon the
  WebView itself decodes is seeded into the same cache.
- The feature set was ported from the sibling `KoiBrowser/` project (Java). See
  "Porting notes" below for what was intentionally not carried over.

### API level traps

`minSdk` is 24 but `compileSdk` is 36, so a Java 11+ method compiles cleanly and then throws
`NoSuchMethodError` on an older device. This already caused one shipped crash:
`InputStream.nullInputStream()` (Java 11, Android 13+) inside the ad blocker killed the app on
Android 11 the moment a page requested a blocked host. Use `ByteArrayInputStream(ByteArray(0))`.
`./gradlew :app:lintDebug` reports zero `NewApi` issues today — keep it that way.

### YouTube caveat

Only renditions whose URL is unsigned are offered. YouTube signs most of them and the signature is
reversible only by running the player's obfuscated JavaScript, so those entries are dropped rather
than presented as a download that would fail. Progressive renditions (itag 18/22) and the
`videoplayback` requests the player makes are normally available.

## Porting notes

The browser features come from `KoiBrowser/`. That project is Java, uses Dagger 2 codegen,
EventBus, RxJava shims and four local `fetch2` AARs, and targets minSdk 21. This app is Kotlin
on minSdk 24 with no code generation, so the features were re-implemented rather than pasted in.

Deliberately **not** carried over, with the reason:

| KoiBrowser | Here |
|---|---|
| `fetch2*` AARs + `DownloadsService` + `CustomDownloader` | Android DownloadManager — no 4 AARs, no foreground service |
| Dagger 2 (`AppComponent`, `*_MembersInjector`, `AppModule`) | Plain constructors; the graph was three singletons |
| EventBus (otto) + `utils/schedulerUtils` + `Observable` | Direct calls and a small executor |
| Sugar ORM (`AdBlockDb`, `BookmarksDb`) | The existing SQLite `DatabaseHelper` |
| `TextDrawable` (amulyakhare) | `FaviconLoader` letter placeholder |
| Five Stars rating library | Native rate prompt after 5 launches |
| Shimmer, leanback, `material-dialogs` 0.9.6, jsoup, Glide, Retrofit | Unused; three of these only ever existed on jcenter |
| Lato custom fonts | System font stack, so the Chrome look is preserved |
| Theme chooser (`dialog_theme_chosser`) | Fixed Chrome + incognito themes |

KoiBrowser's UI was not adopted: its tab drawer, `MaterialSearchView` and bottom settings sheet
are a different design language. The features were re-skinned into the Chrome UI instead.

## Build
Open in Android Studio and run the `app` module, or:

```
./gradlew.bat :app:assembleDebug
```

`local.properties` must point at your Android SDK (`sdk.dir=...`).
